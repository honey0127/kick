"""평가 도구: 인식 결과 저장·기준값 바꿔 다시 판정·조정용/확인용 분할·사유 집계·분포 (모델 없이)."""
import dataclasses

import cv2
import numpy as np
import pytest

from server.analyzer import RawPerception, build_scene
from server.rules import MESSAGES, Thresholds, Vehicle
from tests.synth import make_scene
from training import evaluate as ev
from training.metric_dist import distributions


def _raw(conf=0.9, models_braille=True):
    h, w = 40, 60
    v = np.zeros((h, w), bool)
    v[10:30, 20:30] = True
    sw = np.zeros((h, w), bool)
    sw[20:, :] = True
    empty = np.zeros((h, w), bool)
    stuff = {"sidewalk": sw, "road": empty.copy(), "braille_block": empty.copy(), "crosswalk": empty.copy()}
    return RawPerception([Vehicle(v, conf=conf)], stuff, [], models_braille)


def test_build_scene_applies_conf_threshold_without_touching_raw():
    img = np.zeros((40, 60, 3), np.uint8)
    raw = _raw(conf=0.4)
    before = raw.stuff["sidewalk"].copy()
    # 확신도 0.4 < 기본 0.5: 기기 자리를 보도에서 빼지 않음 / 0.3 으로 낮추면 뺌
    assert build_scene(img, raw, Thresholds()).sidewalk[25, 25]
    assert not build_scene(img, raw, dataclasses.replace(Thresholds(), min_vehicle_conf=0.3)).sidewalk[25, 25]
    assert np.array_equal(raw.stuff["sidewalk"], before)  # 저장본을 여러 번 써도 그대로


def test_cache_round_trip(tmp_path):
    raw = _raw()
    raw.tiles = [raw.vehicles[0].mask.copy()]
    ev.save_raw(tmp_path / "x.npz", raw, "fake backend")
    back, backend = ev.load_raw(tmp_path / "x.npz")
    assert backend == "fake backend" and back.models_braille
    assert np.array_equal(back.vehicles[0].mask, raw.vehicles[0].mask) and back.vehicles[0].conf == pytest.approx(0.9)
    assert all(np.array_equal(back.stuff[k], raw.stuff[k]) for k in raw.stuff)
    assert len(back.tiles) == 1 and np.array_equal(back.tiles[0], raw.tiles[0])


def test_parse_overrides():
    assert ev.parse_overrides(["min_vehicle_conf=0.4", "vehicle_edge_px=3"]) == {"min_vehicle_conf": 0.4, "vehicle_edge_px": 3}
    assert isinstance(ev.parse_overrides(["vehicle_edge_px=3"])["vehicle_edge_px"], int)
    with pytest.raises(SystemExit):
        ev.parse_overrides(["no_such_threshold=1"])
    with pytest.raises(SystemExit):
        ev.parse_overrides(["vehicle_length_cm=100"])  # 숫자 기준값이 아님
    assert ev.parse_sweep("min_vehicle_conf=0.3,0.5") == ("min_vehicle_conf", [0.3, 0.5])


def test_holdout_keeps_same_place_together(tmp_path):
    for label in ("approve", "reject"):
        (tmp_path / label).mkdir()
        for place in ("북문", "정문", "동문", "서문", "후문"):
            for i in range(3):
                (tmp_path / label / f"{place}__IMG_{i}.jpg").write_bytes(b"")
    items = ev.collect(tmp_path, holdout=0.4)
    parts = {}
    for _, p, part in items:
        parts.setdefault(ev.group_of(p.name), set()).add(part)
    assert all(len(s) == 1 for s in parts.values())  # 한 장소는 한쪽에만
    assert {"tune", "check"} == {next(iter(s)) for s in parts.values()} or len(parts) < 2
    assert all(part == "tune" for *_, part in ev.collect(tmp_path, holdout=0))


def test_reason_counts_include_zero_codes():
    rows = [{"gt": "reject", "reasons": "ON_ROAD | BORDER_WIDTH"}, {"gt": "approve", "reasons": ""},
            {"gt": "reject", "reasons": "ON_ROAD"}]
    c = ev.reason_counts(rows, list(MESSAGES))
    assert c["reject"]["ON_ROAD"] == 2 and c["reject"]["BORDER_WIDTH"] == 1
    assert c["approve"]["ON_ROAD"] == 0 and set(MESSAGES) <= set(c["approve"])
    assert ev.summarize([{"file": "a", "gt": "approve", "pred": "approve"}], codes=list(MESSAGES))["reason_counts"]["approve"]["NO_VEHICLE"] == 0


def _synth_raw(box):
    img, scene, _ = make_scene([box])
    zeros = np.zeros(scene.shape, bool)
    stuff = {"sidewalk": scene.sidewalk, "road": scene.road, "braille_block": scene.braille, "crosswalk": zeros}
    return img, RawPerception(scene.vehicles, stuff, [], True)


@pytest.fixture
def eval_dir(tmp_path):
    """합성 장면 사진 2장: 가장자리에 붙인 정상 주차(approve) / 보도를 막은 주차(reject)."""
    raws = {}
    for label, box in (("approve", (115, 165, 350, 460, 110)), ("reject", (200, 330, 350, 400, 110))):
        img, raw = _synth_raw(box)
        (tmp_path / label).mkdir()
        p = tmp_path / label / f"합성__{label}.png"
        p.write_bytes(cv2.imencode(".png", img)[1].tobytes())
        raws[p.name] = raw
    return tmp_path, raws


def test_run_and_threshold_change_without_model(eval_dir):
    d, raws = eval_dir
    items = ev.collect(d)
    get_raw = lambda p, img: raws[p.name]  # noqa: E731
    base = {r["gt"]: r for r in ev.run(items, Thresholds(), get_raw)}
    assert base["approve"]["pred"] == "approve" and base["reject"]["pred"] == "reject"
    # 기준값만 바꿔 다시 판정: 통행폭 기준을 3m 로 올리면 정상 주차(남은 폭 약 2.35m)도 거절
    strict = {r["gt"]: r for r in ev.run(items, dataclasses.replace(Thresholds(), min_residual_cm=300), get_raw)}
    assert strict["approve"]["pred"] == "reject" and "NARROW" in strict["approve"]["reasons"]


def test_raw_source_uses_cache_without_loading_model(eval_dir, monkeypatch):
    d, raws = eval_dir
    src = ev.RawSource(d / "_cache")
    for label, p, _ in ev.collect(d):
        ev.save_raw(src._path(p), raws[p.name], "saved backend")
    monkeypatch.setattr("server.analyzer.Analyzer", lambda *a, **k: pytest.fail("모델을 불러오면 안 됨"))
    rows = ev.run(ev.collect(d), Thresholds(), src)
    assert len(rows) == 2 and src.hits == 2 and src.misses == 0 and src.backend == "saved backend"


def test_sweep_reports_each_value(eval_dir, tmp_path):
    d, raws = eval_dir
    src = lambda p, img: raws[p.name]  # noqa: E731
    table = ev.sweep(ev.collect(d), Thresholds(), "min_residual_cm", [120.0, 300.0], src, {}, list(MESSAGES), tmp_path / "sweep.csv")
    assert [t["changed_vs_base"] for t in table] == [0, 1]
    assert table[1]["false_reject"] == 1 and (tmp_path / "sweep.csv").exists()


def test_metric_distributions_by_label():
    rows = [{"gt": "approve", "target_conf": str(c)} for c in (0.9, 0.8, 0.7)] + [{"gt": "reject", "target_conf": "0.6", "truncated": "True"}]
    d = {(x["metric"], x["gt"]): x for x in distributions(rows)}
    assert d[("target_conf", "approve")]["n"] == 3 and d[("target_conf", "approve")]["median"] == 0.8
    assert d[("target_conf", "reject")]["max"] == 0.6
    assert d[("truncated", "reject")]["max"] == 1.0
