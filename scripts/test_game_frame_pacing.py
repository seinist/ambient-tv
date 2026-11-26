#!/usr/bin/env python3
"""Test the actual patched LibretroDroid frame clock with deterministic timestamps."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--native-dir", type=Path, default=ROOT / "third_party/libretrodroid/src/main/cpp")
    args = parser.parse_args()
    native = args.native_dir.resolve()
    if not (native / "framepacingdeadline.h").is_file():
        raise SystemExit("Run scripts/fetch_libretrodroid.sh with the current patches first")
    with tempfile.TemporaryDirectory(prefix="ambient-frame-pacing-") as temporary:
        work = Path(temporary)
        android = work / "android"
        android.mkdir()
        (android / "log.h").write_text('''#pragma once
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
''')
        binary = work / "frame-pacing"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-pthread",
                   "-I", str(work), "-I", str(native), str(native / "fpssync.cpp"),
                   str(Path(__file__).with_name("native_game_frame_pacing_test.cpp")), "-o", str(binary)]
        if args.sanitize:
            command[1:1] = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
