#!/usr/bin/env python3
"""Compile and exercise the exact patched LibretroDroid resampler on the host."""
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
    source = native / "resamplers/linearresampler.cpp"
    if not source.is_file():
        raise SystemExit("Run scripts/fetch_libretrodroid.sh first")
    with tempfile.TemporaryDirectory(prefix="ambient-audio-test-") as temporary:
        binary = Path(temporary) / "audio-contracts"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                   "-I", str(native), str(source), str(Path(__file__).with_name("native_game_audio_test.cpp")),
                   "-o", str(binary)]
        if args.sanitize:
            command[1:1] = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)

        controls = Path(temporary) / "audio-controls"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-pthread",
                   "-I", str(native), str(Path(__file__).with_name("native_game_audio_controls_test.cpp")),
                   "-o", str(controls)]
        if args.sanitize:
            command[1:1] = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(controls)], check=True)

        gate = Path(temporary) / "audio-processing-gate"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-pthread",
                   "-I", str(native), str(Path(__file__).with_name("native_game_audio_gate_test.cpp")),
                   "-o", str(gate)]
        if args.sanitize:
            command[1:1] = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(gate)], check=True, timeout=30)

        recovery = Path(temporary) / "audio-stream-recovery"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-pthread",
                   "-I", str(native), str(Path(__file__).with_name("native_game_audio_recovery_test.cpp")),
                   "-o", str(recovery)]
        if args.sanitize:
            command[1:1] = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(recovery)], check=True, timeout=30)


if __name__ == "__main__":
    main()
