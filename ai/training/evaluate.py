"""반납 판정 정확도 평가 (모델 mAP 가 아니라 '최종 판정'이 사람 판단과 맞는지).

폴더 구조 (사람이 판단해서 분류):
  data/eval/approve/*.jpg   반납 OK 여야 하는 사진
  data/eval/reject/*.jpg    반납 거절돼야 하는 사진 (차도·60% 이상 점유·점자블록 등)

  python training/evaluate.py
  -> 혼동행렬, 정확도, 틀린 사진 목록 + data/eval/report.csv, 시각화는 data/eval/_vis/

통행폭 오차 (줄자 실측과 비교): data/eval/width_gt.csv 가 있으면 자동으로 계산
  file,residual_cm
  approve/IMG_0012.jpg,185
"""
import argparse
import base64
import csv
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


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
    vis.mkdir(exist_ok=True)
    rows, conf = [], {}
    for label in ("approve", "reject"):
        for p in sorted((args.dir / label).glob("*")):
            if p.suffix.lower() not in IMG_EXT:
                continue
            img = cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)
            r = an.analyze(img)
            pred = r["status"].lower()
            conf[(label, pred)] = conf.get((label, pred), 0) + 1
            (vis / f"{label}_{pred}_{p.stem}.jpg").write_bytes(base64.b64decode(r["overlay_jpeg"]))
            if r.get("bev_jpeg"):
                (vis / f"{label}_{pred}_{p.stem}_bev.jpg").write_bytes(base64.b64decode(r["bev_jpeg"]))
            rows.append({"file": f"{label}/{p.name}", "gt": label, "pred": pred, **{k: v for k, v in r["metrics"].items()},
                         "reasons": " | ".join(x["code"] for x in r["reasons"])})

    if not rows:
        print(f"{args.dir}/approve, {args.dir}/reject 에 사진을 넣어주세요.")
        return
    with open(args.dir / "report.csv", "w", newline="", encoding="utf-8-sig") as f:
        keys = sorted({k for r in rows for k in r}, key=lambda k: (k not in ("file", "gt", "pred", "reasons"), k))
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)

    print("\n           pred: approve  reject  retake")
    for gt in ("approve", "reject"):
        print(f"gt {gt:8s}       {conf.get((gt, 'approve'), 0):5d}  {conf.get((gt, 'reject'), 0):6d}  {conf.get((gt, 'retake'), 0):6d}")
    decided = [r for r in rows if r["pred"] != "retake"]
    if decided:
        acc = sum(r["gt"] == r["pred"] for r in decided) / len(decided)
        print(f"\n판정 정확도(재촬영 제외): {acc:.1%}  /  재촬영 비율: {1 - len(decided) / len(rows):.1%}")
    bad = [r for r in rows if r["gt"] == "reject" and r["pred"] == "approve"]
    if bad:
        print("\n⚠ 잘못 통과된 사진 (가장 위험한 오류):")
        for r in bad:
            print("  ", r["file"], r.get("width_occupancy"), r.get("road_overlap"))
    gt_file = args.dir / "width_gt.csv"
    if gt_file.exists():
        gt = {row["file"].replace("\\", "/"): float(row["residual_cm"]) for row in csv.DictReader(open(gt_file, encoding="utf-8-sig"))}
        errs = [(r["file"], r["residual_cm"] - gt[r["file"]]) for r in rows if r["file"] in gt and r.get("residual_cm") is not None]
        missing = sum(1 for f in gt if f not in {e[0] for e in errs})
        if errs:
            e = np.array([x[1] for x in errs])
            print(f"\n통행폭 오차 (줄자 대비, {len(errs)}장): 평균 절대오차 {np.abs(e).mean():.1f}cm, 편향 {e.mean():+.1f}cm, 최대 {np.abs(e).max():.0f}cm  (목표 10~15cm)")
        if missing:
            print(f"  실측 못 한 사진 {missing}장 (점자블록 안 보임/신뢰도 낮음)")
    print(f"\n상세: {args.dir / 'report.csv'},  시각화: {vis}")


if __name__ == "__main__":
    main()
