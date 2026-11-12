"""합성 마스크로 판정 규칙 검증 (모델 불필요).

장면: 640x480, 위쪽 절반은 건물, 아래쪽 절반 = 왼쪽 차도 | 오른쪽 보도.
"""
import numpy as np

from server.rules import APPROVE, REJECT, RETAKE, Scene, Vehicle, judge

H, W = 480, 640


def base(road_until=200, sidewalk_from=200, sidewalk_to=630):
    road = np.zeros((H, W), bool)
    sidewalk = np.zeros((H, W), bool)
    road[240:, :road_until] = True
    sidewalk[240:, sidewalk_from:sidewalk_to] = True
    return road, sidewalk


def vehicle(x0, x1, y0=200, y1=440, **kw):
    m = np.zeros((H, W), bool)
    m[y0:y1, x0:x1] = True
    return Vehicle(m, **kw)


def scene(vehicles, road, sidewalk, **kw):
    for v in vehicles:
        road &= ~v.mask
        sidewalk &= ~v.mask
    return Scene(vehicles, sidewalk, road, **kw)


def codes(v):
    return {r["code"] for r in v.reasons}


def test_edge_parking_approved():
    road, sw = base()
    v = judge(scene([vehicle(540, 600)], road, sw))  # 보도 430px 중 60px
    assert v.status == APPROVE, v.reasons
    assert v.metrics["width_occupancy"] < 0.2


def test_blocking_sidewalk_rejected():
    road, sw = base()
    v = judge(scene([vehicle(230, 560)], road, sw))  # 330/430 = 77%
    assert v.status == REJECT
    assert "WIDTH" in codes(v)


def test_cluster_counts_together():
    road, sw = base()
    # 한 대씩은 35% 미만인데 두 대 합치면 60% 이상
    v = judge(scene([vehicle(220, 370), vehicle(380, 520)], road, sw))
    assert v.status == REJECT and "WIDTH" in codes(v)


def test_on_road_rejected():
    road, sw = base()
    v = judge(scene([vehicle(60, 140)], road, sw))
    assert v.status == REJECT and "ON_ROAD" in codes(v)


def test_braille_and_crosswalk():
    road, sw = base()
    braille = np.zeros((H, W), bool)
    braille[240:, 400:460] = True
    sw &= ~braille
    v = judge(scene([vehicle(410, 450)], road, sw, braille=braille))
    assert v.status == REJECT and "ON_BRAILLE" in codes(v)


def test_fallen_rejected():
    road, sw = base()
    v = judge(scene([vehicle(500, 620, y0=380, y1=430, fallen=True)], road, sw))
    assert "FALLEN" in codes(v) and v.status == REJECT


def test_no_vehicle_retake():
    road, sw = base()
    assert judge(scene([], road, sw)).status == RETAKE


def test_no_sidewalk_retake():
    road = np.zeros((H, W), bool)
    sw = np.zeros((H, W), bool)
    v = judge(scene([vehicle(300, 400)], road, sw))
    assert v.status == RETAKE and "NO_SIDEWALK" in codes(v)


def test_truncated_frame_asks_retake():
    # 보도가 화면 양끝까지 이어지고 기기가 화면 대부분을 차지 -> 실제 폭을 알 수 없으니 재촬영
    road = np.zeros((H, W), bool)
    sw = np.zeros((H, W), bool)
    sw[240:, :] = True
    v = judge(scene([vehicle(60, 560)], road, sw))
    assert v.status == RETAKE and "TRUNCATED" in codes(v)
