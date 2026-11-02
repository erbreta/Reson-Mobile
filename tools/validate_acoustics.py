"""Compare actual Kotlin analysis output with Praat using local synthetic WAVs.

Run --prepare DIR, the opt-in AcousticReferenceTest with RESON_ANALYSIS_FIXTURES=DIR,
then --compare DIR. Requires Python numpy, scipy, and praat-parselmouth.
Additional WAVs must be in the supported recorder format; no audio is uploaded.
"""
import argparse
import csv
import json
from pathlib import Path
import wave

import numpy as np
import parselmouth
from parselmouth.praat import call
from scipy.signal import lfilter

RATE = 48000


def prepare(directory):
    directory.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(20261031)
    t = np.arange(RATE) / RATE

    def vowel(f0, five=False):
        excitation = np.zeros(RATE)
        excitation[np.arange(0, RATE, RATE / f0).astype(int)] = 1
        result = excitation
        resonances = [(500, 70), (1500, 90), (2500, 120), (3500, 150)]
        if five:
            resonances.append((4500, 200))
        for frequency, bandwidth in resonances:
            radius = np.exp(-np.pi * bandwidth / RATE)
            result = lfilter([1], [1, -2 * radius * np.cos(2 * np.pi * frequency / RATE), radius**2], result)
        return result / np.max(np.abs(result)) * 0.5

    low = vowel(120)
    signals = {
        "vowel120": low, "vowel220": vowel(220),
        "five_formant_vowel": vowel(120, five=True),
        "long_vowel": np.tile(low, 180),
        "tone80": 0.5 * np.sin(2 * np.pi * 80 * t),
        "tone440": 0.5 * np.sin(2 * np.pi * 440 * t),
        "silence": np.zeros(RATE), "unvoiced_noise": rng.normal(0, 0.08, RATE),
        "noisy_vowel": low + rng.normal(0, 0.02, RATE),
        "dynamic_vowel": low * (0.15 + 0.85 * (0.5 + 0.5 * np.sin(2 * np.pi * 2 * t))),
        "voiced_transition": np.concatenate([np.zeros(RATE // 4), low[:RATE // 2], rng.normal(0, 0.08, RATE // 4)]),
        "short": low[:48],
    }
    for name, samples in signals.items():
        with wave.open(str(directory / (name + ".wav")), "wb") as output:
            output.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
            output.writeframes(np.round(np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())


def compare(directory):
    results = {}
    for file in sorted(directory.glob("*.wav")):
        with (directory / (file.stem + ".kotlin.csv")).open() as stream:
            rows = list(csv.DictReader(stream))
        sound = parselmouth.Sound(str(file))
        result = {"seconds": sound.duration}
        if sound.duration < 0.05:
            result["kotlin_frames"] = len(rows)
            result["praat"] = "Too short for default pitch/formant/intensity windows"
            results[file.stem] = result
            continue
        pitch = sound.to_pitch_ac(time_step=0.01, pitch_floor=75, pitch_ceiling=500)
        pitch_rows = [r for r in rows if r["kind"] == "pitch"]
        comparisons = [(r, pitch.get_value_at_time(float(r["time"]))) for r in pitch_rows
                       if 0.08 <= float(r["time"]) <= sound.duration - 0.08]
        both = [(float(r["v1"]), p) for r, p in comparisons if r["v1"] and np.isfinite(p)]
        result["pitch"] = {
            "kotlin_voiced": sum(bool(r["v1"]) for r in pitch_rows), "kotlin_frames": len(pitch_rows),
            "voicing_agreement": float(np.mean([bool(r["v1"]) == np.isfinite(p) for r, p in comparisons])),
            "median_abs_hz": float(np.median([abs(k - p) for k, p in both])) if both else None,
            "median_relative_error_percent": float(np.median([abs(k - p) / p * 100 for k, p in both])) if both else None,
            "octave_error_proportion": float(np.mean([abs(np.log2(k / p)) > 0.5 for k, p in both])) if both else None,
        }
        if "vowel" in file.stem:
            result["formants"] = {}
            for ceiling in (5500, 5000):
                formant = sound.to_formant_burg(time_step=0.00625, max_number_of_formants=5,
                    maximum_formant=ceiling, window_length=0.025, pre_emphasis_from=50)
                frows = [r for r in rows if r["kind"] == f"formant{ceiling}" and 0.15 <= float(r["time"]) <= 0.85]
                summary = []
                for i in range(1, 5):
                    pairs = [(float(r[f"v{i}"]), formant.get_value_at_time(i, float(r["time"]))) for r in frows if r[f"v{i}"]]
                    pairs = [(k, p) for k, p in pairs if np.isfinite(p)]
                    summary.append({"paired_frames": len(pairs), "total_frames": len(frows),
                        "kotlin_median_hz": float(np.median([k for k, _ in pairs])) if pairs else None,
                        "praat_median_hz": float(np.median([p for _, p in pairs])) if pairs else None,
                        "median_abs_hz": float(np.median([abs(k - p) for k, p in pairs])) if pairs else None})
                result["formants"][str(ceiling)] = summary
        intensity = sound.to_intensity(minimum_pitch=75, time_step=0.01, subtract_mean=True)
        pairs = [(float(r["v1"]), intensity.get_value(float(r["time"]))) for r in rows
                 if r["kind"] == "intensity" and r["v1"] and 0.08 <= float(r["time"]) <= sound.duration - 0.08]
        pairs = [(k, p) for k, p in pairs if np.isfinite(p)]
        if pairs:
            k, p = np.array(pairs).T
            offset = float(np.median(p - k))
            result["intensity"] = {"median_praat_minus_dbfs": offset,
                "median_shape_residual_db": float(np.median(abs(p - k - offset))),
                "correlation": float(np.corrcoef(k, p)[0, 1]) if np.std(k) > 0.1 else None}
        point_process = call([sound, pitch], "To PointProcess (cc)")
        reference_pulses = np.array([call(point_process, "Get time from index", i + 1)
                                     for i in range(call(point_process, "Get number of points"))])
        pulses = [float(r["time"]) for r in rows if r["kind"] == "pulse"]
        offsets = [float(np.min(abs(reference_pulses - t))) * 1000 for t in pulses if reference_pulses.size]
        result["pulses"] = {"kotlin_count": len(pulses), "praat_count": len(reference_pulses),
            "median_nearest_praat_ms": float(np.median(offsets)) if offsets else None,
            "p95_nearest_praat_ms": float(np.percentile(offsets, 95)) if offsets else None,
            "outside_praat_voicing": sum(not np.isfinite(pitch.get_value_at_time(t)) for t in pulses)}
        results[file.stem] = result
    report = {"parselmouth": parselmouth.__version__, "fixtures": "Deterministic synthetic signals; no human speech corpus",
        "criteria": {"clean_pitch": "median relative difference <2%, octave errors <1%, voicing agreement >95% away from edges",
            "stable_formants": "median per-formant difference <100 Hz or 10% of reference, whichever is larger",
            "dynamic_intensity": "correlation >0.95 after accounting for reference offset; compare residuals",
            "pulses": "no silence/noise markers; nearest Praat timing median <15% of period on clean periodic signals"},
        "results": results}
    (directory / "comparison.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--prepare", type=Path)
    group.add_argument("--compare", type=Path)
    args = parser.parse_args()
    if args.prepare:
        prepare(args.prepare)
    else:
        compare(args.compare)
