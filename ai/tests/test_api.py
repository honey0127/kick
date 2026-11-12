"""반납 순서(위치 -> 사진 -> 결제) 강제 확인. AI 모델 대신 가짜 분석기를 쓴다."""
import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from server import main


class FakeAnalyzer:
    backend = "fake"

    def __init__(self, status):
        self.status = status

    def analyze(self, img):
        return {"status": self.status, "reasons": [], "metrics": {}, "overlay_jpeg": ""}


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setattr(main.config, "COLLECT_DIR", tmp_path)
    return TestClient(main.app)  # with 블록 없이 쓰면 lifespan(모델 로드)이 돌지 않음


PARKING = {"vehicle_id": "KB1", "lat": 35.89265, "lon": 128.609, "accuracy": 5}
JPG = cv2.imencode(".jpg", np.zeros((32, 32, 3), np.uint8))[1].tobytes()


def photo(client, token):
    return client.post("/api/return/photo", data={"token": token}, files={"file": ("p.jpg", JPG, "image/jpeg")})


def test_full_flow(client, monkeypatch):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("APPROVE"))
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    assert client.post("/api/return/pay", json={"token": token}).status_code == 409  # 사진 전 결제 불가
    assert photo(client, token).json()["status"] == "APPROVE"
    r = client.post("/api/return/pay", json={"token": token})
    assert r.status_code == 200 and r.json()["ok"]
    assert client.post("/api/return/pay", json={"token": token}).status_code == 409  # 중복 결제 불가


def test_rejected_photo_blocks_payment(client, monkeypatch):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("REJECT"))
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    assert photo(client, token).json()["status"] == "REJECT"
    assert client.post("/api/return/pay", json={"token": token}).status_code == 409


def test_photo_attempts_limited(client, monkeypatch):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("REJECT"))
    monkeypatch.setattr(main.config, "MAX_PHOTO_ATTEMPTS", 2)
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    assert photo(client, token).status_code == 200
    assert photo(client, token).status_code == 200
    assert photo(client, token).status_code == 429
    assert photo(client, token).status_code == 410  # 세션이 끝나 위치 확인부터 다시


def test_upload_too_large(client, monkeypatch):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("APPROVE"))
    monkeypatch.setattr(main.config, "MAX_UPLOAD_MB", 0.001)  # 약 1KB
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    r = client.post("/api/return/photo", data={"token": token}, files={"file": ("p.jpg", b"x" * 2000, "image/jpeg")})
    assert r.status_code == 413


def test_saved_photo_has_no_exif(client, monkeypatch, tmp_path):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("APPROVE"))
    monkeypatch.setattr(main.config, "SAVE_UPLOADS", True)
    tiff = b"II*\x00" + (8).to_bytes(4, "little") + (0).to_bytes(2, "little") + (0).to_bytes(4, "little")
    app1 = b"Exif\x00\x00" + tiff
    with_exif = JPG[:2] + b"\xff\xe1" + (len(app1) + 2).to_bytes(2, "big") + app1 + JPG[2:]
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    r = client.post("/api/return/photo", data={"token": token}, files={"file": ("p.jpg", with_exif, "image/jpeg")})
    assert r.status_code == 200
    saved = list(tmp_path.rglob("*.jpg"))
    assert len(saved) == 1 and b"Exif" not in saved[0].read_bytes()


def test_open_analyze_without_dev_mode(client, monkeypatch):
    monkeypatch.setattr(main.config, "DEV_MODE", False)
    monkeypatch.setattr(main.config, "OPEN_ANALYZE", True)
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("REJECT"))
    assert client.get("/api/config").json() == {"dev": False}  # 테스트 위치 버튼은 계속 숨김
    assert client.post("/api/analyze", files={"file": ("p.jpg", JPG, "image/jpeg")}).json()["status"] == "REJECT"


def test_dev_endpoints_off_by_default(client, monkeypatch):
    monkeypatch.setattr(main.config, "DEV_MODE", False)
    monkeypatch.setattr(main.config, "OPEN_ANALYZE", False)
    assert client.get("/api/config").json() == {"dev": False}
    assert client.post("/api/analyze", files={"file": ("p.jpg", JPG, "image/jpeg")}).status_code == 404


def test_dev_mode_enables_analyze(client, monkeypatch):
    monkeypatch.setattr(main.config, "DEV_MODE", True)
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("APPROVE"))
    assert client.post("/api/analyze", files={"file": ("p.jpg", JPG, "image/jpeg")}).json()["status"] == "APPROVE"


def test_expired_sessions_purged(client):
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    main.sessions[token]["created"] -= main.config.SESSION_TTL_S + 1
    client.post("/api/return/location", json=PARKING)
    assert token not in main.sessions


def test_bad_location_gives_no_token(client):
    r = client.post("/api/return/location", json={**PARKING, "lat": 35.89235, "lon": 128.60965}).json()
    assert not r["ok"] and r["code"] == "NO_PARKING_ZONE" and "token" not in r
    r = client.post("/api/return/location", json={**PARKING, "accuracy": 120}).json()
    assert r["code"] == "GPS_INACCURATE"
