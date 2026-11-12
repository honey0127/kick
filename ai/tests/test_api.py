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


def test_bad_location_gives_no_token(client):
    r = client.post("/api/return/location", json={**PARKING, "lat": 35.89235, "lon": 128.60965}).json()
    assert not r["ok"] and r["code"] == "NO_PARKING_ZONE" and "token" not in r
    r = client.post("/api/return/location", json={**PARKING, "accuracy": 120}).json()
    assert r["code"] == "GPS_INACCURATE"
