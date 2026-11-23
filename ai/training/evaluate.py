"""반납 판정 정확도 평가 (모델 mAP 가 아니라 '최종 판정'이 사람 판단과 맞는지).

폴더 구조 (사람이 판단해서 분류, 애매한 사진은 넣지 않음):
  data/eval/approve/*.jpg   반납 OK 여야 하는 사진
  data/eval/reject/*.jpg    반납 거절돼야 하는 사진 (차도·60% 이상 점유·점자블록 등)
  파일 이름 앞에 장소를 붙이면(북문__IMG_0012.jpg) --holdout 이 장소 단위로 나눈다.

  python training/evaluate.py
  -> 혼동행렬, 핵심 지표, 사유 코드별 개수, 틀린 사진 목록
     data/eval/report.csv (사진별 측정값), data/eval/summary.json (지표·커밋·기준값), 시각화는 data/eval/_vis/

기준값 실험 (모델은 처음 한 번만 돌고 결과를 data/eval/_cache/ 에 저장 -> 다음부터는 판정만 다시 함):
  python training/evaluate.py --set min_vehicle_conf=0.4 --set width_hold=0.03
  python training/evaluate.py --sweep min_vehicle_conf=0.3,0.4,0.5,0.6     # 값마다 혼동행렬 비교 -> sweep.csv
조정용/확인용 나누기 (같은 사진으로 맞추고 재면 낙관적이므로):
  python training/evaluate.py --holdout 0.4      # 장소의 약 40% 를 확인용(check)으로, 나머지는 조정용(tune)

통행폭 오차 (줄자 실측과 비교): data/eval/width_gt.csv 가 있으면 자동으로 계산
  file,residual_cm
  approve/IMG_0012.jpg,185
"""
import argparse
import base64
import csv
import dataclasses
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
LABELS = ("approve", "reject")
PREDS = ("approve", "reject", "retake")
PARTS = ("tune", "check")


def _rate(num: int, den: int) -> float | None:
    return round(num / den, 4) if den else None


def summarize(rows: list[dict], width_gt: dict[str, float] | None = None, codes: list[str] | None = None) -> dict:
    """rows: 사진별 {"file", "gt", "pred", "residual_cm"(있으면), "reasons"(있으면)} -> 지표.

    dangerous_pass_rate: 거절돼야 하는 사진 중 통과된 비율 (가장 위험한 오류, 0 에 가까워야 함)
    false_reject_rate:   통과돼야 하는 사진 중 거절된 비율 (사용자 불만)
    retake_rate:         재촬영 요청 비율 (높으면 사용자가 떠남)
    scale_success_rate:  cm 실측(점자블록 기준)이 된 사진 비율
    reason_counts:       정답별 사유 코드 개수. codes 를 주면 한 번도 안 나온 코드도 0 으로 적는다
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
        "reason_counts": reason_counts(rows, codes),
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


def reason_counts(rows: list[dict], codes: list[str] | None = None) -> dict[str, dict[str, int]]:
    out = {g: dict.fromkeys(codes or [], 0) for g in LABELS}
    for r in rows:
        for code in filter(None, (c.strip() for c in str(r.get("reasons", "")).split("|"))):
            out[r["gt"]][code] = out[r["gt"]].get(code, 0) + 1
    return out


def group_of(filename: str) -> str:
    """'북문__IMG_0012.jpg' -> '북문'. 구분자가 없으면 사진 한 장이 한 그룹."""
    stem = Path(filename).stem
    return stem.split("__", 1)[0] if "__" in stem else stem


def part_of(group: str, holdout: float, seed: int = 0) -> str:
    u = int(hashlib.sha256(f"{seed}:{group}".encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "check" if u < holdout else "tune"


def collect(eval_dir: Path, holdout: float = 0.0, seed: int = 0) -> list[tuple[str, Path, str]]:
    """(정답, 사진 경로, 조정용/확인용) 목록. holdout 이 0 이면 전부 tune."""
    items = []
    for label in LABELS:
        for p in sorted((eval_dir / label).glob("*")):
            if p.suffix.lower() in IMG_EXT:
                items.append((label, p, part_of(group_of(p.name), holdout, seed) if holdout > 0 else "tune"))
    return items


def parse_overrides(items: list[str]) -> dict[str, float | int]:
    """['min_vehicle_conf=0.4'] -> {'min_vehicle_conf': 0.4}. Thresholds 에 있는 숫자 기준값만 받는다."""
    from server.rules import Thresholds

    defaults = Thresholds()
    names = {f.name for f in dataclasses.fields(Thresholds)}
    out: dict[str, float | int] = {}
    for item in items:
        name, _, value = (s.strip() for s in item.partition("="))
        if name not in names or not value:
            raise SystemExit(f"'{item}': Thresholds 에 있는 이름=값 으로 적어주세요 (예: min_vehicle_conf=0.4)")
        default = getattr(defaults, name)
        if isinstance(default, bool) or not isinstance(default, (int, float)):
            raise SystemExit(f"{name} 은 숫자 기준값이 아니라서 여기서 바꿀 수 없어요")
        out[name] = int(float(value)) if isinstance(default, int) else float(value)
    return out


def parse_sweep(spec: str) -> tuple[str, list[float | int]]:
    name, _, values = spec.partition("=")
    vals = [v for v in values.split(",") if v.strip()]
    if not vals:
        raise SystemExit("--sweep 은 이름=값1,값2,... 으로 적어주세요 (예: min_vehicle_conf=0.3,0.4,0.5)")
    return name.strip(), [parse_overrides([f"{name}={v}"])[name.strip()] for v in vals]


# ---------------------------------------------------------------- 인식 결과 저장 (기준값 실험용)
def model_signature() -> str:
    """모델 파일이 바뀌면 저장해 둔 인식 결과를 쓰지 않도록, 모델 파일 크기·수정 시각을 묶은 표시."""
    from server import config

    parts = [f"conf={config.CONF}", f"max_side={config.MAX_SIDE}"]
    for p in (config.CUSTOM_WEIGHTS, config.SURFACE_WEIGHTS):
        parts.append(f"{p.name}:{p.stat().st_size}:{p.stat().st_mtime_ns}" if p.exists() else f"{p.name}:none")
    if not config.CUSTOM_WEIGHTS.exists():
        parts.append(f"baseline={config.BASELINE_YOLO}")
    if not config.SURFACE_WEIGHTS.exists():
        parts.append(f"segformer={config.SEGFORMER}")
    return "|".join(parts)


def _pack(m: np.ndarray) -> np.ndarray:
    return np.packbits(m.astype(bool).ravel())


def save_raw(path: Path, raw, backend: str) -> None:
    from server.analyzer import STUFF_CLASSES

    shape = next(iter(raw.stuff.values())).shape
    arrays = {f"stuff_{k}": _pack(raw.stuff[k]) for k in STUFF_CLASSES}
    arrays |= {f"veh_{i}": _pack(v.mask) for i, v in enumerate(raw.vehicles)}
    arrays |= {f"tile_{i}": _pack(t) for i, t in enumerate(raw.tiles)}
    meta = {
        "shape": list(shape), "backend": backend, "models_braille": raw.models_braille, "n_tiles": len(raw.tiles),
        "vehicles": [{"kind": v.kind, "conf": v.conf, "fallen": v.fallen} for v in raw.vehicles],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(path, meta=np.array(json.dumps(meta, ensure_ascii=False)), **arrays)


def load_raw(path: Path):
    from server.analyzer import STUFF_CLASSES, RawPerception
    from server.rules import Vehicle

    with np.load(path) as z:
        meta = json.loads(str(z["meta"]))
        shape = tuple(meta["shape"])
        n = shape[0] * shape[1]
        un = lambda key: np.unpackbits(z[key], count=n).astype(bool).reshape(shape)  # noqa: E731
        vehicles = [Vehicle(un(f"veh_{i}"), kind=v["kind"], conf=v["conf"], fallen=v["fallen"]) for i, v in enumerate(meta["vehicles"])]
        stuff = {k: un(f"stuff_{k}") for k in STUFF_CLASSES}
        tiles = [un(f"tile_{i}") for i in range(meta["n_tiles"])]
    return RawPerception(vehicles, stuff, tiles, meta["models_braille"]), meta["backend"]


class RawSource:
    """사진 -> 모델 인식 결과. 저장본이 있으면 그것을 쓰고, 없을 때만 모델을 불러와 돌린다."""

    def __init__(self, cache_dir: Path | None):
        self.cache_dir = cache_dir
        self.signature = model_signature()
        self.analyzer = None
        self.backend = None
        self.hits = self.misses = 0

    def _path(self, photo: Path) -> Path:
        st = photo.stat()
        key = hashlib.sha1(f"{photo.parent.name}/{photo.name}|{st.st_size}|{st.st_mtime_ns}|{self.signature}".encode()).hexdigest()[:12]
        return self.cache_dir / f"{photo.parent.name}__{photo.stem}__{key}.npz"

    def __call__(self, photo: Path, img: np.ndarray):
        path = self._path(photo) if self.cache_dir else None
        if path is not None and path.exists():
            raw, self.backend = load_raw(path)
            self.hits += 1
            return raw
        if self.analyzer is None:
            from server.analyzer import Analyzer

            self.analyzer = Analyzer()
            self.backend = self.analyzer.backend
            print("backend:", self.backend)
        raw = self.analyzer.detect(img)
        self.misses += 1
        if path is not None:
            save_raw(path, raw, self.backend)
        return raw


# ---------------------------------------------------------------- 판정
def run(items: list[tuple[str, Path, str]], th, get_raw, vis: Path | None = None) -> list[dict]:
    from server import config
    from server.analyzer import analyze_scene, build_scene, resize_max

    rows = []
    for label, p, part in items:
        img = cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            print("읽을 수 없는 사진:", p)
            continue
        img = resize_max(img, config.MAX_SIDE)
        r = analyze_scene(img, build_scene(img, get_raw(p, img), th), th)
        pred = r["status"].lower()
        if vis is not None:
            (vis / f"{label}_{pred}_{p.stem}.jpg").write_bytes(base64.b64decode(r["overlay_jpeg"]))
            if r.get("bev_jpeg"):
                (vis / f"{label}_{pred}_{p.stem}_bev.jpg").write_bytes(base64.b64decode(r["bev_jpeg"]))
        rows.append({"file": f"{label}/{p.name}", "gt": label, "pred": pred, "part": part, **r["metrics"],
                     "reasons": " | ".join(x["code"] for x in r["reasons"])})
    return rows


def git_info() -> dict | None:
    """평가에 쓴 코드 버전. server/ 에 커밋하지 않은 변경이 있으면 dirty (보고서 숫자를 재현할 수 없음)."""
    try:
        run_git = lambda *a: subprocess.run(["git", "-C", str(ROOT), *a], capture_output=True, text=True, check=True).stdout.strip()  # noqa: E731
        return {"commit": run_git("rev-parse", "--short", "HEAD"), "branch": run_git("rev-parse", "--abbrev-ref", "HEAD"),
                "server_dirty": bool(run_git("status", "--porcelain", "--", "server"))}
    except (OSError, subprocess.CalledProcessError):
        return None


# ---------------------------------------------------------------- 출력
def _frac(k: int, n: int) -> str:
    return f"{k / n:.1%} ({k}/{n}장)" if n else "- (0장)"


def print_summary(s: dict, rows: list[dict], title: str = "") -> None:
    c = s["confusion"]
    n_rej, n_app = sum(c["reject"].values()), sum(c["approve"].values())
    n_retake = c["approve"]["retake"] + c["reject"]["retake"]
    n_dec = s["n"] - n_retake
    n_ok = c["approve"]["approve"] + c["reject"]["reject"]
    if title:
        print(f"\n===== {title} =====")
    print("\n           pred: approve  reject  retake")
    for gt in LABELS:
        print(f"gt {gt:8s}       {c[gt]['approve']:5d}  {c[gt]['reject']:6d}  {c[gt]['retake']:6d}")
    print(f"\n판정 정확도(재촬영 제외) {_frac(n_ok, n_dec)}")
    print(f"위험 통과율 {_frac(c['reject']['approve'], n_rej)}  (거절돼야 하는데 통과, 목표 2% 이하)")
    print(f"잘못 거절률 {_frac(c['approve']['reject'], n_app)}")
    print(f"재촬영률   {_frac(n_retake, s['n'])}  (목표 25% 이하)")
    print(f"cm 실측률  {_frac(sum(r.get('residual_cm') is not None for r in rows), s['n'])}")
    for gt in LABELS:
        counts = {k: v for k, v in s["reason_counts"][gt].items() if v}
        print(f"사유 코드 ({gt}): " + (", ".join(f"{k} {v}" for k, v in sorted(counts.items(), key=lambda kv: -kv[1])) or "없음"))
    bad = [r for r in rows if r["gt"] == "reject" and r["pred"] == "approve"]
    if bad:
        print("\n⚠ 잘못 통과된 사진 (가장 위험한 오류):")
        for r in bad:
            print("  ", r["file"], "점유", r.get("width_occupancy"), "유효", r.get("effective_occupancy"), "차도", r.get("road_overlap"))
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


def write_csv(path: Path, rows: list[dict], first: tuple[str, ...] = ()) -> None:
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        keys = sorted({k for r in rows for k in r}, key=lambda k: (k not in first, first.index(k) if k in first else 0, k))
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)


def sweep(items, base_th, name: str, values: list, source, width_gt, codes, out_csv: Path) -> list[dict]:
    """기준값 하나를 여러 값으로 바꿔 가며 판정만 다시 한다. 값마다 혼동행렬과 지표."""
    table = []
    print(f"\n{name}: 기본값 {getattr(base_th, name)}")
    print(f"{'값':>8} | {'위험 통과':>14} | {'잘못 거절':>14} | {'재촬영':>14} | 통과→거절/보류 등 바뀐 사진")
    base_rows = None
    for v in values:
        th = dataclasses.replace(base_th, **{name: v})
        rows = run(items, th, source)
        s = summarize(rows, width_gt, codes)
        c = s["confusion"]
        if base_rows is None:
            base_rows = {r["file"]: r["pred"] for r in run(items, base_th, source)}
        changed = sum(base_rows.get(r["file"]) != r["pred"] for r in rows)
        n_rej, n_app = sum(c["reject"].values()), sum(c["approve"].values())
        n_retake = c["approve"]["retake"] + c["reject"]["retake"]
        print(f"{v:>8} | {_frac(c['reject']['approve'], n_rej):>14} | {_frac(c['approve']['reject'], n_app):>14} | "
              f"{_frac(n_retake, s['n']):>14} | {changed}장")
        table.append({name: v, "n": s["n"], "dangerous_pass": c["reject"]["approve"], "n_reject": n_rej,
                      "false_reject": c["approve"]["reject"], "n_approve": n_app, "retake": n_retake, "changed_vs_base": changed,
                      **{f"{g}_{p}": c[g][p] for g in LABELS for p in PREDS}})
    write_csv(out_csv, table, first=(name,))
    print(f"\n-> {out_csv}")
    return table


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", type=Path, default=ROOT / "data" / "eval")
    ap.add_argument("--set", action="append", default=[], metavar="이름=값", help="Thresholds 기준값 바꿔서 판정 (여러 번 가능)")
    ap.add_argument("--sweep", metavar="이름=값1,값2", help="기준값 하나를 여러 값으로 바꿔 혼동행렬 비교 (sweep.csv)")
    ap.add_argument("--holdout", type=float, default=0.0, help="확인용으로 뺄 장소 비율 (0 이면 나누지 않음)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--no-cache", action="store_true", help="저장해 둔 인식 결과를 쓰지 않고 모델을 다시 돌림")
    args = ap.parse_args()

    from server.rules import MESSAGES, Thresholds

    overrides = parse_overrides(args.set)
    th = dataclasses.replace(Thresholds(), **overrides)
    items = collect(args.dir, args.holdout, args.seed)
    if not items:
        print(f"{args.dir}/approve, {args.dir}/reject 에 사진을 넣어주세요.")
        return
    source = RawSource(None if args.no_cache else args.dir / "_cache")
    width_gt = load_width_gt(args.dir / "width_gt.csv")
    codes = list(MESSAGES)

    if args.sweep:
        name, values = parse_sweep(args.sweep)
        sweep(items, th, name, values, source, width_gt, codes, args.dir / "sweep.csv")
        return

    vis = args.dir / "_vis"
    vis.mkdir(parents=True, exist_ok=True)
    rows = run(items, th, source, vis)
    write_csv(args.dir / "report.csv", rows, first=("file", "gt", "pred", "part", "reasons"))

    summary = summarize(rows, width_gt, codes)
    if args.holdout > 0:
        summary["parts"] = {part: summarize([r for r in rows if r["part"] == part], width_gt, codes) for part in PARTS}
    summary |= {"backend": source.backend, "git": git_info(), "threshold_overrides": overrides,
                "holdout": args.holdout, "cache": {"hits": source.hits, "misses": source.misses}}
    (args.dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")

    print_summary(summary, rows, "전체")
    if args.holdout > 0:
        for part in PARTS:
            sub = [r for r in rows if r["part"] == part]
            print_summary(summary["parts"][part], sub, f"{'조정용' if part == 'tune' else '확인용'} ({part}, {len(sub)}장)")
    g = summary["git"]
    if g:
        print(f"\n코드 버전: {g['branch']} {g['commit']}" + ("  ⚠ server/ 에 커밋 안 한 변경이 있음 (재현 불가)" if g["server_dirty"] else ""))
    if overrides:
        print("바꾼 기준값:", overrides)
    print(f"인식 결과 저장본 사용 {source.hits}장 / 모델로 새로 인식 {source.misses}장")
    print(f"\n상세: {args.dir / 'report.csv'},  지표: {args.dir / 'summary.json'},  시각화: {vis}")


if __name__ == "__main__":
    main()
