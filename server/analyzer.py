"""사진 -> 마스크(Scene) -> 판정(Verdict).

모델 구성
  1) models/kick_seg.pt 가 있으면: 직접 학습한 YOLO-seg 하나로 기기·보도·차도·점자블록·횡단보도 전부 인식
     (학습 데이터에 sidewalk/road 클래스가 없으면 그 부분만 SegFormer로 보충)
  2) 없으면 베이스라인: COCO YOLO-seg(bicycle/motorcycle) + Cityscapes SegFormer(road/sidewalk)
     -> 학습 없이 바로 돌려볼 수 있지만 킥보드·점자블록·횡단보도는 인식 못 함
"""
from __future__ import annotations

import base64
import time

import cv2
import numpy as np

from . import config
from .ground import detect_braille_by_color, estimate_ground_scale
from .rules import APPROVE, REJECT, Scene, Thresholds, Vehicle, bottom_part, judge

# 학습 데이터셋 클래스 (training/dataset.yaml 과 같아야 함)
VEHICLE_CLASSES = {"kickboard", "ebike", "fallen_vehicle"}
STUFF_CLASSES = {"sidewalk", "road", "braille_block", "crosswalk"}
TILE_CLASS = "braille_tile"  # 점자블록 한 장 (선택 라벨, 실측 정확도↑)
COCO_VEHICLES = {1: "bicycle", 3: "motorcycle"}
CITYSCAPES = {"road": 0, "sidewalk": 1, "motorcycle": 17, "bicycle": 18}


def _device() -> str:
    if config.DEVICE:
        return config.DEVICE
    import torch

    return "cuda" if torch.cuda.is_available() else "cpu"


class SegFormerStuff:
    """Cityscapes 사전학습 SegFormer 로 차도/보도(+자전거) 픽셀 분할."""

    def __init__(self, device: str):
        from transformers import SegformerForSemanticSegmentation, SegformerImageProcessor

        self.device = device
        self.proc = SegformerImageProcessor.from_pretrained(config.SEGFORMER)
        self.model = SegformerForSemanticSegmentation.from_pretrained(config.SEGFORMER).to(device).eval()

    def __call__(self, img_bgr: np.ndarray) -> np.ndarray:
        import torch
        import torch.nn.functional as F

        h, w = img_bgr.shape[:2]
        inputs = self.proc(images=np.ascontiguousarray(img_bgr[..., ::-1]), return_tensors="pt").to(self.device)
        with torch.no_grad():
            logits = self.model(**inputs).logits
            logits = F.interpolate(logits, size=(h, w), mode="bilinear", align_corners=False)
        return logits.argmax(1)[0].cpu().numpy()


class Analyzer:
    def __init__(self, thresholds: Thresholds | None = None):
        from ultralytics import YOLO

        self.th = thresholds or Thresholds()
        self.device = _device()
        self.custom = YOLO(str(config.CUSTOM_WEIGHTS)) if config.CUSTOM_WEIGHTS.exists() else None
        self.surface = YOLO(str(config.SURFACE_WEIGHTS)) if config.SURFACE_WEIGHTS.exists() else None
        self.coco = None if self.custom else YOLO(config.BASELINE_YOLO)
        custom_names = set(self.custom.names.values()) if self.custom else set()
        has_stuff = self.surface is not None or {"sidewalk", "road"} <= custom_names
        self.segformer = None if has_stuff else SegFormerStuff(self.device)
        parts = [f"custom YOLO-seg ({config.CUSTOM_WEIGHTS.name})" if self.custom else "COCO YOLO-seg"]
        if self.surface:
            parts.append(f"surface YOLO-seg ({config.SURFACE_WEIGHTS.name})")
        if self.segformer:
            parts.append("Cityscapes SegFormer")
        self.backend = " + ".join(parts)

    # ---------- 인식 ----------
    def _yolo_instances(self, model, img, classes=None):
        r = model.predict(img, conf=config.CONF, classes=classes, retina_masks=True, device=self.device, verbose=False)[0]
        if r.masks is None:
            return []
        h, w = img.shape[:2]
        out = []
        for m, c, k in zip(r.masks.data.cpu().numpy(), r.boxes.conf.cpu().numpy(), r.boxes.cls.cpu().numpy()):
            mask = m > 0.5
            if mask.shape != (h, w):
                mask = cv2.resize(m, (w, h), interpolation=cv2.INTER_LINEAR) > 0.5
            out.append((model.names[int(k)], float(c), mask))
        return out

    def perceive(self, img: np.ndarray) -> Scene:
        h, w = img.shape[:2]
        empty = lambda: np.zeros((h, w), bool)  # noqa: E731
        stuff = {name: empty() for name in STUFF_CLASSES}
        vehicles: list[Vehicle] = []
        tiles: list[np.ndarray] = []

        if self.custom:
            for name, conf, mask in self._yolo_instances(self.custom, img):
                if name in VEHICLE_CLASSES:
                    vehicles.append(Vehicle(mask, kind=name, conf=conf, fallen=name == "fallen_vehicle"))
                elif name == TILE_CLASS:
                    tiles.append(mask)
                    stuff["braille_block"] |= mask
                elif name in stuff:
                    stuff[name] |= mask
        else:
            for name, conf, mask in self._yolo_instances(self.coco, img, classes=list(COCO_VEHICLES)):
                vehicles.append(Vehicle(mask, kind=name, conf=conf))

        if self.surface:
            for name, conf, mask in self._yolo_instances(self.surface, img):
                if name in stuff:
                    stuff[name] |= mask

        if self.segformer:
            lab = self.segformer(img)
            stuff["road"] |= lab == CITYSCAPES["road"]
            stuff["sidewalk"] |= lab == CITYSCAPES["sidewalk"]
            if not vehicles:  # YOLO가 놓쳤으면 SegFormer의 자전거/오토바이 픽셀 중 가장 큰 덩어리
                vehicles = self._largest_blob(np.isin(lab, [CITYSCAPES["bicycle"], CITYSCAPES["motorcycle"]]))

        # 기기 픽셀은 바닥 클래스에서 뺀다
        veh_any = np.zeros((h, w), bool)
        for v in vehicles:
            veh_any |= v.mask
        for k in stuff:
            stuff[k] &= ~veh_any

        # 점자블록을 모델이 못 찾으면(베이스라인 등) 노란색으로 찾아본다
        if not stuff["braille_block"].any():
            strip, color_tiles = detect_braille_by_color(img, stuff["sidewalk"], stuff["road"])
            if strip is not None:
                stuff["braille_block"] = strip & ~veh_any
                tiles = color_tiles
        if stuff["braille_block"].any():
            stuff["sidewalk"] &= ~stuff["braille_block"]
            stuff["road"] &= ~stuff["braille_block"]
        tiles = [t & ~veh_any for t in tiles]
        has = lambda k: stuff[k] if stuff[k].any() else None  # noqa: E731
        return Scene(vehicles, stuff["sidewalk"], stuff["road"], has("braille_block"), has("crosswalk"), tiles)

    @staticmethod
    def _largest_blob(mask: np.ndarray) -> list[Vehicle]:
        n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8))
        if n <= 1:
            return []
        i = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
        return [Vehicle(labels == i, kind="bicycle(seg)", conf=0.5)]

    # ---------- 전체 파이프라인 ----------
    def analyze(self, img: np.ndarray) -> dict:
        t0 = time.time()
        img = resize_max(img, config.MAX_SIDE)
        scene = self.perceive(img)
        return analyze_scene(img, scene, self.th) | {"backend": self.backend, "elapsed_ms": int((time.time() - t0) * 1000)}


def _b64jpg(img: np.ndarray) -> str:
    return base64.b64encode(cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 80])[1]).decode()


def analyze_scene(img: np.ndarray, scene: Scene, th: Thresholds) -> dict:
    """인식 이후 단계: 점자블록 실측 -> 판정 -> 시각화 (모델 없이 합성 장면으로도 호출 가능)."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    ground = estimate_ground_scale(gray, scene, th)
    verdict = judge(scene, th, ground=ground)
    out = verdict.to_dict()
    out["overlay_jpeg"] = _b64jpg(render(img, scene, verdict, th))
    out["bev_jpeg"] = _b64jpg(verdict.passage.bev) if verdict.passage is not None else None
    return out


def resize_max(img: np.ndarray, max_side: int) -> np.ndarray:
    h, w = img.shape[:2]
    s = max_side / max(h, w)
    return cv2.resize(img, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else img


def render(img: np.ndarray, scene: Scene, verdict, th: Thresholds) -> np.ndarray:
    """판정 근거 시각화: 보도=초록, 차도=빨강, 점자블록=노랑, 횡단보도=흰색, 기기=파랑, 접지부=주황."""
    out = img.copy()
    layers = [
        (scene.sidewalk, (80, 200, 80)),
        (scene.road, (60, 60, 230)),
        (scene.braille, (0, 220, 255)),
        (scene.crosswalk, (240, 240, 240)),
    ]
    for i, v in enumerate(scene.vehicles):
        layers.append((v.mask, (255, 140, 30) if i == verdict.target_index else (200, 100, 60)))
    if verdict.target_index is not None:
        layers.append((bottom_part(scene.vehicles[verdict.target_index].mask, th.footprint_frac), (0, 140, 255)))
    color = np.zeros_like(out)
    painted = np.zeros(out.shape[:2], bool)
    for mask, bgr in layers:
        if mask is not None:
            color[mask] = bgr
            painted |= mask
    out[painted] = (0.5 * out[painted] + 0.5 * color[painted]).astype(np.uint8)

    if verdict.ground is not None:  # 실측에 쓴 점자블록 사각형
        for q in verdict.ground.quads:
            cv2.polylines(out, [np.round(q).astype(np.int32)], True, (255, 255, 0), 1, cv2.LINE_AA)
    if verdict.passage is not None:  # 병목 단면 (조감도 -> 원래 사진으로 되돌려 그림)
        p = verdict.ground.to_image(verdict.passage.line_ground)
        if np.isfinite(p).all():
            a, b = tuple(np.round(p[0]).astype(int)), tuple(np.round(p[1]).astype(int))
            cv2.line(out, a, b, (60, 220, 60), 4, cv2.LINE_AA)
            for e in (a, b):
                cv2.circle(out, e, 6, (60, 220, 60), -1)
            label = f"{verdict.passage.residual_cm / 100:.2f}m"
            cv2.putText(out, label, (min(a[0], b[0]) + 6, min(a[1], b[1]) - 12), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 3, cv2.LINE_AA)
    elif verdict.worst_row is not None:
        y, (x0, x1) = verdict.worst_row, verdict.worst_span
        cv2.line(out, (x0, y), (x1, y), (255, 255, 255), 3)
        cv2.line(out, (x0, y - 12), (x0, y + 12), (255, 255, 255), 3)
        cv2.line(out, (x1, y - 12), (x1, y + 12), (255, 255, 255), 3)
        occ = verdict.metrics.get("width_occupancy")
        if occ is not None:
            cv2.putText(out, f"{occ * 100:.0f}%", (x0 + 6, y - 16), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2, cv2.LINE_AA)
    bar = {APPROVE: (60, 180, 60), REJECT: (50, 50, 220)}.get(verdict.status, (0, 170, 240))
    cv2.rectangle(out, (0, 0), (out.shape[1], 36), bar, -1)
    head = verdict.status
    if "residual_cm" in verdict.metrics:
        head += f"  residual {verdict.metrics['residual_cm']}cm  score {verdict.metrics['score']}"
    cv2.putText(out, head, (10, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
    return out
