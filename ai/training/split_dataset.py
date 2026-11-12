"""라벨링된 데이터를 train/val 로 나눈다.

입력 (Roboflow·CVAT 의 "YOLO segmentation" 내보내기 그대로):
  data/labeled/images/*.jpg
  data/labeled/labels/*.txt
출력:
  data/dataset/images/{train,val}, data/dataset/labels/{train,val}

같은 장소에서 연달아 찍은 사진이 train 과 val 에 나뉘어 들어가면 검증 점수가 부풀려진다.
파일 이름 앞에 장소를 붙이면(`북문__IMG_0012.jpg`) 같은 장소 사진은 통째로 한쪽에만 들어간다.
구분자(__)가 없는 사진은 한 장씩 따로 나뉜다.

python training/split_dataset.py --val 0.15
"""
import argparse
import random
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def group_of(stem: str, sep: str) -> str:
    return stem.split(sep, 1)[0] if sep in stem else stem


def split_groups(stems: list[str], val: float, seed: int, sep: str = "__") -> tuple[set[str], set[str]]:
    """사진 이름 목록 -> (train 이름 집합, val 이름 집합). 같은 그룹은 같은 쪽으로."""
    groups: dict[str, list[str]] = {}
    for s in stems:
        groups.setdefault(group_of(s, sep), []).append(s)
    keys = sorted(groups)
    random.Random(seed).shuffle(keys)
    target = max(1, round(len(stems) * val))
    val_set: set[str] = set()
    for k in keys:
        if len(val_set) >= target or len(val_set) + len(groups[k]) >= len(stems):
            continue  # 목표만큼 찼거나, 넣으면 train 이 비어 버리는 그룹은 건너뜀
        val_set.update(groups[k])
    return set(stems) - val_set, val_set


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=Path, default=ROOT / "data" / "labeled")
    ap.add_argument("--dst", type=Path, default=ROOT / "data" / "dataset")
    ap.add_argument("--val", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--sep", default="__", help="파일 이름에서 장소(그룹)와 나머지를 나누는 구분자")
    args = ap.parse_args()

    images = sorted(p for p in (args.src / "images").iterdir() if p.suffix.lower() in IMG_EXT)
    pairs = [(p, args.src / "labels" / f"{p.stem}.txt") for p in images]
    missing = [p.name for p, l in pairs if not l.exists()]
    pairs = [(p, l) for p, l in pairs if l.exists()]
    if missing:
        print(f"라벨 없는 이미지 {len(missing)}장 제외: {missing[:5]}...")

    train, val = split_groups([p.stem for p, _ in pairs], args.val, args.seed, args.sep)
    if not val:
        print("⚠ 검증용으로 뺄 장소가 없어요. 장소가 2곳 이상이 되도록 사진을 모으거나 파일 이름의 장소 구분을 확인하세요.")
    splits = {"val": [x for x in pairs if x[0].stem in val], "train": [x for x in pairs if x[0].stem in train]}

    if args.dst.exists():
        shutil.rmtree(args.dst)
    for split, items in splits.items():
        (args.dst / "images" / split).mkdir(parents=True)
        (args.dst / "labels" / split).mkdir(parents=True)
        for img, lbl in items:
            shutil.copy2(img, args.dst / "images" / split / img.name)
            shutil.copy2(lbl, args.dst / "labels" / split / lbl.name)
        n_groups = len({group_of(i.stem, args.sep) for i, _ in items})
        print(f"{split}: {len(items)}장 (장소 {n_groups}곳)")


if __name__ == "__main__":
    main()
