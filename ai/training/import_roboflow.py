"""Roboflow Universe 에서 내려받은 YOLO-seg 데이터셋(여러 개) -> 킥보드 기기 데이터셋 하나로 합치기.

Roboflow 에서 "YOLOv11"(또는 YOLOv8) 형식으로 내려받아 압축을 풀면
  <폴더>/data.yaml, <폴더>/train|valid|test/images|labels
구조가 된다. 데이터셋마다 클래스 이름이 다르므로 --map 으로 우리 클래스에 맞춘다.

  python training/import_roboflow.py \
      --src data/roboflow/kickboard-a0rbb --map "0=kickboard,1=drop" \
      --src data/roboflow/kickboard-7qar2 --map "scooter=kickboard,guide_block=drop"
  -> data/kick_seg/ (images|labels/train|val|test, data.yaml, stats.json)
  -> python training/train.py --data data/kick_seg/data.yaml --name kick_seg --out kick_seg.pt

- 점자블록은 바닥 모델(kick_surface.pt)이 이미 잘 잡으므로 여기서는 drop 해서 기기만 학습한다
- Roboflow 는 증강본을 "원본이름_jpg.rf.<해시>.jpg" 로 만든다. 같은 원본의 증강본이 train/test 에
  갈라져 들어가면 점수가 부풀려지므로, 원래 split 은 버리고 원본이름 단위로 다시 나눈다
  (두 데이터셋에 같은 AI Hub 사진이 들어 있어도 같은 쪽으로 간다)
- 박스만 있는 줄(값 4개)은 윤곽이 없어 버린다. 개수는 stats.json 에 남는다
- 바닥 클래스로 만들 때 (예: lane 데이터셋의 보도·차도 라벨로 바닥 모델 보강):
    --names sidewalk,road,braille_block,crosswalk --map "Sidewalk=sidewalk,Road=road,..." --skip-if kick
  --skip-if 클래스가 들어 있는 사진은 통째로 뺀다 (킥보드 시험용으로 따로 둔 사진이 학습에 섞이지 않게)
"""
import argparse
import hashlib
import json
import re
import shutil
from collections import Counter
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
NAMES = ["kickboard", "fallen_kickboard"]  # server/analyzer.py VEHICLE_CLASSES 와 같은 이름
IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def parse_map(spec: str, names: list[str], targets: list[str] = NAMES) -> dict[int, int | None]:
    """'scooter=kickboard,guide_block=drop' -> {원본 클래스 id: 우리 id 또는 None}. 원본은 이름이나 번호로 지정."""
    out = {}
    for item in filter(None, (s.strip() for s in spec.split(","))):
        src, dst = (x.strip() for x in item.split("="))
        sid = names.index(src) if src in names else int(src)
        if dst != "drop" and dst not in targets:
            raise SystemExit(f"--map 의 대상 '{dst}' 는 {targets} 또는 drop 이어야 해요")
        out[sid] = None if dst == "drop" else targets.index(dst)
    missing = [n for i, n in enumerate(names) if i not in out]
    if missing:
        raise SystemExit(f"--map 에 빠진 클래스: {missing} (원본 클래스: {names})")
    return out


def group_of(stem: str) -> str:
    return re.sub(r"_(jpe?g|png|bmp|webp)$", "", stem.split(".rf.")[0], flags=re.I).lower()


def split_of(group: str, seed: int, val: float, test: float) -> str:
    u = int(hashlib.sha256(f"{seed}:{group}".encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "test" if u < test else "val" if u < test + val else "train"


def convert(label: Path, cmap: dict, stats: Counter, targets: list[str] = NAMES) -> list[str]:
    lines = []
    for raw in label.read_text(encoding="utf-8").splitlines():
        v = raw.split()
        if not v:
            continue
        dst = cmap.get(int(v[0]))
        if dst is None:
            stats["dropped_class"] += 1
            continue
        if len(v) < 7:  # 박스(cls cx cy w h) 이거나 점 2개 이하
            stats["box_only"] += 1
            continue
        lines.append(" ".join([str(dst), *v[1:]]))
        stats[targets[dst]] += 1
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=Path, action="append", required=True, help="Roboflow 내보내기 폴더 (data.yaml 있는 곳)")
    ap.add_argument("--map", action="append", required=True, help="--src 마다 하나씩. 예: 'scooter=kickboard,guide_block=drop'")
    ap.add_argument("--dst", type=Path, default=ROOT / "data" / "kick_seg")
    ap.add_argument("--val", type=float, default=0.15)
    ap.add_argument("--test", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--names", default=",".join(NAMES), help="만들 데이터셋의 클래스 (기본: 킥보드)")
    ap.add_argument("--skip-if", default="", help="이 원본 클래스(이름)가 들어 있는 사진은 뺌, 쉼표로 여러 개")
    args = ap.parse_args()
    targets = args.names.split(",")
    if len(args.src) != len(args.map):
        raise SystemExit("--src 와 --map 은 같은 개수로 짝지어 주세요")

    if args.dst.exists():
        shutil.rmtree(args.dst)
    stats = {"sources": {}, "images": Counter(), "background_images": 0}
    seen = set()
    for src, spec in zip(args.src, args.map):
        names = yaml.safe_load((src / "data.yaml").read_text(encoding="utf-8"))["names"]
        names = list(names.values()) if isinstance(names, dict) else list(names)
        names = [str(n) for n in names]
        cmap = parse_map(spec, names, targets)
        skip = {names.index(n) for n in filter(None, args.skip_if.split(",")) if n in names}
        s = Counter()
        for img in sorted(p for p in src.glob("*/images/*") if p.suffix.lower() in IMG_EXT):
            label = img.parent.parent / "labels" / f"{img.stem}.txt"
            if skip and label.exists() and any(int(r.split()[0]) in skip for r in label.read_text(encoding="utf-8").splitlines() if r.strip()):
                s["skipped_images"] += 1
                continue
            lines = convert(label, cmap, s, targets) if label.exists() else []
            group = group_of(img.stem)
            split = split_of(group, args.seed, args.val, args.test)
            name = f"{src.name}__{img.stem}"
            if name in seen:
                continue
            seen.add(name)
            (args.dst / "images" / split).mkdir(parents=True, exist_ok=True)
            (args.dst / "labels" / split).mkdir(parents=True, exist_ok=True)
            shutil.copy2(img, args.dst / "images" / split / f"{name}{img.suffix}")
            (args.dst / "labels" / split / f"{name}.txt").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
            stats["images"][split] += 1
            stats["background_images"] += not lines
            s["images"] += 1
        stats["sources"][src.name] = {"classes": names, "map": spec, **s}
        print(src.name, dict(s))

    cfg = {"path": str(args.dst.resolve()).replace("\\", "/"), "train": "images/train", "val": "images/val", "test": "images/test",
           "names": dict(enumerate(targets))}
    (args.dst / "data.yaml").write_text("# training/import_roboflow.py 로 생성\n" + yaml.safe_dump(cfg, allow_unicode=True, sort_keys=False), encoding="utf-8")
    (args.dst / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({"images": stats["images"], "background_images": stats["background_images"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
