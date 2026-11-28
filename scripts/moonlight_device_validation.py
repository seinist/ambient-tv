#!/usr/bin/env python3
"""Compare two redacted Moonlight diagnostic snapshots from the same real stream.

Optional high-speed camera CSV measurements are kept distinct from decoder telemetry.
No device connection or physical HDR/color measurement is inferred from this report.
"""
import argparse
import csv
import json
import math
from pathlib import Path
import re
import statistics

KEY = re.compile(r"(?:^| )([a-z][a-z0-9_]*)=")


def parse_snapshot(text):
    if not text.startswith("Moonlight diagnostics v1\n"):
        raise ValueError("Not a Moonlight diagnostic snapshot")
    fields = {}
    for line in text.splitlines()[1:]:
        matches = list(KEY.finditer(line))
        for index, match in enumerate(matches):
            name = match.group(1)
            if name in fields:
                raise ValueError(f"Duplicate snapshot field: {name}")
            end = matches[index + 1].start() if index + 1 < len(matches) else len(line)
            fields[name] = line[match.end():end].strip()
    return fields


def integer(snapshot, key):
    value = int(snapshot[key])
    if value < 0:
        raise ValueError(f"Negative counter: {key}")
    return value


def compare(before, after, expected_hdr=None, minimum_seconds=60):
    if not math.isfinite(minimum_seconds) or minimum_seconds <= 0:
        raise ValueError("Minimum interval must be positive")
    session = integer(before, "session_generation")
    if not session or session != integer(after, "session_generation"):
        raise ValueError("Snapshots must be from the same running stream generation")
    for field in ("decoder", "mime", "size", "fps", "hdr_signaled", "display_mode"):
        if not before[field] or before[field] != after[field]:
            raise ValueError(f"Stream profile changed during measurement: {field}")
    elapsed = (integer(after, "sample_ns") - integer(before, "sample_ns")) / 1e9
    if elapsed < minimum_seconds:
        raise ValueError(f"Measurement interval too short: {elapsed:.3f}s")
    deltas = {}
    for field in ("received", "missing", "released", "dropped", "presented", "underruns", "dropped_samples"):
        delta = integer(after, field) - integer(before, field)
        if delta < 0:
            raise ValueError(f"Counter reset during measurement: {field}")
        deltas[field] = delta
    if not deltas["received"]:
        raise ValueError("No received video frames during measurement")
    evidence = None
    if expected_hdr:
        def color_evidence(snapshot):
            if expected_hdr == "hdr10":
                if (snapshot.get("hdr_signaled") == "false" or
                        snapshot.get("display_hdr10_capable") == "false" or
                        snapshot.get("decoder_hdr_evidence") == "mismatch"):
                    return False
                if (snapshot.get("hdr_signaled") == "true" and
                        snapshot.get("display_hdr10_capable") == "true" and
                        snapshot.get("decoder_hdr_evidence") == "confirmed"):
                    return True
            else:
                if snapshot.get("hdr_signaled") == "true" or snapshot.get("color_transfer") in {"6", "7"}:
                    return False
                if snapshot.get("hdr_signaled") == "false" and snapshot.get("color_transfer") in {"1", "3"}:
                    return True
            return None
        observations = [color_evidence(snapshot) for snapshot in (before, after)]
        evidence = False if False in observations else True if all(value is True for value in observations) else None
    return {
        "elapsed_seconds": elapsed,
        "profile": {field: after[field] for field in ("decoder", "mime", "size", "fps", "display_mode")},
        "counter_deltas": deltas,
        "released_fps": deltas["released"] / elapsed,
        # No callbacks means no verified presentation measurement, not zero FPS.
        "presented_fps": deltas["presented"] / elapsed if integer(after, "presented") else None,
        "missing_frame_fraction": deltas["missing"] / (deltas["received"] + deltas["missing"]),
        "latest_decoder_telemetry": {
            field: None if after.get(field) in (None, "null") else after[field]
            for field in ("assembly_p95_ms", "release_p95_ms", "presentation_p95_ms",
                          "rtt_ms", "rtt_variance_ms", "input_dispatch_p95_ms", "queue_ms")
        },
        "expected_hdr": expected_hdr,
        "decoder_and_display_capability_evidence": evidence,
        "physical_hdr_and_color_validation": "not established by snapshots",
        "input_to_photon": None,
    }


def camera_latency(csv_text, camera_fps):
    if not math.isfinite(camera_fps) or camera_fps <= 0:
        raise ValueError("Camera FPS must be positive")
    reader = csv.DictReader(csv_text.splitlines())
    if not reader.fieldnames or not {"input_frame", "presented_frame"} <= set(reader.fieldnames):
        raise ValueError("Camera CSV requires input_frame,presented_frame")
    durations = []
    for row in reader:
        start, end = int(row["input_frame"]), int(row["presented_frame"])
        if start < 0 or end < start:
            raise ValueError("Invalid measured frame indices")
        durations.append((end - start) * 1000 / camera_fps)
    if not durations:
        raise ValueError("No measured camera events")
    values = sorted(durations)
    return {
        "method": "externally measured camera frame indices supplied by operator",
        "camera_fps": camera_fps,
        "camera_frame_resolution_ms": 1000 / camera_fps,
        "samples": len(values),
        "minimum_30_samples": len(values) >= 30,
        "median_ms": statistics.median(values),
        "p95_ms": values[math.ceil(.95 * len(values)) - 1],
        "min_ms": values[0],
        "max_ms": values[-1],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--expected-hdr", choices=("sdr", "hdr10"))
    parser.add_argument("--minimum-seconds", type=float, default=60)
    parser.add_argument("--camera-csv", type=Path)
    parser.add_argument("--camera-fps", type=float)
    args = parser.parse_args()
    if (args.camera_csv is None) != (args.camera_fps is None):
        parser.error("--camera-csv and --camera-fps must be supplied together")
    try:
        report = compare(parse_snapshot(args.before.read_text()), parse_snapshot(args.after.read_text()),
                         args.expected_hdr, args.minimum_seconds)
        if args.camera_csv:
            report["input_to_photon"] = camera_latency(args.camera_csv.read_text(), args.camera_fps)
        print(json.dumps(report, indent=2, allow_nan=False))
    except (OSError, KeyError, ValueError) as error:
        parser.exit(1, f"Invalid measurement: {error}\n")


if __name__ == "__main__":
    main()
