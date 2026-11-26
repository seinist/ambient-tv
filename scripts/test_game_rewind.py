#!/usr/bin/env python3
"""Exercise production rewind storage and timing on the host, without an Android SDK.

The same C++ sources are copied into LibretroDroid by fetch_libretrodroid.sh.
Use --native-dir to additionally check the prepared copy, or --sanitize for
AddressSanitizer and UndefinedBehaviorSanitizer instrumentation.
"""
from pathlib import Path
import argparse
import os
import shlex
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--native-dir", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    native = args.native_dir or root / "scripts/native_rewind"
    required = ("rewindbuffer.h", "rewindbuffer.cpp", "rewindtimeline.h", "rewindcapture.h")
    if not all((native / name).is_file() for name in required):
        parser.error(f"Rewind sources are missing from {native}")
    with tempfile.TemporaryDirectory(prefix="ambient-rewind-test-") as temporary:
        compiler = shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++17", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-pedantic", "-pthread",
            "-I", str(native), str(native / "rewindbuffer.cpp"),
        ]
        if args.sanitize:
            compiler += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        for name in ("rewindbuffer", "rewindtimeline", "rewindcapture"):
            binary = Path(temporary) / f"{name}-contracts"
            command = compiler + [
                str(root / f"scripts/native_rewind/{name}_test.cpp"), "-o", str(binary),
            ]
            subprocess.run(command, check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
