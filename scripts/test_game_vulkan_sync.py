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
    jni_source = (native / "libretrodroidjni.cpp").read_text()
    methods = [
        "bool VulkanBackend::createFrameResources(",
        "bool VulkanBackend::checkFrameResult(",
        "bool VulkanBackend::waitForFrame(",
        "bool VulkanBackend::canImportBridgeReadFence(",
        "bool VulkanBackend::importBridgeReadFence(",
        "bool VulkanBackend::canExportBridgeWriteFence(",
        "bool VulkanBackend::prepareBridgeWriteFence(",
        "int VulkanBackend::exportBridgeWriteFence(",
        "bool VulkanBackend::waitForDeviceIdle(",
        "bool VulkanBackend::prepareForShutdown(",
        "bool VulkanBackend::clearFrameImage(",
        "bool VulkanBackend::destroyFrameBuffer(",
        "bool VulkanBackend::ensureFrameBuffer(",
        "bool VulkanBackend::destroyNativeSdrSwapchain(",
        "void VulkanBackend::disableNativeSdrSwapchain(",
        "bool VulkanBackend::ensureNativeSdrSwapchain(",
        "bool VulkanBackend::presentFrame(",
        "bool VulkanBackend::completeDuplicateFrame(",
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
        # Preserve the actual C++ include scope. Including the exception header
        # inside namespace libretrodroid creates a different nested type even
        # when changing the catch spelling would make that JNI unit compile.
        prefix = jni_source[:jni_source.index("JNIEXPORT ")]
        scope_lines = [line for line in prefix.splitlines() if line.strip() in {
            '#include "utils/libretrodroidexception.h"',
            'namespace libretrodroid {', 'extern "C" {', '}',
        }]
        jni_methods = [
            "JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_step(",
            "JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_stepForNetplay(",
        ]
        (work / "vulkan_jni_boundaries_under_test.h").write_text(
            "\n".join(scope_lines) + "\n" +
            "\n".join(cpp_function(jni_source, signature) for signature in jni_methods) +
            "\n}\n}\n")
        thrower = work / "vulkan_jni_thrower.cpp"
        thrower.write_text(
            '#include "utils/libretrodroidexception.h"\n'
            'void throwProductionVulkanError() {\n'
            '    throw ::libretrodroid::LibretroDroidError("Vulkan frame failed", -1);\n}\n')
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
        command += ["-include", "stdexcept"]
        subprocess.run(command + [str(root / "scripts/native_game_vulkan_sync_test.cpp"),
            str(native / "utils/libretrodroidexception.cpp"), "-o", str(binary)], check=True)
        # Fault queues are finite. A policy regression must fail this host test,
        # not leave CI waiting for a fabricated permanently stalled driver.
        subprocess.run([str(binary)], check=True, timeout=20)
        jni_binary = work / "vulkan-jni-test"
        subprocess.run(command + [str(root / "scripts/native_game_jni_error_test.cpp"),
            str(thrower), str(native / "utils/libretrodroidexception.cpp"),
            "-o", str(jni_binary)], check=True)
        subprocess.run([str(jni_binary)], check=True, timeout=20)

        (work / "external_fence_export_under_test.h").write_text(cpp_function(
            (native / "video.cpp").read_text(), "int Video::exportExternalReadFence("))
        fence_binary = work / "external-fence-export-test"
        subprocess.run(command + [str(root / "scripts/native_game_external_fence_export_test.cpp"),
            "-o", str(fence_binary)], check=True)
        subprocess.run([str(fence_binary)], check=True, timeout=20)

        (work / "write_fence_methods.h").write_text("\n\n".join(cpp_function(
            (native / "video.cpp").read_text(), signature) for signature in (
                "bool Video::canImportExternalWriteFence(", "bool Video::importExternalWriteFence(")))
        write_fence_binary = work / "external-write-fence-test"
        subprocess.run(command + [str(root / "scripts/native_game_external_write_fence_test.cpp"),
            "-o", str(write_fence_binary)], check=True)
        subprocess.run([str(write_fence_binary)], check=True, timeout=20)

        external_binary = work / "external-frame-lease-test"
        subprocess.run(command + [str(root / "scripts/native_game_external_frame_test.cpp"),
            "-o", str(external_binary)], check=True)
        subprocess.run([str(external_binary)], check=True, timeout=20)

        slots_binary = work / "external-frame-slots-test"
        subprocess.run(command + [str(root / "scripts/native_game_external_frame_slots_test.cpp"),
            "-o", str(slots_binary)], check=True)
        subprocess.run([str(slots_binary)], check=True, timeout=20)

        access_binary = work / "external-frame-access-test"
        subprocess.run(command + [str(root / "scripts/native_game_external_frame_access_test.cpp"),
            "-o", str(access_binary)], check=True)
        subprocess.run([str(access_binary)], check=True, timeout=20)

        format_binary = work / "vulkan-format-cache-test"
        subprocess.run(command + [str(root / "scripts/native_game_vulkan_format_cache_test.cpp"),
            "-o", str(format_binary)], check=True)
        subprocess.run([str(format_binary)], check=True, timeout=20)

        bridge_sync_binary = work / "vulkan-bridge-sync-state-test"
        subprocess.run(command + [str(root / "scripts/native_game_vulkan_bridge_sync_state_test.cpp"),
            "-o", str(bridge_sync_binary)], check=True)
        subprocess.run([str(bridge_sync_binary)], check=True, timeout=20)

        bridge_image_binary = work / "vulkan-bridge-image-test"
        subprocess.run(command + [str(root / "scripts/native_game_vulkan_bridge_image_test.cpp"),
            "-o", str(bridge_image_binary)], check=True)
        subprocess.run([str(bridge_image_binary)], check=True, timeout=20)

        alternate_binary = work / "vulkan-alternate-policy-test"
        subprocess.run(command + [str(root / "scripts/native_game_vulkan_alternate_policy_test.cpp"),
            "-o", str(alternate_binary)], check=True)
        subprocess.run([str(alternate_binary)], check=True, timeout=20)


if __name__ == "__main__":
    main()
