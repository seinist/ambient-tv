#!/usr/bin/env python3
"""Fail preview builds if migrated payloads leak back into the base APK."""
import argparse
from pathlib import Path
import zipfile
import struct
import re
from export_monogram_adapter import owns, owns_smarttube

ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
EXTERNAL = {"libprojectM-4.so", "libambientprojectm.so",
            "libmpv.so", "libambientmpv.so", "libspotify_receiver_core.so", "libmonogram_mtproto.so", "libjnidispatch.so", "libj2v8.so", "libambientsvp.so", "liblibrashader_capi.so"}
VLC_EXTERNAL = {"libvlc.so", "libvlcjni.so"}
SHARED = {"libc++_shared.so", "libavcodec.so", "libavutil.so", "libswresample.so"}


def verify_no_optional_classes(data):
    """Check defined classes, not reference strings (host ports still refer to adapters)."""
    if len(data) < 112 or data[:4] != b"dex\n":
        raise ValueError("invalid APK DEX")
    def uint(offset):
        if not 0 <= offset <= len(data) - 4:
            raise ValueError("DEX table outside file")
        return struct.unpack_from("<I", data, offset)[0]
    if uint(32) != len(data) or uint(36) != 112 or uint(40) != 0x12345678:
        raise ValueError("unsupported APK DEX header")
    string_count, string_offset, type_count, type_offset = (uint(i) for i in (56, 60, 64, 68))
    class_count, class_offset = uint(96), uint(100)
    for count, offset, width in ((string_count, string_offset, 4), (type_count, type_offset, 4),
                                 (class_count, class_offset, 32)):
        if count and (offset < 112 or offset > len(data) or count > (len(data) - offset) // width):
            raise ValueError("invalid DEX class table")
    for index in range(class_count):
        type_index = uint(class_offset + index * 32)
        if type_index >= type_count:
            raise ValueError("invalid DEX class type")
        string_index = uint(type_offset + type_index * 4)
        if string_index >= string_count:
            raise ValueError("invalid DEX descriptor index")
        offset = uint(string_offset + string_index * 4)
        if offset < 112 or offset >= len(data):
            raise ValueError("invalid DEX descriptor offset")
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("truncated DEX descriptor")
            byte = data[offset]
            offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError("invalid DEX descriptor length")
        end = data.find(b"\0", offset, min(len(data), offset + 65536))
        if end < 0:
            raise ValueError("unterminated DEX descriptor")
        descriptor = data[offset:end].decode("utf-8", errors="replace")
        if descriptor.startswith("L") and descriptor.endswith(";"):
            name = descriptor[1:-1] + ".class"
            if owns(name):
                raise ValueError(f"bundled Monogram adapter class: {descriptor}")
            if owns_smarttube(name):
                raise ValueError(f"bundled SmartTube adapter class: {descriptor}")


def verify(apk, expect_vlc_external=False):
    external = EXTERNAL | (VLC_EXTERNAL if expect_vlc_external else set())
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for name in names:
            if re.fullmatch(r"classes([2-9]|[1-9][0-9]+)?[.]dex", name):
                verify_no_optional_classes(archive.read(name))
    if len(names) != len(set(names)):
        raise ValueError(f"{apk}: duplicate APK entries")
    libraries = {}
    for name in names:
        parts = name.split("/")
        if name.startswith("assets/games/slang/"):
            raise ValueError(f"{apk}: bundled shader preset: {name}")
        if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
            abi, library = parts[1:]
            if abi not in ABIS or library in external:
                raise ValueError(f"{apk}: unexpected native payload: {name}")
            libraries.setdefault(abi, set()).add(library)
    if not libraries:
        raise ValueError(f"{apk}: no native ABI")
    for abi, installed in libraries.items():
        missing = SHARED - installed
        if missing:
            raise ValueError(f"{apk}: {abi}: missing shared decoder dependency: {sorted(missing)}")
    return set(libraries)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expect-vlc-external", action="store_true")
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    seen = set()
    for apk in args.apks:
        abis = verify(apk, args.expect_vlc_external)
        if seen & abis:
            raise ValueError("duplicate ABI across preview APKs")
        seen.update(abis)
    if seen != ABIS:
        raise ValueError(f"preview release ABI set mismatch: {sorted(seen)}")
    print("Verified preview APK payloads for all four ABIs")


if __name__ == "__main__":
    main()
