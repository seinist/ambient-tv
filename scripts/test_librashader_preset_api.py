#!/usr/bin/env python3
"""Host-side FFI contract check for the *pinned* librashader C runtime.

Unlike glslangValidator, this exercises the real RetroArch .slangp parser,
reference resolution, C ABI v2/API v5 and output color-space declaration.
It does NOT imply Android GPU/driver or actual display validation.
"""
import ctypes as ct
from pathlib import Path
import sys

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/games/slang"

class PresetOptions(ct.Structure):
    _fields_ = [
        ("version", ct.c_size_t),
        ("original_aspect_uniforms", ct.c_bool),
        ("frametime_uniforms", ct.c_bool),
        ("sensor_uniforms", ct.c_bool),
    ]

def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: test_librashader_preset_api.py PATH_TO_HOST_LIBRASHADER_SO")
    path = Path(sys.argv[1]).resolve(strict=True)
    lib = ct.CDLL(str(path))
    lib.libra_instance_abi_version.restype = ct.c_size_t
    lib.libra_instance_api_version.restype = ct.c_size_t
    abi = lib.libra_instance_abi_version()
    api = lib.libra_instance_api_version()
    if abi != 2 or api < 5:
        raise SystemExit(f"Unexpected C API version: ABI {abi}, API {api}")

    create = lib.libra_preset_create_with_options
    create.argtypes = [ct.c_char_p, ct.c_void_p, ct.POINTER(PresetOptions), ct.POINTER(ct.c_void_p)]
    create.restype = ct.c_void_p

    color = lib.libra_preset_color_space
    color.argtypes = [ct.POINTER(ct.c_void_p), ct.POINTER(ct.c_uint32)]
    color.restype = ct.c_void_p

    close = lib.libra_preset_free
    close.argtypes = [ct.POINTER(ct.c_void_p)]
    close.restype = ct.c_void_p
    error_free = lib.libra_error_free
    error_free.argtypes = [ct.POINTER(ct.c_void_p)]
    error_free.restype = ct.c_int32

    count = 0
    for file in sorted(ASSETS.glob("*.slangp")):
        preset = ct.c_void_p()
        options = PresetOptions(5, True, True, False)
        error = create(str(file).encode(), None, ct.byref(options), ct.byref(preset))
        if error:
            ptr = ct.c_void_p(error)
            error_free(ct.byref(ptr))
            raise AssertionError(f"librashader could not parse {file.name}")
        try:
            if not preset.value:
                raise AssertionError(f"Null preset after successful parse: {file.name}")
            colorspace = ct.c_uint32(0xFFFF)
            error = color(ct.byref(preset), ct.byref(colorspace))
            if error:
                ptr = ct.c_void_p(error)
                error_free(ct.byref(ptr))
                raise AssertionError(f"Cannot query output colorspace for {file.name}")
            if colorspace.value != 0:
                raise AssertionError(f"Unsupported native HDR shader output in {file.name}: {colorspace.value}")
            count += 1
        finally:
            if preset.value:
                error = close(ct.byref(preset))
                if error:
                    ptr = ct.c_void_p(error)
                    error_free(ct.byref(ptr))
                    raise AssertionError(f"Cannot free librashader preset: {file.name}")

    if count != 9:
        raise AssertionError(f"Expected exactly 9 approved Slang presets, got {count}")
    print(f"OK: pinned librashader v{api}/ABI{abi} loaded and parsed {count} SDR Slang presets")

if __name__ == "__main__":
    main()
