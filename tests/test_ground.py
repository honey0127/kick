"""점자블록 기준 cm 실측: 정답을 아는 합성 원근 장면(tests/synth.py)으로 검증."""
import cv2
import numpy as np
import pytest

from server.ground import detect_braille_by_color, estimate_ground_scale, fit_grid_homography, transform
from server.rules import APPROVE, REJECT, Scene, Thresholds, judge
from tests.synth import make_scene

TH = Thresholds()
TOL_CM = 15  # 문제정의서 목표: 평균 오차 10~15cm


def _gray(img):
    return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)


def _run(box):
    img, scene, H_g2i = make_scene([box])
    gs = estimate_ground_scale(_gray(img), scene, TH)
    return img, scene, H_g2i, gs, judge(scene, TH, ground=gs)


def test_scale_recovered_from_braille():
    img, scene, H_g2i, gs, _ = _run((115, 165, 350, 460, 110))
    assert gs is not None and gs.confidence == "high" and gs.n_tiles >= 4
    # 지면 위 3m 길이를 가로·세로·먼 곳에서 재본다
    pts = np.array([[100, 500], [400, 500], [100, 300], [100, 600], [100, 1200], [400, 1200]], float)
    g = gs.to_ground(transform(pts, H_g2i)[0])
    for i in (0, 2, 4):
        assert abs(np.linalg.norm(g[i + 1] - g[i]) - 300) < 12  # 4% 이내


@pytest.mark.parametrize(
    "box, residual, status, grade",
    [
        ((115, 165, 350, 460, 110), 400 - 165, APPROVE, "양호"),  # 차도 쪽 가장자리에 붙여 세움
        ((160, 220, 350, 460, 110), 400 - 220, APPROVE, "주의"),  # 조금 안쪽 -> 반납은 되지만 안내
        ((200, 330, 350, 400, 110), 200 - 100, REJECT, "위험"),  # 가로로 막고 점자블록 점유
    ],
)
def test_residual_width_cm(box, residual, status, grade):
    *_, v = _run(box)
    m = v.metrics
    assert abs(m["residual_cm"] - residual) <= TOL_CM, m
    assert abs(m["original_cm"] - 300) <= 25, m
    assert v.status == status and m["grade"] == grade, (v.status, v.reasons)


def test_narrow_rejected_even_below_60_percent():
    """점유율은 60% 미만이어도 남은 폭이 120cm 미만이면 거절 (cm 실측이 있어야 잡히는 경우)."""
    *_, v = _run((200, 330, 350, 400, 110))
    codes = {r["code"] for r in v.reasons}
    assert v.metrics["width_occupancy"] < TH.max_width_occupancy
    assert "NARROW" in codes and "ON_BRAILLE" in codes


def test_caution_is_info_not_blocking():
    *_, v = _run((160, 220, 350, 460, 110))
    assert v.status == APPROVE
    assert [r["status"] for r in v.reasons] == ["INFO"]


def test_no_braille_falls_back_to_ratio():
    img, scene, _ = make_scene([(115, 165, 350, 460, 110)])
    s = Scene(scene.vehicles, scene.sidewalk | scene.braille, scene.road)
    gs = estimate_ground_scale(_gray(img), s, TH)
    v = judge(s, TH, ground=gs)
    # 점자블록이 없으면 cm 판정은 안 하고(차체 길이 추정치는 표시만) 60% 비율 규칙으로 판정
    assert gs is None and "residual_cm" not in v.metrics and "grade" not in v.metrics
    assert v.metrics["scale_source"] in ("none", "vehicle_length(추정)")


def test_color_detection_finds_braille():
    img, scene, _ = make_scene([(115, 165, 350, 460, 110)])
    strip, tiles = detect_braille_by_color(img, scene.sidewalk | scene.braille, scene.road)
    iou = (strip & scene.braille).sum() / (strip | scene.braille).sum()
    assert iou > 0.9 and len(tiles) >= 4


def test_grid_fit_survives_missed_seam():
    """이음매 하나를 놓쳐 60cm짜리 사각형이 섞여도 같은 격자로 맞춰진다."""
    sq = lambda y: np.array([[0, y], [30, y], [30, y + 30], [0, y + 30]], float)  # noqa: E731
    H = np.array([[2.0, 0.3, 100], [0.1, 1.5, 50], [0, 0.001, 1]])
    quads = [transform(q, H)[0] for q in (sq(0), sq(30), np.array([[0, 60], [30, 60], [30, 120], [0, 120]], float), sq(120))]
    Hf, used, err = fit_grid_homography(quads, 30, 30)
    assert len(used) == 4 and err < 0.5
