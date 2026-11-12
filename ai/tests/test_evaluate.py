"""판정 평가 지표 계산 (모델 없이)."""
from training.evaluate import summarize


def row(file, gt, pred, residual_cm=None):
    return {"file": file, "gt": gt, "pred": pred, "residual_cm": residual_cm}


def test_summary_rates():
    rows = [
        row("approve/a1.jpg", "approve", "approve", 180),
        row("approve/a2.jpg", "approve", "reject"),
        row("approve/a3.jpg", "approve", "retake"),
        row("reject/r1.jpg", "reject", "reject", 90),
        row("reject/r2.jpg", "reject", "approve"),
    ]
    s = summarize(rows)
    assert s["n"] == 5
    assert s["confusion"]["reject"]["approve"] == 1
    assert s["dangerous_pass_rate"] == 0.5
    assert s["false_reject_rate"] == round(1 / 3, 4)
    assert s["retake_rate"] == 0.2
    assert s["accuracy_decided"] == 0.5
    assert s["scale_success_rate"] == 0.4
    assert s["width"] is None


def test_width_error_against_tape_measure():
    rows = [row("approve/a1.jpg", "approve", "approve", 180), row("reject/r1.jpg", "reject", "reject", 90)]
    s = summarize(rows, {"approve/a1.jpg": 170, "reject/r1.jpg": 100, "approve/missing.jpg": 150})
    assert s["width"] == {"n_gt": 3, "n_measured": 2, "mae_cm": 10.0, "bias_cm": 0.0, "max_abs_cm": 10.0}


def test_empty_classes_give_none():
    s = summarize([row("approve/a1.jpg", "approve", "approve")])
    assert s["dangerous_pass_rate"] is None and s["false_reject_rate"] == 0.0
