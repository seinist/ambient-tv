#!/usr/bin/env python3
"""Render every bundled Slang preset with the production VulkanSlangPipeline.

Reuse the pinned host librashader library already built by slang-preset-api CI:
  python3 scripts/test_game_vulkan_slang.py --runtime PATH/liblibrashader_capi.so

Requires a C++ compiler, libvulkan-dev and mesa-vulkan-drivers. The test uses
lavapipe and no display or Android GPU. It checks successful render/readback,
nonempty color-chart content, black borders and a source/output resize; it does
not claim pixel-identical output to GLES or Android-AHB/display validation.
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

from check_game_slang import PRESETS, SHADERS, validate_assets


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, help="Existing host librashader C-API shared library")
    parser.add_argument("--include", action="append", type=Path, default=[])
    parser.add_argument("--loader", default="-lvulkan", help="Vulkan loader path or linker flag")
    parser.add_argument("--compile-only", action="store_true", help="Compile host source without claiming GPU coverage")
    args = parser.parse_args()
    if not args.compile_only and args.runtime is None:
        parser.error("--runtime is required unless --compile-only is requested")
    runtime = args.runtime.resolve(strict=True) if args.runtime is not None else None
    root = Path(__file__).resolve().parents[1]
    production = root / "scripts/native_libretro_vulkan"
    compiler = shutil.which(os.environ.get("CXX", "c++"))
    if not compiler:
        raise SystemExit("C++ compiler unavailable")
    validate_assets()
    presets = [(SHADERS / (name + ".slangp")).resolve(strict=True) for name in sorted(PRESETS)]
    environment = os.environ.copy()
    if not args.compile_only and not environment.get("VK_ICD_FILENAMES") and not environment.get("VK_DRIVER_FILES"):
        candidates = sorted(Path("/usr/share/vulkan/icd.d").glob("*lvp*.json"))
        if not candidates:
            raise SystemExit("Mesa lavapipe unavailable: install mesa-vulkan-drivers or set VK_ICD_FILENAMES")
        environment["VK_ICD_FILENAMES"] = str(candidates[0])
    environment.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    # Report all leaked object classes, not only the first ten repeated VUIDs.
    environment.setdefault("VK_LAYER_DUPLICATE_MESSAGE_LIMIT", "1000")
    with tempfile.TemporaryDirectory(prefix="ambient-vulkan-slang-") as temporary:
        scratch = Path(temporary)
        executable = scratch / "vulkan-slang-test"
        geometry_test = scratch / "slang-geometry-test"
        subprocess.run([compiler, "-std=c++17", "-O1", "-Wall", "-Wextra", "-Werror",
                        "-I", str(production), str(root / "scripts/native_game_vulkan_slang_geometry_test.cpp"),
                        "-o", str(geometry_test)], check=True, cwd=root)
        subprocess.run([str(geometry_test)], check=True, timeout=20)
        command = [compiler, "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                   "-Wno-missing-field-initializers", "-I", str(production),
                   "-I", str(root / "scripts/host_vulkan_stubs")]
        for include in args.include:
            command += ["-I", str(include)]
        command += [str(root / "scripts/native_game_vulkan_slang_test.cpp"),
                    str(production / "vulkanslangpipeline.cpp"), args.loader, "-ldl", "-o", str(executable)]
        subprocess.run(command, check=True, cwd=root)
        if args.compile_only:
            print(f"COMPILE ONLY: production VulkanSlangPipeline + smoke harness; {len(presets)} preset paths validated, no GPU/runtime test run")
            return
        assert runtime is not None
        # Production dlopen uses this exact soname. The existing C-API build is
        # reused as-is; no second Cargo build or altered production loader.
        (scratch / "liblibrashader_capi.so").symlink_to(runtime)
        library_paths = [str(scratch), str(runtime.parent)]
        if environment.get("LD_LIBRARY_PATH"):
            library_paths.append(environment["LD_LIBRARY_PATH"])
        environment["LD_LIBRARY_PATH"] = os.pathsep.join(library_paths)
        result = subprocess.run([str(executable), *map(str, presets)], cwd=root, env=environment,
                                text=True, capture_output=True, timeout=300)
        print(result.stdout, end="")
        print(result.stderr, end="", file=sys.stderr)
        result.check_returncode()
        if re.search(r"Validation Error|VUID-|SYNC-HAZARD", result.stdout + result.stderr):
            raise SystemExit("Vulkan validation reported an API or synchronization error")


if __name__ == "__main__":
    main()
