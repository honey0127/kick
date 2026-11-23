"""report.csv (training/evaluate.py 결과) 의 사진별 측정값 분포를 정답(approve/reject)별로 본다.

기준값이 두 분포를 얼마나 가르는지 보려는 것이다. 예: 통과여야 할 사진의 target_conf 하위 10% 가
min_vehicle_conf(0.5) 아래면, 그만큼 정상 사진이 LOW_CONF 재촬영으로 빠진다.

  python training/metric_dist.py                       # data/eval/report.csv
  python training/metric_dist.py --part tune           # --holdout 으로 나눈 조정용 사진만
  -> 표 출력 + data/eval/metric_dist.csv
"""
import argparse
import csv
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
LABELS = ("approve", "reject")
# 판정 기준값과 짝이 되는 측정값을 먼저 보여 준다 (report.csv 에 없으면 건너뜀)
KEY_METRICS = (
    "target_conf", "width_occupancy", "effective_occupancy", "residual_cm", "road_overlap", "braille_overlap",
    "crosswalk_overlap", "ground_support", "frame_span", "sidewalk_edge_rows", "sidewalk_frac", "vehicle_count",
)
SKIP = {"file", "gt", "pred", "part", "reasons", "grade", "scale_source", "target_kind"}


def _num(v: str) -> float | None:
    if v is None or v == "":
        return None
    if v in ("True", "False"):
        return float(v == "True")
    try:
        return float(v)
    except ValueError:
        return None


def distributions(rows: list[dict]) -> list[dict]:
    """숫자 열마다 정답별 장수·최솟값·10%·중앙값·90%·최댓값."""
    cols = [c for c in KEY_METRICS if any(c in r for r in rows)]
    cols += sorted({c for r in rows for c in r} - set(cols) - SKIP)
    out = []
    for col in cols:
        for gt in LABELS:
            vals = np.array([x for r in rows if r["gt"] == gt and (x := _num(r.get(col))) is not None], float)
            if col not in KEY_METRICS and vals.size == 0:
                continue
            row = {"metric": col, "gt": gt, "n": int(vals.size)}
            if vals.size:
                row |= {k: round(float(v), 3) for k, v in zip(("min", "p10", "median", "p90", "max"),
                                                              (vals.min(), *np.percentile(vals, [10, 50, 90]), vals.max()))}
            out.append(row)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", type=Path, default=ROOT / "data" / "eval" / "report.csv")
    ap.add_argument("--part", choices=("tune", "check"), help="evaluate.py --holdout 으로 나눈 한쪽만")
    args = ap.parse_args()
    if not args.report.exists():
        sys.exit(f"{args.report} 이 없어요. 먼저 training/evaluate.py 를 실행하세요.")
    with open(args.report, encoding="utf-8-sig") as f:
        rows = [r for r in csv.DictReader(f) if not args.part or r.get("part") == args.part]
    dist = distributions(rows)
    print(f"사진 {len(rows)}장" + (f" ({args.part})" if args.part else ""))
    print(f"{'측정값':<22} {'정답':<8} {'장수':>4} {'최소':>8} {'10%':>8} {'중앙':>8} {'90%':>8} {'최대':>8}")
    for d in dist:
        vals = " ".join(f"{d[k]:>8}" if k in d else f"{'-':>8}" for k in ("min", "p10", "median", "p90", "max"))
        print(f"{d['metric']:<22} {d['gt']:<8} {d['n']:>4} {vals}")
    out = args.report.with_name("metric_dist.csv")
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["metric", "gt", "n", "min", "p10", "median", "p90", "max"])
        w.writeheader()
        w.writerows(dist)
    print(f"\n-> {out}")


if __name__ == "__main__":
    main()
