#!/usr/bin/env python3
"""Summarize opt-in MonogramPerf media-read diagnostics from an attached Android device.

Use the same device, APK settings, content, network and workload for each run.
This reports backend service time, not end-to-end video startup or throughput.
No credentials, filenames or chat IDs are collected.
"""
import argparse
import collections
import math
import re
import subprocess
import sys
import json

SAMPLE = re.compile(r"\bmedia-read path=(ram|disk-or-mixed|native) bytes=(\d+) us=(\d+)\s*$")


def summarize(lines):
    buckets = collections.defaultdict(list)
    for line in lines:
        match = SAMPLE.search(line)
        if match:
            path, size, micros = match.groups()
            size, micros = int(size), int(micros)
            if 0 < size <= 1024 * 1024 and micros >= 0:
                buckets[path].append((size, micros))
    result = {}
    for path, samples in sorted(buckets.items()):
        durations = sorted(us for _, us in samples)
        count = len(durations)

        def percentile(p):
            return round(durations[max(0, math.ceil(p * count) - 1)] / 1000, 3)

        result[path] = {
            "windows": count,
            "bytes": sum(size for size, _ in samples),
            "p50_ms": percentile(0.50),
            "p95_ms": percentile(0.95),
            "max_ms": percentile(1),
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("application_id")
    parser.add_argument("--serial", help="adb device serial")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._]+", args.application_id):
        parser.error("invalid Android application ID")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    pid = subprocess.check_output(adb + ["shell", "pidof", args.application_id], text=True).split()
    if len(pid) != 1 or not pid[0].isdigit():
        parser.error("expected a single active application process")
    log = subprocess.check_output(
        adb + ["logcat", "-d", "--pid=" + pid[0], "-s", "MonogramPerf:I", "*:S"],
        text=True,
    )
    result = summarize(log.splitlines())
    if not result:
        print("No samples: build with MONOGRAM_PERF_COUNTERS=1 and exercise playback.", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
