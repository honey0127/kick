"""YOLO-seg 학습. 끝나면 best.pt 를 models/<--out> 으로 복사 -> 서버가 자동으로 사용.

GTX 1050 Ti(4GB) 기준 권장:
  python training/train.py --model yolo11n-seg.pt --batch 8          # 가볍고 빠름
  python training/train.py --model yolo11s-seg.pt --batch 4          # 더 정확, 더 느림

바닥 전용 모델 (AI Hub 인도보행 Surface, training/import_aihub189.py 로 변환):
  python training/train.py --data data/aihub189_surface/data.yaml --out kick_surface.pt --name kick_surface
"""
import argparse
import shutil
import tempfile
from pathlib import Path

import yaml
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=ROOT / "training" / "dataset.yaml")
    ap.add_argument("--model", default="yolo11n-seg.pt", help="COCO 사전학습 가중치에서 시작 (전이학습)")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--device", default="0", help="GPU 번호, 없으면 cpu")
    ap.add_argument("--name", default="kick_seg")
    ap.add_argument("--out", default="kick_seg.pt", help="models/ 아래 저장할 이름 (바닥 모델은 kick_surface.pt)")
    ap.add_argument("--fraction", type=float, default=1.0, help="학습 이미지 중 이 비율만 사용 (시험용)")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--resume", action="store_true", help="중단된 학습을 runs/<name>/weights/last.pt 에서 이어하기")
    args = ap.parse_args()

    cfg = yaml.safe_load(args.data.read_text(encoding="utf-8"))
    cfg["path"] = str((args.data.parent / cfg["path"]).resolve())  # ultralytics 는 상대경로를 자기 datasets 폴더 기준으로 해석함
    tmp = Path(tempfile.gettempdir()) / "kick_dataset.yaml"
    tmp.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")

    if args.resume:
        YOLO(str(ROOT / "runs" / args.name / "weights" / "last.pt")).train(resume=True)
    else:
        _train(args, tmp)
    _finish(args, tmp)


def _train(args, tmp):
    model = YOLO(args.model)
    model.train(
        data=str(tmp),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        project=str(ROOT / "runs"),
        name=args.name,
        exist_ok=True,
        patience=30,
        fraction=args.fraction,
        workers=args.workers,
        amp=False,  # GTX 1050 Ti 는 FP16 이 느리고 AMP 점검이 실패함 (edge_walk 에서 확인)
        # 야간·역광 반납 사진이 많아서 밝기 증강을 넉넉히
        hsv_v=0.5,
        degrees=5,
        fliplr=0.5,
        close_mosaic=10,
    )


def _finish(args, tmp):
    best = ROOT / "runs" / args.name / "weights" / "best.pt"
    (ROOT / "models").mkdir(exist_ok=True)
    shutil.copy2(best, ROOT / "models" / args.out)
    print(f"\n완료: {best} -> models/{args.out} (서버 재시작하면 적용)")
    metrics = YOLO(best).val(data=str(tmp), imgsz=args.imgsz, device=args.device, verbose=False)
    print(f"mask mAP50 = {metrics.seg.map50:.3f}  (목표: 킥보드 0.85 이상)")


if __name__ == "__main__":
    main()
