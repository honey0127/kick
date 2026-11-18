"""AI Hub '인도보행 영상'(189) Surface 폴리곤 -> YOLO-seg 바닥(surface) 데이터셋.

원본(CVAT XML, 1920x1080 보행자 시점)의 바닥 폴리곤을 우리 클래스로 바꾼다.
  sidewalk(전부)                       -> sidewalk
  braille_guide_blocks(전부)           -> braille_block
  roadway·alley·bike_lane (normal 등)  -> road      (킥보드를 세우면 안 되는 곳)
  roadway·alley 의 attribute=crosswalk -> crosswalk
  caution_zone(맨홀·가로수 구역 등)     -> 제외

- 이미지는 복사하지 않고 하드링크 (같은 D: 드라이브라 추가 용량 0, 원본 폴더는 건드리지 않음)
- 연속 프레임이 train/val 에 같이 들어가면 성능이 부풀려지므로 영상(XML) 단위로 나눈다
- 완료 후: python training/train.py --data data/aihub189_surface/data.yaml --out kick_surface.pt

python training/import_aihub189.py --src D:/edge_walk/data/local/aihub/189/Surface
"""
import argparse
import hashlib
import json
import os
import shutil
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
NAMES = ["sidewalk", "road", "braille_block", "crosswalk"]  # server/analyzer.py STUFF_CLASSES 와 같은 이름


def target_of(label: str, attr: str | None) -> str | None:
    if label == "sidewalk":
        return "sidewalk"
    if label == "braille_guide_blocks":
        return "braille_block"
    if label in ("roadway", "alley"):
        return "crosswalk" if attr == "crosswalk" else "road"
    if label == "bike_lane":
        return "road"
    return None


def polygon_area(p: np.ndarray) -> float:
    x, y = p[:, 0], p[:, 1]
    return 0.5 * abs(np.dot(x, np.roll(y, 1)) - np.dot(y, np.roll(x, 1)))


def parse_xml(path: Path, min_area_frac: float):
    """yield (image_name, [(class_id, Nx2 정규화 좌표), ...]), stats Counter"""
    stats = Counter()
    for _, el in ET.iterparse(path, events=("end",)):
        if el.tag != "image":
            continue
        w, h = float(el.get("width")), float(el.get("height"))
        polys = []
        for pg in sorted(el.findall("polygon"), key=lambda e: int(e.get("z_order", 0))):
            a = pg.find("attribute")
            label, attr = pg.get("label"), (a.text if a is not None else None)
            tgt = target_of(label, attr)
            stats[f"{label}|{attr} -> {tgt}"] += 1
            if tgt is None:
                continue
            pts = np.array([[float(v) for v in xy.split(",")] for xy in pg.get("points").split(";")])
            if len(pts) < 3 or polygon_area(pts) < min_area_frac * w * h:
                stats["too_small"] += 1
                continue
            pts = np.clip(pts / [w, h], 0, 1)
            polys.append((NAMES.index(tgt), pts))
        yield el.get("name"), polys
        el.clear()
    return stats


def split_of(group: str, seed: int, val: float, test: float) -> str:
    u = int(hashlib.sha256(f"{seed}:{group}".encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "val" if u < val else "test" if u < val + test else "train"


def link(src: Path, dst: Path):
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)  # 다른 드라이브면 복사


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=Path, default=Path("D:/edge_walk/data/local/aihub/189/Surface"))
    ap.add_argument("--dst", type=Path, default=ROOT / "data" / "aihub189_surface")
    ap.add_argument("--val", type=float, default=0.1)
    ap.add_argument("--test", type=float, default=0.05)
    ap.add_argument("--stride", type=int, default=2, help="영상 안에서 N 프레임마다 1장 (연속 프레임은 거의 같은 장면)")
    ap.add_argument("--min-area", type=float, default=0.0005, help="이미지 대비 이보다 작은 폴리곤은 버림")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--limit-groups", type=int, default=0, help="시험용: 영상 N 개만")
    args = ap.parse_args()

    xmls = sorted(args.src.glob("Surface_*/*/*.xml"))
    if args.limit_groups:
        xmls = xmls[: args.limit_groups]
    if args.dst.exists():
        shutil.rmtree(args.dst)
    for s in ("train", "val", "test"):
        (args.dst / "images" / s).mkdir(parents=True)
        (args.dst / "labels" / s).mkdir(parents=True)

    stats, per_split, per_class, n_empty = Counter(), Counter(), Counter(), 0
    for gi, x in enumerate(xmls, 1):
        group = f"{x.parent.parent.name}/{x.parent.name}/{x.stem}"
        split = split_of(group, args.seed, args.val, args.test)
        gen = parse_xml(x, args.min_area)
        k = 0
        while True:
            try:
                name, polys = next(gen)
            except StopIteration as e:
                stats.update(e.value or {})
                break
            k += 1
            if (k - 1) % args.stride:
                continue
            img = x.parent / name
            if not img.exists():
                stats["missing_image"] += 1
                continue
            if not polys:
                n_empty += 1
            stem = f"{x.parent.parent.name}_{x.parent.name}_{Path(name).stem}"
            link(img, args.dst / "images" / split / f"{stem}{img.suffix}")
            lines = [f"{c} " + " ".join(f"{px:.4f} {py:.4f}" for px, py in p) for c, p in polys]
            (args.dst / "labels" / split / f"{stem}.txt").write_text("\n".join(lines), encoding="utf-8")
            per_split[split] += 1
            per_class.update(NAMES[c] for c, _ in polys)
        if gi % 50 == 0 or gi == len(xmls):
            print(f"[{gi}/{len(xmls)}] {dict(per_split)}")

    (args.dst / "data.yaml").write_text(
        "# training/import_aihub189.py 로 생성. 원본: AI Hub 인도보행 영상 Surface\n"
        f"path: {args.dst.resolve().as_posix()}\n"
        "train: images/train\nval: images/val\ntest: images/test\nnames:\n"
        + "".join(f"  {i}: {n}\n" for i, n in enumerate(NAMES)),
        encoding="utf-8",
    )
    summary = {"src": str(args.src), "groups": len(xmls), "stride": args.stride, "images": dict(per_split),
               "empty_images": n_empty, "polygons_per_class": dict(per_class), "source_labels": dict(stats)}
    (args.dst / "stats.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("images", "empty_images", "polygons_per_class")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
