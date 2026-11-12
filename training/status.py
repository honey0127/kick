"""백그라운드 학습 진행 상황 보기.

  python training/status.py              # 바닥 모델 (kick_surface)
  python training/status.py kick_seg
"""
import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "kick_surface"
    short = name.replace("kick_", "")
    log = ROOT / "logs" / f"train_{short}.log"
    if log.exists():
        s = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", log.read_text(encoding="utf-8", errors="replace")).replace("\r", "\n")
        lines = [l for l in s.split("\n") if l.strip()]
        if lines:
            print("최근:", lines[-1][:200])
        if any("Traceback" in l for l in lines):
            print("⚠ 오류가 있어요:", log)

    res = ROOT / "runs" / name / "results.csv"
    if not res.exists():
        print("아직 끝난 에폭이 없어요.")
        return
    rows = [{k.strip(): v for k, v in r.items()} for r in csv.DictReader(open(res, encoding="utf-8"))]
    print(f"\n{'epoch':>5} {'mask mAP50':>10} {'mask mAP50-95':>13} {'누적시간':>8}")
    for r in rows:
        print(f"{r['epoch']:>5} {float(r['metrics/mAP50(M)']):>10.3f} {float(r['metrics/mAP50-95(M)']):>13.3f} {float(r['time']) / 3600:>7.1f}h")
    last = rows[-1]
    epochs = None
    args = ROOT / "runs" / name / "args.yaml"
    if args.exists():
        m = re.search(r"^epochs:\s*(\d+)", args.read_text(encoding="utf-8"), re.M)
        epochs = int(m.group(1)) if m else None
    if epochs:
        per = float(last["time"]) / int(last["epoch"])
        left = (epochs - int(last["epoch"])) * per / 3600
        print(f"\n{last['epoch']}/{epochs} 에폭, 에폭당 {per / 60:.0f}분, 남은 시간 약 {left:.1f}시간")
    if (ROOT / "models" / f"{name}.pt").exists():
        print(f"완료: models/{name}.pt (서버 재시작하면 적용)")


if __name__ == "__main__":
    main()
