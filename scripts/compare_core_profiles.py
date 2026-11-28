"""Compare repeated on-device Perfetto captures of Libretro build profiles.

The manifest fixes the same game, input trace, app build, device, renderer and FPS.
Each run supplies its own build report and thermal CSV. Incomparable or incomplete
runs fail rather than being silently averaged together.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
from analyze_libretro_trace import MAIN_LABELS, PREFIX, percentile

PUBLIC = ("baseline", "optimized", "optimized-a57")

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def rows(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))

def compare(manifest, directory):
    required = ("core", "abi", "commit", "ndk", "device_fingerprint", "app_commit",
                "content_sha256", "input_sha256", "settings_sha256", "renderer", "fps")
    if any(not manifest.get(key) for key in required):
        raise ValueError("Missing workload/build/device identity")
    for key in ("content_sha256", "input_sha256", "settings_sha256"):
        if len(manifest[key]) != 64 or any(c not in "0123456789abcdef" for c in manifest[key]):
            raise ValueError(f"Invalid digest: {key}")
    fps = float(manifest["fps"])
    if not math.isfinite(fps) or not 1 <= fps <= 240:
        raise ValueError("Invalid FPS")
    warmup = int(manifest.get("warmup_frames", 300))
    count = int(manifest.get("frames", 1800))
    if warmup < 0 or count < 60:
        raise ValueError("Invalid measurement window")
    profiles = tuple(manifest.get("profiles", PUBLIC))
    if len(set(profiles)) != len(profiles) or not profiles or "baseline" not in profiles:
        raise ValueError("Unique profiles including baseline are required")
    repetitions = {}
    kinds = set()
    binaries = {}
    for run in manifest["runs"]:
        profile = run["profile"]
        if profile not in profiles:
            raise ValueError("Unexpected profile")
        # Every capture attests its identity, not merely a global label.
        for key in required:
            if run.get(key) != manifest[key]:
                raise ValueError(f"Incomparable run: {key}")
        report_path = directory / run["build_report"]
        build = json.loads(report_path.read_text())
        for key in ("core", "abi", "commit", "ndk"):
            if build.get(key) != manifest[key]:
                raise ValueError(f"Build identity mismatch: {key}")
        if build.get("profile") != profile or build.get("binary_sha256") != run.get("binary_sha256"):
            raise ValueError("Build profile or binary digest mismatch")
        if not run.get("binary_sha256") or len(run["binary_sha256"]) != 64 or any(c not in "0123456789abcdef" for c in run["binary_sha256"]):
            raise ValueError("Missing binary digest")
        previous = binaries.setdefault(profile, run["binary_sha256"])
        if previous != run["binary_sha256"]:
            raise ValueError("Different binaries within a profile")
        slices = rows(directory / run["slices"])
        matching = []
        for row in slices:
            name = row["name"]
            if name == "AmbientGame core run":
                kinds.add("main")
            elif name == PREFIX + "retro_run":
                kinds.add("direct")
            else:
                continue
            start, duration = int(row["ts"]), int(row["dur"])
            if duration >= 0:
                matching.append((start, duration))
        matching.sort()
        measured = matching[warmup:warmup+count]
        if len(measured) != count:
            raise ValueError("Not enough complete frame samples")
        if any(a[0]+a[1] > b[0] for a, b in zip(measured, measured[1:])):
            raise ValueError("Overlapping core trace slices")
        thermals = sorted((int(row["ts"]), int(row["status"])) for row in rows(directory / run["thermal"]))
        # Demand matching, recent, non-throttled thermal readings throughout.
        cursor = -1
        for start, duration in measured:
            while cursor+1 < len(thermals) and thermals[cursor+1][0] <= start:
                cursor += 1
            if (cursor < 0 or start - thermals[cursor][0] > 5_000_000_000 or
                    thermals[cursor][1] not in (0, 1) or
                    any(status not in (0, 1) for ts, status in thermals[cursor+1:]
                        if ts <= start + duration)):
                raise ValueError("Missing/stale or throttled thermal evidence")
        durations = sorted(d for _, d in measured)
        key = (int(run["round"]), profile)
        if key in repetitions:
            raise ValueError("Duplicate profile/round")
        repetitions[key] = {"p50_ms": percentile(durations, 50)/1e6,
                            "p95_ms": percentile(durations, 95)/1e6,
                            "p99_ms": percentile(durations, 99)/1e6,
                            "overruns": sum(d > 1e9/fps for d in durations),
                            "trace_sha256": sha(directory/run["slices"]),
                            "thermal_sha256": sha(directory/run["thermal"]),
                            "build_report_sha256": sha(report_path)}
    rounds = sorted({round_id for round_id, _ in repetitions})
    if len(kinds) != 1 or len(rounds) < 3 or set(repetitions) != {(n,p) for n in rounds for p in profiles}:
        raise ValueError("Need at least three complete, comparable paired rounds")
    result = {}
    for profile in profiles:
        stats = [repetitions[n, profile] for n in rounds]
        med = {key: statistics.median(run[key] for run in stats)
               for key in ("p50_ms", "p95_ms", "p99_ms", "overruns")}
        for key in ("p50_ms", "p95_ms", "p99_ms"):
            baseline = [repetitions[n, "baseline"][key] for n in rounds]
            if any(value <= 0 for value in baseline):
                raise ValueError("Zero-duration baseline")
            med[key + "_delta_pct"] = statistics.median(
                (repetitions[n, profile][key]/repetitions[n,"baseline"][key]-1)*100 for n in rounds)
        result[profile] = {"binary_sha256": binaries[profile], "median": med, "runs": stats}
    return {"identity": {key: manifest[key] for key in required}, "trace_kind": next(iter(kinds)),
            "warmup_frames": warmup, "measured_frames": count, "rounds": rounds,
            "profiles": result, "measurement": "Core-call time only; not end-to-end input latency"}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = json.dumps(compare(json.loads(args.manifest.read_text()), args.manifest.parent), indent=2)
    if args.output:
        args.output.write_text(report+"\n")
    else:
        print(report)
