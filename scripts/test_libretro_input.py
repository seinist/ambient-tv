#!/usr/bin/env python3
"""Run patched LibretroDroid input/display contracts on the host, without an Android SDK.

The generated headers supply compile-only Android key constants; no Android event,
JNI, graphics or lifecycle behavior is simulated. Input, descriptor and content
aspect behavior uses production C++ fetched by scripts/fetch_libretrodroid.sh.
"""
from pathlib import Path
import argparse
import os
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    native = root / "third_party/libretrodroid/src/main/cpp"
    if not all((native / header).is_file() for header in ("inputdescriptors.h", "contentaspect.h")):
        raise SystemExit("Run scripts/fetch_libretrodroid.sh with the current patches first")
    with tempfile.TemporaryDirectory(prefix="ambient-input-test-") as temporary:
        work = Path(temporary)
        headers = work / "android"
        headers.mkdir()
        keys = sorted(set(re.findall(r"\bAKEYCODE_[A-Z0-9_]+\b", (native / "input.cpp").read_text())))
        (headers / "keycodes.h").write_text("#pragma once\n" + "".join(
            f"#define {key} {index + 100}\n" for index, key in enumerate(keys)))
        (headers / "input.h").write_text("#pragma once\n#define AKEY_EVENT_ACTION_DOWN 0\n#define AKEY_EVENT_ACTION_UP 1\n")
        (headers / "log.h").write_text("#pragma once\n")
        binary = work / "input-contracts"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-pthread",
                   "-I", str(work), "-I", str(native), "-I", str(native / "libretro/libretro-common/include"),
                   str(native / "input.cpp"),
                   str(root / "scripts/native_input_contracts_test.cpp"), "-o", str(binary)]
        if args.sanitize:
            command[1:1] = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
