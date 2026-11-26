#!/usr/bin/env python3
"""Render real patched LibretroDroid filter transitions on Mesa/EGL without Android.

Install g++, libegl-dev, libgles-dev and Mesa DRI (llvmpipe). Android log and
AHardwareBuffer are declaration stubs only; EGL, GL compilation, allocations,
sampling and pixel readback run through the actual driver. Native sources and
all shader programs are the exact production inputs. Optional --include/--lib
paths support locally extracted development packages; CI uses system packages.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

from import_game_upscalers import presets, stage_sources
from game_filter_audit_sources import write_audit_header

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--include", action="append", default=[])
    parser.add_argument("--lib", action="append", default=[])
    parser.add_argument("--gl-library", default="GLESv2")
    parser.add_argument("--egl-library", default="EGL")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--test-source", type=Path, default=ROOT / "scripts/native_game_filter_renderer_test.cpp")
    parser.add_argument("--gles-version", choices=("2", "3", "all"), default="all")
    parser.add_argument("--audit-report", type=Path, help="Write per-case deep color audit results (JSON lines)")
    parser.add_argument("--audit-phase", default="all",
                        choices=("all", "output", "input", "unconfirmed", "switch", "effects", "alpha", "upload", "hardware", "rgb10", "fp16", "finite", "capture"),
                        help="Select one phase of the deep audit")
    args = parser.parse_args()
    native = ROOT / "third_party/libretrodroid/src/main/cpp"
    if not (native / "video.cpp").is_file():
        raise SystemExit("Run scripts/fetch_libretrodroid.sh first")
    with tempfile.TemporaryDirectory(prefix="ambient-filter-render-") as temporary:
        work = Path(temporary)
        android = work / "android"
        android.mkdir()
        (android / "hardware_buffer.h").write_text(
            "#pragma once\nstruct AHardwareBuffer;\n"
            "inline void AHardwareBuffer_acquire(AHardwareBuffer*) {}\n"
            "inline void AHardwareBuffer_release(AHardwareBuffer*) {}\n")
        (android / "log.h").write_text(r'''#pragma once
#include <cstdarg>
#include <cstdio>
#include <cstring>
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
extern int ambientShaderFallbacks;
inline int __android_log_print(int priority,const char*,const char* format,...) {
    if(std::strstr(format,"Requested shader unavailable")) ++ambientShaderFallbacks;
    if(priority < ANDROID_LOG_WARN) return 0;
    va_list args;va_start(args,format);int result=std::vfprintf(stderr,format,args);va_end(args);
    std::fputc('\n',stderr);return result;
}
''')
        # Android ships an empty gl3ext.h; upstream Khronos places extensions in gl2ext.h.
        (work / "GLES3").mkdir()
        (work / "GLES3/gl3ext.h").write_text("#pragma once\n")
        source = stage_sources()
        header = ['#pragma once', '#include "shadermanager.h"',
                  'struct TestPreset { std::string name; std::vector<libretrodroid::ShaderManager::Pass> passes; };',
                  'inline std::vector<TestPreset> gameFilterTestPresets() { return {']
        for name, passes in presets().items():
            header.append('{"' + name + '",{')
            for identifier, scale, linear, floating in passes:
                vertex, fragment = source[identifier]
                assert ')AMBIENT"' not in vertex + fragment
                header.append('{R"AMBIENT(' + vertex + ')AMBIENT",R"AMBIENT(' + fragment + ')AMBIENT",' +
                              str(linear).lower() + ',' + str(scale) + 'f,' + str(floating).lower() + '},')
            header.append('}},')
        header.append('}; }')
        (work / "game_filter_test_presets.h").write_text('\n'.join(header))
        write_audit_header(work / "game_filter_audit_presets.h")
        sources = ['video.cpp', 'videolayout.cpp', 'shadermanager.cpp', 'renderers/renderer.cpp',
                   'renderers/es3/es3utils.cpp', 'renderers/es3/imagerendereres3.cpp',
                   'renderers/es3/framebufferrenderer.cpp', 'renderers/es2/imagerendereres2.cpp',
                   'backgroundframe.cpp', 'immersivemode.cpp', 'utils/rect.cpp']
        binary = work / "filter-renderer"
        command = [os.environ.get("CXX", "c++"), "-std=c++17", "-O1", "-g", "-pthread",
                   "-include", "cstring", "-include", "cmath", "-include", "algorithm", "-include", "stdexcept",
                   "-I", str(work), "-I", str(native), "-I", str(native / "libretro/libretro-common/include")]
        for path in args.include:
            command += ["-I", path]
        for path in args.lib:
            command += ["-L", path]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        command += [str(native / source) for source in sources]
        command += [str(args.test_source),
                    "-Wl,--wrap=glTexStorage2D", "-Wl,--wrap=glUniform1i",
                    "-Wl,--wrap=glUniform2f", "-Wl,--wrap=glUniform1f",
                    "-Wl,--wrap=glLinkProgram", "-Wl,--wrap=glBufferData",
                    "-Wl,--wrap=glVertexAttribPointer", "-Wl,--wrap=glInvalidateFramebuffer",
                    "-l" + args.egl_library, "-l" + args.gl_library, "-o", str(binary)]
        subprocess.run(command, check=True)
        environment = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="1", EGL_PLATFORM="surfaceless")
        environment["AMBIENT_FILTER_AUDIT_PHASE"] = args.audit_phase
        if args.audit_report:
            environment["AMBIENT_FILTER_AUDIT_REPORT"] = str(args.audit_report.resolve())
        failures = 0
        for version in (("2.0", "3.0") if args.gles_version == "all" else (args.gles_version + ".0",)):
            environment["MESA_GLES_VERSION_OVERRIDE"] = version
            completed = subprocess.run([str(binary)] + (["--gles2"] if version == "2.0" else []),
                                       env=environment)
            failures += completed.returncode != 0
        if failures:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
