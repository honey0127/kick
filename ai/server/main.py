"""반납 API.  순서 강제: ① GPS 위치 확인 -> ② 주차 사진 AI 판정 -> ③ 반납 완료

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
from .rules import APPROVE, RETAKE
from .zones import ZoneIndex

def _warm_up() -> None:
    """모델을 불러오고 빈 사진으로 한 번 판정해 둔다. 첫 판정만 몇 초 걸리는 것을 시연 전에 미리 치른다."""
    t = time.perf_counter()
    analyzer = get_analyzer()
    try:
        analyzer.analyze(np.zeros((720, 960, 3), np.uint8))
    except Exception as e:  # 미리 돌리기 실패는 서비스에 영향 없음 (첫 요청이 조금 느릴 뿐)
        print(f"[warm-up] 실패: {e!r}")
        return
    print(f"[warm-up] 모델 준비 완료 ({(time.perf_counter() - t) * 1000:.0f}ms)")


@asynccontextmanager
async def lifespan(_app):
    threading.Thread(target=_warm_up, daemon=True).start()  # 첫 요청 전에 모델 로드 + 한 번 판정
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
            "retakes": 0,  # 연달아 나온 재촬영(RETAKE) 횟수
            "review": False,  # 재촬영이 이어져 운영팀 검토 조건으로 반납을 받는 세션인지
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
    status = result["status"]
    # 재촬영이 연달아 나온 횟수. 통과·거절이 나오면 0으로 돌아간다.
    s["retakes"] = s["retakes"] + 1 if status == RETAKE else 0
    # AI가 판단하지 못해 재촬영이 이어지면 반납은 받되, 이 사진을 운영팀 검토 대상으로 남긴다.
    # 판정 자체는 RETAKE 그대로 돌려준다 (통과로 바꾸지 않는다). 거절(REJECT)은 다시 세워야만 반납된다.
    s["review"] = status == RETAKE and 0 < config.REVIEW_AFTER_RETAKES <= s["retakes"]
    s["stage"] = "photo_ok" if status == APPROVE or s["review"] else "location_ok"
    result["review"] = s["review"]
    if s["review"]:
        result["review_message"] = "사진으로 판단하기 어려운 상태가 이어졌어요. 반납은 받고, 이 사진은 운영팀이 확인합니다."
    s["photo_id"] = result["photo_id"] = _save(
        img, result, {k: s[k] for k in ("vehicle_id", "lat", "lon", "attempts", "retakes", "review")}
    )
    result["attempts"] = s["attempts"]
    return result


def _queue_review(s: dict, return_id: str) -> None:
    """검토 조건으로 받은 반납을 운영팀 검토 목록(review_queue.jsonl)에 한 줄 남긴다."""
    if not config.SAVE_UPLOADS:
        return
    config.COLLECT_DIR.mkdir(parents=True, exist_ok=True)
    rec = {
        "return_id": return_id,
        "vehicle_id": s["vehicle_id"],
        "photo_id": s.get("photo_id", ""),
        "lat": s["lat"],
        "lon": s["lon"],
        "time": datetime.now().isoformat(timespec="seconds"),
    }
    with (config.COLLECT_DIR / "review_queue.jsonl").open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


@app.post("/api/return/complete")
def complete(body: TokenIn):
    """사진이 APPROVE 됐거나, 재촬영이 이어져 검토 조건이 된 세션만 반납 완료 처리 (결제는 범위에서 뺌)."""
    s = _session(body.token, "photo_ok")
    s["stage"] = "done"
    return_id = "RT" + secrets.token_hex(4).upper()
    if s["review"]:
        _queue_review(s, return_id)
    return {"ok": True, "vehicle_id": s["vehicle_id"], "zone": s["zone"], "return_id": return_id, "review": s["review"]}  # zone: 위치 확인 때와 같은 형식 (지정 주차구역이면 {"name": ...}, 아니면 null)


@app.post("/api/analyze")
async def analyze_only(file: UploadFile = File(...)):
    """세션 없이 사진만 판정 (KICK_DEV=1 또는 KICK_OPEN_ANALYZE=1 일 때만)."""
    if not (config.DEV_MODE or config.OPEN_ANALYZE):
        raise HTTPException(404, "Not Found")
    img = _decode(await _read_upload(file))
    return await run_in_threadpool(get_analyzer().analyze, img)


app.mount("/", StaticFiles(directory=config.ROOT / "web", html=True), name="web")
