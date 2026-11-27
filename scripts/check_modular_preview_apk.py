#!/usr/bin/env python3
"""Fail preview builds if migrated payloads leak back into the base APK."""
import argparse
from pathlib import Path
import zipfile

ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
EXTERNAL = {"libprojectM-4.so", "libambientprojectm.so",
            "libmpv.so", "libambientmpv.so", "libspotify_receiver_core.so"}
SHARED = {"libc++_shared.so", "libavcodec.so", "libavutil.so", "libswresample.so"}


def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError(f"{apk}: duplicate APK entries")
    libraries = {}
    for name in names:
        parts = name.split("/")
        if name.startswith("assets/games/slang/"):
            raise ValueError(f"{apk}: bundled shader preset: {name}")
        if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
            abi, library = parts[1:]
            if abi not in ABIS or library in EXTERNAL:
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
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    seen = set()
    for apk in args.apks:
        abis = verify(apk)
        if seen & abis:
            raise ValueError("duplicate ABI across preview APKs")
        seen.update(abis)
    if seen != ABIS:
        raise ValueError(f"preview release ABI set mismatch: {sorted(seen)}")
    print("Verified preview APK payloads for all four ABIs")


if __name__ == "__main__":
    main()
