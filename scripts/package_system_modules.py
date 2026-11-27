#!/usr/bin/env python3
"""Build a deterministic signed-catalog candidate for the first migrated modules.

Signing is separate; this tool never handles a private key. ProjectM inputs
must be the matching, verified Full APKs, not arbitrary binaries.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
SEGMENT = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}\Z")
PROJECTM = ("libprojectM-4.so", "libambientprojectm.so")
SHARED = "libc++_shared.so"
PLATFORM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libjnigraphics.so", "libz.so", "libEGL.so", "libGLESv1_CM.so",
    "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libOpenSLES.so",
    "libaaudio.so", "libmediandk.so", "libnativewindow.so", "libcamera2ndk.so", "libsync.so",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_package(out, module, abi, version, files, shared=None):
    if not files or len(files) > 4096 or sum(map(len, files.values())) > 512 * 1024**2:
        raise ValueError("package expansion exceeds limits")
    folded = set()
    for name in files:
        parts = name.split("/")
        if (len(name) > 512 or not 2 <= len(parts) <= 12 or
                any(not SEGMENT.fullmatch(p) or p.endswith((".", " ")) for p in parts) or
                name.lower() in folded):
            raise ValueError(f"unsafe package path: {name}")
        folded.add(name.lower())
    asset = f"{module}--{abi}--baseline.zip"
    path = out / asset
    with zipfile.ZipFile(path, "x", compression=zipfile.ZIP_DEFLATED, allowZip64=False) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100444 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)
    raw = path.read_bytes()
    if not 0 < len(raw) <= 256 * 1024**2:
        raise ValueError("compressed package exceeds limits")
    return dict(module=module, abi=abi, profile="baseline", version=version,
                hostApi=1, minSdk=29, asset=asset, size=len(raw), sha256=digest(raw),
                hostNativeSha256=shared or {})


def shaders(out, version):
    source = ROOT / "app/src/main/assets/games/slang"
    files = {}
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"symlink in shader source: {path}")
        if path.is_file():
            files["assets/games/slang/" + path.relative_to(source).as_posix()] = path.read_bytes()
    if not any(name.endswith(".slangp") for name in files):
        raise ValueError("no shader presets found")
    return [write_package(out, "shaders", "any", version, files)]


def projectm(out, version, apks, licenses):
    import tempfile
    entries = []
    seen = set()
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError(f"duplicate APK entries: {apk}")
            for abi in ABIS:
                prefix = f"lib/{abi}/"
                if prefix + PROJECTM[0] not in names:
                    continue
                if abi in seen:
                    raise ValueError(f"duplicate APK ABI: {abi}")
                seen.add(abi)
                files = {"lib/" + name: archive.read(prefix + name) for name in PROJECTM}
                shared = {SHARED: digest(archive.read(prefix + SHARED))}
                for name, data in list(files.items()):
                    with tempfile.NamedTemporaryFile() as f:
                        f.write(data)
                        f.flush()
                        dynamic = subprocess.check_output(["readelf", "-d", f.name], text=True)
                        needed = set(re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic))
                        if needed - PLATFORM - set(PROJECTM) - {SHARED}:
                            raise ValueError(f"unresolved native dependencies: {name}: {needed}")
                        sonames = re.findall(r"\(SONAME\).*?\[(.*?)\]", dynamic)
                        if sonames and sonames != [name.split("/")[-1]]:
                            raise ValueError(f"unexpected SONAME: {name}: {sonames}")
                for license in licenses:
                    files["licenses/" + license.name] = license.read_bytes()
                entries.append(write_package(out, "projectm", abi, version, files, shared))
    if seen != set(ABIS):
        raise ValueError("ProjectM publication requires matching Full APKs for all four ABIs")
    return entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", choices=("shaders", "projectm"), required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--license", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.version <= 0 or not re.fullmatch(r"modules-" + args.module + r"-[A-Za-z0-9._-]{1,70}", args.tag):
        parser.error("invalid tag or version")
    args.output.mkdir(parents=True, exist_ok=False)
    if args.module == "shaders":
        entries = shaders(args.output, args.version)
    else:
        if not args.apk or not args.license:
            parser.error("ProjectM requires verified Full APKs and corresponding license notices")
        entries = projectm(args.output, args.version, args.apk, args.license)
    manifest = dict(schema=1, tag=args.tag, modules=entries)
    (args.output / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
