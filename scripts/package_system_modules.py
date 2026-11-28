#!/usr/bin/env python3
"""Build a deterministic signed-catalog candidate for the first migrated modules.

Signing is separate; this tool never handles a private key. ProjectM inputs
must be the matching, verified Full APKs, not arbitrary binaries.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
SEGMENT = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}\Z")
NATIVE_ROOTS = {
    "projectm": ("libprojectM-4.so", "libambientprojectm.so"),
    "mpv": ("libmpv.so", "libambientmpv.so"),
    "spotify": ("libspotify_receiver_core.so",),
    "vlc": ("libvlc.so", "libvlcjni.so"),
    "svp": ("libambientsvp.so",),
    "libretrodroid": ("liblibretrodroid.so",),
    "shaders": ("liblibrashader_capi.so",),
}
HOST_LIBRARIES = {
    "projectm": {"libc++_shared.so"},
    "spotify": {"libc++_shared.so"},
    "vlc": {"libc++_shared.so"},
    "svp": set(),
    "libretrodroid": {"libc++_shared.so", "libadrenotools.so", "liboboe.so"},
    "shaders": set(),
    "mpv": {"libc++_shared.so", "libavcodec.so", "libavdevice.so", "libavfilter.so",
            "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so",
            "libpostproc.so", "libass.so", "libplacebo.so", "libfreetype.so",
            "libharfbuzz.so", "libfribidi.so", "libssl.so", "libcrypto.so"},
}
PLATFORM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libjnigraphics.so", "libz.so", "libEGL.so", "libGLESv1_CM.so",
    "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libOpenSLES.so",
    "libaaudio.so", "libmediandk.so", "libnativewindow.so", "libcamera2ndk.so", "libsync.so",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_package(out, module, abi, version, files, shared=None, binding=None):
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
                hostNativeSha256=shared or {}, **({"hostBindingSha256": binding} if binding else {}))


def shader_assets():
    source = ROOT / "app/src/main/assets/games/slang"
    files = {}
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"symlink in shader source: {path}")
        if path.is_file():
            files["assets/games/slang/" + path.relative_to(source).as_posix()] = path.read_bytes()
    if not any(name.endswith(".slangp") for name in files):
        raise ValueError("no shader presets found")
    return files


def shaders(out, version):
    return [write_package(out, "shaders", "any", version, shader_assets())]


def native_dependencies(data, name):
    import tempfile
    with tempfile.NamedTemporaryFile() as f:
        f.write(data)
        f.flush()
        dynamic = subprocess.check_output(["readelf", "-d", f.name], text=True)
    needed = set(re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic))
    sonames = re.findall(r"\(SONAME\).*?\[(.*?)\]", dynamic)
    if sonames and sonames != [name]:
        raise ValueError(f"unexpected SONAME: {name}: {sonames}")
    return needed


def native_module(out, module, version, apks, licenses, binding=None):
    if module in {"vlc", "svp", "shaders", "libretrodroid"} and not re.fullmatch(r"[0-9a-f]{64}", binding or ""):
        raise ValueError(f"{module} requires exact host binding fingerprint")
    entries = []
    seen = set()
    roots = NATIVE_ROOTS[module]
    allowed = HOST_LIBRARIES[module]
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError(f"duplicate APK entries: {apk}")
            for abi in ABIS:
                prefix = f"lib/{abi}/"
                if prefix + roots[0] not in names:
                    continue
                if abi in seen:
                    raise ValueError(f"duplicate APK ABI: {abi}")
                seen.add(abi)
                files = {"lib/" + name: archive.read(prefix + name) for name in roots}
                if module == "shaders":
                    files.update(shader_assets())
                shared = {}
                visited = set()

                def visit(name):
                    if name in PLATFORM or name in visited:
                        return
                    visited.add(name)
                    if name not in roots and name not in allowed:
                        raise ValueError(f"unapproved native dependency: {name}")
                    data = archive.read(prefix + name)
                    if name not in roots:
                        shared[name] = digest(data)
                    for dependency in native_dependencies(data, name):
                        if name not in roots and dependency in roots:
                            raise ValueError(f"host dependency links back into module: {name}")
                        visit(dependency)

                for name in roots:
                    visit(name)
                for license in licenses:
                    path = "licenses/" + license.name
                    if path in files:
                        raise ValueError("duplicate license filename")
                    files[path] = license.read_bytes()
                entries.append(write_package(out, module, abi, version, files, shared, binding))
    if seen != set(ABIS):
        raise ValueError(f"{module} publication requires matching Full APKs for all four ABIs")
    return entries


def vlc_binding(aar):
    with zipfile.ZipFile(aar) as archive:
        classes = archive.read("classes.jar")
        with zipfile.ZipFile(io.BytesIO(classes)) as jar:
            if "org/videolan/libvlc/AmbientNativeLibraryLoader.class" not in jar.namelist():
                raise ValueError("VLC AAR lacks the verified module loader")
        for abi in ABIS:
            for name in NATIVE_ROOTS["vlc"]:
                if f"jni/{abi}/{name}" not in archive.namelist():
                    raise ValueError(f"VLC AAR lacks {abi}/{name}")
    return digest(classes)


def monogram(out, version, aar, licenses):
    """Package Rust only; pair it with the exact generated host binding JAR."""
    entries = []
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate Monogram AAR entries")
        classes = archive.read("classes.jar")
        if not classes:
            raise ValueError("empty Monogram language bindings")
        binding = digest(classes)
        for abi in ABIS:
            name = "libmonogram_mtproto.so"
            data = archive.read(f"jni/{abi}/{name}")
            needed = native_dependencies(data, name)
            if needed - PLATFORM:
                raise ValueError(f"unapproved Monogram native dependencies: {needed - PLATFORM}")
            files = {"lib/" + name: data}
            for license in licenses:
                path = "licenses/" + license.name
                if path in files:
                    raise ValueError("duplicate license filename")
                files[path] = license.read_bytes()
            entries.append(write_package(out, "monogram", abi, version, files, binding=binding))
    return entries


def adapter_dex(out, module, version, dex_dir, apks, licenses):
    """Package isolated adapter and native roots from matching Full APKs."""
    binding = (dex_dir / "host-binding.sha256").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", binding):
        raise ValueError("invalid Monogram adapter host fingerprint")
    dex = {path.name: path.read_bytes() for path in dex_dir.glob("*.dex")}
    expected = {"classes.dex"} | {f"classes{i}.dex" for i in range(2, len(dex) + 1)}
    if not dex or set(dex) != expected or any(
            len(data) < 112 or not data.startswith(b"dex\n") for data in dex.values()):
        raise ValueError("invalid or noncontiguous Monogram adapter DEX")
    roots = {"libmonogram_mtproto.so", "libjnidispatch.so"} if module == "monogram" else {"libj2v8.so"}
    primary = "libmonogram_mtproto.so" if module == "monogram" else "libj2v8.so"
    entries = []
    seen = set()
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError(f"duplicate APK entries: {apk}")
            for abi in ABIS:
                prefix = f"lib/{abi}/"
                if prefix + primary not in names:
                    continue
                if abi in seen:
                    raise ValueError(f"duplicate APK ABI: {abi}")
                seen.add(abi)
                files = {"code/" + name: data for name, data in dex.items()}
                for name in sorted(roots):
                    data = archive.read(prefix + name)
                    needed = native_dependencies(data, name)
                    if needed - PLATFORM - roots:
                        raise ValueError(f"unapproved adapter native dependencies: {name}: {needed}")
                    files["lib/" + name] = data
                for license in licenses:
                    path = "licenses/" + license.name
                    if path in files:
                        raise ValueError("duplicate license filename")
                    files[path] = license.read_bytes()
                entries.append(write_package(out, module, abi, version, files, binding=binding))
    if seen != set(ABIS):
        raise ValueError("Monogram adapter requires matching Full APKs for all four ABIs")
    return entries


def monogram_dex(out, version, dex_dir, apks, licenses):
    return adapter_dex(out, "monogram", version, dex_dir, apks, licenses)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", choices=("shaders", "projectm", "mpv", "spotify", "monogram", "smarttube", "vlc", "svp", "libretrodroid"), required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--license", type=Path, action="append", default=[])
    parser.add_argument("--monogram-aar", type=Path)
    parser.add_argument("--vlc-aar", type=Path)
    parser.add_argument("--monogram-dex-dir", type=Path)
    parser.add_argument("--smarttube-dex-dir", type=Path)
    args = parser.parse_args()
    if args.version <= 0 or not re.fullmatch(r"modules-" + args.module + r"-[A-Za-z0-9._-]{1,70}", args.tag):
        parser.error("invalid tag or version")
    args.output.mkdir(parents=True, exist_ok=False)
    if args.module == "shaders" and not args.apk:
        entries = shaders(args.output, args.version)
    elif args.module == "monogram":
        if not args.license or bool(args.monogram_aar) == bool(args.monogram_dex_dir):
            parser.error("Monogram requires one adapter format and corresponding license notices")
        if args.monogram_dex_dir:
            if not args.apk:
                parser.error("Monogram DEX requires matching Full APKs")
            entries = monogram_dex(args.output, args.version, args.monogram_dex_dir, args.apk, args.license)
        else:
            entries = monogram(args.output, args.version, args.monogram_aar, args.license)
    elif args.module == "smarttube":
        if not args.smarttube_dex_dir or not args.apk or not args.license:
            parser.error("SmartTube requires matching DEX, Full APKs and license notices")
        entries = adapter_dex(args.output, "smarttube", args.version, args.smarttube_dex_dir, args.apk, args.license)
    else:
        if not args.apk or not args.license:
            parser.error("Native modules require verified Full APKs and corresponding license notices")
        if args.module == "vlc" and not args.vlc_aar:
            parser.error("VLC requires the matching patched AAR")
        binding = vlc_binding(args.vlc_aar) if args.module == "vlc" else None
        if args.module == "libretrodroid":
            source = ROOT / "third_party/libretrodroid"
            marker = (source / ".ambient-libretrodroid").read_bytes()
            java = (source / "src/main/java/com/swordfish/libretrodroid/LibretroDroid.java").read_bytes()
            if b"ambient.libretrodroid.native.directory" not in java:
                raise ValueError("unpatched LibretroDroid bindings")
            binding = digest(marker + b"\x00" + java)
        if args.module == "svp":
            binding = digest((ROOT / "native/ambientsvp/include/ambientsvp.h").read_bytes())
        if args.module == "shaders":
            binding = digest((ROOT / "scripts/native_libretro_vulkan/librashader.h").read_bytes())
        entries = native_module(args.output, args.module, args.version, args.apk, args.license, binding)
    manifest = dict(schema=1, tag=args.tag, modules=entries)
    (args.output / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
