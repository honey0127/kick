"""GPS 반납 가능 위치 판정 (1순위 검사).

zones.geojson 의 Feature properties.type:
  service_area : 반납 가능 지역 (이 밖이면 반납 불가)
  parking      : 지정 주차구역
  no_parking   : 반납 금지구역 (횡단보도 앞, 지하철 출입구, 점자블록 구간 등)
"""
from __future__ import annotations

import json
import math
from pathlib import Path


def _in_ring(lon: float, lat: float, ring: list) -> bool:
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][:2]
        xj, yj = ring[j][:2]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def _in_polygon(lon: float, lat: float, rings: list) -> bool:
    return _in_ring(lon, lat, rings[0]) and not any(_in_ring(lon, lat, h) for h in rings[1:])


def _contains(geom: dict, lon: float, lat: float) -> bool:
    if geom["type"] == "Polygon":
        return _in_polygon(lon, lat, geom["coordinates"])
    if geom["type"] == "MultiPolygon":
        return any(_in_polygon(lon, lat, p) for p in geom["coordinates"])
    return False


def _dist_to_ring_m(lon: float, lat: float, ring: list) -> float:
    """점과 폴리곤 경계 사이 거리(m), 좁은 범위라 등장방형 근사."""
    kx = 111_320 * math.cos(math.radians(lat))
    ky = 110_540
    px, py = lon * kx, lat * ky
    best = float("inf")
    for (x1, y1, *_), (x2, y2, *_) in zip(ring, ring[1:]):
        ax, ay, bx, by = x1 * kx, y1 * ky, x2 * kx, y2 * ky
        dx, dy = bx - ax, by - ay
        t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        best = min(best, math.hypot(px - (ax + t * dx), py - (ay + t * dy)))
    return best


def _dist_m(geom: dict, lon: float, lat: float) -> float:
    if _contains(geom, lon, lat):
        return 0.0
    polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
    return min(_dist_to_ring_m(lon, lat, p[0]) for p in polys)


class ZoneIndex:
    def __init__(self, path: Path, require_parking_zone: bool = False, max_accuracy_m: float = 50):
        self.geojson = json.loads(Path(path).read_text(encoding="utf-8"))
        self.require_parking_zone = require_parking_zone
        self.max_accuracy_m = max_accuracy_m
        self.features = self.geojson["features"]

    def _of(self, kind: str):
        return [f for f in self.features if f["properties"].get("type") == kind]

    def check(self, lat: float, lon: float, accuracy: float | None = None) -> dict:
        def res(ok, code, message, zone=None):
            return {"ok": ok, "code": code, "message": message, "zone": zone}

        if accuracy is not None and accuracy > self.max_accuracy_m:
            return res(False, "GPS_INACCURATE", f"GPS 정확도가 낮아요(±{accuracy:.0f}m). 하늘이 트인 곳에서 다시 시도해주세요.")

        # 오차 반경 안에 금지구역이 걸치면 보수적으로 막는다
        margin = min(accuracy or 0, 10)
        for f in self._of("no_parking"):
            if _dist_m(f["geometry"], lon, lat) <= margin:
                return res(False, "NO_PARKING_ZONE", f"반납 금지구역이에요: {f['properties'].get('name', '')}", f["properties"])

        areas = self._of("service_area")
        if areas and not any(_contains(f["geometry"], lon, lat) for f in areas):
            return res(False, "OUT_OF_SERVICE_AREA", "서비스 지역 밖이에요. 지도에 표시된 반납 가능 지역으로 이동해주세요.")

        for f in self._of("parking"):
            if _dist_m(f["geometry"], lon, lat) <= margin:
                return res(True, "PARKING_ZONE", f"지정 주차구역이에요: {f['properties'].get('name', '')}", f["properties"])

        if self.require_parking_zone:
            return res(False, "NOT_IN_PARKING_ZONE", "지정 주차구역 안에서만 반납할 수 있어요.")
        return res(True, "SERVICE_AREA", "반납 가능한 위치예요.")
