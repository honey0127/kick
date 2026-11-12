"""반납 사진 판정 규칙.

모델 출력(마스크)만 받아서 판정하므로 어떤 AI 모델을 쓰든 그대로 재사용하고,
모델 없이도 합성 마스크로 테스트할 수 있다.

판정 기준
  - 차도 위에 세워짐 / 횡단보도·점자블록 점유 / 전도        -> REJECT
  - 보도 가로 폭의 60% 이상 점유                             -> REJECT
  - (점자블록으로 cm 실측이 되면) 남은 통행폭 120cm 미만      -> REJECT
  - 차량·보도가 안 보이거나 화면이 너무 좁아 판단 불가        -> RETAKE (재촬영)
  - 그 외                                                    -> APPROVE (경고/주의 등급이면 안내 문구)

등급 (문제정의서 ⑤, 병목 통행폭 기준)
  위험 < 120cm 또는 점자블록·횡단보도 점유 / 경고 120~150 / 주의 150~200 또는 3대 이상 군집 / 양호 >= 200
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

APPROVE, REJECT, RETAKE, INFO = "APPROVE", "REJECT", "RETAKE", "INFO"
GRADES = ("위험", "경고", "주의", "양호")


@dataclass
class Vehicle:
    mask: np.ndarray  # bool HxW
    kind: str = "bicycle"
    conf: float = 1.0
    fallen: bool = False


@dataclass
class Scene:
    vehicles: list[Vehicle]
    sidewalk: np.ndarray
    road: np.ndarray
    braille: np.ndarray | None = None
    crosswalk: np.ndarray | None = None
    braille_tiles: list[np.ndarray] = field(default_factory=list)  # 블록 한 장씩 분할된 마스크 (실측용)

    @property
    def shape(self) -> tuple[int, int]:
        return self.sidewalk.shape[:2]


@dataclass
class Thresholds:
    max_width_occupancy: float = 0.60  # 보도 가로폭 점유율 한도
    max_road_overlap: float = 0.30  # 접지부 주변 바닥 중 차도 비율 한도
    max_braille_overlap: float = 0.05
    max_crosswalk_overlap: float = 0.10
    footprint_frac: float = 0.25  # 차체 아래쪽 몇 %를 바닥 접지부로 볼지
    band_frac: float = 0.40  # 점유율을 잴 세로 구간 (차체 아래쪽)
    ring_frac: float = 0.03  # 접지부 주변 바닥을 볼 링 두께 (이미지 높이 대비)
    min_sidewalk_frac: float = 0.03  # 보도가 이만큼은 보여야 판단
    min_vehicle_frac: float = 0.005
    min_span_frac: float = 0.05  # 한 행의 보도 폭이 이보다 좁으면 그 행은 무시
    gap_frac: float = 0.02  # 보도 마스크의 작은 끊김(가로수·사람 등)은 이어붙임
    edge_margin_px: int = 3
    row_percentile: float = 90

    # --- cm 실측 (server/ground.py)
    min_residual_cm: float = 120  # 교통약자법 시행규칙 최소 유효폭 -> 이보다 좁으면 반납 거절
    warn_cm: float = 150  # 국토부 보도 유효폭 (불가피 시)
    caution_cm: float = 200  # 국토부 보도 유효폭 (최소)
    cluster_count: int = 3  # 이 대수 이상 몰려 있으면 '주의'
    tile_cm: float = 30  # 점자블록 한 장 크기 (현장 실측으로 확인)
    strip_tiles: int = 1  # 선형 점자블록 띠가 블록 몇 장 폭인지
    max_scale_err_cm: float = 5  # 재투영 오차가 이보다 크면 실측 신뢰도 낮음 -> 판정에 안 씀
    contact_frac: float = 0.12  # 접지점으로 볼 차체 맨 아래 구간
    vehicle_min_width_cm: float = 55  # 접지점만 보여 얇게 잡힐 때 보정할 최소 차체(핸들) 폭
    obstacle_margin_cm: float = 5
    bev_res_cm: float = 2  # 조감도 1px = 2cm
    bev_half_across_cm: float = 500
    bev_half_along_cm: float = 300
    section_step_cm: float = 10  # 단면 간격
    vehicle_length_cm: dict = field(default_factory=lambda: {"bicycle": 165})


@dataclass
class Verdict:
    status: str
    reasons: list[dict] = field(default_factory=list)
    metrics: dict = field(default_factory=dict)
    target_index: int | None = None
    worst_row: int | None = None
    worst_span: tuple[int, int] | None = None
    ground: object = None  # GroundScale
    passage: object = None  # Passage

    def to_dict(self) -> dict:
        return {
            "status": self.status,
            "reasons": self.reasons,
            "metrics": self.metrics,
            "target_index": self.target_index,
            "worst_row": self.worst_row,
            "worst_span": self.worst_span,
            "scale": self.ground.to_dict() if self.ground is not None else None,
            "passage": self.passage.to_dict() if self.passage is not None else None,
        }


MESSAGES = {
    "NO_VEHICLE": (RETAKE, "자전거가 보이지 않아요. 자전거 전체가 파란 틀 안에 들어오게 찍어주세요."),
    "NO_SIDEWALK": (RETAKE, "주변 바닥이 잘 보이지 않아요. 한 걸음 물러나 주변이 보이게 찍어주세요."),
    "TRUNCATED": (RETAKE, "보도 양 끝이 화면 밖이라 폭을 판단할 수 없어요. 뒤로 물러나 보도 전체가 보이게 찍어주세요."),
    "FALLEN": (REJECT, "기기가 넘어져 있어요. 똑바로 세운 뒤 다시 찍어주세요."),
    "ON_ROAD": (REJECT, "차도에 세워져 있어요. 보도 안쪽으로 옮겨주세요."),
    "ON_CROSSWALK": (REJECT, "횡단보도(진입부)를 막고 있어요. 다른 곳으로 옮겨주세요."),
    "ON_BRAILLE": (REJECT, "점자블록 위에 세워져 있어요. 점자블록을 비워주세요."),
    "WIDTH": (REJECT, "보도 폭을 너무 많이 차지해요({pct}%). 보도 가장자리에 붙여 세워주세요."),
    "NARROW": (REJECT, "남은 통행폭이 {cm}cm라 휠체어가 지나갈 수 없어요(최소 120cm). 보도 가장자리로 바짝 붙여주세요."),
    "WARN_WIDTH": (INFO, "남은 통행폭 {cm}cm — 반납은 되지만 휠체어·유모차가 지나가기 빠듯해요. 가장자리로 더 붙여주시면 좋아요."),
    "CAUTION_WIDTH": (INFO, "남은 통행폭 {cm}cm — 보도 기준폭(2m)보다 좁아요."),
    "CLUSTER": (INFO, "이 지점에 기기가 {n}대 몰려 있어요."),
}


def _reason(code: str, **fmt) -> dict:
    status, msg = MESSAGES[code]
    return {"code": code, "status": status, "message": msg.format(**fmt)}


def _union(masks: list[np.ndarray], shape) -> np.ndarray:
    out = np.zeros(shape, bool)
    for m in masks:
        out |= m
    return out


def bottom_part(mask: np.ndarray, frac: float) -> np.ndarray:
    """마스크의 세로 범위 중 아래쪽 frac 만큼만 남긴다."""
    ys = np.flatnonzero(mask.any(axis=1))
    if ys.size == 0:
        return mask.copy()
    top, bot = ys[0], ys[-1]
    cut = bot - max(1, int(round((bot - top + 1) * frac)))
    out = mask.copy()
    out[: cut + 1] = False
    return out


def pick_target(vehicles: list[Vehicle], shape) -> int | None:
    """반납 대상 기기: 화면 중앙에 가깝고 큰 것."""
    if not vehicles:
        return None
    h, w = shape
    best, best_score = None, -1.0
    for i, v in enumerate(vehicles):
        ys, xs = np.nonzero(v.mask)
        if ys.size == 0:
            continue
        dist = np.hypot(xs.mean() / w - 0.5, ys.mean() / h - 0.5)
        score = ys.size * v.conf * (1.2 - dist)
        if score > best_score:
            best, best_score = i, score
    return best


def ground_ring(footprint: np.ndarray, vehicles_any: np.ndarray, ring_px: int) -> np.ndarray:
    """접지부 바로 주변의 바닥 픽셀 (차체에 가려진 부분은 제외)."""
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * ring_px + 1, 2 * ring_px + 1))
    dil = cv2.dilate(footprint.astype(np.uint8), k).astype(bool)
    return dil & ~vehicles_any


def _runs(row: np.ndarray, gap: int) -> list[tuple[int, int]]:
    """True 구간 목록 [(x0, x1)], gap 이하의 끊김은 이어붙인다."""
    xs = np.flatnonzero(row)
    if xs.size == 0:
        return []
    splits = np.flatnonzero(np.diff(xs) > gap + 1)
    starts = np.r_[xs[0], xs[splits + 1]]
    ends = np.r_[xs[splits], xs[-1]]
    return list(zip(starts.tolist(), ends.tolist()))


def width_occupancy(scene: Scene, rows: np.ndarray, th: Thresholds) -> list[dict]:
    """행(=같은 거리의 가로 단면)마다 '보도 폭 중 기기가 가로막은 비율'을 잰다.

    지면이 평평하고 카메라가 수평이면 이미지의 한 행은 지면 위 같은 거리의 가로선이므로
    같은 행 안의 픽셀 비율은 실제 폭 비율과 같다 (보도 진행 방향으로 촬영했을 때).
    """
    h, w = scene.shape
    veh_any = _union([v.mask for v in scene.vehicles], (h, w))
    walk = scene.sidewalk | veh_any
    if scene.braille is not None:
        walk |= scene.braille
    gap = max(1, int(w * th.gap_frac))
    out = []
    for y in rows:
        occ = np.zeros(w, bool)
        for v in scene.vehicles:
            vx = np.flatnonzero(v.mask[y])
            if vx.size:
                occ[vx[0] : vx[-1] + 1] = True  # 바퀴살 사이도 지나갈 수 없으니 좌우 끝까지 점유
        if not occ.any():
            continue
        runs = _runs(walk[y] | occ, gap)
        if not runs:
            continue
        x0, x1 = max(runs, key=lambda r: occ[r[0] : r[1] + 1].sum())
        span = x1 - x0 + 1
        if span < w * th.min_span_frac:
            continue
        sidewalk_px = scene.sidewalk[y, x0 : x1 + 1].sum()
        if sidewalk_px == 0:
            continue  # 기기만 있고 보도가 없는 행은 비율 의미 없음
        free = _runs(~occ[x0 : x1 + 1], 0)
        out.append(
            {
                "y": int(y),
                "x0": int(x0),
                "x1": int(x1),
                "ratio": float(occ[x0 : x1 + 1].sum() / span),
                "free_px": max((b - a + 1 for a, b in free), default=0),
                "truncated": bool(x0 <= th.edge_margin_px or x1 >= w - 1 - th.edge_margin_px),
            }
        )
    return out


def grade_of(residual_cm: float, braille_hit: bool, crosswalk_hit: bool, n_vehicles: int, th: Thresholds) -> str:
    if residual_cm < th.min_residual_cm or braille_hit or crosswalk_hit:
        return "위험"
    if residual_cm < th.warn_cm:
        return "경고"
    if residual_cm < th.caution_cm or n_vehicles >= th.cluster_count:
        return "주의"
    return "양호"


def score_of(residual_cm, braille_hit, braille_dist_cm, crosswalk_hit, fallen, n_vehicles) -> int:
    """침해 점수 0~100: 같은 등급 안에서 우선순위. 가중치는 사람 판정 데이터로 보정할 것."""
    width = np.clip((200 - residual_cm) / 120, 0, 1)
    braille = 1.0 if braille_hit else (np.clip(1 - braille_dist_cm / 100, 0, 1) if braille_dist_cm is not None else 0.0)
    cluster = np.clip((n_vehicles - 1) / 4, 0, 1)
    s = 0.45 * width + 0.20 * braille + 0.15 * float(crosswalk_hit) + 0.10 * float(fallen) + 0.10 * cluster
    return int(round(100 * s))


def judge(scene: Scene, th: Thresholds | None = None, ground=None) -> Verdict:
    """ground: server.ground.GroundScale (점자블록 실측 결과). 있으면 cm 단위로 판정한다."""
    th = th or Thresholds()
    h, w = scene.shape
    total = h * w
    metrics: dict = {"vehicle_count": len(scene.vehicles)}
    reasons: list[dict] = []

    vehicles = [v for v in scene.vehicles if v.mask.sum() >= total * th.min_vehicle_frac]
    scene = Scene(vehicles, scene.sidewalk, scene.road, scene.braille, scene.crosswalk, scene.braille_tiles)
    metrics["vehicle_count"] = len(vehicles)
    ti = pick_target(vehicles, (h, w))
    if ti is None:
        return Verdict(RETAKE, [_reason("NO_VEHICLE")], metrics, ground=ground)

    target = vehicles[ti]
    metrics["target_kind"] = target.kind
    metrics["target_conf"] = round(float(target.conf), 3)
    if target.fallen:
        reasons.append(_reason("FALLEN"))

    veh_any = _union([v.mask for v in vehicles], (h, w))
    footprint = bottom_part(target.mask, th.footprint_frac)
    ring = ground_ring(footprint, veh_any, max(3, int(h * th.ring_frac)))
    ground_mask = scene.road | scene.sidewalk
    for extra in (scene.braille, scene.crosswalk):
        if extra is not None:
            ground_mask = ground_mask | extra
    ground_px = (ring & ground_mask).sum()
    metrics["ground_px"] = int(ground_px)

    def ratio(mask):
        if mask is None or ground_px == 0:
            return 0.0
        return float((ring & mask).sum() / ground_px)

    road_r, cross_r, braille_r = ratio(scene.road), ratio(scene.crosswalk), ratio(scene.braille)
    metrics.update(road_overlap=round(road_r, 3), crosswalk_overlap=round(cross_r, 3), braille_overlap=round(braille_r, 3))
    on_road = road_r > th.max_road_overlap
    braille_hit = braille_r > th.max_braille_overlap
    cross_hit = cross_r > th.max_crosswalk_overlap
    if on_road:
        reasons.append(_reason("ON_ROAD"))
    if cross_hit:
        reasons.append(_reason("ON_CROSSWALK"))
    if braille_hit:
        reasons.append(_reason("ON_BRAILLE"))

    sidewalk_frac = float(scene.sidewalk.sum() / total)
    metrics["sidewalk_frac"] = round(sidewalk_frac, 3)
    verdict = Verdict(APPROVE, reasons, metrics, ti, ground=ground)

    if sidewalk_frac < th.min_sidewalk_frac:
        if not on_road:
            reasons.append(_reason("NO_SIDEWALK"))
        return _finish(verdict)

    # ---- cm 실측 경로: 점자블록 기준 조감도
    passage = None
    if ground is not None and ground.confidence != "low":
        from .ground import measure_passage

        passage = measure_passage(scene, ti, ground, th)
    if passage is not None:
        verdict.passage = passage
        res_cm = passage.residual_cm
        metrics.update(
            residual_cm=round(res_cm),
            original_cm=round(passage.original_cm),
            reduced_cm=round(passage.original_cm - res_cm),
            width_occupancy=round(passage.occupancy, 3),
            truncated=passage.truncated,
            scale_source=ground.source,
        )
        if res_cm < th.min_residual_cm:
            reasons.append(_reason("NARROW", cm=round(res_cm)) if passage.conclusive else _reason("TRUNCATED"))
        if passage.occupancy >= th.max_width_occupancy:
            reasons.append(_reason("TRUNCATED") if passage.truncated else _reason("WIDTH", pct=round(passage.occupancy * 100)))
        grade = grade_of(res_cm, braille_hit, cross_hit, len(vehicles), th)
        metrics["grade"] = grade
        metrics["score"] = score_of(res_cm, braille_hit, passage.braille_dist_cm, cross_hit, target.fallen, len(vehicles))
        if grade in ("경고", "주의") and res_cm >= th.min_residual_cm:
            code = "WARN_WIDTH" if res_cm < th.warn_cm else "CAUTION_WIDTH" if res_cm < th.caution_cm else None
            if code:
                reasons.append(_reason(code, cm=round(res_cm)))
        if len(vehicles) >= th.cluster_count:
            reasons.append(_reason("CLUSTER", n=len(vehicles)))
        return _finish(verdict)

    # ---- 픽셀 비율 경로 (실측 불가: 점자블록 없음/가려짐/신뢰도 낮음)
    metrics["scale_source"] = "none" if ground is None else f"{ground.source}(신뢰도 낮음)"
    rows = np.flatnonzero(bottom_part(target.mask, th.band_frac).any(axis=1))
    rows_info = width_occupancy(scene, rows, th)
    metrics["measured_rows"] = len(rows_info)
    if not rows_info:
        if not on_road:
            reasons.append(_reason("NO_SIDEWALK"))
        return _finish(verdict)
    ratios = np.array([r["ratio"] for r in rows_info])
    occ = float(np.percentile(ratios, th.row_percentile))
    worst = rows_info[int(np.argmax(ratios))]
    over = [r for r in rows_info if r["ratio"] >= th.max_width_occupancy]
    truncated = bool(over) and sum(r["truncated"] for r in over) > len(over) / 2
    metrics.update(width_occupancy=round(occ, 3), truncated=truncated)
    verdict.worst_row, verdict.worst_span = worst["y"], (worst["x0"], worst["x1"])
    if occ >= th.max_width_occupancy:
        reasons.append(_reason("TRUNCATED") if truncated else _reason("WIDTH", pct=round(occ * 100)))

    # 최후 대체 기준: 기기를 옆에서 찍었으면 차체 길이로 대략 cm 환산 (표시만, 판정에는 안 씀)
    L = th.vehicle_length_cm.get(target.kind)
    ys, xs = np.nonzero(footprint)
    ext = xs.max() - xs.min() + 1 if xs.size else 0
    if L and ext and ext >= 1.2 * (ys.max() - ys.min() + 1):
        metrics["residual_cm_est"] = round(worst["free_px"] * L / ext)
        metrics["scale_source"] = "vehicle_length(추정)"
    return _finish(verdict)


def _finish(verdict: Verdict) -> Verdict:
    seen, uniq = set(), []
    for r in verdict.reasons:
        if r["code"] not in seen:
            seen.add(r["code"])
            uniq.append(r)
    verdict.reasons = uniq
    statuses = {r["status"] for r in uniq}
    verdict.status = REJECT if REJECT in statuses else RETAKE if RETAKE in statuses else APPROVE
    return verdict
