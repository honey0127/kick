"""막고 있는 킥보드가 '반납 가능'으로 통과되던 경우를 재현하는 테스트 (모델 불필요).

여기서 다루는 경우는 모두 '사진 한 장으로는 판단할 근거가 부족한' 경우라서,
거절이 아니라 판단 보류(재촬영)로 보낸다. 새 기준값은 Thresholds 의 임시값이다 (실제 사진으로 검증 전).

합성 마스크는 규칙이 의도대로 움직이는지만 확인한다. 실제 모델이 그리는 마스크에서
얼마나 자주 걸리는지(정상 사진의 보류율)는 직접 찍은 사진으로 training/evaluate.py 를 돌려야 알 수 있다.
"""
import cv2
import numpy as np

from server.ground import estimate_ground_scale
from server.rules import APPROVE, REJECT, RETAKE, Scene, Thresholds, Vehicle, judge
from tests.synth import make_scene

H, W = 480, 640


def codes(v):
    return {r["code"] for r in v.reasons}


def box_vehicle(x0, x1, y0, y1, **kw):
    m = np.zeros((H, W), bool)
    m[y0:y1, x0:x1] = True
    return Vehicle(m, **kw)


def flat_scene(vehicles, sidewalk, road):
    for v in vehicles:
        sidewalk &= ~v.mask
        road &= ~v.mask
    return Scene(vehicles, sidewalk, road)


def without_braille(scene):
    """점자블록이 없는 보도로 바꿔서 cm 실측 없이 비율 규칙만 타게 한다."""
    return Scene(scene.vehicles, scene.sidewalk | scene.braille, scene.road)


# ---------------------------------------------------------------- 1. 보도를 가로질러 찍은 사진
def band_scene(veh, top=240, bottom=440, tilt=0):
    """보도를 가로질러(옆에서) 찍은 모습: 보도가 화면을 좌우로 가로지르는 띠, 그 아래는 차도, 위는 건물.

    띠의 세로 두께(top~bottom)가 실제 보도 폭이고, 가로 방향은 보도의 '길이'다.
    tilt: 먼 쪽 경계가 화면 왼쪽에서 오른쪽으로 가며 내려오는 픽셀 수 (비스듬히 선 경우).
    """
    yy, xx = np.mgrid[:H, :W]
    far = top + tilt * xx / W
    sidewalk = (yy >= far) & (yy < bottom)
    road = yy >= bottom
    return flat_scene([veh], sidewalk, road)


def test_cross_view_band_is_held_not_approved():
    # 킥보드가 보도 폭(세로 200px)의 80%를 가로막고 서 있지만, 사진의 한 줄에서는 640px 중 60px(9%)만 차지한다
    v = judge(band_scene(box_vehicle(290, 350, 200, 400)))
    assert v.metrics["width_occupancy"] < 0.2  # 비율 규칙만 보면 통과였던 값
    assert v.status == RETAKE and "CROSS_VIEW" in codes(v), v.reasons


def test_cross_view_tilted_band_is_held():
    # 정확히 직각이 아니어서 먼 쪽 경계가 기울어 보여도 같은 결과
    v = judge(band_scene(box_vehicle(290, 350, 220, 400), tilt=60))
    assert v.status == RETAKE and "CROSS_VIEW" in codes(v), v.reasons


def test_cross_view_ratio_is_not_used_to_reject_either():
    """가로질러 찍은 사진의 줄 단위 비율은 통과 근거로도 거절 근거로도 쓰지 않는다.

    띠 위에 기둥 두 개(인식 안 된 세로 띠)가 서 있고 그 사이에 킥보드가 있으면, 예전에는
    '기둥 사이 구간의 80% 를 차지'했다고 WIDTH 거절이 났다. 그 80% 는 보도의 길이 방향 비율이라 의미가 없다.
    """
    veh = box_vehicle(240, 400, 200, 400)
    scene = band_scene(veh)
    for x0 in (200, 420):  # 기둥: 띠의 먼 쪽 끝부터 기둥 밑동(y=420)까지 보도를 가림
        scene.sidewalk[:420, x0 : x0 + 20] = False
    v = judge(scene)
    assert v.metrics["width_occupancy"] > 0.65
    assert v.status == RETAKE and codes(v) == {"CROSS_VIEW"}, v.reasons


def test_cross_view_keeps_truncated_reason():
    # 화면 대부분을 차지해 TRUNCATED 가 나던 사진은 그 사유를 그대로 두고 CROSS_VIEW 를 덧붙인다
    v = judge(band_scene(box_vehicle(60, 560, 200, 400)))
    assert v.status == RETAKE and codes(v) == {"TRUNCATED", "CROSS_VIEW"}, v.reasons


def test_cross_view_does_not_soften_reject():
    # 가로질러 찍었어도 차도 위에 세운 것이 보이면 그대로 거절
    v = judge(band_scene(box_vehicle(290, 350, 300, 470)))
    assert v.status == REJECT and "ON_ROAD" in codes(v), v.reasons


def test_along_view_wide_sidewalk_still_approved():
    """보도를 따라 찍었는데 보도가 넓어서 킥보드 높이에서는 화면 양끝을 넘는 경우.

    먼 쪽(위)으로 갈수록 좁아져 양 끝이 화면 안에 들어오므로 가로질러 찍은 것으로 보지 않는다.
    이 구도에서 잰 점유율은 실제보다 크게 나오는 쪽이라(보도가 화면 밖으로 더 있음) 통과시켜도 안전하다.
    """
    yy, xx = np.mgrid[:H, :W]
    half = 60 + (yy - 240) * 3.0  # 화면 가운데(x=320) 기준 보도 반폭: 위에서 60px, 아래로 갈수록 넓어짐
    sidewalk = (yy >= 240) & (np.abs(xx - 320) <= half)
    v = judge(flat_scene([box_vehicle(480, 540, 200, 440)], sidewalk, np.zeros((H, W), bool)))
    assert v.metrics["frame_span"] > 0.5  # 폭을 잰 줄에서는 보도가 화면 양끝에 닿아 있음
    assert v.metrics["sidewalk_edge_rows"] >= 50  # 그래도 위쪽 줄에서는 보도 양 끝이 보임
    assert v.status == APPROVE, v.reasons


def test_same_parking_seen_along_and_across():
    """같은 주차 상태(3m 보도 중 2.4m 를 가로막음)를 원근 장면으로: 따라 찍으면 거절, 가로질러 찍으면 보류."""
    _, along, _ = make_scene([(130, 370, 400, 450, 110)])
    v = judge(without_braille(along))
    assert v.status == REJECT and "WIDTH" in codes(v), v.reasons

    # 차도 쪽(X=-150)에 서서 건물 쪽으로 보도를 가로질러 찍음
    _, across, _ = make_scene([(130, 370, 1275, 1325, 110)], cam=(-150.0, 1300.0, 150.0), pitch_deg=30.0, yaw_deg=90.0)
    v = judge(without_braille(across))
    assert v.metrics["width_occupancy"] < 0.3  # 한 줄에서는 16% 로 보여 예전에는 통과됐다
    assert v.status == RETAKE and "CROSS_VIEW" in codes(v), v.reasons

    # 15도쯤 비스듬히 서서 찍어도 같은 결과
    _, oblique, _ = make_scene([(130, 370, 1275, 1325, 110)], cam=(-150.0, 1215.0, 150.0), pitch_deg=30.0, yaw_deg=75.0)
    v = judge(without_braille(oblique))
    assert v.status == RETAKE and "CROSS_VIEW" in codes(v), v.reasons


# ---------------------------------------------------------------- 2. 보도 가장자리에서 떨어져 세움 (유효 점유율)
def along_scene(vehicles):
    """보도를 따라 찍은 모습 (tests/test_rules.py 와 같은 장면): 위쪽 절반은 건물, 아래는 왼쪽 차도 | 오른쪽 보도(x 200~630, 430px)."""
    road = np.zeros((H, W), bool)
    sidewalk = np.zeros((H, W), bool)
    road[240:, :200] = True
    sidewalk[240:, 200:630] = True
    return flat_scene(vehicles, sidewalk, road)


def upright(x0, x1):
    return box_vehicle(x0, x1, 200, 440)


def test_middle_of_sidewalk_is_not_approved():
    # 보도 폭의 25% 만 차지하지만 한가운데라서 양옆 통로가 각각 37% 뿐 -> 예전에는 통과, 이제 보류
    v = judge(along_scene([upright(361, 469)]))
    assert round(v.metrics["width_occupancy"], 2) == 0.25
    assert v.status == RETAKE and codes(v) == {"BORDER_OFF_EDGE"}, v.reasons

    # 40% 를 차지하고 한가운데면 양옆 통로가 각각 30% -> 거절 (차지한 폭만 보면 여전히 60% 미만)
    v = judge(along_scene([upright(329, 501)]))
    assert round(v.metrics["width_occupancy"], 2) == 0.40
    assert v.status == REJECT and codes(v) == {"OFF_EDGE"}, v.reasons


def test_same_width_at_edge_is_still_approved():
    # 같은 폭이라도 보도 끝에 붙여 세우면 유효 점유율 = 차지한 폭 -> 예전과 같이 통과
    for px in (108, 172):
        v = judge(along_scene([upright(630 - px, 630)]))
        assert v.status == APPROVE, (px, v.reasons)
        assert v.metrics["effective_occupancy"] == v.metrics["width_occupancy"]


def test_small_gap_to_edge_is_still_approved():
    # 벽에 딱 붙이지 않고 보도 폭의 5% 쯤 떨어뜨려 세운 평범한 주차는 그대로 통과 (25% + 5% = 30%)
    v = judge(along_scene([upright(500, 608)]))
    assert v.status == APPROVE, v.reasons
    assert round(v.metrics["effective_occupancy"], 2) == 0.30


def test_off_edge_hold_band():
    # 보도 폭의 40% 를 차지한 킥보드를 가장자리에서 조금씩 떼어 놓는다: 가장 넓은 통로가 46% 통과, 44%·36% 보류, 34% 거절
    want = {60: (APPROVE, set()), 69: (RETAKE, {"BORDER_OFF_EDGE"}), 103: (RETAKE, {"BORDER_OFF_EDGE"}), 112: (REJECT, {"OFF_EDGE"})}
    for gap, (status, cs) in want.items():
        v = judge(along_scene([upright(200 + gap, 200 + gap + 172)]))
        assert (v.status, codes(v)) == (status, cs), (gap, v.metrics["effective_occupancy"])


def test_two_scooters_splitting_the_passage():
    # 두 대가 각각 14% 씩(합쳐 28%)만 차지해도 보도를 세 토막으로 나누면 가장 넓은 통로가 26% -> 거절
    v = judge(along_scene([upright(300, 360), upright(470, 530)]))
    assert v.metrics["width_occupancy"] < 0.3
    assert v.status == REJECT and codes(v) == {"OFF_EDGE"}, v.reasons

    # 같은 두 대를 양쪽 가장자리로 옮기면 가운데 통로가 66% 로 이어져 통과
    # (차도 쪽 한 대는 바퀴 둘레가 차도에 걸치지 않게 연석에서 25px 띄움)
    v = judge(along_scene([upright(225, 285), upright(570, 630)]))
    assert v.status == APPROVE, v.reasons
    assert round(v.metrics["effective_occupancy"], 2) == 0.34


def test_sidewalk_running_out_of_frame_keeps_old_ratio():
    """보도가 화면 밖으로 이어진 줄에서는 통로의 실제 폭을 알 수 없으므로, 좁다고 단정하지 않고 예전 점유율을 그대로 쓴다.

    가까이서 찍어 보도 양 끝이 화면 밖이고 킥보드가 화면 가운데 있는 사진: 보이는 통로는 양쪽 45% 씩이지만
    화면 밖으로 더 이어져 있어, 넓은 보도에 잘 세운 것인지 좁은 보도 한가운데인지 이 사진만으로는 알 수 없다.
    (이 구도의 가운데 주차는 여전히 걸러내지 못한다. 보도 끝이 보이게 찍은 사진에서만 걸러진다.)
    """
    yy, xx = np.mgrid[:H, :W]
    half = 60 + (yy - 240) * 3.0  # test_along_view_wide_sidewalk_still_approved 와 같은 보도
    sidewalk = (yy >= 240) & (np.abs(xx - 320) <= half)
    v = judge(flat_scene([box_vehicle(288, 352, 200, 440)], sidewalk, np.zeros((H, W), bool)))
    assert v.metrics["frame_span"] > 0.5
    assert v.metrics["effective_occupancy"] == v.metrics["width_occupancy"]
    assert v.status == APPROVE, v.reasons


def test_middle_parking_in_perspective():
    """원근 장면(3m 보도, 폭 50cm 킥보드): 벽 쪽 가장자리면 통과, 한가운데면 양옆이 125cm 씩(42%)이라 보류."""
    _, edge, _ = make_scene([(340, 390, 400, 510, 110)])
    v = judge(without_braille(edge))
    assert v.status == APPROVE, v.reasons

    _, middle, _ = make_scene([(225, 275, 400, 510, 110)])
    v = judge(without_braille(middle))
    assert v.metrics["width_occupancy"] < 0.3  # 차지한 폭만 보면 통과였다
    assert v.status == RETAKE and codes(v) == {"BORDER_OFF_EDGE"}, (v.reasons, v.metrics)


# ---------------------------------------------------------------- 3. 바퀴 앞 바닥을 모델이 알아보지 못함
def curb_scene(veh, road_seen=True, road_until=200):
    """along_scene 과 같은 장면. road_seen=False 면 바닥 모델이 차도를 놓친 상황(차도 자리가 '인식 안 됨')."""
    road = np.zeros((H, W), bool)
    sidewalk = np.zeros((H, W), bool)
    if road_seen:
        road[240:, :road_until] = True
    sidewalk[240:, 200:630] = True
    return flat_scene([veh], sidewalk, road)


def test_curb_straddle_with_missed_road_is_held():
    # 킥보드(x 150~250)가 연석(x=200)에 반쯤 걸쳐 있다
    seen = judge(curb_scene(upright(150, 250)))
    assert seen.status == REJECT and "ON_ROAD" in codes(seen), seen.reasons  # 차도를 찾았으면 거절

    # 같은 장면에서 모델이 차도를 놓치면: 예전에는 인식된 보도만 보고 통과, 이제 보류
    missed = judge(curb_scene(upright(150, 250), road_seen=False))
    assert missed.metrics["road_overlap"] == 0 and missed.metrics["width_occupancy"] < 0.3
    assert missed.metrics["ground_support"] == 0.5  # 접지부 열의 절반만 앞 바닥이 인식됨
    assert missed.status == RETAKE and codes(missed) == {"UNKNOWN_GROUND"}, missed.reasons


def test_parked_on_missed_road_next_to_curb_is_held():
    # 연석 바로 옆 차도 위(x 130~190)에 세웠는데 모델이 차도를 놓친 경우: 예전에는 옆의 보도와 한 줄로 이어져 통과됐다
    v = judge(curb_scene(upright(130, 190), road_seen=False))
    assert v.metrics["ground_support"] == 0
    assert v.status == RETAKE and codes(v) == {"UNKNOWN_GROUND"}, v.reasons


def test_wall_side_parking_is_not_held():
    # 보도 끝(x=630, 그 너머는 벽이라 인식 안 됨)에 붙여 세운 정상 주차: 옆·뒤가 인식 안 돼도 바퀴 앞 바닥은 보도
    v = judge(curb_scene(upright(570, 630)))
    assert v.metrics["ground_support"] == 1.0
    assert v.status == APPROVE, v.reasons


def test_ground_support_threshold():
    # 폭 100px 접지부가 보도 끝(x=500) 밖의 인식 안 된 바닥으로 나간다: 30% 나가면 통과(인식 70%), 50% 나가면 보류(인식 50%)
    for x0, support, status in ((430, 0.7, APPROVE), (450, 0.5, RETAKE)):
        v = judge(flat_scene([box_vehicle(x0, x0 + 100, 200, 440)], *_sidewalk_only(200, 500)))
        assert round(v.metrics["ground_support"], 2) == support
        assert v.status == status, (x0, v.reasons)


def _sidewalk_only(x0, x1):
    sidewalk = np.zeros((H, W), bool)
    sidewalk[240:, x0:x1] = True
    return sidewalk, np.zeros((H, W), bool)


def test_thin_gap_around_vehicle_is_tolerated():
    # 킥보드 윤곽 둘레 8px 가 인식 안 된 틈으로 남아도(마스크 경계 오차·그림자) 바퀴 앞 바닥은 알아본 것으로 본다
    veh = upright(540, 600)
    scene = curb_scene(veh)
    halo = cv2.dilate(veh.mask.astype(np.uint8), np.ones((17, 17), np.uint8)).astype(bool)
    scene.sidewalk &= ~halo
    v = judge(scene)
    assert v.metrics["ground_support"] == 1.0
    assert v.status == APPROVE, v.reasons


def test_unknown_ground_does_not_soften_reject():
    # 차도는 x<150 만 인식되고 x 150~200 은 인식 안 됨. 킥보드(x 100~220)는 대부분 차도 위 -> 보류 사유가 붙어도 거절
    v = judge(curb_scene(upright(100, 220), road_until=150))
    assert v.status == REJECT and {"ON_ROAD", "UNKNOWN_GROUND"} <= codes(v), v.reasons


def test_curb_straddle_in_perspective():
    """원근 장면: 킥보드(폭 50cm)가 연석(X=100)에 반쯤 걸쳐 있고 바닥 모델이 차도를 놓친 경우.

    연석 선 위에 서서 킥보드를 정면으로 본 구도라 걸친 모습이 바퀴 앞 바닥에 그대로 드러난다.
    (보도 쪽에서 비스듬히 보면 바퀴 앞 바닥이 전부 보도라서 이 규칙으로는 잡히지 않는다 -> docs/판정규칙.md)
    """
    _, scene, _ = make_scene([(75, 125, 420, 530, 110)], cam=(100.0, 0.0, 150.0))
    v = judge(Scene(scene.vehicles, scene.sidewalk | scene.braille, np.zeros_like(scene.road)))
    assert 0.4 < v.metrics["ground_support"] < 0.6
    assert v.status == RETAKE and codes(v) == {"UNKNOWN_GROUND"}, v.reasons


def test_unknown_ground_also_holds_cm_path():
    """점자블록으로 cm 실측이 돼서 남은 통행폭이 충분하게 나와도, 바퀴 앞 바닥을 모르면 통과시키지 않는다."""
    img, scene, _ = make_scene([(160, 220, 350, 460, 110)])  # tests/test_ground.py 의 '주의'(반납은 됨) 장면
    th = Thresholds()
    gs = estimate_ground_scale(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32), scene, th)
    assert judge(scene, th, ground=gs).status == APPROVE

    # 킥보드 바로 앞 바닥 60px 을 '인식 안 됨'으로 비운다 (모델이 못 알아본 바닥: 화단 흙, 놓친 차도 등)
    ys, xs = np.nonzero(scene.vehicles[0].mask)
    patch = np.zeros_like(scene.sidewalk)
    patch[ys.max() + 1 : ys.max() + 61, xs.min() : xs.max() + 1] = True
    missed = Scene(scene.vehicles, scene.sidewalk & ~patch, scene.road & ~patch, scene.braille & ~patch)
    v = judge(missed, th, ground=gs)
    assert v.metrics["residual_cm"] > 150  # 통행폭만 보면 통과
    assert v.metrics["ground_support"] < th.min_ground_support
    assert v.status == RETAKE and "UNKNOWN_GROUND" in codes(v), v.reasons
