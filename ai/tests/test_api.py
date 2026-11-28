"""반납 순서(위치 -> 사진 -> 반납 완료) 강제 확인. AI 모델 대신 가짜 분석기를 쓴다."""
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
    assert client.post("/api/return/complete", json={"token": token}).status_code == 409  # 사진 전 반납 완료 불가
    assert photo(client, token).json()["status"] == "APPROVE"
    r = client.post("/api/return/complete", json={"token": token})
    assert r.status_code == 200 and r.json()["ok"]
    assert client.post("/api/return/complete", json={"token": token}).status_code == 409  # 중복 완료 불가


def test_rejected_photo_blocks_completion(client, monkeypatch):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("REJECT"))
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    assert photo(client, token).json()["status"] == "REJECT"
    assert client.post("/api/return/complete", json={"token": token}).status_code == 409


def test_photo_attempts_limited(client, monkeypatch):
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("REJECT"))
    monkeypatch.setattr(main.config, "MAX_PHOTO_ATTEMPTS", 2)
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    assert photo(client, token).status_code == 200
    assert photo(client, token).status_code == 200
    assert photo(client, token).status_code == 429
    assert photo(client, token).status_code == 410  # 세션이 끝나 위치 확인부터 다시


class SequenceAnalyzer:
    """부를 때마다 정해 둔 판정을 차례로 돌려주는 가짜 분석기."""

    backend = "fake"

    def __init__(self, *statuses):
        self.statuses = list(statuses)

    def analyze(self, img):
        return {"status": self.statuses.pop(0), "reasons": [], "metrics": {}, "overlay_jpeg": ""}


def test_review_return_after_consecutive_retakes(client, monkeypatch, tmp_path):
    """재촬영이 3번 이어지면 판정은 RETAKE 그대로 두고, 운영팀 검토 조건으로 반납을 받는다."""
    monkeypatch.setattr(main.config, "SAVE_UPLOADS", True)
    monkeypatch.setattr(main.config, "REVIEW_AFTER_RETAKES", 3)
    fake = SequenceAnalyzer("RETAKE", "RETAKE", "RETAKE")
    monkeypatch.setattr(main, "get_analyzer", lambda: fake)
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    for _ in range(2):
        r = photo(client, token).json()
        assert r["status"] == "RETAKE" and r["review"] is False
        assert client.post("/api/return/complete", json={"token": token}).status_code == 409
    r = photo(client, token).json()
    assert r["status"] == "RETAKE" and r["review"] is True and r["review_message"]
    done = client.post("/api/return/complete", json={"token": token})
    assert done.status_code == 200 and done.json()["review"] is True
    queue = (tmp_path / "review_queue.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(queue) == 1 and done.json()["return_id"] in queue[0] and r["photo_id"] in queue[0]


def test_rejected_photo_never_becomes_review_return(client, monkeypatch):
    """거절(REJECT)은 몇 번을 찍어도 검토 조건 반납이 되지 않는다."""
    monkeypatch.setattr(main.config, "REVIEW_AFTER_RETAKES", 3)
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("REJECT"))
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    for _ in range(5):
        assert photo(client, token).json()["review"] is False
    assert client.post("/api/return/complete", json={"token": token}).status_code == 409


def test_reject_resets_retake_streak_and_cancels_review(client, monkeypatch):
    """재촬영 사이에 거절이 끼면 횟수를 처음부터 다시 세고, 검토 조건이 된 뒤 거절이 나오면 반납을 다시 막는다."""
    monkeypatch.setattr(main.config, "REVIEW_AFTER_RETAKES", 3)
    fake = SequenceAnalyzer("RETAKE", "RETAKE", "REJECT", "RETAKE", "RETAKE", "RETAKE", "REJECT")
    monkeypatch.setattr(main, "get_analyzer", lambda: fake)
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    assert [photo(client, token).json()["review"] for _ in range(6)] == [False, False, False, False, False, True]
    assert photo(client, token).json()["review"] is False  # 검토 조건이 된 뒤 다시 찍은 사진이 거절
    assert client.post("/api/return/complete", json={"token": token}).status_code == 409


def test_review_return_can_be_turned_off(client, monkeypatch):
    monkeypatch.setattr(main.config, "REVIEW_AFTER_RETAKES", 0)
    monkeypatch.setattr(main, "get_analyzer", lambda: FakeAnalyzer("RETAKE"))
    token = client.post("/api/return/location", json=PARKING).json()["token"]
    for _ in range(5):
        assert photo(client, token).json()["review"] is False
    assert client.post("/api/return/complete", json={"token": token}).status_code == 409


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
