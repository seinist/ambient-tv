"""Summarize exported Perfetto core slices without additional on-device frame work."""
import argparse
from bisect import bisect_right
import csv
import json
import math
from pathlib import Path

PREFIX = "AmbientTV:Libretro/"
PATHS = ("retro_run", "netplay", "rewind", "rewind_seek")
MAIN_LABELS = {
    "AmbientGame core run": "retro_run",
    "AmbientGame netplay core": "netplay",
    "AmbientGame rewind core": "rewind",
    "AmbientGame rewind preview core": "rewind_seek",
}


def percentile(values, p):
    return values[max(0, math.ceil(len(values) * p / 100) - 1)]


def summarize(rows, fps, thermal=(), max_thermal_age_ns=5_000_000_000):
    if not math.isfinite(fps) or not 1 <= fps <= 240:
        raise ValueError("Content FPS must be in 1..240")
    if max_thermal_age_ns < 0:
        raise ValueError("Thermal sample age cannot be negative")
    budget = 1_000_000_000 / fps
    samples = sorted((int(row["ts"]), int(row["status"])) for row in thermal)
    if any(status not in range(7) for _, status in samples):
        raise ValueError("Thermal status must use Android's 0..6 scale")
    if len({ts for ts, _ in samples}) != len(samples):
        raise ValueError("Ambiguous duplicate thermal timestamp")
    sample_times = [ts for ts, _ in samples]
    groups = {name: [] for name in PATHS}
    ignored_incomplete = 0
    for row in rows:
        name = row["name"]
        path = MAIN_LABELS.get(name, name[len(PREFIX):] if name.startswith(PREFIX) else None)
        if path not in groups:
            continue
        ts, dur = int(row["ts"]), int(row["dur"])
        if dur < 0:
            ignored_incomplete += 1
            continue
        groups[path].append((ts, dur))

    def stats(items, frame_budget):
        durations = sorted(dur for _, dur in items)
        if not durations:
            return {"samples": 0}
        result = {"samples": len(durations),
                  **{f"p{p}_ms": percentile(durations, p) / 1e6 for p in (50, 95, 99)},
                  "max_ms": durations[-1] / 1e6}
        if frame_budget:
            result["core_budget_overruns"] = sum(dur > budget for dur in durations)
        return result

    report = {}
    for name, items in groups.items():
        frame_budget = name in ("retro_run", "netplay")
        entry = stats(items, frame_budget)
        by_status = {}
        for ts, dur in items:
            index = bisect_right(sample_times, ts) - 1
            label = "unknown"
            if index >= 0 and ts - sample_times[index] <= max_thermal_age_ns:
                label = str(samples[index][1])
            by_status.setdefault(label, []).append((ts, dur))
        entry["by_thermal_status"] = {key: stats(value, frame_budget)
                                      for key, value in sorted(by_status.items())}
        report[name] = entry
    return {"content_fps": fps, "core_budget_ms": budget / 1e6,
            "ignored_incomplete_slices": ignored_incomplete,
            "thermal_samples": len(samples),
            "measurement": "synchronous retro_run only; not end-to-end frame latency",
            "paths": report}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("slices", type=Path, help="Perfetto CSV with name,ts,dur (nanoseconds)")
    parser.add_argument("--fps", type=float, required=True, help="Actual content frame rate")
    parser.add_argument("--thermal", type=Path, help="Optional same-clock CSV with ts,status (Android 0..6)")
    parser.add_argument("--max-thermal-age-ms", type=int, default=5000)
    args = parser.parse_args()
    with args.slices.open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    thermal = []
    if args.thermal:
        with args.thermal.open(newline="") as stream:
            thermal = list(csv.DictReader(stream))
    print(json.dumps(summarize(rows, args.fps, thermal, args.max_thermal_age_ms * 1_000_000), indent=2))


if __name__ == "__main__":
    main()
