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


def test_low_confidence_retake_not_guess():
    road, sw = base()
    v = judge(scene([vehicle(230, 560, conf=0.3)], road, sw))  # 막고 있어도 흐리게 잡히면 재촬영
    assert v.status == RETAKE and codes(v) == {"LOW_CONF"}


def test_cut_vehicle_retake_even_on_road():
    road, sw = base()
    v = judge(scene([vehicle(60, 140, y1=H)], road, sw))  # 바퀴가 화면 아래로 잘림
    assert v.status == RETAKE and codes(v) == {"VEHICLE_CUT"}


def test_same_scene_same_verdict():
    for x0, x1 in ((540, 600), (230, 560), (60, 140)):
        a, b = (judge(scene([vehicle(x0, x1)], *base())).to_dict() for _ in range(2))
        assert a == b


def test_width_hold_band():
    # 보도 430px: 약 50% 통과, 약 60% 보류(재촬영), 약 70% 거절
    # 보도 끝(x=630)에 붙여 세운다: 가장자리에서 떨어지면 그 틈만큼 유효 점유율이 올라가
    # 같은 폭이라도 결과가 달라지기 때문 (tests/test_false_approve.py 의 OFF_EDGE 테스트)
    got = {}
    for px in (215, 258, 301):
        v = judge(scene([vehicle(630 - px, 630)], *base()))
        got[px] = (v.status, codes(v), v.metrics["width_occupancy"])
    assert got[215][0] == APPROVE, got
    assert got[258][0] == RETAKE and got[258][1] == {"BORDER_WIDTH"}, got
    assert got[301][0] == REJECT and "WIDTH" in got[301][1], got


def test_braille_hold_band():
    # 킥보드(x 540~600) 오른쪽 점자블록 띠를 조금씩 가까이: 떨어짐 통과, 바퀴 둘레에 살짝 걸침 보류, 확실히 걸침 거절
    want = {620: (APPROVE, set()), 616: (RETAKE, {"BORDER_BRAILLE"}), 610: (REJECT, {"ON_BRAILLE"})}
    for b0, (status, cs) in want.items():
        road, sw = base()
        braille = np.zeros((H, W), bool)
        braille[240:, b0:b0 + 20] = True
        sw &= ~braille
        v = judge(scene([vehicle(540, 600)], road, sw, braille=braille))
        assert (v.status, codes(v)) == (status, cs), (b0, v.metrics["braille_overlap"])


def test_faint_detection_not_counted():
    # 확신도 0.3 짜리 오검출이 옆에 있어도 점유율·대수 계산에 안 들어간다
    # (analyzer 는 희미한 검출의 픽셀을 보도에서 빼지 않으므로, 보도는 진짜 킥보드만 뺀 상태)
    alone = scene([vehicle(540, 600)], *base())
    faint = vehicle(260, 480, conf=0.3)
    a = judge(alone)
    b = judge(Scene([alone.vehicles[0], faint], alone.sidewalk, alone.road))
    assert (b.status, b.metrics["vehicle_count"], b.metrics["width_occupancy"]) == (a.status, 1, a.metrics["width_occupancy"])


def test_ambiguous_target_retake():
    # 비슷한 크기 두 대: 하나는 차도, 하나는 보도 가장자리 -> 누구를 고르느냐에 따라 결과가 달라지므로 재촬영
    road, sw = base()
    v = judge(scene([vehicle(60, 140), vehicle(540, 620)], road, sw))
    assert v.status == RETAKE and codes(v) == {"AMBIGUOUS_TARGET"}, v.reasons


def test_clear_target_not_ambiguous():
    # 반납 대상이 확실히 크면(2등이 1등의 80% 미만) 후보 비교 없이 판정
    road, sw = base()
    v = judge(scene([vehicle(60, 100, y0=380), vehicle(520, 620)], road, sw))
    assert v.status == APPROVE, v.reasons


def test_ring_same_for_portrait_and_landscape():
    from server.rules import Thresholds
    th = Thresholds()
    for h, w in ((1280, 960), (960, 1280)):
        assert max(3, int(max(h, w) * th.ring_frac)) == 38
