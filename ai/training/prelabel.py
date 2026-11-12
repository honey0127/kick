"""사전 라벨 생성: 베이스라인 AI 가 먼저 마스크를 그려서 YOLO-seg 라벨(.txt)로 저장.
CVAT / Roboflow 에 이미지와 함께 올려서 '고치기만' 하면 되므로 라벨링 시간이 크게 줄어든다.

  python training/prelabel.py data/raw
  -> data/prelabels/images/*.jpg, data/prelabels/labels/*.txt

자전거는 COCO 모델이 그려 주지만, 넘어진 자전거는 직접 fallen_bicycle 로 바꿔야 한다.
점자블록·횡단보도는 베이스라인이 못 그리므로 라벨링 툴에서 직접 추가해야 한다.
"""
import argparse
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np
import yaml

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from server import config  # noqa: E402

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def mask_to_polygons(mask: np.ndarray, min_area: float, eps_frac: float = 0.004) -> list[np.ndarray]:
    h, w = mask.shape
    contours, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    polys = []
    for c in contours:
        if cv2.contourArea(c) < min_area:
            continue
        c = cv2.approxPolyDP(c, eps_frac * cv2.arcLength(c, True), True)
        if len(c) >= 3:
            polys.append(c.reshape(-1, 2) / [w, h])
    return polys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src", type=Path, help="원본 사진 폴더")
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "prelabels")
    args = ap.parse_args()

    names = yaml.safe_load((ROOT / "training" / "dataset.yaml").read_text(encoding="utf-8"))["names"]
    cid = {v: k for k, v in names.items()}

    # 사전 라벨은 반드시 베이스라인으로 (학습된 모델로 하면 그 모델의 실수를 그대로 학습하게 됨)
    config.CUSTOM_WEIGHTS = Path("__none__")
    from server.analyzer import Analyzer, resize_max

    an = Analyzer()
    (args.out / "images").mkdir(parents=True, exist_ok=True)
    (args.out / "labels").mkdir(parents=True, exist_ok=True)

    files = sorted(p for p in args.src.rglob("*") if p.suffix.lower() in IMG_EXT)
    for i, p in enumerate(files, 1):
        img = cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            continue
        img = resize_max(img, config.MAX_SIDE)
        scene = an.perceive(img)
        min_area = img.shape[0] * img.shape[1] * 0.002
        lines = []
        layers = [(cid["bicycle"], v.mask) for v in scene.vehicles]
        layers += [(cid["sidewalk"], scene.sidewalk), (cid["road"], scene.road)]
        for c, mask in layers:
            for poly in mask_to_polygons(mask, min_area):
                lines.append(f"{c} " + " ".join(f"{x:.5f} {y:.5f}" for x, y in poly))
        shutil.copy2(p, args.out / "images" / p.name)  # 좌표가 0~1 정규화라 원본 해상도 그대로 써도 됨
        (args.out / "labels" / f"{p.stem}.txt").write_text("\n".join(lines), encoding="utf-8")
        print(f"[{i}/{len(files)}] {p.name}: 기기 {len(scene.vehicles)}대, 폴리곤 {len(lines)}개")

    (args.out / "data.yaml").write_text(
        yaml.safe_dump({"names": names, "train": "images", "val": "images"}, allow_unicode=True), encoding="utf-8"
    )
    print(f"\n완료 -> {args.out}  (CVAT/Roboflow 에 'YOLO segmentation' 형식으로 업로드)")


if __name__ == "__main__":
    main()
