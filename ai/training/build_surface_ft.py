"""바닥 모델 보강용 데이터셋: AI Hub 189 일부 + Roboflow lane 의 국내 보도 사진.

lane 은 보도·차도만 라벨돼 있고 점자블록·횡단보도 라벨이 없다. 점자블록이 찍힌 사진을 그대로 넣으면
'점자블록 = 배경'으로 배우므로, 지금 바닥 모델이 점자블록·횡단보도를 찾는 사진은 뺀다.

  python training/import_roboflow.py --src data/roboflow/lane-eb6kp --names sidewalk,road,braille_block,crosswalk \
      --map "Sidewalk=sidewalk,Road=road,BicRoad=road,Lane=road,Curb=drop,Verge=drop,kick=drop" --skip-if kick --dst data/lane_surface
  python training/build_surface_ft.py --aihub D:/kick/data/aihub189_surface
  python training/train.py --data data/surface_ft/data.yaml --model models/kick_surface.pt --name kick_surface_v2 --out kick_surface_v2.pt --epochs 15
"""
import argparse
import random
from pathlib import Path

import yaml
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent
NAMES = ["sidewalk", "road", "braille_block", "crosswalk"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aihub", type=Path, required=True, help="import_aihub189.py 결과 폴더")
    ap.add_argument("--lane", type=Path, default=ROOT / "data" / "lane_surface")
    ap.add_argument("--dst", type=Path, default=ROOT / "data" / "surface_ft")
    ap.add_argument("--aihub-train", type=int, default=2000)
    ap.add_argument("--aihub-val", type=int, default=400)
    ap.add_argument("--model", type=Path, default=ROOT / "models" / "kick_surface.pt")
    ap.add_argument("--conf", type=float, default=0.4)
    args = ap.parse_args()

    model = YOLO(str(args.model))
    risky = {i for i, n in model.names.items() if n in ("braille_block", "crosswalk")}
    args.dst.mkdir(parents=True, exist_ok=True)
    rng = random.Random(0)
    lists, kept = {}, {}
    for split, n_aihub in (("train", args.aihub_train), ("val", args.aihub_val), ("test", 0)):
        lane = []
        for img in sorted((args.lane / "images" / split).glob("*")):
            r = model.predict(str(img), conf=args.conf, verbose=False)[0]
            if r.boxes is not None and any(int(c) in risky for c in r.boxes.cls.tolist()):
                continue
            lane.append(img)
        aihub = sorted((args.aihub / "images" / split).glob("*.jpg"))
        aihub = rng.sample(aihub, min(n_aihub, len(aihub))) if n_aihub else []
        paths = [str(p.resolve()).replace("\\", "/") for p in lane + aihub]
        (args.dst / f"{split}.txt").write_text("\n".join(paths) + "\n", encoding="utf-8")
        lists[split] = f"{split}.txt"
        kept[split] = {"lane": len(lane), "lane_dropped": len(list((args.lane / "images" / split).glob("*"))) - len(lane), "aihub": len(aihub)}
    cfg = {"path": str(args.dst.resolve()).replace("\\", "/"), **lists, "names": dict(enumerate(NAMES))}
    (args.dst / "data.yaml").write_text("# training/build_surface_ft.py 로 생성\n" + yaml.safe_dump(cfg, sort_keys=False), encoding="utf-8")
    # test.txt 는 lane 시험 사진만 들어 있다 (국내 보도 장면에서 좋아졌는지 보는 용도). AI Hub 쪽은 원래 test split 으로 따로 잰다.
    print(kept)


if __name__ == "__main__":
    main()
