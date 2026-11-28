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

def fixture_identity(args, content, system, rom):
    def tree(folder):
        return {str(p.relative_to(folder)):sha(p) for p in sorted(folder.rglob("*")) if p.is_file()}
    return {"content_sha256":sha(rom), "input_sha256":sha(args.input),
            "settings_sha256":sha(args.options), "runner_sha256":sha(args.runner),
            "runtime_sha256":sha(args.runtime) if args.runtime else None,
            "fixture_files":tree(content), "bios_files":tree(system)}

def run(args):
    report_text = args.report.read_text()
    build = json.loads(report_text)
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
    identity = fixture_identity(args, content, system, rom)
    # Resolve once: reconnects or a changed default ADB target must not mix devices.
    selection = ["adb", "-s", args.serial] if args.serial else ["adb"]
    serial = subprocess.run([*selection, "get-serialno"], check=True, text=True,
                            capture_output=True).stdout.strip()
    if not serial or serial == "unknown":
        raise ValueError("No unique ADB device")
    adb = ["adb", "-s", serial]
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
        for name, digest in (("runner", identity["runner_sha256"]),
                             ("input.txt", identity["input_sha256"]),
                             ("options.txt", identity["settings_sha256"]),
                             ("libc++_shared.so", identity["runtime_sha256"])):
            if digest is not None and shell("sha256sum", remote+"/"+name, capture=True).split()[0] != digest:
                raise ValueError("Transferred fixture digest mismatch: "+name)
        # Verify the actual transferred ROM, all sidecar tracks and BIOS files,
        # not just the host snapshot. Each argument is individually quoted.
        for folder, files in (("content", identity["fixture_files"]), ("system", identity["bios_files"])):
            for name, digest in files.items():
                if shell("sha256sum", remote+"/"+folder+"/"+name, capture=True).split()[0] != digest:
                    raise ValueError("Transferred content/BIOS digest mismatch: "+name)
        prefix=remote+"/capture"
        shell("env","AMBIENT_BENCHMARK_RENDERER="+getattr(args,"renderer","auto"),"LD_LIBRARY_PATH="+remote,"LLVM_PROFILE_FILE="+remote+"/training-%p-%m.profraw",
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
        if (fixture_identity(args, content, system, rom) != identity or
                sha(args.binary) != build["binary_sha256"] or args.report.read_text() != report_text):
            raise ValueError("Local fixture or build changed during capture")
        metadata={"build":build,"device_fingerprint":fingerprint,"device_serial":serial,
                  "frames":args.frames,**identity}
        (out/"metadata.json").write_text(json.dumps(metadata,indent=2)+"\n")
        (out/"build.json").write_text(report_text)
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
    p.add_argument("--renderer",choices=("auto","gles","vulkan"),default="auto")
    p.add_argument("--training",action="store_true")
    run(p.parse_args())
