#!/usr/bin/env python3
"""Validate actual signed-candidate build artifacts against local Android fixtures.
No content, BIOS or save state is published. A failure is never treated as a skip.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
from types import SimpleNamespace
from core_build_policy import validate_catalog
from benchmark_core_matrix import matrix
from run_core_benchmark import sha

ROOT=Path(__file__).resolve().parents[1]
JITS={"citra2018":"citra-arm64-jit","yabause":"yabause-arm64-jit"}


def selection(catalog, abi, cores="", include_jit=False):
    abis, entries, ids=validate_catalog(catalog)
    if abi not in abis:
        raise ValueError("Unknown validation ABI")
    eligible={c["core"]:c for c in entries if abi in c.get("abis",abis)}
    requested=cores.split(",") if cores else sorted(eligible)
    if not requested or len(set(requested))!=len(requested) or not set(requested)<=eligible.keys():
        raise ValueError("Unknown, duplicate or unsupported validation core")
    result={}
    for core in requested:
        profiles=["baseline","optimized"]
        if abi=="arm64-v8a":
            profiles.append("optimized-a57")
            if include_jit and core in JITS:
                profiles.append(JITS[core])
        result[core]=profiles
    return result


def artifact_name(core, abi, profile):
    return ("core" if profile=="baseline" else "candidate-"+profile)+"-"+core+"-"+abi


def fixture_for(args, core, profiles, entry):
    directory=(args.fixture_root/core).resolve()
    if directory.parent!=args.fixture_root.resolve() or not directory.is_dir():
        raise ValueError("Missing or unsafe local core fixture")
    config=json.loads((directory/"fixture.json").read_text())
    # Do not let fixture JSON replace trusted candidate paths or the runner.
    out={key:config[key] for key in ("rom","frames","warmup_frames","renderer",
          "verify_states","verify_rewind","rewind_budget_mib") if key in config}
    for key in ("content","system","input","options"):
        path=(directory/config[key]).resolve()
        if not path.is_relative_to(directory) or not path.exists():
            raise ValueError("Fixture path escapes the core's local directory")
        out[key]=str(path)
    for check in ("verify_states","verify_rewind"):
        if type(config.get(check)) is not bool:
            raise ValueError("Fixture must explicitly declare "+check)
    if not config["verify_states"] or not config["verify_rewind"]:
        reason=config.get("state_validation_limitation")
        if not isinstance(reason,str) or not reason.strip():
            raise ValueError("Unsupported state/rewind validation needs an explicit reason")
    out.update(verify_output=True,require_video_hash=True,runner=str(args.runner.resolve()),
               required_profiles=profiles,profiles={})
    if args.runtime:
        out["runtime"]=str(args.runtime.resolve())
    for profile in profiles:
        folder=args.artifacts/artifact_name(core,args.abi,profile)
        binary=folder/(core+"_libretro_android.so")
        report=folder/"build.json"
        identity=json.loads(report.read_text())
        if any(identity.get(key)!=value for key,value in
               dict(core=core,abi=args.abi,profile=profile,commit=entry["commit"],ndk=args.ndk).items()):
            raise ValueError("Unexpected core build identity")
        if sha(binary)!=identity.get("binary_sha256"):
            raise ValueError("Candidate binary digest mismatch")
        out["profiles"][profile]={"binary":str(binary.resolve()),"report":str(report.resolve())}
    return out,config.get("state_validation_limitation")


def validate(args):
    catalog=json.loads(args.catalog.read_text())
    targets=selection(catalog,args.abi,args.cores,args.include_jit)
    entries={c["core"]:c for c in catalog["cores"]}
    args.output.mkdir(parents=True,exist_ok=False)
    adb=["adb"]+(["-s",args.serial] if args.serial else [])
    serial=subprocess.check_output([*adb,"get-serialno"],text=True).strip()
    if not serial or serial=="unknown":
        raise ValueError("No unique connected Android device")
    result={"abi":args.abi,"requested_cores":list(targets),"results":{}}
    # Validate independently so a broken core cannot hide the remaining results.
    for core,profiles in targets.items():
        try:
            config,limitation=fixture_for(args,core,profiles,entries[core])
            # Generated fixtures contain trusted local paths; keep them separate
            # from uploaded reports. They contain no actual content bytes.
            fixture=args.output/(core+"-fixture.json")
            fixture.write_text(json.dumps(config))
            matrix(SimpleNamespace(fixture=fixture,output=args.output/core,
                                   serial=serial,rounds=args.rounds,cooldown=args.cooldown))
            report=json.loads((args.output/core/"comparison.json").read_text())
            if report.get("correctness_verified") is not True:
                raise ValueError("No functional output proof")
            if config["verify_states"] and ("state_interchange" not in report or "reverse_state_interchange" not in report):
                raise ValueError("Missing bidirectional state proof")
            if config["verify_rewind"] and any(p.get("rewind_checked",0)<4
                    for p in report["output_correctness"].values()):
                raise ValueError("Missing rewind proof")
            result["results"][core]={"status":"pass","profiles":profiles,
                "state_validation": "verified" if config["verify_states"] and config["verify_rewind"] else "limited",
                "state_validation_limitation":limitation,"comparison":report}
        except Exception as error:
            print(f"{core}: {type(error).__name__}: {error}",flush=True)
            result["results"][core]={"status":"failed","profiles":profiles,"error":type(error).__name__}
        finally:
            # Generated local paths and captured state bytes are not retained
            # in the publishable summary.
            (args.output/(core+"-fixture.json")).unlink(missing_ok=True)
            (args.output/"summary.json").write_text(json.dumps(result,indent=2)+"\n")
    result["complete"]=all(r["status"]=="pass" for r in result["results"].values())
    result["full_state_coverage"]=result["complete"] and all(r.get("state_validation")=="verified"
                                                            for r in result["results"].values())
    (args.output/"summary.json").write_text(json.dumps(result,indent=2)+"\n")
    return 0 if result["complete"] else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog",type=Path,default=ROOT/"cores/cores.json")
    parser.add_argument("--abi",required=True)
    parser.add_argument("--cores",default="")
    parser.add_argument("--include-jit",action="store_true")
    parser.add_argument("--list-artifacts",action="store_true")
    parser.add_argument("--ndk",default="30.0.16248370")
    for key in ("fixture-root","artifacts","runner","runtime","output"):
        parser.add_argument("--"+key,type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--rounds",type=int,default=3)
    parser.add_argument("--cooldown",type=float,default=30)
    args=parser.parse_args()
    if args.list_artifacts:
        for core,profiles in selection(json.loads(args.catalog.read_text()),args.abi,args.cores,args.include_jit).items():
            for profile in profiles:
                print(artifact_name(core,args.abi,profile))
        return 0
    if not all((args.fixture_root,args.artifacts,args.runner,args.output)):
        parser.error("Validation requires fixtures, artifacts, runner and output")
    if args.rounds<3 or args.cooldown<0:
        parser.error("Invalid paired-run settings")
    return validate(args)

if __name__=="__main__":
    raise SystemExit(main())
