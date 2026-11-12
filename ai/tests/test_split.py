"""장소 단위 train/val 분할."""
from training.split_dataset import split_groups


def test_same_place_stays_together():
    stems = [f"{place}__IMG_{i}" for place in ("북문", "정문", "동문", "서문") for i in range(5)]
    for seed in range(5):
        train, val = split_groups(stems, 0.25, seed)
        assert train and val and not (train & val) and train | val == set(stems)
        for place in ("북문", "정문", "동문", "서문"):
            sides = {s in val for s in stems if s.startswith(place)}
            assert len(sides) == 1, (seed, place)


def test_ungrouped_photos_split_individually():
    stems = [f"IMG_{i}" for i in range(20)]
    train, val = split_groups(stems, 0.15, 0)
    assert len(val) == 3 and len(train) == 17


def test_single_group_keeps_train_non_empty():
    stems = [f"북문__IMG_{i}" for i in range(10)]
    train, val = split_groups(stems, 0.2, 0)
    assert len(train) == 10 and not val
