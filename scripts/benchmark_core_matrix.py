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
        if (row.get("av_hash"), row.get("sample_rate")) != (baseline.get("av_hash"), baseline.get("sample_rate")):
            raise ValueError("Incomparable dynamic A/V configuration")
        for actual, expected, tolerance in zip(counts(row), counts(baseline), (0.01, 0.01, 0.02)):
            # Allow modest asynchronous boot/scheduling variation, not
            # skipped frames, half-rate rendering or silent audio.
            if expected == 0:
                if actual != 0:
                    raise ValueError("Incomparable workload: unexpected output")
            elif actual == 0 or abs(actual - expected) > max(1, expected * tolerance):
                raise ValueError("Incomparable workload: output count differs")


def validate_output_proofs(proofs, require_video=False, state_loaded=False):
    if not proofs or "baseline" not in proofs:
        raise ValueError("Missing correctness baseline")
    baseline=proofs["baseline"]
    for proof in proofs.values():
        for key in ("audio_hash","video_hash"):
            value=proof.get(key)
            if value is None and key=="video_hash" and not require_video:
                continue
            if not isinstance(value,str) or not re.fullmatch(r"[0-9a-f]{16}",value):
                raise ValueError("Missing or invalid output hash: "+key)
        if proof.get("state_loaded") is not state_loaded:
            raise ValueError("Incorrect state interchange execution")
        if (proof["audio_hash"],proof.get("video_hash"))!=(baseline["audio_hash"],baseline.get("video_hash")):
            raise ValueError("Core output differs from baseline")


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
    # Optional strict deterministic correctness replay, outside the timed rounds.
    result["correctness_verified"]=False
    if config.get("verify_output",True) or config.get("verify_states") or config.get("verify_rewind"):
        def correctness(profile, target, state_input=None, export_state=False, verify_rewind=False):
            record=profiles[profile]
            run(SimpleNamespace(
                runner=resolve(config["runner"]),binary=resolve(record["binary"]),
                report=resolve(record["report"]),content=resolve(config["content"]),
                system=resolve(config["system"]),input=resolve(config["input"]),
                options=resolve(config["options"]),output=target,
                runtime=resolve(config["runtime"]) if config.get("runtime") else None,
                rom=config["rom"],serial=initial_fixture["device_serial"],
                frames=warmup+measured,warmup_frames=warmup,training=False,
                renderer=config.get("renderer","auto"),verify_output=True,
                export_state=export_state,state_input=state_input,
                verify_rewind=verify_rewind,rewind_budget_mib=config.get("rewind_budget_mib",256)))
            meta=json.loads((target/"metadata.json").read_text())
            summary=json.loads((target/"capture-summary.json").read_text())
            if (meta["build"]["binary_sha256"]!=result["profiles"][profile]["binary_sha256"] or
                any(meta["build"].get(k)!=identity[k] for k in ("core","abi","commit","ndk")) or
                meta["build"].get("profile")!=profile or
                meta["device_serial"]!=initial_fixture["device_serial"] or
                meta["content_sha256"]!=identity["content_sha256"] or
                meta.get("verify_output") is not True or
                meta["device_fingerprint"]!=identity["device_fingerprint"] or
                meta["fixture_files"]!=initial_fixture["files"] or meta["bios_files"]!=initial_fixture["bios"] or
                meta["runner_sha256"]!=initial_fixture["runner"] or meta["runtime_sha256"]!=initial_fixture["runtime"] or
                meta["input_sha256"]!=identity["input_sha256"] or meta["settings_sha256"]!=identity["settings_sha256"] or
                summary["renderer"]!=identity["renderer"]):
                raise ValueError("Correctness replay identity mismatch")
            validate_workload([{"round":0,"profile":"baseline",**summary}],warmup,measured)
            proof=json.loads((target/"capture-output.json").read_text())
            if verify_rewind and (meta.get("verify_rewind") is not True or
                                  type(proof.get("rewind_checked")) is not int or proof["rewind_checked"]<4 or
                                  type(proof.get("rewind_history_frames")) is not int or proof["rewind_history_frames"]<4):
                raise ValueError("Missing complete rewind proof")
            return proof,summary
        def compare_replay(records, state_loaded=False):
            proofs={p:record[0] for p,record in records.items()}
            validate_output_proofs(proofs,require_video,state_loaded)
            validate_workload([{"round":0,"profile":p,**record[1]} for p,record in records.items()],warmup,measured)
            return proofs
        require_video=bool(config.get("require_video_hash",True))
        interchange=bool(config.get("verify_states"))
        records={p:correctness(p,out/("verify-"+p),export_state=interchange,
                               verify_rewind=bool(config.get("verify_rewind"))) for p in ordered}
        result["output_correctness"]=compare_replay(records)
        if interchange:
            baseline_state=out/"verify-baseline"/"capture-state.bin"
            restored={p:correctness(p,out/("restore-"+p),state_input=baseline_state) for p in ordered}
            result["state_interchange"]=compare_replay(restored,state_loaded=True)
            # Reverse direction: compare the candidate's own restored output
            # with baseline loading the identical candidate-generated state.
            reverse={}
            for p in ordered:
                if p=="baseline":
                    continue
                candidate_state=out/("verify-"+p)/"capture-state.bin"
                own=correctness(p,out/("restore-own-"+p),state_input=candidate_state)
                base=correctness("baseline",out/("restore-base-from-"+p),state_input=candidate_state)
                reverse[p]=compare_replay({"baseline":base,p:own},state_loaded=True)
            result["reverse_state_interchange"]=reverse
        result["correctness_verified"]=True
    (out/"comparison.json").write_text(json.dumps(result,indent=2)+"\n")

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixture",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--serial")
    p.add_argument("--rounds",type=int,default=3)
    p.add_argument("--cooldown",type=float,default=30)
    matrix(p.parse_args())
