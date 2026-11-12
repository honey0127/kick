"""반납 판정 정확도 평가 (모델 mAP 가 아니라 '최종 판정'이 사람 판단과 맞는지).

폴더 구조 (사람이 판단해서 분류):
  data/eval/approve/*.jpg   반납 OK 여야 하는 사진
  data/eval/reject/*.jpg    반납 거절돼야 하는 사진 (차도·60% 이상 점유·점자블록 등)

  python training/evaluate.py
  -> 혼동행렬과 핵심 지표, 틀린 사진 목록
     data/eval/report.csv (사진별), data/eval/summary.json (지표, 학습할 때마다 비교용), 시각화는 data/eval/_vis/

통행폭 오차 (줄자 실측과 비교): data/eval/width_gt.csv 가 있으면 자동으로 계산
  file,residual_cm
  approve/IMG_0012.jpg,185
"""
import argparse
import base64
import csv
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
LABELS = ("approve", "reject")
PREDS = ("approve", "reject", "retake")


def _rate(num: int, den: int) -> float | None:
    return round(num / den, 4) if den else None


def summarize(rows: list[dict], width_gt: dict[str, float] | None = None) -> dict:
    """rows: 사진별 {"file", "gt", "pred", "residual_cm"(있으면)} -> 지표.

    dangerous_pass_rate: 거절돼야 하는 사진 중 통과된 비율 (가장 위험한 오류, 0 에 가까워야 함)
    false_reject_rate:   통과돼야 하는 사진 중 거절된 비율 (사용자 불만)
    retake_rate:         재촬영 요청 비율 (높으면 사용자가 떠남)
    scale_success_rate:  cm 실측(점자블록 기준)이 된 사진 비율
    """
    conf = {(g, p): 0 for g in LABELS for p in PREDS}
    for r in rows:
        conf[(r["gt"], r["pred"])] += 1
    n_gt = {g: sum(conf[(g, p)] for p in PREDS) for g in LABELS}
    decided = [r for r in rows if r["pred"] != "retake"]
    out = {
        "n": len(rows),
        "confusion": {g: {p: conf[(g, p)] for p in PREDS} for g in LABELS},
        "accuracy_decided": _rate(sum(r["gt"] == r["pred"] for r in decided), len(decided)),
        "dangerous_pass_rate": _rate(conf[("reject", "approve")], n_gt["reject"]),
        "false_reject_rate": _rate(conf[("approve", "reject")], n_gt["approve"]),
        "retake_rate": _rate(len(rows) - len(decided), len(rows)),
        "scale_success_rate": _rate(sum(r.get("residual_cm") is not None for r in rows), len(rows)),
        "width": None,
    }
    if width_gt:
        by_file = {r["file"]: r for r in rows}
        errs = [by_file[f]["residual_cm"] - cm for f, cm in width_gt.items()
                if f in by_file and by_file[f].get("residual_cm") is not None]
        e = np.array(errs, float)
        out["width"] = {
            "n_gt": len(width_gt),
            "n_measured": len(errs),
            "mae_cm": round(float(np.abs(e).mean()), 1) if errs else None,
            "bias_cm": round(float(e.mean()), 1) if errs else None,
            "max_abs_cm": round(float(np.abs(e).max()), 1) if errs else None,
        }
    return out


def _pct(v) -> str:
    return "-" if v is None else f"{v:.1%}"


def print_summary(s: dict, rows: list[dict]) -> None:
    print("\n           pred: approve  reject  retake")
    for gt in LABELS:
        c = s["confusion"][gt]
        print(f"gt {gt:8s}       {c['approve']:5d}  {c['reject']:6d}  {c['retake']:6d}")
    print(f"\n판정 정확도(재촬영 제외) {_pct(s['accuracy_decided'])}")
    print(f"위험 통과율 {_pct(s['dangerous_pass_rate'])}  (거절돼야 하는데 통과, 목표 2% 이하)")
    print(f"잘못 거절률 {_pct(s['false_reject_rate'])}")
    print(f"재촬영률   {_pct(s['retake_rate'])}  (목표 25% 이하)")
    print(f"cm 실측률  {_pct(s['scale_success_rate'])}")
    bad = [r for r in rows if r["gt"] == "reject" and r["pred"] == "approve"]
    if bad:
        print("\n⚠ 잘못 통과된 사진 (가장 위험한 오류):")
        for r in bad:
            print("  ", r["file"], r.get("width_occupancy"), r.get("road_overlap"))
    w = s["width"]
    if w:
        if w["n_measured"]:
            print(f"\n통행폭 오차 (줄자 대비, {w['n_measured']}장): 평균 절대오차 {w['mae_cm']}cm, 편향 {w['bias_cm']:+}cm, 최대 {w['max_abs_cm']}cm  (목표 10~15cm)")
        if w["n_gt"] > w["n_measured"]:
            print(f"  실측 못 한 사진 {w['n_gt'] - w['n_measured']}장 (점자블록 안 보임/신뢰도 낮음)")


def load_width_gt(path: Path) -> dict[str, float]:
    if not path.exists():
        return {}
    with open(path, encoding="utf-8-sig") as f:
        return {row["file"].replace("\\", "/"): float(row["residual_cm"]) for row in csv.DictReader(f)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, default=ROOT / "data" / "eval")
    ap.add_argument("--max-width-occupancy", type=float, default=None, help="임계값 바꿔서 실험")
    args = ap.parse_args()

    from server.analyzer import Analyzer
    from server.rules import Thresholds

    th = Thresholds()
    if args.max_width_occupancy is not None:
        th.max_width_occupancy = args.max_width_occupancy
    an = Analyzer(th)
    print("backend:", an.backend)

    vis = args.dir / "_vis"
    vis.mkdir(parents=True, exist_ok=True)
    rows = []
    for label in LABELS:
        for p in sorted((args.dir / label).glob("*")):
            if p.suffix.lower() not in IMG_EXT:
                continue
            img = cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                print("읽을 수 없는 사진:", p)
                continue
            r = an.analyze(img)
            pred = r["status"].lower()
            (vis / f"{label}_{pred}_{p.stem}.jpg").write_bytes(base64.b64decode(r["overlay_jpeg"]))
            if r.get("bev_jpeg"):
                (vis / f"{label}_{pred}_{p.stem}_bev.jpg").write_bytes(base64.b64decode(r["bev_jpeg"]))
            rows.append({"file": f"{label}/{p.name}", "gt": label, "pred": pred, **r["metrics"],
                         "reasons": " | ".join(x["code"] for x in r["reasons"])})

    if not rows:
        print(f"{args.dir}/approve, {args.dir}/reject 에 사진을 넣어주세요.")
        return
    with open(args.dir / "report.csv", "w", newline="", encoding="utf-8-sig") as f:
        keys = sorted({k for r in rows for k in r}, key=lambda k: (k not in ("file", "gt", "pred", "reasons"), k))
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)

    summary = summarize(rows, load_width_gt(args.dir / "width_gt.csv"))
    summary |= {"backend": an.backend, "thresholds": {"max_width_occupancy": th.max_width_occupancy}}
    (args.dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print_summary(summary, rows)
    print(f"\n상세: {args.dir / 'report.csv'},  지표: {args.dir / 'summary.json'},  시각화: {vis}")


if __name__ == "__main__":
    main()
