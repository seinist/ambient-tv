#!/usr/bin/env python3
"""Run the real Vulkan encoded-source regression on Mesa lavapipe.

Ubuntu/Debian prerequisites: g++, libvulkan-dev, mesa-vulkan-drivers. No display,
Android device or physical GPU is required. --include/--loader allow extracted
SDK packages without a system installation. Vulkan validation is opt-in through
the standard VK_INSTANCE_LAYERS and VK_LAYER_PATH environment variables.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=Path)
    parser.add_argument("--include", action="append", type=Path, default=[])
    parser.add_argument("--loader", default="-lvulkan", help="Vulkan library path or linker flag")
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    native = args.native_dir or root / "third_party/libretrodroid/src/main/cpp"
    helper = native / "vulkan/vulkancolorcopy.cpp"
    if not helper.is_file():
        raise SystemExit("Fetch patched native sources first: ./scripts/fetch_libretrodroid.sh")
    compiler = shutil.which(os.environ.get("CXX", "c++"))
    if not compiler:
        raise SystemExit("C++ compiler unavailable")
    environment = os.environ.copy()
    if not environment.get("VK_ICD_FILENAMES") and not environment.get("VK_DRIVER_FILES"):
        candidates = sorted(Path("/usr/share/vulkan/icd.d").glob("*lvp*.json"))
        if not candidates:
            raise SystemExit("Mesa lavapipe unavailable: install mesa-vulkan-drivers or set VK_ICD_FILENAMES")
        environment["VK_ICD_FILENAMES"] = str(candidates[0])
    environment.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    with tempfile.TemporaryDirectory(prefix="ambient-vulkan-color-") as temporary:
        executable = Path(temporary) / "vulkan-color-test"
        command = [
            compiler, "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-missing-field-initializers", "-I", str(native),
        ]
        for include in args.include:
            command += ["-I", str(include)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-no-pie"]
        command += [str(root / "scripts/native_game_vulkan_color_test.cpp"), str(helper),
                    args.loader, "-o", str(executable)]
        subprocess.run(command, check=True, cwd=root)
        result = subprocess.run([str(executable)], cwd=root, env=environment, timeout=120,
                                text=True, capture_output=True)
        print(result.stdout, end="")
        print(result.stderr, end="", file=sys.stderr)
        result.check_returncode()
        # Validation layers can report errors without changing VkResult. Keep
        # opt-in layer/synchronization checks effective as a CI gate as well.
        if re.search(r"Validation Error|VUID-|SYNC-HAZARD", result.stdout + result.stderr):
            raise SystemExit("Vulkan validation reported an API or synchronization error")


if __name__ == "__main__":
    main()
