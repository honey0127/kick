"""정답(cm)을 아는 합성 원근 장면.

지면 좌표 (cm): X = 보도를 가로지르는 방향, Y = 보도 진행 방향, Z = 위.
  X < 100        차도
  100 <= X < 400 보도 (폭 300cm)
  300 <= X < 330 선형 점자블록 띠 (30cm 블록, 이음매 2cm)
  X >= 400       건물 벽
카메라: (250, 0) 높이 150cm, 보도 방향(+Y)을 보며 35도 아래로.
"""
import cv2
import numpy as np

from server.rules import Scene, Vehicle

IMG_W, IMG_H, F = 960, 1280, 900
GX0, GW, GH = -300, 900, 2600  # 지면 래스터 1px = 1cm, X ∈ [-300, 600), Y ∈ [0, 2600)
ROAD, SIDEWALK, BRAILLE, WALL = 1, 2, 3, 4


def camera(cam=(250.0, 0.0, 150.0), pitch_deg=35.0):
    K = np.array([[F, 0, IMG_W / 2], [0, F, IMG_H / 2], [0, 0, 1.0]])
    p = np.radians(pitch_deg)
    right = np.array([1.0, 0, 0])
    fwd = np.array([0, np.cos(p), -np.sin(p)])
    down = np.cross(fwd, right)
    R = np.array([right, down, fwd])
    t = -R @ np.array(cam)
    P = K @ np.c_[R, t]
    H_g2i = K @ np.c_[R[:, 0], R[:, 1], t]
    return P, H_g2i


def ground_raster(seed=0):
    rng = np.random.default_rng(seed)
    lab = np.zeros((GH, GW), np.uint8)
    X = np.arange(GW) + GX0
    lab[:, X < 100] = ROAD
    lab[:, (X >= 100) & (X < 400)] = SIDEWALK
    lab[:, X >= 400] = WALL
    lab[:, (X >= 300) & (X < 330)] = BRAILLE
    tex = np.zeros((GH, GW, 3), np.uint8)
    tex[lab == ROAD] = (70, 70, 70)
    tex[lab == SIDEWALK] = (150, 150, 155)
    tex[lab == WALL] = (110, 120, 130)
    tex[lab == BRAILLE] = (20, 200, 235)
    # 선형 블록의 돌기 4줄 (띠 방향), 블록 사이 이음매 (2cm, 30cm 간격)
    for bx in (305, 312, 319, 326):
        c = bx - GX0
        tex[:, c - 1 : c + 1][lab[:, c - 1 : c + 1] == BRAILLE] = (10, 170, 205)
    yy = np.arange(GH)
    seam = (yy % 30) < 2
    bcols = slice(300 - GX0, 330 - GX0)
    tex[seam, bcols] = (40, 45, 50)
    noise = rng.normal(0, 6, tex.shape)
    return lab, np.clip(tex + noise, 0, 255).astype(np.uint8)


def make_scene(boxes, seed=0):
    """boxes: [(x0, x1, y0, y1, height_cm), ...] 지면 위 직육면체 기기."""
    P, H_g2i = camera()
    T = np.array([[1, 0, GX0], [0, 1, 0], [0, 0, 1.0]])  # 래스터 -> 지면
    H_r2i = H_g2i @ T
    lab_g, tex_g = ground_raster(seed)
    img = cv2.warpPerspective(tex_g, H_r2i, (IMG_W, IMG_H), flags=cv2.INTER_LINEAR)
    lab = cv2.warpPerspective(lab_g, H_r2i, (IMG_W, IMG_H), flags=cv2.INTER_NEAREST)

    # 지평선 위(카메라 뒤 지면으로 매핑되는 곳)는 하늘
    Hi = np.linalg.inv(H_g2i)
    gx, gy = np.meshgrid(np.arange(IMG_W), np.arange(IMG_H))
    w = Hi[2, 0] * gx + Hi[2, 1] * gy + Hi[2, 2]
    w_ref = Hi[2] @ [IMG_W / 2, IMG_H - 1, 1]
    sky = np.sign(w) != np.sign(w_ref)
    img[sky] = (230, 210, 190)
    lab[sky] = 0

    vehicles = []
    veh_any = np.zeros((IMG_H, IMG_W), bool)
    for x0, x1, y0, y1, hgt in boxes:
        corners = np.array([[x, y, z, 1.0] for x in (x0, x1) for y in (y0, y1) for z in (0, hgt)])
        p = (P @ corners.T).T
        p = p[:, :2] / p[:, 2:3]
        m = np.zeros((IMG_H, IMG_W), np.uint8)
        cv2.fillConvexPoly(m, cv2.convexHull(p.astype(np.float32)).astype(np.int32), 1)
        m = m.astype(bool)
        vehicles.append(Vehicle(m, kind="bicycle"))
        veh_any |= m
    img[veh_any] = (90, 60, 40)
    scene = Scene(
        vehicles,
        sidewalk=(lab == SIDEWALK) & ~veh_any,
        road=(lab == ROAD) & ~veh_any,
        braille=(lab == BRAILLE) & ~veh_any,
    )
    return img, scene, H_g2i
