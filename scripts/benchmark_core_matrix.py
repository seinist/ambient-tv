"""Run all available profiles in rotated, paired rounds on one Android device."""
import argparse
import json
from pathlib import Path
import re
import time
from types import SimpleNamespace
from run_core_benchmark import run
from compare_core_profiles import compare, PUBLIC

def validate_workload(results, warmup=None, measured=None):
    """Reject apparent speedups caused by less rendering or missing audio."""
    def counts(row):
        if warmup is not None and (row.get("warmup_frames"), row.get("measured_frames"), row.get("frames")) != (warmup, measured, warmup+measured):
            raise ValueError("Missing or incorrect measured workload window")
        values = [row[k] for k in ("video_frames", "fresh_frames", "duplicate_frames", "audio_frames")]
        if any(type(n) is not int or n < 0 for n in values):
            raise ValueError("Invalid workload counters")
        video, fresh, duplicate, audio = values
        if not fresh or fresh + duplicate != video or not row["width"] or not row["height"]:
            raise ValueError("Incomplete rendered workload")
        return video, fresh, audio

    baselines = {r["round"]: r for r in results if r["profile"] == "baseline"}
    for row in results:
        baseline = baselines.get(row["round"])
        if baseline is None:
            raise ValueError("Missing paired workload baseline")
        if (row["width"], row["height"], row["hardware"]) != (
                baseline["width"], baseline["height"], baseline["hardware"]):
            raise ValueError("Incomparable render geometry or hardware mode")
        for actual, expected, tolerance in zip(counts(row), counts(baseline), (0.01, 0.01, 0.02)):
            # Allow modest asynchronous boot/scheduling variation, not
            # skipped frames, half-rate rendering or silent audio.
            if expected == 0:
                if actual != 0:
                    raise ValueError("Incomparable workload: unexpected output")
            elif actual == 0 or abs(actual - expected) > max(1, expected * tolerance):
                raise ValueError("Incomparable workload: output count differs")


def matrix(args):
    config=json.loads(args.fixture.read_text())
    root=args.fixture.resolve().parent
    def resolve(value):
        return (root/value).resolve()
    profiles=config["profiles"]
    expected=config.get("required_profiles",list(PUBLIC))
    if set(expected)!=set(profiles) or "baseline" not in profiles:
        raise ValueError("Missing or unexpected requested profile")
    if len(profiles)!=len(expected) or any(not re.fullmatch(r"[a-z0-9-]+",p) for p in profiles):
        raise ValueError("Invalid profile list")
    if args.rounds<3 or args.cooldown<0:
        raise ValueError("At least three paired rounds required")
    warmup=int(config.get("warmup_frames",300))
    measured=int(config.get("frames",1800))
    if warmup<0 or measured<60:
        raise ValueError("Invalid measurement window")
    out=args.output.resolve()
    out.mkdir(parents=True,exist_ok=False)
    identity=None
    runs=[]
    results=[]
    ordered=list(expected)
    # Rotate first position to avoid systematically giving baseline the
    # coolest device. A fixed delay is not a substitute for thermal evidence.
    for i in range(args.rounds):
        order=ordered[i%len(ordered):]+ordered[:i%len(ordered)]
        if (i//len(ordered))%2:
            order.reverse()
        for profile in order:
            record=profiles[profile]
            name=f"round-{i+1:02d}-{profile}"
            target=out/name
            run(SimpleNamespace(
                runner=resolve(config["runner"]),binary=resolve(record["binary"]),
                report=resolve(record["report"]),content=resolve(config["content"]),
                system=resolve(config["system"]),input=resolve(config["input"]),
                options=resolve(config["options"]),output=target,
                runtime=resolve(config["runtime"]) if config.get("runtime") else None,
                rom=config["rom"],serial=args.serial,frames=warmup+measured,warmup_frames=warmup,training=False,renderer=config.get("renderer","auto")))
            meta=json.loads((target/"metadata.json").read_text())
            summary=json.loads((target/"capture-summary.json").read_text())
            build=meta["build"]
            if (build["profile"]!=profile or summary["frames"]!=warmup+measured
                    or summary.get("warmup_frames")!=warmup or summary.get("measured_frames")!=measured
                    or meta.get("warmup_frames")!=warmup or meta.get("measured_frames")!=measured
                    or not summary["video_frames"]):
                raise ValueError("Incorrect profile or incomplete rendered workload")
            common={key:build[key] for key in ("core","abi","commit","ndk")}
            common.update({key:meta[key] for key in ("device_fingerprint","content_sha256","input_sha256","settings_sha256")})
            common.update({"app_commit":meta["runner_sha256"],"renderer":summary["renderer"],
                           "fps":summary["fps"]})
            fixture={"files":meta["fixture_files"],"bios":meta["bios_files"],
                     "runner":meta["runner_sha256"],"runtime":meta["runtime_sha256"],
                     "device_serial":meta["device_serial"]}
            if identity is None:
                identity=common
                initial_fixture=fixture
            if common!=identity or fixture!=initial_fixture:
                raise ValueError("Fixture, device, renderer or build changed between measurements")
            runs.append({**common,"round":i+1,"profile":profile,
                         "binary_sha256":build["binary_sha256"],
                         "build_report":name+"/build.json","slices":name+"/capture.csv",
                         "thermal":name+"/capture-thermal.csv"})
            results.append({"round":i+1,"profile":profile,**summary})
            manifest={**identity,"profiles":ordered,"warmup_frames":warmup,"frames":measured,"runs":runs}
            (out/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
            (out/"workload-results.json").write_text(json.dumps(results,indent=2)+"\n")
            if args.cooldown:
                time.sleep(args.cooldown)
    validate_workload(results,warmup,measured)
    result=compare(manifest,out)
    result["runner_mode"]=True
    result["fixture"]=initial_fixture
    # Save-state hashes are diagnostic, not a universal correctness gate:
    # some cores include nondeterministic metadata or do not serialize.
    hashes={x["state_hash"] for x in results if x["state_valid"]}
    result["state_hash_diagnostic"]={"valid_runs":sum(x["state_valid"] for x in results),
                                     "distinct_hashes":len(hashes)}
    (out/"comparison.json").write_text(json.dumps(result,indent=2)+"\n")

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixture",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--serial")
    p.add_argument("--rounds",type=int,default=3)
    p.add_argument("--cooldown",type=float,default=30)
    matrix(p.parse_args())
