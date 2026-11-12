"""반납 API.  순서 강제: ① GPS 위치 확인 -> ② 주차 사진 AI 판정 -> ③ 결제

실행:  .venv\\Scripts\\python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import json
import secrets
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import config
from .rules import APPROVE
from .zones import ZoneIndex

@asynccontextmanager
async def lifespan(_app):
    threading.Thread(target=get_analyzer, daemon=True).start()  # 첫 요청 전에 모델 미리 로드
    yield


app = FastAPI(title="안주잘부 - 공유 PM 반납 판정", lifespan=lifespan)
zones = ZoneIndex(config.ZONES_FILE, config.REQUIRE_PARKING_ZONE, config.MAX_GPS_ACCURACY_M)
sessions: dict[str, dict] = {}

_analyzer = None
_analyzer_lock = threading.Lock()


def get_analyzer():
    global _analyzer
    with _analyzer_lock:
        if _analyzer is None:
            from .analyzer import Analyzer

            _analyzer = Analyzer()
    return _analyzer


def _session(token: str, *stages: str) -> dict:
    s = sessions.get(token)
    if not s or time.time() - s["created"] > config.SESSION_TTL_S:
        sessions.pop(token, None)
        raise HTTPException(410, "반납 세션이 만료됐어요. 위치 확인부터 다시 해주세요.")
    if s["stage"] not in stages:
        raise HTTPException(409, f"순서가 맞지 않아요 (현재 단계: {s['stage']}).")
    return s


def _purge_expired_sessions() -> None:
    now = time.time()
    for token, s in list(sessions.items()):
        if now - s["created"] > config.SESSION_TTL_S:
            sessions.pop(token, None)


async def _read_upload(file: UploadFile) -> bytes:
    limit = int(config.MAX_UPLOAD_MB * 1024 * 1024)
    data = await file.read(limit + 1)
    if len(data) > limit:
        raise HTTPException(413, f"사진이 너무 커요. {config.MAX_UPLOAD_MB:g}MB 이하로 올려주세요.")
    return data


def _decode(data: bytes) -> np.ndarray:
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(400, "이미지를 읽을 수 없어요.")
    return img


def _save(img: np.ndarray, result: dict, meta: dict) -> str:
    if not config.SAVE_UPLOADS:
        return ""
    day = datetime.now().strftime("%Y%m%d")
    d = config.COLLECT_DIR / day
    d.mkdir(parents=True, exist_ok=True)
    name = datetime.now().strftime("%H%M%S_") + secrets.token_hex(3)
    # 원본 바이트 대신 다시 인코딩해 저장: EXIF(촬영 GPS·기기 정보)를 남기지 않는다
    (d / f"{name}.jpg").write_bytes(cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 92])[1].tobytes())
    rec = {k: v for k, v in result.items() if not k.endswith("_jpeg")} | {"meta": meta}
    (d / f"{name}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=1), encoding="utf-8")
    return f"{day}/{name}"


# ---------------- API ----------------
class LocationIn(BaseModel):
    vehicle_id: str
    lat: float
    lon: float
    accuracy: float | None = None


class TokenIn(BaseModel):
    token: str


@app.get("/api/health")
def health():
    return {"ok": True, "model_loaded": _analyzer is not None, "backend": _analyzer.backend if _analyzer else None}


@app.get("/api/config")
def get_config():
    return {"dev": config.DEV_MODE}


@app.get("/api/zones")
def get_zones():
    return zones.geojson


@app.post("/api/return/location")
def check_location(body: LocationIn):
    _purge_expired_sessions()
    r = zones.check(body.lat, body.lon, body.accuracy)
    if r["ok"]:
        token = secrets.token_urlsafe(16)
        sessions[token] = {
            "stage": "location_ok",
            "vehicle_id": body.vehicle_id,
            "lat": body.lat,
            "lon": body.lon,
            "zone": r["zone"],
            "created": time.time(),
            "attempts": 0,
        }
        r["token"] = token
    return r


@app.post("/api/return/photo")
async def check_photo(token: str = Form(...), file: UploadFile = File(...)):
    s = _session(token, "location_ok", "photo_ok")
    if s["attempts"] >= config.MAX_PHOTO_ATTEMPTS:
        sessions.pop(token, None)
        raise HTTPException(429, "사진 판정 횟수를 넘었어요. 위치 확인부터 다시 해주세요.")
    img = _decode(await _read_upload(file))
    result = await run_in_threadpool(get_analyzer().analyze, img)
    s["attempts"] += 1
    s["stage"] = "photo_ok" if result["status"] == APPROVE else "location_ok"
    result["photo_id"] = _save(img, result, {k: s[k] for k in ("vehicle_id", "lat", "lon", "attempts")})
    result["attempts"] = s["attempts"]
    return result


@app.post("/api/return/pay")
def pay(body: TokenIn):
    s = _session(body.token, "photo_ok")
    # TODO: 실제 PG 연동. 지금은 데모 요금으로 결제 성공 처리.
    amount = 1200 + 180 * 12
    s["stage"] = "paid"
    return {"ok": True, "vehicle_id": s["vehicle_id"], "amount": amount, "receipt_id": "R" + secrets.token_hex(4).upper()}


@app.post("/api/analyze")
async def analyze_only(file: UploadFile = File(...)):
    """세션 없이 사진만 판정 (KICK_DEV=1 또는 KICK_OPEN_ANALYZE=1 일 때만)."""
    if not (config.DEV_MODE or config.OPEN_ANALYZE):
        raise HTTPException(404, "Not Found")
    img = _decode(await _read_upload(file))
    return await run_in_threadpool(get_analyzer().analyze, img)


app.mount("/", StaticFiles(directory=config.ROOT / "web", html=True), name="web")
