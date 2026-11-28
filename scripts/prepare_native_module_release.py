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

MODULES = ("monogram", "smarttube", "spotify", "mpv", "vlc", "libretrodroid", "projectm", "svp", "shaders", "moonlight")
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


def verify_payload(archive, module, entry):
    names = archive.namelist()
    dex = {n.removeprefix("code/"): archive.read(n) for n in names if n.startswith("code/")}
    native = {n.removeprefix("lib/") for n in names if n.startswith("lib/")}
    if any(not re.fullmatch(r"lib[A-Za-z0-9_+.-]+[.]so", n) or ("+" in n and n != "libc++_shared.so") for n in native):
        raise ValueError("Unexpected native library name")
    roots = set(packages.NATIVE_ROOTS.get(module, ("libmonogram_mtproto.so", "libjnidispatch.so") if module == "monogram" else ("libj2v8.so",)))
    host = entry.get("hostNativeSha256", {})
    if not isinstance(host, dict) or set(host) & native or set(host) - packages.HOST_LIBRARIES.get(module, set()) or any(
            not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value) for value in host.values()):
        raise ValueError("Invalid host native fingerprints")
    if dex:
        if module not in {"monogram", "smarttube", "vlc", "libretrodroid"} or host:
            raise ValueError("Unexpected adapter DEX or host dependency")
        from verify_module_dex import verify_adapter
        verify_adapter(module, dex)
        if module == "vlc":
            roots.add("libc++_shared.so")
        optional = packages.HOST_LIBRARIES[module] if module == "libretrodroid" else set()
        if not roots <= native or native - roots - optional:
            raise ValueError("Unexpected adapter native payload")
    elif module in {"monogram", "smarttube"} or native != roots:
        raise ValueError("Unexpected native payload or missing adapter DEX")

    if (dex or module in {"vlc", "svp", "shaders", "libretrodroid", "moonlight"}) and not re.fullmatch(
            r"[a-f0-9]{64}", entry.get("hostBindingSha256") or ""):
        raise ValueError("Missing exact host ABI fingerprint")
    visited = set()
    def visit(name):
        if name in packages.PLATFORM or name in host or name in visited:
            return
        if name not in native:
            raise ValueError(f"Missing native dependency: {name}")
        visited.add(name)
        for needed in packages.native_dependencies(archive.read("lib/" + name), name):
            visit(needed)
    for name in roots:
        visit(name)
    if visited != native:
        raise ValueError("Unused private native library")
    return native


def verify_notices(data, source, expected_notices):
    """Match nested license texts to the exact corresponding-source inventory."""
    import io
    from module_corresponding_source import safe_archive_name
    if not 0 < len(data) <= 128 * 1024**2:
        raise ValueError("License archive exceeds review bounds")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if not 1 <= len(entries) <= 32768 or sum(info.file_size for info in entries) > 128 * 1024**2:
            raise ValueError("License archive exceeds review bounds")
        seen = set()
        actual = {}
        for info in entries:
            name = info.filename
            if (not safe_archive_name(name) or name.casefold() in seen or info.is_dir()
                    or info.flag_bits & 1 or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    or (info.external_attr >> 16) & 0xf000 not in (0, 0x8000)
                    or not 0 < info.file_size <= 4 * 1024**2):
                raise ValueError("Unsafe license archive member")
            seen.add(name.casefold())
            contents = archive.read(info)
            if name == "corresponding-source.json":
                if info.file_size > 512 * 1024 or json.loads(contents) != source:
                    raise ValueError("Source descriptor is not bound to the signed module payload")
            elif name == "Ambient-TV-LICENSE":
                if contents != (ROOT / "LICENSE").read_bytes():
                    raise ValueError("Ambient TV license differs from reviewed checkout")
            elif re.match(r"^source-[0-9]+/", name):
                actual[name] = (len(contents), hashlib.sha256(contents).hexdigest())
            elif not re.match(r"^reviewed-[0-9]+/", name):
                raise ValueError("Unexpected license archive entry")
        if not {"ambient-tv-license", "corresponding-source.json"} <= seen or actual != expected_notices:
            raise ValueError("License archive differs from corresponding-source notices")
    return hashlib.sha256(data).hexdigest()


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
    source = provenance.get("source")
    if not isinstance(source, dict) or source.get("asset") != "corresponding-source.tar.gz" or source.get("module") != module or source.get("commit") != sha:
        raise ValueError("Missing corresponding-source provenance")
    source_path = root / source["asset"]
    if not source_path.is_file() or source_path.is_symlink() or not 0 < source["size"] <= 1_800_000_000 or source_path.stat().st_size != source["size"] or digest(source_path) != source["sha256"]:
        raise ValueError("Corresponding-source hash or size mismatch")
    from module_corresponding_source import validate_source_archive
    source_notices = {}
    j2v8 = validate_source_archive(source_path, source, source_notices)
    notice_digest = None
    expected = {"manifest.json", "provenance.json", source["asset"]}
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
            packages.validate_package_paths(names, abi)
            if archive.comment or any(info.is_dir() or info.flag_bits & ~0x080e or
                    info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) or
                    (info.external_attr >> 16) & 0xf000 not in (0, 0x8000) or
                    info.external_attr & 0x10 for info in archive.infolist()):
                raise ValueError("Unsupported module ZIP entry type")
            if "licenses/notices.zip" not in names:
                raise ValueError("Missing license notices")
            if archive.getinfo("licenses/notices.zip").file_size > 128 * 1024**2:
                raise ValueError("License archive exceeds review bounds")
            current_digest = verify_notices(archive.read("licenses/notices.zip"), source, source_notices)
            if notice_digest is not None and current_digest != notice_digest:
                raise ValueError("License archive differs between ABI packages")
            notice_digest = current_digest
            native = verify_payload(archive, module, entry)
            if module == "smarttube":
                row = next(item for item in j2v8["libraries"] if item["abi"] == abi)
                if hashlib.sha256(archive.read("lib/libj2v8.so")).hexdigest() != row["apkSha256"]:
                    raise ValueError("Module J2V8 differs from reviewed Full APK provenance")
            dex_mode = any(n.startswith("code/") for n in names)
            if dex_mode and module in {"vlc", "libretrodroid"}:
                from check_vlc_shared_native import CORE, JNI, LIBRETRO, require_exports
                required = {"libvlc.so": CORE, "libvlcjni.so": JNI} if module == "vlc" else {"liblibretrodroid.so": LIBRETRO}
                for name, exports in required.items():
                    require_exports(archive.read("lib/" + name), exports, f"{abi}/{name}")
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
    if not args.source or args.source.name != "corresponding-source.tar.gz":
        raise ValueError("Corresponding source candidate required")
    source = json.loads((notices / "corresponding-source.json").read_text())
    if source.get("commit") != args.sha or source.get("module") != args.module or source.get("size") != args.source.stat().st_size or source.get("sha256") != digest(args.source):
        raise ValueError("Corresponding source candidate does not match notices")
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
            if args.vlc_dex:
                command += ["--vlc-dex-dir", str(ROOT / "app/build/outputs/system-modules/vlc-dex")]
        if args.module == "libretrodroid" and args.libretro_dex:
            command += ["--libretrodroid-dex-dir", str(ROOT / "app/build/outputs/system-modules/libretrodroid-dex")]
        subprocess.run(command, cwd=ROOT, check=True)
    shutil.copyfile(args.source, args.output / args.source.name)
    (args.output / "provenance.json").write_text(json.dumps({"commit": args.sha, "runId": args.run_id,
        "module": args.module, "apks": actual["apks"], "versionCode": actual["versionCode"], "source": source}, sort_keys=True) + "\n")
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
    parser.add_argument("--source", type=Path)
    parser.add_argument("--vlc-dex", action="store_true")
    parser.add_argument("--libretro-dex", action="store_true")
    args = parser.parse_args()
    if args.action == "build":
        if not all((args.dist, args.aapt2, args.apksigner, args.notices, args.source)):
            parser.error("build requires APK directory, Android tools and notices")
        build(args)
    else:
        print(verify(args.output, args.module, args.run_id, args.sha))


if __name__ == "__main__":
    main()
