#!/usr/bin/env python3
"""Export a standalone pre-R8 Monogram DEX using only explicitly owned classes."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import struct
import tempfile
import zipfile

PREFIXES = ("org/monogram/mtproto/", "uniffi/monogram_mtproto/", "com/sun/jna/", "org/rustls/")
CLASSES = tuple("com/ambient/tv/data/telegram/" + name for name in (
    "MonogramModuleAdapter", "MonogramNativeBackend", "MonogramNativeBackendKt",
    "AmbientMonogramJni", "AmbientMonogramJniFailure",
))
ENTRY = CLASSES[0] + ".class"
REQUIRED = (ENTRY, CLASSES[1] + ".class", CLASSES[3] + ".class", "com/sun/jna/Native.class",
            "org/monogram/mtproto/MtprotoNativeLoader.class")


# Leaf libraries used by SmartTube, not shared with the host's public API.
SMARTTUBE_PRIVATE_PREFIXES = ("com/jayway/jsonpath/", "net/minidev/", "com/grack/nanojson/", "com/florianingerl/util/regex/", "com/jakewharton/disklrucache/")
SMARTTUBE_PREFIXES = ("com/liskovsoft/", "com/eclipsesource/v8/") + SMARTTUBE_PRIVATE_PREFIXES
SMARTTUBE_ENTRY = "com/ambient/tv/youtube/SmartTubeModuleAdapter"
SMARTTUBE_REQUIRED = (
    SMARTTUBE_ENTRY + ".class",
    "com/liskovsoft/youtubeapi/service/YouTubeServiceManager.class",
    "com/liskovsoft/sharedutils/prefs/GlobalPreferences.class",
    "com/liskovsoft/youtubeapi/lounge/LoungeService.class",
    "com/eclipsesource/v8/V8.class",
    "com/jayway/jsonpath/JsonPath.class",
    "net/minidev/json/JSONValue.class",
    "com/grack/nanojson/JsonParser.class",
    "com/florianingerl/util/regex/Pattern.class",
    "com/jakewharton/disklrucache/DiskLruCache.class",
)


VLC_PREFIX = "org/videolan/libvlc/"
VLC_CLASSES = tuple("com/ambient/tv/ui/player/backend/" + name for name in (
    "VlcModuleAdapter", "VlcNativeEngine", "VlcTrackAccess",
))
VLC_REQUIRED = tuple(name + ".class" for name in VLC_CLASSES) + tuple(
    VLC_PREFIX + name + ".class" for name in (
        "LibVLC", "MediaPlayer", "Media", "AmbientNativeLibraryLoader",
    )
)


def owns_vlc(path):
    if not path.endswith(".class"):
        return False
    name = path[:-6]
    simple = name.rsplit("/", 1)[-1]
    if simple == "R" or simple.startswith("R$"):
        return False
    return name.startswith(VLC_PREFIX) or any(
        name == owned or name.startswith(owned + "$") for owned in VLC_CLASSES
    )


LIBRETRO_PREFIX = "com/swordfish/libretrodroid/"
LIBRETRO_CLASSES = tuple("com/ambient/tv/ui/games/" + name for name in (
    "LibretroModuleAdapter", "BundledRetroCore", "BundledRetroView",
    "BundledRetroContentAdapterKt", "BundledRetroShaderAdapterKt",
))
LIBRETRO_REQUIRED = tuple(name + ".class" for name in LIBRETRO_CLASSES) + tuple(
    LIBRETRO_PREFIX + name + ".class" for name in ("LibretroDroid", "GLRetroView", "GLRetroViewData")
)


def owns_libretrodroid(path):
    if not path.endswith(".class"):
        return False
    name = path[:-6]
    simple = name.rsplit("/", 1)[-1]
    if simple == "R" or simple.startswith("R$"):
        return False
    return name.startswith(LIBRETRO_PREFIX) or any(
        name == owned or name.startswith(owned + "$") for owned in LIBRETRO_CLASSES
    )


def boundary_bytes(name, data):
    """The ownership policy names the forbidden types as string constants.

    Only that exact policy class may contain those literals. Its actual JVM
    class, member and method-type descriptors remain subject to the same
    boundary checks, even when a UTF-8 entry is shared with a string literal.
    """
    if name != "com/ambient/tv/modules/ModuleDexOwnership.class":
        return data
    # Parse constant pool rather than blanket-allowlisting the policy class.
    if len(data) < 10:
        raise ValueError("Invalid ownership policy class")
    count = struct.unpack_from(">H", data, 8)[0]
    offset, index = 10, 1
    strings, structural, utf8 = set(), set(), {}
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
              11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    try:
        while index < count:
            tag = data[offset]
            offset += 1
            if tag == 1:
                length = struct.unpack_from(">H", data, offset)[0]
                offset += 2
                if offset + length > len(data):
                    raise ValueError("Truncated ownership policy constant")
                utf8[index] = data[offset:offset + length]
                offset += length
            elif tag in widths:
                if offset + widths[tag] > len(data):
                    raise ValueError("Truncated ownership policy constant")
                if tag == 8:
                    strings.add(struct.unpack_from(">H", data, offset)[0])
                elif tag in (7, 16):
                    structural.add(struct.unpack_from(">H", data, offset)[0])
                elif tag == 12:
                    structural.add(struct.unpack_from(">H", data, offset + 2)[0])
                offset += widths[tag]
                if tag in (5, 6):
                    index += 1
            else:
                raise ValueError("Unknown ownership policy constant")
            index += 1
    except (IndexError, struct.error) as error:
        raise ValueError("Invalid ownership policy class") from error
    if not strings | structural <= utf8.keys():
        raise ValueError("Invalid ownership policy string reference")
    # Field/method declarations and signatures can reference UTF-8 directly
    # without a CONSTANT_Class/NameAndType. Keep descriptor-shaped entries.
    return b"\xca\xfe\xba\xbe" + b"\x00".join(
        value for key, value in utf8.items()
        if key not in strings or key in structural or re.search(rb"L[A-Za-z0-9_/$-]+[;<]", value))


def validate_libretro_boundary(name, data):
    if not data.startswith(b"\xca\xfe\xba\xbe"):
        return
    data = boundary_bytes(name, data)
    matches = re.findall(rb"com/swordfish/libretrodroid/[A-Za-z0-9_/$-]+", data)
    if any(owns_libretrodroid(match.decode("ascii") + ".class") for match in matches):
        raise ValueError(f"host class references private LibretroDroid type: {name}")
    if any(owned.encode() in data for owned in LIBRETRO_CLASSES):
        raise ValueError(f"host class references private LibretroDroid adapter: {name}")


def ownership(module):
    return {"monogram": (owns, REQUIRED), "smarttube": (owns_smarttube, SMARTTUBE_REQUIRED),
            "vlc": (owns_vlc, VLC_REQUIRED),
            "libretrodroid": (owns_libretrodroid, LIBRETRO_REQUIRED)}[module]


def validate_vlc_boundary(name, data):
    if not data.startswith(b"\xca\xfe\xba\xbe"):
        return
    data = boundary_bytes(name, data)
    matches = re.findall(rb"org/videolan/libvlc/[A-Za-z0-9_/$-]+", data)
    if any(owns_vlc(match.decode("ascii") + ".class") for match in matches):
        raise ValueError(f"host class references private VLC type: {name}")
    if any(owned.encode() in data for owned in VLC_CLASSES):
        raise ValueError(f"host class references private VLC adapter: {name}")


def owns_smarttube(path):
    if not path.endswith(".class"):
        return False
    name = path[:-6]
    simple = name.rsplit("/", 1)[-1]
    if simple == "R" or simple.startswith("R$"):
        return False
    return (name.startswith(SMARTTUBE_PREFIXES) or name == SMARTTUBE_ENTRY or
            name.startswith(SMARTTUBE_ENTRY + "$"))


def owns(path):
    if not path.endswith(".class"):
        return False
    name = path[:-6]
    return name.startswith(PREFIXES) or any(name == c or name.startswith(c + "$") for c in CLASSES)


def validate_private_boundary(name, data):
    # Fail the build rather than strip a class that the host still links.
    # JVM class names/descriptors are stored verbatim in the constant pool.
    # Scan only actual class files (test fixtures use opaque dummy bytes).
    if not data.startswith(b"\xca\xfe\xba\xbe"):
        return
    data = boundary_bytes(name, data)
    for prefix in SMARTTUBE_PRIVATE_PREFIXES:
        internal = prefix.encode()
        dotted = prefix.replace("/", ".").encode()
        if internal in data or dotted in data:
            raise ValueError(f"host class references private SmartTube dependency: {name}: {prefix}")


def read_classes(path):
    if path.is_dir():
        for item in sorted(path.rglob("*.class")):
            yield item.relative_to(path).as_posix(), item.read_bytes()
    else:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                name = info.filename
                if name.endswith(".class") and not name.startswith("META-INF/") and name != "module-info.class":
                    yield name, archive.read(info)


def split(inputs, program, classpath, module='monogram'):
    owner, required = ownership(module)
    seen = {}
    owned = set()
    with zipfile.ZipFile(program, "w", compression=zipfile.ZIP_DEFLATED) as p, \
         zipfile.ZipFile(classpath, "w", compression=zipfile.ZIP_DEFLATED) as h:
        for path in inputs:
            for name, data in read_classes(path):
                if not re.fullmatch(r"[A-Za-z0-9_/$-]+[.]class", name):
                    raise ValueError(f"unsafe class entry: {name}")
                sha = hashlib.sha256(data).digest()
                if name in seen:
                    if seen[name] != sha:
                        raise ValueError(f"conflicting duplicate class: {name}")
                    continue
                seen[name] = sha
                if owner(name):
                    p.writestr(name, data)
                    owned.add(name)
                else:
                    if module == 'smarttube':
                        validate_private_boundary(name, data)
                    if module == 'vlc':
                        validate_vlc_boundary(name, data)
                    if module == 'libretrodroid':
                        validate_libretro_boundary(name, data)
                    h.writestr(name, data)
    if not set(required) <= owned:
        raise ValueError(f"missing adapter classes: {set(required) - owned}")
    for prefix in (("uniffi/monogram_mtproto/", "org/rustls/") if module == "monogram" else ()):
        if not any(name.startswith(prefix) for name in owned):
            raise ValueError(f"missing adapter dependency {prefix}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--android-lib", type=Path, action="append", required=True)
    parser.add_argument("--d8", type=Path, required=True)
    parser.add_argument("--binding", required=True)
    parser.add_argument("--module", choices=("monogram", "smarttube", "vlc", "libretrodroid"), default="monogram")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.binding):
        parser.error("invalid host adapter fingerprint")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        parser.error("output must be empty")
    with tempfile.TemporaryDirectory() as temp:
        program = Path(temp) / "program.jar"
        host = Path(temp) / "host.jar"
        split(args.input, program, host, args.module)
        cmd = [str(args.d8), "--release", "--min-api", "29", "--classpath", str(host),
               "--output", str(args.output)]
        for lib in args.android_lib:
            cmd += ["--lib", str(lib)]
        subprocess.run(cmd + [str(program)], check=True)
    from verify_module_dex import verify_adapter
    verify_adapter(args.module, {path.name: path.read_bytes() for path in args.output.glob("*.dex")})
    (args.output / "host-binding.sha256").write_text(args.binding + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
