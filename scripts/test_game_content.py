#!/usr/bin/env python3
"""Run actual LibretroDroid ROM/VFS ownership with optional ASan/UBSan/LSan.

Only Android logging is stubbed. File reads, GameContent, VFS handles and the
libretro-common close path are production sources. Linker wrappers inject read
errors, truncation, EINTR and FD reuse to cover failure paths deterministically.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    native = root / "third_party/libretrodroid/src/main/cpp"
    common = native / "libretro/libretro-common"
    if not (native / "gamecontent.h").is_file():
        raise SystemExit("Run scripts/fetch_libretrodroid.sh with patch 0037 first")
    with tempfile.TemporaryDirectory(prefix="ambient-content-test-") as temporary:
        work = Path(temporary)
        (work / "android").mkdir()
        (work / "android/log.h").write_text(
            "#pragma once\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n"
            "#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_FATAL 7\n"
            "inline int __android_log_print(int, const char*, const char*, ...) { return 0; }\n")
        flags = ["-O1", "-g", "-ffunction-sections", "-fdata-sections",
                 "-DVFS_FRONTEND", "-D_GNU_SOURCE",
                 "-I", str(work), "-iquote", str(native), "-I", str(common / "include")]
        if args.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        common_objects = []
        for source in ("vfs/vfs_implementation.c", "file/file_path.c", "compat/compat_strl.c"):
            compiled = work / (Path(source).stem + ".o")
            subprocess.run([os.environ.get("CC", "cc"), "-std=gnu11", *flags,
                            "-c", str(common / source), "-o", str(compiled)], check=True)
            common_objects.append(str(compiled))
        binary = work / "content-contracts"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", *flags,
                        str(native / "utils/utils.cpp"), str(native / "vfs/vfs.cpp"),
                        str(native / "vfs/vfsfile.cpp"), str(native / "vfs/fdwrapper.cpp"),
                        str(root / "scripts/native_game_content_test.cpp"), *common_objects,
                        "-Wl,--gc-sections", "-Wl,--wrap=pread", "-Wl,--wrap=fstat",
                        "-Wl,--wrap=fclose", "-o", str(binary)], check=True)
        env = os.environ.copy()
        if args.sanitize:
            env.setdefault("ASAN_OPTIONS", "detect_leaks=1:halt_on_error=1")
            env.setdefault("UBSAN_OPTIONS", "halt_on_error=1:print_stacktrace=1")
        subprocess.run([str(binary), str(work)], env=env, check=True)


if __name__ == "__main__":
    main()
