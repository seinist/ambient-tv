"""Run a deterministic diagnostic fixture on a connected Android device.

Only user-provided, legally usable content and BIOS files are accepted.
Never install a core into the production app or modify its save data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def run(args):
    build = json.loads(args.report.read_text())
    if sha(args.binary) != build["binary_sha256"]:
        raise ValueError("Core binary does not match verified build report")
    if args.training and build["profile"] != "pgo-generate":
        raise ValueError("Training requires pgo-generate")
    if args.frames < 60 or args.frames > 1000000:
        raise ValueError("Invalid frame count")
    content = args.content.resolve()
    rom = (content / args.rom).resolve()
    if not rom.is_relative_to(content) or not rom.is_file():
        raise ValueError("ROM must be inside fixture content directory")
    system = args.system.resolve()
    for folder in (content, system):
        if not folder.is_dir() or any(p.is_symlink() for p in folder.rglob("*")):
            raise ValueError("Fixture directory must exist and contain no symlinks")
    if len(args.input.read_text().splitlines()) != args.frames:
        raise ValueError("Input must contain exactly one mask per frame")
    adb = ["adb", "-s", args.serial] if args.serial else ["adb"]
    def cmd(*items, capture=False):
        result = subprocess.run([*adb,*map(str,items)],check=True,text=True,capture_output=capture)
        return result.stdout.strip() if capture else None
    def shell(*items,capture=False):
        return cmd("shell",shlex.join(map(str,items)),capture=capture)
    remote = "/data/local/tmp/ambient-core-"+uuid.uuid4().hex
    fingerprint = shell("getprop","ro.build.fingerprint",capture=True)
    actual_abi = shell("getprop","ro.product.cpu.abilist",capture=True).split(",")
    if build["abi"] not in actual_abi:
        raise ValueError("Device cannot run selected ABI")
    out = args.output.resolve()
    out.mkdir(parents=True,exist_ok=False)
    try:
        shell("mkdir","-p",remote+"/save",remote+"/content",remote+"/system")
        for path,name in ((args.runner,"runner"),(args.binary,"core.so"),
                          (args.input,"input.txt"),(args.options,"options.txt")):
            cmd("push",path,remote+"/"+name)
        cmd("push",str(content)+"/.",remote+"/content")
        cmd("push",str(system)+"/.",remote+"/system")
        shell("chmod","700",remote+"/runner")
        actual=shell("sha256sum",remote+"/core.so",capture=True).split()[0]
        if actual!=build["binary_sha256"]:
            raise ValueError("Transferred core digest mismatch")
        if args.runtime:
            cmd("push",args.runtime,remote+"/libc++_shared.so")
        prefix=remote+"/capture"
        shell("env","LD_LIBRARY_PATH="+remote,"LLVM_PROFILE_FILE="+remote+"/training-%p-%m.profraw",
              remote+"/runner",remote+"/core.so",remote+"/content/"+str(rom.relative_to(content)),
              remote+"/system",remote+"/save",remote+"/input.txt",remote+"/options.txt",args.frames,prefix)
        for suffix in (".csv","-thermal.csv","-summary.json"):
            cmd("pull",prefix+suffix,out/("capture"+suffix))
        if args.training:
            profiles=shell("sh","-c","find "+shlex.quote(remote)+" -maxdepth 1 -name 'training-*.profraw' -type f",capture=True)
            names=profiles.splitlines()
            if not names:
                raise ValueError("Instrumented core produced no profile data")
            for name in names:
                if not re.fullmatch(re.escape(remote)+r"/training-[A-Za-z0-9_.-]+\.profraw",name):
                    raise ValueError("Unexpected profile path")
                local=out/Path(name).name
                cmd("pull",name,local)
                if not local.stat().st_size:
                    raise ValueError("Empty profile data")
        metadata={"build":build,"device_fingerprint":fingerprint,"content_sha256":sha(rom),
                  "input_sha256":sha(args.input),"settings_sha256":sha(args.options),
                  "runner_sha256":sha(args.runner),"frames":args.frames,
                  "fixture_files":{str(p.relative_to(content)):sha(p) for p in sorted(content.rglob("*")) if p.is_file()},
                  "bios_files":{str(p.relative_to(system)):sha(p) for p in sorted(system.rglob("*")) if p.is_file()}}
        (out/"metadata.json").write_text(json.dumps(metadata,indent=2)+"\n")
        (out/"build.json").write_text(args.report.read_text())
    finally:
        shell("rm","-rf",remote)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("runner","binary","report","content","system","input","options","output"):
        p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--runtime",type=Path,help="Matching NDK libc++_shared.so, when required")
    p.add_argument("--rom",required=True)
    p.add_argument("--serial")
    p.add_argument("--frames",type=int,required=True)
    p.add_argument("--training",action="store_true")
    run(p.parse_args())
