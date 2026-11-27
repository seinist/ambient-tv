"""Inspect emitted compiler commands for architecture-specific core acceleration."""
from pathlib import Path
import re
import shlex

DRIVER = re.compile(r"(?:[\w.-]+-)?clang(?:\+\+)?(?:-\d+)?")
COMPILER_COMMAND = re.compile(r"^\s*(?:\d{4}-\d{2}-\d{2}T[^ ]+Z\s+)?(?:\S*/)?(?:[\w.-]+-)?clang(?:\+\+)?(?:-\d+)?(?:\s|$)")
ASSEMBLER = re.compile(r"(?:yasm|nasm)(?:-\d+)?")
SOURCE = re.compile(r"\.(?:c|cc|cpp|cxx|C|s|S|asm)$")
AUDITED = {"gpsp", "pcsx_rearmed", "mupen64plus_next_gles3", "picodrive", "parallel_n64", "mednafen_psx_hw", "swanstation", "gambatte", "genesis_plus_gx",
           "mednafen_pce_fast", "mednafen_ngp", "mednafen_wswan", "mednafen_supergrafx", "snes9x2010",
           "nestopia", "snes9x", "mgba", "handy", "stella2014", "mednafen_vb",
           "fceumm", "mesen", "bsnes", "sameboy", "race", "mednafen_lynx",
           "melondsds", "noods", "citra2018", "panda3ds", "dolphin", "cemu", "ppsspp",
           "flycast", "mednafen_saturn", "yabause", "yabasanshiro", "ymir", "armsx2",
           "vice_x64sc", "vice_x64", "vice_xscpu64", "vice_x128", "vice_xvic", "vice_xplus4",
           "vice_xpet", "vice_xcbm2", "vice_xcbm5x0", "prosystem", "a5200"}


def expand_response(tokens, cwd, root, depth=0):
    if depth > 4:
        raise ValueError("Compiler response-file nesting exceeds limit")
    result = []
    for token in tokens:
        if not token.startswith("@"):
            result.append(token)
            continue
        path = (cwd / token[1:]).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError(f"Cannot audit compiler response file: {path}")
        if path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("Compiler response file exceeds audit limit")
        result.extend(expand_response(shlex.split(path.read_text()), cwd, root, depth + 1))
    return result


def build_commands(log, cwd=None, root=None, strict=False):
    cwd = Path(cwd or Path.cwd()).resolve()
    root = Path(root or Path.cwd()).resolve()
    link_cwd = None
    for line in log.splitlines():
        # Accept only actual command starts or recognized CMake/Ninja wrappers.
        # A diagnostic mentioning clang is not evidence that clang was invoked.
        line = re.sub(r"^\s*\d{4}-\d{2}-\d{2}T[^ ]+Z\s+", "", line).strip()
        line = re.sub(r"^\[\d+/\d+\]\s+", "", line)
        # GNU Make prints the CMake link-script invocation with its working
        # directory, then echoes the child compiler/ar commands on separate
        # lines. --output-sync=target keeps this group contiguous.
        if re.match(r"^(?:g?make)(?:\[\d+\])?: (?:Entering|Leaving) directory", line):
            link_cwd = None
            continue
        if line.startswith("cd "):
            link_cwd = None
        if line.startswith("cd ") and "cmake_link_script" in line:
            context = shlex.split(line)
            if (len(context) >= 7 and context[2] == "&&"
                    and Path(context[3]).name == "cmake"
                    and context[4:6] == ["-E", "cmake_link_script"]):
                link_cwd = (cwd / context[1]).resolve()
                if not link_cwd.is_relative_to(root):
                    raise ValueError("CMake link script directory is outside the build root")
                continue
        wrapped = line.startswith(("cd ", ": && ")) or re.match(r"^(?:\S*/)?ccache\s+", line)
        archive_start = re.match(r"^(?:\S*/)?(?:[\w.-]+-)?llvm-ar(?:-\d+)?(?:\s|$)", line)
        quoted_driver = re.match(r'^"[^"]*/(?:[\w.-]+-)?(?:clang(?:\+\+)?|llvm-ar)(?:-\d+)?"(?:\s|$)', line)
        assembler_start = re.match(r'^(?:"[^"]*/|(?:\S*/)?)' + ASSEMBLER.pattern + r'"?(?:\s|$)', line)
        if not COMPILER_COMMAND.match(line) and not wrapped and not archive_start and not quoted_driver and not assembler_start:
            continue
        try:
            tokens = shlex.split(line)
        except ValueError:
            if strict:
                raise ValueError("Malformed compiler command in build log")
            continue
        command_cwd = link_cwd or cwd
        while tokens[:2] == [":", "&&"]:
            tokens = tokens[2:]
        if len(tokens) >= 3 and tokens[0] == "cd" and tokens[2] == "&&":
            command_cwd = (cwd / tokens[1]).resolve()
            tokens = tokens[3:]
        while tokens[:2] == [":", "&&"]:
            tokens = tokens[2:]
        # Ninja's CMake archive rule removes the old archive before llvm-ar.
        # Only skip this known prefix, never search arbitrary diagnostics.
        if tokens and ((Path(tokens[0]).name == "cmake" and tokens[1:4] == ["-E", "rm", "-f"])
                       or (Path(tokens[0]).name == "rm" and tokens[1:2] == ["-f"])):
            if "&&" not in tokens:
                continue
            tokens = tokens[tokens.index("&&") + 1:]
        if tokens and Path(tokens[0]).name == "ccache":
            tokens = tokens[1:]
        if not tokens:
            continue
        driver = Path(tokens[0]).name
        if not DRIVER.fullmatch(driver) and not ASSEMBLER.fullmatch(driver) and not re.fullmatch(r"(?:[\w.-]+-)?llvm-ar(?:-\d+)?", driver):
            continue
        end = next((i for i, token in enumerate(tokens) if token in {"&&", ";", "||"}), len(tokens))
        try:
            args = expand_response(tokens[1:end], command_cwd, root)
        except (ValueError, OSError):
            if strict:
                raise
            continue
        yield driver, args, command_cwd



def assembler_source(args):
    # Yasm/Nasm assemble by default, with no clang-style -c. Only accept
    # a single explicit .asm input, ELF output format and .o output.
    if "-o" not in args or "-f" not in args:
        return None
    oi, fi = args.index("-o"), args.index("-f")
    if oi + 1 >= len(args) or fi + 1 >= len(args):
        return None
    if Path(args[oi + 1]).suffix != ".o" or args[fi + 1] not in {"elf", "elf32", "elf64"}:
        return None
    sources = [arg for arg in args if not arg.startswith("-") and arg.endswith(".asm")]
    return sources[0] if len(sources) == 1 else None


def compiler_commands(log, cwd=None, root=None, strict=False):
    for driver, args, _ in build_commands(log, cwd, root, strict):
        if DRIVER.fullmatch(driver):
            yield args
        elif ASSEMBLER.fullmatch(driver) and (source := assembler_source(args)):
            yield ["-c", source, *args]


def compile_source(args):
    if "-c" not in args:
        return None
    # Do not accidentally treat a dependency path or include directory as a source.
    index = args.index("-c")
    if index + 1 < len(args) and SOURCE.search(args[index + 1]):
        return args[index + 1].replace("\\", "/")
    sources = [token for token in args if SOURCE.search(token) and not token.startswith("-")]
    return sources[0].replace("\\", "/") if len(sources) == 1 else None


def defines(args):
    result = {}
    index = 0
    while index < len(args):
        token = args[index]
        index += 1
        if token in {"-D", "-U"}:
            if index >= len(args):
                break
            token += args[index]
            index += 1
        if token.startswith("-D"):
            key, _, value = token[2:].partition("=")
            result[key] = value if _ else "1"
        elif token.startswith("-U"):
            result.pop(token[2:], None)
    return result


def evidence(commands, source, required=()):
    """Require the source and its effective macros in the SAME invocation."""
    for args in commands:
        path = compile_source(args)
        if path is None or not (path == source or path.endswith("/" + source)):
            continue
        macros = defines(args)
        if all(macros.get(key) == value for key, value in required):
            return True
    return False


def requirements(core, abi, profile):
    arm = abi in {"arm64-v8a", "armeabi-v7a"}
    arm64 = abi == "arm64-v8a"
    # These are build-path contracts, not claims that a given runtime option
    # is enabled or a particular function has become faster.
    if core == "melondsds":
        result = {"software_compositor": ("src/libretro/render/software.cpp", ()),
                  "input": ("src/libretro/input/joypad.cpp", ()),
                  "core": ("src/libretro/core/core.cpp", ())}
        if abi in {"arm64-v8a", "x86_64"}:
            result["jit_fastmem_build"] = ("src/libretro/core/core.cpp", (("HAVE_JIT", "1"), ("HAVE_JIT_FASTMEM", "1")))
        return result
    if core == "noods":
        return {"interpreter": ("src/interpreter.cpp", ()),
                "software_2d": ("src/gpu_2d.cpp", ()),
                "software_3d": ("src/gpu_3d_renderer.cpp", ()),
                "frontend": ("src/libretro/libretro.cpp", ())}
    if core == "citra2018":
        result = {"cpu_interpreter": ("src/core/arm/dyncom/arm_dyncom_interpreter.cpp", ()),
                  "audio": ("src/audio_core/libretro_sink.cpp", ()),
                  "frontend": ("src/citra_libretro/citra_libretro.cpp", ())}
        # This pinned fork only links Dynarmic on x86_64. Do not claim ARM64 JIT.
        if abi == "x86_64":
            result["dynarmic"] = ("src/core/arm/dynarmic/arm_dynarmic.cpp", ())
        return result
    if core == "panda3ds":
        return {"dynarmic": ("src/core/CPU/cpu_dynarmic.cpp", (("CPU_DYNARMIC", "1"),)),
                "arm64_shader_jit": ("src/core/PICA/dynapica/shader_rec_emitter_arm64.cpp", (("PANDA3DS_ARM64_HOST", "1"),)),
                "frontend": ("src/libretro_core.cpp", ())}
    if core == "dolphin":
        jit = "JitArm64" if arm64 else "Jit64"
        return {"powerpc_jit": ("Source/Core/Core/PowerPC/" + jit + "/Jit.cpp", ()),
                "input": ("Source/Core/DolphinLibretro/Input.cpp", ()),
                "audio": ("Source/Core/DolphinLibretro/Audio.cpp", ()),
                "vulkan": ("Source/Core/DolphinLibretro/Vulkan.cpp", ())}
    if core == "cemu":
        backend = "BackendAArch64" if arm64 else "BackendX64"
        return {"powerpc_recompiler": ("src/Cafe/HW/Espresso/Recompiler/" + backend + "/" + backend + ".cpp", ()),
                "vulkan": ("src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.cpp", ()),
                "frontend": ("src/libretro/CemuLibretro.cpp", ())}
    if core == "ppsspp":
        result = {"ir_jit": ("Core/MIPS/IR/IRJit.cpp", ()),
                  "frontend": ("libretro/libretro.cpp", ())}
        native = {"arm64-v8a": "ARM64/Arm64IRJit.cpp", "armeabi-v7a": "ARM/ArmIRJit.cpp",
                  "x86_64": "x86/X64IRJit.cpp"}.get(abi)
        if native:
            result["native_jit"] = ("Core/MIPS/" + native, ())
        return result
    if core == "flycast":
        backend = {"arm64-v8a": "rec-ARM64/rec_arm64.cpp", "armeabi-v7a": "rec-ARM/rec_arm.cpp",
                   "x86_64": "rec-x64/rec_x64.cpp", "x86": "rec-x86/rec_x86.cpp"}[abi]
        return {"dynarec": ("core/" + backend, ()),
                "audio": ("shell/libretro/audiostream.cpp", ()),
                "frontend": ("shell/libretro/libretro.cpp", ())}
    if core == "mednafen_saturn":
        result = {"vdp1": ("mednafen/ss/vdp1.c", ()),
                  "vdp2": ("mednafen/ss/vdp2_render.c", ()),
                  "scsp_dsp_jit": ("mednafen/ss/scsp_dsp_jit.c", ()),
                  "scu_dsp_jit": ("mednafen/ss/scu_dsp_jit.c", ()),
                  "frontend": ("libretro.c", ())}
        if arm64:
            result["arm64_emitter"] = ("mednafen/ss/a64emit.c", ())
        return result
    if core in {"yabause", "yabasanshiro"}:
        result = {"frontend": ("yabause/src/libretro/libretro.c", ()),
                  "disc_io": ("yabause/src/cd-libretro.c", ())}
        if core == "yabasanshiro" and arm:
            result["sh2_dynarec"] = ("yabause/src/sh2_dynarec_devmiyax/DynarecSh2.cpp", (("DYNAREC_DEVMIYAX", "1"),))
        return result
    if core == "ymir":
        return {"sh2": ("libs/ymir-core/src/ymir/hw/sh2/sh2.cpp", ()),
                "scsp": ("libs/ymir-core/src/ymir/hw/scsp/scsp.cpp", ()),
                "software_vdp": ("libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp", ()),
                "frontend": ("apps/ymir-libretro/src/libretro.cpp", ())}
    if core == "armsx2":
        return {"ee_dynarec": ("pcsx2/arm64/iR5900-arm64.cpp", ()),
                "vif_dynarec": ("pcsx2/arm64/Vif_Dynarec.cpp", ()),
                "vif_neon": ("pcsx2/arm64/Vif_UnpackNEON.cpp", ()),
                "spu2_neon": ("pcsx2/SPU2/spu2_neon.cpp", ()),
                "frontend": ("pcsx2-libretro/Main.cpp", ())}
    if core.startswith("vice_") and core in AUDITED:
        # The NDK recipe injects CORE_NAME for each machine, so the common
        # source being linked alone is insufficient to identify the variant.
        return {"machine": ("libretro/libretro-core.c", (("CORE_NAME", '"' + core[5:] + '"'),)),
                "input": ("libretro/libretro-mapper.c", ()),
                "video": ("libretro/libretro-graph.c", ())}
    if core == "prosystem":
        return {"maria_video": ("core/Maria.c", ()),
                "tia_audio": ("core/Tia.c", ()),
                "pokey_audio": ("core/Pokey.c", ()),
                "frontend": ("core/libretro.c", ())}
    if core == "a5200":
        return {"antic_video": ("emu/antic.c", ()),
                "gtia_video": ("emu/gtia.c", ()),
                "pokey_audio": ("emu/pokey.c", ()),
                "frontend": ("libretro/libretro.c", ())}
    if core == "nestopia":
        return {"cpu": ("source/core/NstCpu.cpp", (("__LIBRETRO__", "1"),)),
                "ppu": ("source/core/NstPpu.cpp", ()),
                "audio": ("source/core/NstApu.cpp", ()),
                "video_renderer": ("source/core/NstVideoRenderer.cpp", ())}
    if core == "snes9x":
        return {"cpu_dispatch": ("cpuops.cpp", (("__LIBRETRO__", "1"),)),
                "cpu_execution": ("cpuexec.cpp", ()),
                "audio_dsp": ("apu/bapu/dsp/sdsp.cpp", ())}
    if core == "mgba":
        return {"arm_cpu": ("src/arm/arm.c", (("__LIBRETRO__", "1"),)),
                "gba_software_renderer": ("src/gba/renderers/video-software.c", ()),
                "gb_software_renderer": ("src/gb/renderers/software.c", ()),
                "libretro_vfs": ("src/platform/libretro/libretro.c", (("ENABLE_VFS", "1"),))}
    if core == "handy":
        return {"blip_audio": ("blip/Blip_Buffer.cpp", ()),
                "stereo_audio": ("blip/Stereo_Buffer.cpp", ()),
                "crc_rom_loader": ("libretro/libretro.cpp", (("WANT_CRC32", "1"),))}
    if core == "stella2014":
        return {"cpu": ("emucore/M6502.cxx", (("__LIBRETRO__", "1"),)),
                "tia": ("emucore/TIA.cxx", ()),
                "tia_audio": ("emucore/TIASnd.cxx", ())}
    if core == "mednafen_vb":
        return {"v810_cpu": ("hw_cpu/v810/v810_cpu.cpp", (("WANT_VB_EMU", "1"),)),
                "gles2_renderer_option": ("libretro.cpp", (("HAVE_OPENGLES2", "1"), ("HAVE_VR", "1"))),
                "blip_audio": ("sound/Blip_Buffer.c", ())}
    if core == "fceumm":
        return {"cpu": ("x6502.c", ()),
                "ntsc_filter": ("ntsc/nes_ntsc.c", (("HAVE_NTSC_FILTER", "1"),)),
                "audio": ("sound.c", (("PSS_STYLE", "1"),))}
    if core == "mesen":
        return {"cpu": ("Core/CPU.cpp", (("LIBRETRO", "1"),)),
                "ppu": ("Core/PPU.cpp", ()),
                "video_renderer": ("Core/VideoRenderer.cpp", ()),
                "blip_audio": ("Utilities/blip_buf.cpp", ())}
    if core == "bsnes":
        return {"cpu": ("sfc/cpu/cpu.cpp", (("DISABLE_DEBUGGER", "1"),)),
                "fast_ppu": ("sfc/ppu-fast/ppu.cpp", ()),
                "audio_dsp": ("sfc/dsp/dsp.cpp", ()),
                "integrated_gb": ("gb/Core/sm83_cpu.c", (("GB_INTERNAL", "1"),))}
    if core == "sameboy":
        return {"sm83_cpu": ("Core/sm83_cpu.c", (("GB_INTERNAL", "1"),)),
                "libretro": ("libretro/libretro.c", (("GB_INTERNAL", "1"),))}
    if core == "race":
        return {"portable_z80": ("libretro/libretro.c", (("CZ80", "1"),)),
                "blip_audio": ("deps/blip/Blip_Buffer.c", ()),
                "audio": ("neopop_blip.c", ())}
    if core == "mednafen_lynx":
        return {"rgb565_option": ("libretro.cpp", (("FRONTEND_SUPPORTS_RGB565", "1"),)),
                "crc_loader": ("scrc32.c", (("WANT_CRC32", "1"),)),
                "blip_audio": ("sound/Blip_Buffer.cpp", ()),
                "stereo_audio": ("sound/Stereo_Buffer.cpp", ())}
    if core == "snes9x2010":
        result = {"rgb565_renderer": ("libretro/libretro.c", (("FRONTEND_SUPPORTS_RGB565", "1"),))}
        if profile in {"snes9x2010-threaded", "optimized", "optimized-a57"}:
            result["threaded_ppu"] = ("src/ppu.c", (("HAVE_THREADS", "1"),))
            result["threaded_frontend"] = ("libretro/libretro.c", (("HAVE_THREADS", "1"),))
            result["eventcount"] = ("rthreads/retro_eventcount.c", ())
            result["rthreads"] = ("rthreads/rthreads.c", ())
        return result
    if core == "gambatte":
        return {"rgb565_renderer": ("video_libretro.cpp", (("VIDEO_RGB565", "1"),)),
                "resampler_config": ("libretro/libretro.cpp", (("CC_RESAMPLER_NO_HIGHPASS", "1"),))}
    if core == "genesis_plus_gx":
        flags = (("USE_16BPP_RENDERING", "1"), ("FRONTEND_SUPPORTS_RGB565", "1"))
        if abi == "armeabi-v7a":
            flags += (("_ARM_ASSEM_", "1"),)
        return {"native_pixel_path": ("libretro/libretro.c", flags)}
    if core in {"mednafen_pce_fast", "mednafen_supergrafx"}:
        source = "libretro.cpp" if core == "mednafen_supergrafx" else "libretro.c"
        return {"pce_fast_renderer": (source, (("WANT_PCE_FAST_EMU", "1"), ("FRONTEND_SUPPORTS_RGB565", "1")))}
    if core == "mednafen_ngp":
        return {"memory_loader_and_rgb565": ("libretro.c", (("LOAD_FROM_MEMORY", "1"), ("FRONTEND_SUPPORTS_RGB565", "1")))}
    if core == "mednafen_wswan":
        return {"fixed_point_rgb565": ("libretro.c", (("MPC_FIXED_POINT", "1"), ("FRONTEND_SUPPORTS_RGB565", "1")))}
    if core == "gpsp":
        stub = "arm/arm64_stub.S" if arm64 else "arm/arm_stub.S" if arm else "x86/x86_stub.S"
        return {"dynarec": ("cpu_threaded.c", (("HAVE_DYNAREC", "1"), ("MMAP_JIT_CACHE", "1"))),
                "native_stub": (stub, ())}
    if core == "pcsx_rearmed":
        gpu = "psx_gpu/psx_gpu_arm_neon.S" if abi == "armeabi-v7a" else "psx_gpu/psx_gpu_simd.c"
        result = {"gpu_acceleration": ("plugins/gpu_neon/" + gpu, ()),
                  "async_gpu": ("plugins/gpulib/gpu_async.c", (("USE_ASYNC_GPU", "1"),)),
                  "async_cd": ("libpcsxcore/cdrom-async.c", (("USE_ASYNC_CDROM", "1"),))}
        if arm:
            result["dynarec"] = ("new_dynarec/new_dynarec.c", (("NDRC_THREAD", "1"),))
            result["native_stub"] = ("new_dynarec/linkage_arm64.S" if arm64 else "new_dynarec/linkage_arm.S", ())
        else:
            result["dynarec"] = ("lightrec/recompiler.c", (("LIGHTREC", "1"),))
        return result
    if core == "mupen64plus_next_gles3":
        variant = {"x86": "1", "x86_64": "2", "armeabi-v7a": "3", "arm64-v8a": "4"}[abi]
        result = {"dynarec": ("new_dynarec/new_dynarec.c", (("NEW_DYNAREC", variant), ("DYNAREC", "1")))}
        if arm:
            result["parallel_rsp"] = ("parallel.cpp", (("HAVE_PARALLEL_RSP", "1"),))
            result["parallel_rdp"] = ("new_dynarec/new_dynarec.c", (("HAVE_PARALLEL_RDP", "1"),))
        else:
            stub = "x86/linkage_x86.asm" if abi == "x86" else "x64/linkage_x64.asm"
            result["native_stub"] = ("new_dynarec/" + stub, ())
        return result
    if core == "parallel_n64":
        result = {"parallel_rsp": ("mupen64plus-rsp-paraLLEl/parallel.cpp", (("HAVE_PARALLEL_RSP", "1"),)),
                  "parallel_rdp": ("mupen64plus-video-paraLLEl/parallel.c", (("HAVE_PARALLEL", "1"),))}
        if arm:
            result["dynarec"] = ("new_dynarec/new_dynarec.c", (("DYNAREC", "1"), ("NEW_DYNAREC", "4" if arm64 else "3")))
        return result
    if core == "mednafen_psx_hw":
        return {"lightrec": ("lightrec/lightrec.c", (("HAVE_LIGHTREC", "1"),)),
                "threaded_recompiler": ("lightrec/recompiler.c", (("ENABLE_THREADED_COMPILER", "1"), ("HAVE_VULKAN", "1")))}
    if core == "swanstation" and abi != "x86":
        arch = {"arm64-v8a": "aarch64", "armeabi-v7a": "aarch32", "x86_64": "x64"}[abi]
        macros = (("WITH_RECOMPILER", "1"),)
        if abi in {"arm64-v8a", "x86_64"}:
            macros += (("WITH_MMAP_FASTMEM", "1"),)
        return {"dynarec": ("cpu_recompiler_code_generator_" + arch + ".cpp", macros)}
    if core == "picodrive" and (abi == "armeabi-v7a" or profile == "picodrive-sh2" or (abi == "arm64-v8a" and profile in {"optimized", "optimized-a57"})):
        return {"sh2_dynarec": ("cpu/sh2/compiler.c", (("DRC_SH2", "1"),))}
    return {}


def audit_acceleration(commands, core, abi, profile):
    rules = requirements(core, abi, profile)
    checks = {key: evidence(commands, source, macros) for key, (source, macros) in rules.items()}
    if any(not value for value in checks.values()):
        missing = [key for key, value in checks.items() if not value]
        raise ValueError(f"{core}/{abi}: missing compiled acceleration evidence: {missing}")
    if core == "picodrive" and abi == "arm64-v8a" and profile == "baseline":
        # Record the exact baseline/candidate difference without assuming that the
        # presence of an upstream source file means it was actually compiled.
        checks["sh2_dynarec"] = evidence(commands, "cpu/sh2/compiler.c", (("DRC_SH2", "1"),))
    return checks
