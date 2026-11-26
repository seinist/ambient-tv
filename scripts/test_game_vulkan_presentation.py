#!/usr/bin/env python3
"""Check native WSI/GLES geometry parity and visibility handoffs without Android.

Compiles the production VideoLayout and native presentation policy. These checks
do not claim device WSI timing or SurfaceFlinger validation; those require a TV.
"""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


def cpp_function(source: str, signature: str) -> str:
    """Extract an actual function, keeping its body unchanged for execution.

    Blank comments and string/character literals only while locating braces, so
    an explanatory brace in a comment cannot silently truncate the compiled body.
    """
    ignored = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\\r\n])*"|\'(?:\\.|[^\'\\\r\n])*\'', re.S)
    masked = ignored.sub(lambda match: " " * len(match.group()), source)
    start = masked.index(signature)
    opening = masked.index("{", start)
    depth = 1
    for end in range(opening + 1, len(masked)):
        if masked[end] == "{":
            depth += 1
        elif masked[end] == "}":
            depth -= 1
            if depth == 0:
                return source[start:end + 1] + "\n"
    raise ValueError(f"Unterminated C++ function: {signature}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    native = root / "third_party/libretrodroid/src/main/cpp"
    if not (native / "vulkan/nativepresentation.h").is_file():
        parser.error("Run scripts/fetch_libretrodroid.sh first")
    with tempfile.TemporaryDirectory(prefix="ambient-native-presentation-") as temporary:
        work = Path(temporary)
        (work / "android").mkdir()
        (work / "android/log.h").write_text("#pragma once\n")
        source = (native / "libretrodroid.cpp").read_text()
        (work / "game_video_refresh_under_test.h").write_text("\n".join(
            cpp_function(source, signature) for signature in (
                "void LibretroDroid::attachVulkanFrame(",
                "bool LibretroDroid::prepareSpareVulkanFrame(",
                "void LibretroDroid::handleVideoRefresh(",
            )))
        video_source = (native / "video.cpp").read_text()
        (work / "spare_egl_under_test.h").write_text("\n".join(
            cpp_function(video_source, signature) for signature in (
                "bool Video::matchesPrimaryExternalFrame(",
                "bool Video::matchesExternalFrameSlot(",
                "bool Video::finishExternalFrameSlotUse(",
                "bool Video::externalFrameSlotNeedsReadWait(",
                "void Video::externalFrameSlotReadWaitSubmitted(",
                "bool Video::selectExternalFrameSlot(",
                "bool Video::prepareExternalFrameResize(",
                "bool Video::prepareSpareExternalFrame(",
                "bool Video::releaseSpareExternalFrame(",
            )))
        command = shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-parameter", "-Wno-sign-compare", "-include", "cmath",
            "-include", "algorithm", "-include", "limits",
            "-I", str(work), "-I", str(native),
        ]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        cases = {
            "native-presentation": [native / "videolayout.cpp", native / "utils/rect.cpp",
                root / "scripts/native_game_vulkan_presentation_test.cpp"],
            "video-ingress": [root / "scripts/native_game_video_ingress_test.cpp"],
            "spare-egl": [root / "scripts/native_game_spare_egl_import_test.cpp"],
        }
        for name, sources in cases.items():
            binary = work / name
            subprocess.run(command + [str(source) for source in sources] + ["-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
