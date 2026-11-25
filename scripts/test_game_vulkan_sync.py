#!/usr/bin/env python3
"""Execute production Vulkan frame lifetime code with injected driver outcomes.

Requires a C++17 compiler and Vulkan headers (libvulkan-dev), but no Android SDK,
Vulkan loader or GPU. Actual backend declarations and unchanged function bodies
are compiled; dispatch calls replace the driver, never the synchronization policy.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

from test_game_vulkan_presentation import cpp_function


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include", type=Path, action="append", default=[])
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    native = root / "third_party/libretrodroid/src/main/cpp"
    source = (native / "vulkan/vulkanbackend.cpp").read_text()
    methods = [
        "bool VulkanBackend::checkFrameResult(",
        "bool VulkanBackend::waitForFrame(",
        "bool VulkanBackend::waitForDeviceIdle(",
        "bool VulkanBackend::prepareForShutdown(",
        "bool VulkanBackend::clearFrameImage(",
        "bool VulkanBackend::destroyFrameBuffer(",
        "bool VulkanBackend::ensureFrameBuffer(",
        "bool VulkanBackend::destroyNativeSdrSwapchain(",
        "void VulkanBackend::disableNativeSdrSwapchain(",
        "bool VulkanBackend::ensureNativeSdrSwapchain(",
        "bool VulkanBackend::presentFrame(",
        "void VulkanBackend::callbackWaitSyncIndex(",
        "void VulkanBackend::destroyCoreDevice(",
        "void VulkanBackend::shutdown(",
        "void VulkanBackend::setNativeSdrSurface(",
        "void VulkanBackend::setNativeOutputTransfer(",
        "void VulkanBackend::setNativeCoreSourceTransfer(",
        "void VulkanBackend::setNativeShaderPresetPath(",
        "void VulkanBackend::setPreferFp16Frame(",
        "void VulkanBackend::disableFp16Frame(",
    ]
    with tempfile.TemporaryDirectory(prefix="ambient-vulkan-sync-") as temporary:
        work = Path(temporary)
        (work / "android").mkdir()
        (work / "android/hardware_buffer.h").write_text(
            "#pragma once\nstruct AHardwareBuffer;\nvoid AHardwareBuffer_release(AHardwareBuffer*);\n")
        (work / "android/native_window.h").write_text(
            "#pragma once\nstruct ANativeWindow;\nvoid ANativeWindow_release(ANativeWindow*);\n"
            "int ANativeWindow_getWidth(ANativeWindow*);\nint ANativeWindow_getHeight(ANativeWindow*);\n")
        (work / "android/log.h").write_text("#pragma once\n")
        (work / "vulkan_sync_under_test.h").write_text("\n".join(
            cpp_function(source, signature) for signature in methods))
        (work / "vulkan_error_boundary_under_test.h").write_text(cpp_function(
            (native / "libretrodroid.cpp").read_text(), "void LibretroDroid::checkVulkanFrameError("))
        binary = work / "vulkan-sync-test"
        command = shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++17", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-missing-field-initializers", "-Wno-unused-parameter", "-Wno-error=extra", "-pthread",
            "-I", str(work), "-I", str(native),
            "-I", str(native / "libretro/libretro-common/include"),
        ]
        for include in args.include:
            command += ["-I", str(include)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-no-pie"]
        command += [str(root / "scripts/native_game_vulkan_sync_test.cpp"),
                    str(native / "utils/libretrodroidexception.cpp"), "-include", "stdexcept",
                    "-o", str(binary)]
        subprocess.run(command, check=True)
        # Fault queues are finite. A policy regression must fail this host test,
        # not leave CI waiting for a fabricated permanently stalled driver.
        subprocess.run([str(binary)], check=True, timeout=20)


if __name__ == "__main__":
    main()
