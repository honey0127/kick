import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models"
DATA_DIR = ROOT / "data"

# 직접 학습한 모델 (training/train.py 가 여기로 복사). 없으면 베이스라인으로 동작.
CUSTOM_WEIGHTS = Path(os.getenv("KICK_WEIGHTS", MODELS_DIR / "kick_seg.pt"))
# 바닥 전용 모델 (AI Hub 인도보행 Surface 로 학습: sidewalk/road/braille_block/crosswalk). 있으면 SegFormer 대신 사용.
SURFACE_WEIGHTS = Path(os.getenv("KICK_SURFACE_WEIGHTS", MODELS_DIR / "kick_surface.pt"))
# 베이스라인: COCO 사전학습 YOLO-seg (bicycle/motorcycle) + Cityscapes SegFormer (road/sidewalk)
BASELINE_YOLO = os.getenv("KICK_BASELINE_YOLO", str(MODELS_DIR / "yolo11s-seg.pt"))
SEGFORMER = os.getenv("KICK_SEGFORMER", "nvidia/segformer-b2-finetuned-cityscapes-1024-1024")
DEVICE = os.getenv("KICK_DEVICE", "")  # "" = 자동(cuda 있으면 cuda)
CONF = float(os.getenv("KICK_CONF", "0.25"))
MAX_SIDE = int(os.getenv("KICK_MAX_SIDE", "1280"))  # 업로드 사진 긴 변을 이 크기로 줄여서 분석

# 반납 사진 저장 (재라벨링해서 모델을 다시 학습시키는 용도)
SAVE_UPLOADS = os.getenv("KICK_SAVE_UPLOADS", "1") == "1"
COLLECT_DIR = DATA_DIR / "collected"

# GPS
ZONES_FILE = Path(os.getenv("KICK_ZONES", ROOT / "server" / "zones.geojson"))
REQUIRE_PARKING_ZONE = os.getenv("KICK_REQUIRE_PARKING_ZONE", "0") == "1"  # 1이면 지정 주차구역 안에서만 반납
MAX_GPS_ACCURACY_M = float(os.getenv("KICK_MAX_GPS_ACCURACY_M", "50"))
SESSION_TTL_S = int(os.getenv("KICK_SESSION_TTL_S", "600"))  # 위치 확인 후 사진·결제까지 유효 시간
