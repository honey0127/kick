"""점자블록을 '자'로 써서 지면 실측(cm)을 복원하고, 조감도에서 잔여 통행폭을 잰다.

1) 실측 스케일: 점자블록 한 장 = 30cm x 30cm.
   블록 사각형(이미지 속 네 꼭짓점)을 찾아 '이미지 픽셀 -> 지면 cm' 호모그래피 H 를 구한다.
   - 모델이 블록을 한 장씩(braille_tile) 분할해 주거나 색으로 블록이 따로 떨어져 보이면 그 사각형을 쓰고
   - 아니면 점자블록 띠(braille_block) 마스크에서 블록 사이 이음매(어두운 선)를 찾아 사각형을 만든다.
   한 블록으로 H 를 만든 뒤 주변 블록 꼭짓점을 30cm 격자에 맞춰가며 다시 맞추고(region growing),
   남는 재투영 오차(cm)를 측정 신뢰도로 쓴다.
2) 잔여 통행폭: 바닥 마스크를 조감도(2cm/px)로 펴고, 보도 진행 방향에 수직인 단면을 10cm 간격으로 잘라
   기기를 피해 연속으로 지나갈 수 있는 폭을 잰다. 그 최솟값이 병목 통행폭.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from .rules import _runs, bottom_part


# ---------------------------------------------------------------- 기하 유틸
def transform(pts, H) -> tuple[np.ndarray, np.ndarray]:
    """동차좌표 변환. (xy, w) 반환 - w 부호로 카메라 앞/뒤를 구분한다."""
    pts = np.asarray(pts, np.float64).reshape(-1, 2)
    p = np.c_[pts, np.ones(len(pts))] @ np.asarray(H, np.float64).T
    with np.errstate(divide="ignore", invalid="ignore"):
        return p[:, :2] / p[:, 2:3], p[:, 2]


def _cross(a, b):
    return a[..., 0] * b[..., 1] - a[..., 1] * b[..., 0]


def _intersect(c, m, p, e):
    """직선 c + s*m 과 p + u*e 의 교점 (c 는 여러 점 가능)."""
    den = _cross(m, e)
    if abs(den) < 1e-9:
        return None
    s = _cross(p - c, e) / den
    return c + np.asarray(s)[..., None] * m


def _rot(v, th):
    c, s = np.cos(th), np.sin(th)
    return np.array([c * v[0] - s * v[1], s * v[0] + c * v[1]])


def _sample_mean(gray, a, b, mask, fr=(0.15, 0.85), n=15):
    """선분 a-b 위(양끝 제외) 픽셀 밝기 평균. a, b: (T,2) -> (T,)"""
    t = np.linspace(fr[0], fr[1], n)
    pts = a[:, None, :] + t[None, :, None] * (b - a)[:, None, :]
    xs = np.round(pts[..., 0]).astype(int)
    ys = np.round(pts[..., 1]).astype(int)
    h, w = gray.shape
    ok = (xs >= 0) & (xs < w) & (ys >= 0) & (ys < h)
    xs, ys = np.clip(xs, 0, w - 1), np.clip(ys, 0, h - 1)
    ok &= mask[ys, xs]
    vals = np.where(ok, gray[ys, xs], 0.0)
    cnt = ok.sum(1)
    with np.errstate(invalid="ignore", divide="ignore"):
        out = vals.sum(1) / cnt
    out[cnt < n // 3] = np.nan
    return out


# ---------------------------------------------------------------- 블록 사각형 찾기
def mask_quad(mask: np.ndarray) -> np.ndarray | None:
    """블록 한 장 마스크 -> 네 꼭짓점 (순서: 둘레 방향)."""
    cnts, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None
    c = max(cnts, key=cv2.contourArea)
    if cv2.contourArea(c) < 40:
        return None
    hull = cv2.convexHull(c)
    peri = cv2.arcLength(hull, True)
    for eps in np.linspace(0.02, 0.12, 11):
        ap = cv2.approxPolyDP(hull, eps * peri, True)
        if len(ap) == 4:
            q = ap.reshape(4, 2).astype(np.float64)
            # 블록은 볼록 사각형이라 사각형이 볼록껍질을 거의 채워야 함
            return q if cv2.contourArea(q.astype(np.float32)) >= 0.75 * cv2.contourArea(hull) else None
        if len(ap) < 4:
            break
    return None


def strip_quads(gray: np.ndarray, strip: np.ndarray) -> list[np.ndarray]:
    """점자블록 띠 하나에서 이음매(어두운 가로선)를 찾아 블록 사각형 목록을 만든다.

    사각형 꼭짓점 순서는 [L_k, R_k, R_k+1, L_k+1] (L/R = 띠의 두 가장자리, k = 이음매 순서)
    -> 지면 좌표 (0,0),(폭,0),(폭,30),(0,30) 에 대응: x = 띠를 가로지르는 방향, y = 띠 방향.
    """
    ys, xs = np.nonzero(strip)
    if xs.size < 200:
        return []
    pts = np.c_[xs, ys].astype(np.float64)
    mean = pts.mean(0)
    evals, evecs = np.linalg.eigh(np.cov((pts - mean).T))
    if evals[1] < 9 * evals[0]:  # 길쭉한 띠가 아님 (점형 블록 묶음 등)
        return []
    d, n = evecs[:, 1], evecs[:, 0]

    cnts, _ = cv2.findContours(strip.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cp = max(cnts, key=len).reshape(-1, 2).astype(np.float64)
    t, s = (cp - mean) @ d, (cp - mean) @ n
    lo, hi = np.percentile(t, [4, 96])
    keep = (t > lo) & (t < hi)
    edges = []
    for sel, side in ((keep & (s < 0), -1), (keep & (s > 0), 1)):
        if sel.sum() < 10:
            return []
        vx, vy, x0, y0 = cv2.fitLine(cp[sel].astype(np.float32), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        # 윤곽 픽셀 중심은 실제 경계보다 0.5px 안쪽 -> 바깥으로 보정 (안 하면 폭이 좁게 잡혀 cm 가 ~2% 커짐)
        edges.append((np.array([x0, y0], float) + side * 0.5 * n, np.array([vx, vy], float)))
    (pL, dL), (pR, dR) = edges

    # 띠를 따라 1px 간격으로 가로 단면 밝기 프로파일 (이음매 기울기를 모르니 여러 각도 중 최솟값)
    ts = np.arange(lo, hi, 1.0)
    centers = mean[None] + ts[:, None] * d[None]
    angles = np.radians(np.arange(-24, 25, 4))
    profs, widths = [], None
    for th in angles:
        m = _rot(n, th)
        a, b = _intersect(centers, m, pL, dL), _intersect(centers, m, pR, dR)
        if a is None or b is None:
            profs.append(np.full(len(ts), np.nan))
            continue
        if th == 0:
            widths = np.linalg.norm(b - a, axis=1)
        profs.append(_sample_mean(gray, a, b, strip))
    profs = np.array(profs)
    with np.errstate(all="ignore"):
        prof = np.nanmin(profs, axis=0)
    valid = np.isfinite(prof)
    if valid.sum() < 20 or widths is None:
        return []
    prof = np.interp(np.arange(len(prof)), np.flatnonzero(valid), prof[valid]).astype(np.float32)
    w_med = float(np.median(widths))
    win = max(5, int(w_med * 1.5)) | 1
    base = cv2.blur(prof.reshape(1, -1), (win, 1), borderType=cv2.BORDER_REFLECT).ravel()
    sm = cv2.blur(prof.reshape(1, -1), (3, 1), borderType=cv2.BORDER_REFLECT).ravel()
    ratio = sm / np.maximum(base, 1)

    seams = []
    for k in range(len(ts)):
        if not valid[k] or ratio[k] > 0.85:
            continue
        sep = max(2, int(0.3 * widths[k]))
        if sm[k] <= sm[max(0, k - sep) : k + sep + 1].min():
            seams.append(k)

    # 이음매마다 기울기 정밀 탐색 -> 두 가장자리와의 교점
    segs = []
    for k in seams:
        c = centers[k]
        best = None
        for th in np.radians(np.arange(-30, 30.5, 1)):
            m = _rot(n, th)
            a, b = _intersect(c, m, pL, dL), _intersect(c, m, pR, dR)
            if a is None or b is None:
                continue
            v = _sample_mean(gray, a[None], b[None], strip, fr=(0.1, 0.9), n=21)[0]
            if np.isfinite(v) and (best is None or v < best[0]):
                best = (v, a, b)
        if best:
            segs.append((ts[k], best[1], best[2]))

    quads = []
    h, w = strip.shape
    for (_, a1, b1), (_, a2, b2) in zip(segs, segs[1:]):
        q = np.array([a1, b1, b2, a2])
        if abs(cv2.contourArea(q.astype(np.float32))) < 30:
            continue
        cx, cy = np.round(q.mean(0)).astype(int)
        if not (0 <= cx < w and 0 <= cy < h and strip[cy, cx]):  # 가운데가 가려진 칸은 버림
            continue
        quads.append(q)
    return quads


# ---------------------------------------------------------------- 30cm 격자 맞춤
def fit_grid_homography(quads: list[np.ndarray], sx: float, sy: float, tol: float = 0.25):
    """여러 블록 사각형을 하나의 (sx, sy) 격자에 맞추는 호모그래피.
    반환: (H, used_idx, err_cm) 또는 None
    """
    if not quads:
        return None
    step = np.array([sx, sy])
    sq = np.float32([[0, 0], [sx, 0], [sx, sy], [0, sy]])
    cents = np.array([q.mean(0) for q in quads])
    areas = [abs(cv2.contourArea(q.astype(np.float32))) for q in quads]
    best = None
    for i in sorted(range(len(quads)), key=lambda i: -areas[i])[:10]:
        H = cv2.getPerspectiveTransform(quads[i].astype(np.float32), sq).astype(np.float64)
        src, dst, used = [], [], []
        for j in np.argsort(np.linalg.norm(cents - cents[i], axis=1)):
            g, _ = transform(quads[j], H)
            if not np.isfinite(g).all():
                continue
            snap = np.round(g / step) * step
            if np.abs(g - snap).max() > tol * step.min() or len({tuple(p) for p in snap}) < 4:
                continue
            src.append(quads[j])
            dst.append(snap)
            used.append(int(j))
            if len(used) >= 2:
                Hn, _ = cv2.findHomography(np.concatenate(src).astype(np.float32), np.concatenate(dst).astype(np.float32), 0)
                if Hn is not None:
                    H = Hn
        g, _ = transform(np.concatenate(src), H)
        err = float(np.linalg.norm(g - np.concatenate(dst), axis=1).mean())
        score = (len(used), -err)
        if best is None or score > best[0]:
            best = (score, H, used, err)
    return best[1], best[2], best[3]


@dataclass
class GroundScale:
    H: np.ndarray  # 이미지 px -> 지면 cm (y = 보도 진행 방향)
    source: str  # braille_tiles | braille_seams
    n_tiles: int
    err_cm: float
    confidence: str  # high | medium | low
    w_sign: float  # 카메라 앞쪽 점의 동차좌표 w 부호
    quads: list = field(default_factory=list)

    def to_ground(self, pts):
        return transform(pts, self.H)[0]

    def to_image(self, pts):
        return transform(pts, np.linalg.inv(self.H))[0]

    def to_dict(self) -> dict:
        return {"source": self.source, "n_tiles": self.n_tiles, "err_cm": round(self.err_cm, 2), "confidence": self.confidence}


def _align_along(H: np.ndarray, quads: list, shape) -> np.ndarray:
    """블록 한 장씩 찾은 경우 격자 x/y 중 어느 쪽이 보도 방향인지 정한다.
    점자블록이 길게 늘어선 방향, 애매하면 카메라가 바라보는 방향을 보도 방향(y)으로."""
    cg = np.array([transform(q, H)[0].mean(0) for q in quads])
    spread = cg.std(0) if len(cg) >= 2 else np.zeros(2)
    if spread.max() > 2 * max(spread.min(), 1e-6) and spread.max() > 20:
        along_x = spread[0] > spread[1]
    else:
        h, w = shape
        g, _ = transform([[w / 2, h * 0.95], [w / 2, h * 0.6]], H)
        v = g[1] - g[0]
        along_x = abs(v[0]) > abs(v[1])
    if along_x:
        H = np.array([[0, 1, 0], [1, 0, 0], [0, 0, 1]], float) @ H
    return H


def estimate_ground_scale(gray: np.ndarray, scene, th) -> GroundScale | None:
    cands = []
    if scene.braille_tiles:
        quads = [q for q in (mask_quad(m) for m in scene.braille_tiles) if q is not None]
        r = fit_grid_homography(quads, th.tile_cm, th.tile_cm)
        if r:
            cands.append(("braille_tiles", quads, *r))
    if scene.braille is not None:
        quads = []
        n, lab, stats, _ = cv2.connectedComponentsWithStats(scene.braille.astype(np.uint8))
        for i in range(1, n):
            if stats[i, cv2.CC_STAT_AREA] >= 300:
                quads += strip_quads(gray, lab == i)
        r = fit_grid_homography(quads, th.tile_cm * th.strip_tiles, th.tile_cm)
        if r:
            cands.append(("braille_seams", quads, *r))
    if not cands:
        return None
    src, quads, H, used, err = max(cands, key=lambda c: (len(c[3]), -c[4]))
    used_q = [quads[i] for i in used]
    if src == "braille_tiles":
        H = _align_along(H, used_q, gray.shape)
    n_t = len(used)
    conf = "high" if n_t >= 4 and err <= 2.5 else "medium" if n_t >= 2 and err <= th.max_scale_err_cm else "low"
    w_sign = float(np.sign(transform(used_q[0], H)[1][0]))
    gs = GroundScale(H, src, n_t, err, conf, w_sign, used_q)

    # 상식 검사: 사진 아래쪽은 카메라 앞 수 m 이내여야 함
    h, w = gray.shape
    g, ww = transform([[w / 2, h - 1], [w / 2, h * 0.7]], H)
    if not np.isfinite(g).all() or (np.sign(ww) != w_sign).any() or np.linalg.norm(g[0] - g[1]) > 3000:
        return None
    return gs


# ---------------------------------------------------------------- 조감도 통행폭
@dataclass
class Passage:
    residual_cm: float  # 병목 통행폭 (기기를 피해 연속으로 지나갈 수 있는 최소 폭)
    original_cm: float  # 그 단면의 원래 보도 폭 (기기 없을 때)
    occupancy: float  # 기기가 막은 폭 / 원래 폭 (단면들의 90퍼센타일)
    truncated: bool  # 보도 끝이 화면 밖이거나 가려짐 -> 원래 폭이 더 넓을 수 있음
    conclusive: bool  # 병목 통행폭이 화면 밖 때문에 과소추정된 게 아님
    braille_dist_cm: float | None
    sections: list
    line_ground: np.ndarray  # 병목 단면의 통행 가능 구간 양끝 (지면 cm)
    bev: np.ndarray  # 조감도 시각화 (BGR)

    def to_dict(self) -> dict:
        return {
            "residual_cm": round(self.residual_cm),
            "original_cm": round(self.original_cm),
            "reduced_cm": round(self.original_cm - self.residual_cm),
            "occupancy": round(self.occupancy, 3),
            "truncated": self.truncated,
            "conclusive": self.conclusive,
            "braille_dist_cm": None if self.braille_dist_cm is None else round(self.braille_dist_cm),
            "sections": [{"v_cm": s["v"], "residual_cm": round(s["free"]), "original_cm": round(s["orig"])} for s in self.sections],
        }


def contact_points(mask: np.ndarray, frac: float) -> np.ndarray:
    """기기가 바닥에 닿는 점: 아래쪽 frac 구간에서 열마다 가장 아래 픽셀."""
    band = bottom_part(mask, frac)
    cols = np.flatnonzero(band.any(axis=0))
    if cols.size == 0:
        return np.zeros((0, 2))
    ys = band.shape[0] - 1 - np.argmax(band[::-1, cols], axis=0)
    return np.c_[cols, ys].astype(np.float64)


def measure_passage(scene, target_idx: int, gs: GroundScale, th) -> Passage | None:
    h, w = scene.shape
    res = th.bev_res_cm

    polys = []
    for v in scene.vehicles:
        g, ww = transform(contact_points(v.mask, th.contact_frac), gs.H)
        ok = np.isfinite(g).all(1) & (np.sign(ww) == gs.w_sign)
        polys.append(g[ok] if ok.sum() >= 1 else None)
    if polys[target_idx] is None:
        return None
    c = polys[target_idx].mean(0)
    x0, y0 = c[0] - th.bev_half_across_cm, c[1] - th.bev_half_along_cm
    Wr, Hr = int(2 * th.bev_half_across_cm / res), int(2 * th.bev_half_along_cm / res)
    M = np.array([[1 / res, 0, -x0 / res], [0, 1 / res, -y0 / res], [0, 0, 1]]) @ gs.H

    def warp(m):
        return cv2.warpPerspective(m.astype(np.uint8), M, (Wr, Hr), flags=cv2.INTER_NEAREST) > 0

    valid = warp(np.ones((h, w), bool))
    Minv = np.linalg.inv(M)
    gx, gy = np.meshgrid(np.arange(Wr), np.arange(Hr))
    wv = Minv[2, 0] * gx + Minv[2, 1] * gy + Minv[2, 2]
    cr = (c - [x0, y0]) / res
    valid &= np.sign(wv) == np.sign(Minv[2, 0] * cr[0] + Minv[2, 1] * cr[1] + Minv[2, 2])  # 카메라 뒤쪽 제거

    braille_img = scene.braille if scene.braille is not None else np.zeros((h, w), bool)
    veh_any = np.zeros((h, w), bool)
    for v in scene.vehicles:
        veh_any |= v.mask
    floor = warp(scene.sidewalk | braille_img) & valid
    road_b = warp(scene.road) & valid
    unknown = warp(veh_any) & valid  # 기기 몸체에 가려 안 보이는 바닥
    braille_b = warp(braille_img) & valid

    # 기기 바닥 점유 영역: 접지점 볼록껍질 + 최소 폭(핸들 폭) + 여유
    minw = th.vehicle_min_width_cm / res
    margin = int(round(th.obstacle_margin_cm / res))
    obst = np.zeros((Hr, Wr), bool)
    target_m = None
    for i, g in enumerate(polys):
        if g is None:
            continue
        r = ((g - [x0, y0]) / res).astype(np.float32)
        m = np.zeros((Hr, Wr), np.uint8)
        cv2.fillConvexPoly(m, cv2.convexHull(r).astype(np.int32), 1)
        if not m.any():
            continue
        yy, xx = np.nonzero(m)
        xa, xb, ya, yb = xx.min(), xx.max(), yy.min(), yy.max()
        thin = False
        if xb - xa + 1 < minw:
            xm, thin = (xa + xb) / 2, True
            xa, xb = xm - minw / 2, xm + minw / 2
        if yb - ya + 1 < minw:
            ym, thin = (ya + yb) / 2, True
            ya, yb = ym - minw / 2, ym + minw / 2
        if thin:  # 바퀴 접지점만 보여 얇게 나오면 최소 차체 폭만큼 넓힘
            cv2.rectangle(m, (int(xa), int(ya)), (int(xb), int(yb)), 1, -1)
        if margin:
            m = cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * margin + 1, 2 * margin + 1)))
        obst |= m.astype(bool)
        if i == target_idx:
            target_m = m.astype(bool)
    if target_m is None:
        return None

    rows_t = np.flatnonzero(target_m.any(1))
    pad = int(30 / res)
    step = max(1, int(round(th.section_step_cm / res)))
    gap = max(1, int(round(20 / res)))
    secs = []
    for r in range(max(0, rows_t[0] - pad), min(Hr - 1, rows_t[-1] + pad) + 1, step):
        orow = obst[r]
        if not orow.any():
            continue
        anchor = floor[r] | orow
        best = None
        for a, b in _runs(anchor | unknown[r], gap):
            ov = orow[a : b + 1].sum()
            if ov and (best is None or ov > best[2]):
                best = (a, b, ov)
        if not best:
            continue
        a, b, _ = best
        an = np.flatnonzero(anchor[a : b + 1])  # 양끝이 가려진 영역뿐이면 잘라냄
        a, b = a + an[0], a + an[-1]
        free_runs = _runs(~orow[a : b + 1], 0)
        if free_runs:
            fa, fb = max(free_runs, key=lambda p: p[1] - p[0])
            free_cm = (fb - fa + 1) * res
        else:
            fa = fb = 0
            free_cm = 0.0
        edge_a = a == 0 or not valid[r, a - 1] or unknown[r, a - 1]
        edge_b = b == Wr - 1 or not valid[r, b + 1] or unknown[r, b + 1]
        n = b - a + 1
        secs.append({
            "r": r, "v": round((r - rows_t[0]) * res), "a": a, "b": b, "fa": a + fa, "fb": a + fb,
            "free": free_cm if free_runs else 0.0, "orig": n * res, "occ": orow[a : b + 1].sum() / n,
            "trunc": bool(edge_a or edge_b),
            "conclusive": bool(not free_runs or not ((fa == 0 and edge_a) or (fb == n - 1 and edge_b))),
        })
    if not secs:
        return None

    worst = min(secs, key=lambda s: s["free"])
    occ = float(np.percentile([s["occ"] for s in secs], th.row_percentile))
    bdist = None
    if braille_b.any():
        dt = cv2.distanceTransform((~braille_b).astype(np.uint8), cv2.DIST_L2, 5)
        bdist = float(dt[target_m].min() * res)
    line = np.array([[worst["fa"], worst["r"]], [worst["fb"] + 1, worst["r"]]], float) * res + [x0, y0 + res / 2]

    bev = _render_bev(valid, floor, road_b, braille_b, unknown, obst, target_m, worst, res, M, (h, w))
    return Passage(
        residual_cm=worst["free"], original_cm=worst["orig"], occupancy=occ,
        truncated=any(s["trunc"] for s in secs), conclusive=worst["conclusive"],
        braille_dist_cm=bdist, sections=secs, line_ground=line, bev=bev,
    )


def _render_bev(valid, floor, road, braille, unknown, obst, target, worst, res, M, img_shape):
    img = np.full(valid.shape + (3,), 25, np.uint8)
    img[valid] = (70, 70, 70)
    img[road] = (60, 60, 160)
    img[floor] = (175, 175, 175)
    img[braille] = (0, 210, 255)
    img[unknown & ~obst] = (125, 125, 125)
    img[obst] = (200, 110, 50)
    img[target] = (255, 140, 30)
    r = worst["r"]
    cv2.line(img, (worst["a"], r), (worst["b"], r), (255, 255, 255), 1)
    cv2.line(img, (worst["fa"], r), (worst["fb"], r), (60, 200, 60), 3)

    # 카메라 쪽이 아래, 사진의 왼쪽이 왼쪽이 되도록 뒤집기
    h, w = img_shape
    p, _ = transform([[w / 2, h - 1], [w * 0.3, h * 0.85], [w * 0.7, h * 0.85]], M)
    flip_v = np.isfinite(p).all() and p[0, 1] < r
    flip_h = np.isfinite(p).all() and p[1, 0] > p[2, 0]
    if flip_v:
        img = img[::-1]
        r = img.shape[0] - 1 - r
    tx = (worst["fa"] + worst["fb"]) // 2
    if flip_h:
        img = img[:, ::-1]
        tx = img.shape[1] - 1 - tx
    img = np.ascontiguousarray(img)
    label = f"{worst['free'] / 100:.2f}m"
    (tw, _), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)
    cv2.putText(img, label, (int(np.clip(tx - tw / 2, 2, img.shape[1] - tw - 2)), max(16, r - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2, cv2.LINE_AA)
    # 1m 축척 막대
    bar = int(100 / res)
    cv2.line(img, (8, img.shape[0] - 10), (8 + bar, img.shape[0] - 10), (255, 255, 255), 2)
    cv2.putText(img, "1m", (12 + bar, img.shape[0] - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
    return img


# ---------------------------------------------------------------- 점자블록 색 검출 (베이스라인용)
def detect_braille_by_color(img: np.ndarray, sidewalk: np.ndarray, road: np.ndarray):
    """노란 점자블록을 색으로 찾는다 (학습 모델이 없을 때 쓰는 임시 방법).
    반환: (띠 마스크 또는 None, 블록 한 장씩 떨어져 보이는 마스크 목록)"""
    h, w = img.shape[:2]
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    raw = cv2.inRange(hsv, (15, 90, 70), (38, 255, 255)) > 0
    k = max(3, int(0.015 * w)) | 1
    ground = cv2.dilate((sidewalk | road).astype(np.uint8), np.ones((k, k), np.uint8)) > 0
    raw &= ground
    raw = cv2.morphologyEx(raw.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)) > 0
    closed = cv2.morphologyEx(raw.astype(np.uint8), cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))) > 0
    n, lab, stats, _ = cv2.connectedComponentsWithStats(closed.astype(np.uint8))
    strip = np.zeros((h, w), bool)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < 0.001 * h * w:
            continue
        comp = lab == i
        ring = (cv2.dilate(comp.astype(np.uint8), np.ones((k, k), np.uint8)) > 0) & ~comp
        if (ring & sidewalk).sum() >= (ring & road).sum():  # 차도 중앙선(노란 선)은 제외
            strip |= comp
    if not strip.any():
        return None, []
    tiles = []
    n, lab, stats, _ = cv2.connectedComponentsWithStats((raw & strip).astype(np.uint8))
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] >= 120:
            tiles.append(lab == i)
    return strip, tiles
