#!/usr/bin/env python3
"""Create and verify native module candidates from one signed Full APK build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

import generate_update_manifest as updates
import package_system_modules as packages

MODULES = ("monogram", "smarttube", "spotify", "mpv", "vlc", "libretrodroid", "projectm", "svp", "shaders")
ROOT = packages.ROOT


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def identity(module, run_id, sha):
    if module not in MODULES or not re.fullmatch(r"[1-9][0-9]{0,17}", run_id) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid candidate identity")
    return f"modules-{module}-v{run_id}", int(run_id)


def validate_elf(data, abi):
    # Match the runtime's basic Android ABI and 16 KiB PT_LOAD rules.
    import struct
    machines = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}
    cls, machine = machines[abi]
    wide = cls == 2
    header = 64 if wide else 52
    if len(data) < header or data[:7] != b"\x7fELF" + bytes((cls, 1, 1)):
        raise ValueError("Wrong ELF class or encoding")
    if struct.unpack_from("<HHI", data, 16) != (3, machine, 1):
        raise ValueError("Wrong ELF type or machine")
    phoff = struct.unpack_from("<Q" if wide else "<I", data, 32 if wide else 28)[0]
    ehsize, entsize, count = struct.unpack_from("<HHH", data, 52 if wide else 40)
    if ehsize != header or entsize != (56 if wide else 32) or not 1 <= count <= 1024 or phoff < header or phoff + entsize * count > len(data):
        raise ValueError("Invalid ELF program headers")
    executable = False
    for i in range(count):
        at = phoff + i * entsize
        if wide:
            typ, flags, offset, addr, _, size, mem, align = struct.unpack_from("<IIQQQQQQ", data, at)
        else:
            typ, offset, addr, _, size, mem, flags, align = struct.unpack_from("<IIIIIIII", data, at)
        if offset + size > len(data) or typ == 3 or (typ == 0x6474e551 and flags & 1):
            raise ValueError("Unsafe ELF segment")
        if typ == 1:
            if mem < size or align < 16384 or align & (align - 1) or offset % align != addr % align or flags & 3 == 3:
                raise ValueError("Unsafe or non-16-KiB ELF load segment")
            executable |= bool(flags & 1 and size)
    if not executable:
        raise ValueError("ELF lacks executable segment")


def verify(root, module, run_id, sha):
    tag, version = identity(module, run_id, sha)
    manifest_path = root / "manifest.json"
    if manifest_path.stat().st_size > 512 * 1024:
        raise ValueError("Oversized catalog")
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schema") != 1 or manifest.get("tag") != tag:
        raise ValueError("Incorrect manifest identity")
    entries = manifest["modules"]
    if len(entries) != 4 or {x["abi"] for x in entries} != set(packages.ABIS):
        raise ValueError("Native candidate requires all four ABIs")
    provenance = json.loads((root / "provenance.json").read_text())
    if provenance.get("commit") != sha or provenance.get("runId") != run_id or provenance.get("module") != module:
        raise ValueError("Candidate provenance mismatch")
    apks = provenance["apks"]
    if len(apks) != 4 or {x["abi"] for x in apks} != set(packages.ABIS) or any(not re.fullmatch(r"[0-9a-f]{64}", x["sha256"]) for x in apks):
        raise ValueError("Missing APK provenance")
    expected = {"manifest.json", "provenance.json"}
    for entry in entries:
        abi = entry["abi"]
        asset = f"{module}--{abi}--baseline.zip"
        if (entry["module"], entry["version"], entry["profile"], entry["hostApi"], entry["minSdk"], entry["asset"]) != (module, version, "baseline", 1, 29, asset):
            raise ValueError("Unexpected module entry")
        expected.add(asset)
        path = root / asset
        if not path.is_file() or path.is_symlink() or not 0 < entry["size"] <= 256 * 1024**2 or path.stat().st_size != entry["size"] or digest(path) != entry["sha256"]:
            raise ValueError("Module asset hash or size mismatch")
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(x.lower() for x in names)) or not 0 < len(names) <= 4096 or sum(x.file_size for x in archive.infolist()) > 512 * 1024**2:
                raise ValueError("Invalid archive bounds")
            if not any(n.startswith("licenses/") for n in names):
                raise ValueError("Missing license notices")
            roots = set(packages.NATIVE_ROOTS.get(module, ("libmonogram_mtproto.so", "libjnidispatch.so") if module == "monogram" else ("libj2v8.so",)))
            native = {n.removeprefix("lib/") for n in names if n.startswith("lib/")}
            if native != roots:
                raise ValueError("Unexpected native payload")
            for name in native:
                validate_elf(archive.read("lib/" + name), abi)
            if archive.testzip() is not None:
                raise ValueError("ZIP CRC mismatch")
    if {p.name for p in root.iterdir()} != expected or any(not p.is_file() or p.is_symlink() for p in root.iterdir()):
        raise ValueError("Unexpected candidate files")
    return tag


def build(args):
    tag, version = identity(args.module, args.run_id, args.sha)
    paths = sorted(args.dist.glob("*.apk"))
    actual = updates.generate(paths, args.aapt2, args.apksigner, packages.ABIS)
    if actual != json.loads((args.dist / "update.json").read_text()):
        raise ValueError("Full APK manifest does not match signed APKs")
    notices = args.notices
    if not notices.is_dir() or notices.is_symlink():
        raise ValueError(f"Missing reviewed module notices: {notices}")
    files = sorted(p for p in notices.rglob("*") if p.is_file())
    if not files or any(p.is_symlink() or p.stat().st_size == 0 for p in files):
        raise ValueError("Missing or invalid module notices")
    # Keep the original notice hierarchy in a single deterministic archive.
    with tempfile.TemporaryDirectory() as temp:
        notice = Path(temp) / "notices.zip"
        with zipfile.ZipFile(notice, "w", compression=zipfile.ZIP_DEFLATED) as out:
            for path in files:
                info = zipfile.ZipInfo(path.relative_to(notices).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                out.writestr(info, path.read_bytes())
        command = [sys.executable, str(ROOT / "scripts/package_system_modules.py"), "--module", args.module,
                   "--tag", tag, "--version", str(version), "--output", str(args.output), "--license", str(notice)]
        for path in paths:
            command += ["--apk", str(path)]
        if args.module in ("monogram", "smarttube"):
            command += [f"--{args.module}-dex-dir", str(ROOT / f"app/build/outputs/system-modules/{args.module}-dex")]
        if args.module == "vlc":
            command += ["--vlc-aar", str(ROOT / "app/libs/libvlc-vulkan.aar")]
        subprocess.run(command, cwd=ROOT, check=True)
    (args.output / "provenance.json").write_text(json.dumps({"commit": args.sha, "runId": args.run_id,
        "module": args.module, "apks": actual["apks"], "versionCode": actual["versionCode"]}, sort_keys=True) + "\n")
    verify(args.output, args.module, args.run_id, args.sha)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "verify"))
    parser.add_argument("--module", choices=MODULES, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dist", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--apksigner", type=Path)
    parser.add_argument("--notices", type=Path)
    args = parser.parse_args()
    if args.action == "build":
        if not all((args.dist, args.aapt2, args.apksigner, args.notices)):
            parser.error("build requires APK directory, Android tools and notices")
        build(args)
    else:
        print(verify(args.output, args.module, args.run_id, args.sha))


if __name__ == "__main__":
    main()
