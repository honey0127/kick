"""라벨링된 데이터를 train/val 로 나눈다.

입력 (Roboflow·CVAT 의 "YOLO segmentation" 내보내기 그대로):
  data/labeled/images/*.jpg
  data/labeled/labels/*.txt
출력:
  data/dataset/images/{train,val}, data/dataset/labels/{train,val}

python training/split_dataset.py --val 0.15
"""
import argparse
import random
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=Path, default=ROOT / "data" / "labeled")
    ap.add_argument("--dst", type=Path, default=ROOT / "data" / "dataset")
    ap.add_argument("--val", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    images = sorted(p for p in (args.src / "images").iterdir() if p.suffix.lower() in IMG_EXT)
    pairs = [(p, args.src / "labels" / f"{p.stem}.txt") for p in images]
    missing = [p.name for p, l in pairs if not l.exists()]
    pairs = [(p, l) for p, l in pairs if l.exists()]
    if missing:
        print(f"라벨 없는 이미지 {len(missing)}장 제외: {missing[:5]}...")

    random.Random(args.seed).shuffle(pairs)
    n_val = max(1, int(len(pairs) * args.val))
    splits = {"val": pairs[:n_val], "train": pairs[n_val:]}

    if args.dst.exists():
        shutil.rmtree(args.dst)
    for split, items in splits.items():
        (args.dst / "images" / split).mkdir(parents=True)
        (args.dst / "labels" / split).mkdir(parents=True)
        for img, lbl in items:
            shutil.copy2(img, args.dst / "images" / split / img.name)
            shutil.copy2(lbl, args.dst / "labels" / split / lbl.name)
        print(f"{split}: {len(items)}장")


if __name__ == "__main__":
    main()
