"""Reproducible Libretro build experiments; never publish an unvalidated candidate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from core_acceleration_audit import AUDITED, audit_acceleration, compiler_commands, compile_source
from core_link_evidence import target_commands
from core_hotpath_patches import prepare_hotpaths, SUPPORTED as HOTPATH_CORES
import subprocess
import struct
import sys

PROFILES = {
    "baseline": ((), ()),
    "o3": (("-O3",), ()),
    "thinlto": (("-flto=thin",), ("-flto=thin",)),
    "o3-thinlto": (("-O3", "-flto=thin"), ("-flto=thin",)),
    "tune-a57": (("-mtune=cortex-a57",), ()),
    "picodrive-sh2": ((), ()),
    "snes9x2010-threaded": ((), ()),
    "core-hotpaths": ((), ()),
}
ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
REQUIRED_EXPORTS = {
    "retro_init", "retro_deinit", "retro_api_version", "retro_get_system_info",
    "retro_get_system_av_info", "retro_set_environment", "retro_set_video_refresh",
    "retro_set_audio_sample", "retro_set_audio_sample_batch", "retro_set_input_poll",
    "retro_set_input_state", "retro_set_controller_port_device", "retro_reset",
    "retro_run", "retro_serialize_size", "retro_serialize", "retro_unserialize",
    "retro_cheat_reset", "retro_cheat_set", "retro_load_game",
    "retro_load_game_special", "retro_unload_game", "retro_get_region",
    "retro_get_memory_data", "retro_get_memory_size",
}


def selection(raw, available, label):
    if not raw.strip():
        return list(available)
    values = [part.strip() for part in raw.split(",")]
    if any(not value or value not in available for value in values):
        raise ValueError(f"Unknown or empty {label}: {raw}")
    if len(set(values)) != len(values):
        raise ValueError(f"Duplicate {label}: {raw}")
    return values


def plan(catalog, event, ref, publish, cores="", abis="", profile="baseline", comparison="none"):
    if profile not in PROFILES:
        raise ValueError("Unknown build profile")
    declared_abis = catalog["abis"]
    if not declared_abis or len(set(declared_abis)) != len(declared_abis) or not set(declared_abis) <= ABIS:
        raise ValueError("Invalid ABI catalog")
    entries = catalog["cores"]
    ids = [entry["core"] for entry in entries]
    if not ids or len(set(ids)) != len(ids):
        raise ValueError("Empty or duplicate core catalog")
    for entry in entries:
        if not re.fullmatch(r"[a-z0-9_]+", entry["core"]):
            raise ValueError("Unsafe core id")
        if not re.fullmatch(r"https://github\.com/[\w.-]+/[\w.-]+", entry["repo"]):
            raise ValueError("Invalid upstream repository")
        if not re.fullmatch(r"[0-9a-f]{40}", entry["commit"]):
            raise ValueError("Expected a full pinned commit")
        if entry["build"] not in {"ndk", "cmake"}:
            raise ValueError("Unknown build system")
        if entry["build"] == "ndk":
            jni = Path(entry["jni"])
            if jni.is_absolute() or ".." in jni.parts:
                raise ValueError("Unsafe JNI path")
    if event not in {"push", "pull_request", "workflow_dispatch"}:
        raise ValueError("Unsupported build event")
    if comparison not in {"none", "baseline-thinlto", "o3-o3-thinlto"}:
        raise ValueError("Unknown comparison matrix")
    if comparison != "none" and (event != "workflow_dispatch" or publish or profile != "baseline"):
        raise ValueError("Paired comparisons require an artifact-only manual dispatch and baseline profile")
    # Narrow specialized experiments instead of silently applying them to unrelated cores.
    if profile == "picodrive-sh2":
        if event != "workflow_dispatch" or publish:
            raise ValueError("PicoDrive SH2 candidate is artifact-only")
        cores = cores or "picodrive"
        abis = abis or "arm64-v8a"
    if profile == "core-hotpaths":
        if event != "workflow_dispatch" or publish:
            raise ValueError("Source hotpath candidates are artifact-only")
        cores = cores or ",".join(sorted(HOTPATH_CORES))
    if profile == "snes9x2010-threaded":
        if event != "workflow_dispatch" or publish:
            raise ValueError("Snes9x2010 threaded renderer candidate is artifact-only")
        cores = cores or "snes9x2010"
    if profile == "tune-a57":
        abis = abis or "arm64-v8a"
    chosen_cores = selection(cores, ids, "core")
    chosen_abis = selection(abis, declared_abis, "ABI")
    if profile == "picodrive-sh2" and (chosen_cores != ["picodrive"] or chosen_abis != ["arm64-v8a"]):
        raise ValueError("PicoDrive SH2 candidate requires PicoDrive on arm64-v8a")
    if profile == "core-hotpaths" and not set(chosen_cores) <= HOTPATH_CORES:
        raise ValueError("No reviewed source hotpath patch for selected core")
    if profile == "snes9x2010-threaded" and chosen_cores != ["snes9x2010"]:
        raise ValueError("Threaded renderer candidate requires Snes9x2010")
    if profile == "tune-a57" and chosen_abis != ["arm64-v8a"]:
        raise ValueError("Cortex-A57 tuning is initially restricted to arm64-v8a")
    if event == "push" or publish:
        if ref != "refs/heads/main" or profile != "baseline" or set(chosen_cores) != set(ids) or set(chosen_abis) != set(declared_abis):
            raise ValueError("Release requires the full baseline catalog on main; use publish=false for experiments.")
    if event == "pull_request":
        if publish:
            raise ValueError("PRs may never publish cores")
        # Baseline/ThinLTO plus real dynarec/SIMD smoke builds and two narrow candidates.
        jobs = [(core, abi, variant)
                for core in ("mgba", "snes9x")
                for abi in ("arm64-v8a", "armeabi-v7a")
                for variant in ("baseline", "thinlto")]
        jobs += [(core, abi, "baseline")
                 for core in ("gpsp", "pcsx_rearmed")
                 for abi in ("arm64-v8a", "armeabi-v7a")]
        jobs += [("mupen64plus_next_gles3", "arm64-v8a", "baseline"),
                 ("picodrive", "arm64-v8a", "baseline"),
                 ("picodrive", "arm64-v8a", "picodrive-sh2"),
                 ("snes9x", "arm64-v8a", "tune-a57")]
        # Fast PR gate: exercise both toolchains and all audited Android backends.
        jobs += [(core, "arm64-v8a", variant)
                 for core in ("snes9x", "mgba")
                 for variant in ("o3", "o3-thinlto")]
        jobs += [(core, "arm64-v8a", "baseline")
                 for core in ("parallel_n64", "mednafen_psx_hw", "swanstation")]
        jobs += [(core, "arm64-v8a", variant) for core in sorted(HOTPATH_CORES)
                 for variant in ("baseline", "core-hotpaths")]
        jobs += [("pcsx_rearmed", "x86_64", "baseline"),
                 ("gpsp", "x86", "baseline"),
                 ("snes9x2010", "arm64-v8a", "baseline"),
                 ("snes9x2010", "arm64-v8a", "snes9x2010-threaded")]
    elif comparison != "none":
        variants = ("baseline", "thinlto") if comparison == "baseline-thinlto" else ("o3", "o3-thinlto")
        jobs = [(core, abi, variant) for core in chosen_cores for abi in chosen_abis for variant in variants]
    else:
        jobs = [(core, abi, profile) for core in chosen_cores for abi in chosen_abis]
    # A baseline may already be included in the targeted smoke matrix.
    jobs = list(dict.fromkeys(jobs))
    by_id = {entry["core"]: entry for entry in entries}
    matrix = []
    for core, abi, variant in jobs:
        if core not in by_id or abi not in declared_abis:
            raise ValueError(f"Missing required CI recipe: {core}/{abi}")
        entry = by_id[core]
        cflags, ldflags = PROFILES[variant]
        matrix.append({**entry, "abi": abi, "profile": variant,
                       "cflags": " ".join(cflags), "ldflags": " ".join(ldflags),
                       "makevars": "use_sh2drc=1" if variant == "picodrive-sh2" else "",
                       "artifact": ("core" if variant == "baseline" else "candidate-" + variant)
                                   + "-" + core + "-" + abi})
    if not matrix or len(matrix) > 256:
        raise ValueError("Build matrix is empty or exceeds the Actions limit")
    return {"include": matrix}


# ELF e_machine, ELFCLASS, and little-endian Android ABI contract.
ELF_ABIS = {"arm64-v8a": (183, 2), "armeabi-v7a": (40, 1),
            "x86_64": (62, 2), "x86": (3, 1)}


def verify_elf_abi(binary, abi):
    with binary.open("rb") as stream:
        header = stream.read(64)
    machine, elfclass = ELF_ABIS[abi]
    size = 64 if elfclass == 2 else 52
    if len(header) < size or header[:4] != b"\x7fELF" or header[4:7] != bytes((elfclass, 1, 1)):
        raise ValueError(f"{binary}: invalid ELF class, byte order or identification for {abi}")
    kind, actual, version = struct.unpack_from("<HHI", header, 16)
    if kind != 3 or actual != machine or version != 1:
        raise ValueError(f"{binary}: expected ELF shared object for {abi}; type={kind}, machine={actual}")
    return {"elf_class": elfclass * 32, "elf_machine": actual}


# Public NDK libraries (API <= 29) and the C++ runtime already packaged by
# Ambient TV. A downloaded core is a single ELF, not an arbitrary dependency bundle.
ALLOWED_NEEDED = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libz.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so",
    "libvulkan.so", "libOpenSLES.so", "libaaudio.so", "libjnigraphics.so",
    "libmediandk.so", "libnativewindow.so", "libsync.so", "libc++_shared.so",
}


def dynamic_dependencies(readelf, binary):
    result = subprocess.check_output([readelf, "--dynamic", str(binary)], text=True)
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", result)
    if len(needed) != len(re.findall(r"\(NEEDED\)", result)):
        raise ValueError(f"{binary}: unrecognized dynamic dependency format")
    unknown = set(needed) - ALLOWED_NEEDED
    if unknown:
        raise ValueError(f"{binary}: unbundled or non-NDK shared dependencies: {sorted(unknown)}")
    if re.search(r"\((?:RPATH|RUNPATH|TEXTREL)\)", result):
        raise ValueError(f"{binary}: unexpected runtime search path or text relocation")
    return sorted(set(needed))


def exports(nm, binary):
    lines = subprocess.check_output([nm, "-D", "--defined-only", str(binary)], text=True).splitlines()
    return {line.split()[-1].split("@")[0] for line in lines if line.split()}


def select_library(nm, root, pattern):
    matches = []
    for candidate in sorted({path.resolve() for path in root.rglob(pattern) if path.is_file()}):
        if REQUIRED_EXPORTS <= exports(nm, candidate):
            matches.append(candidate)
    # CMake may leave byte-identical copies in separate output directories.
    unique = {}
    for path in matches:
        unique.setdefault(file_digest(path), path)
    if len(unique) != 1:
        raise ValueError(f"Expected exactly one distinct complete Libretro ELF under {root}; found {matches}")
    return next(iter(unique.values()))


def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def audit_commands(log, profile, cwd=None, root=None, core=None, abi=None, scoped_commands=None):
    """Check actual verbose driver invocations, not only requested CMake/Make settings.

    Assembly and non-compiler commands are deliberately ignored. If response files hide
    candidate flags, fail closed; the corresponding recipe needs explicit support.
    """
    compile_count = 0
    link_count = 0
    opt_levels = {}
    thin_compiles = 0
    thin_links = 0
    compile_lto_modes = {}
    link_lto_modes = {}
    shared_outputs = []
    commands = (list(compiler_commands(log, cwd, root, strict=profile != "baseline" or core in AUDITED))
                if scoped_commands is None else scoped_commands)
    tuning_count = 0
    codegen_flags = {}
    tracked_flags = {"-funroll-loops", "-fno-unroll-loops", "-ffast-math", "-fno-fast-math", "-fomit-frame-pointer", "-fno-omit-frame-pointer", "-marm", "-mthumb"}
    for args in commands:
        source = compile_source(args)
        if source and re.search(r"\.(?:c|cc|cpp|cxx|C)$", source):
            compile_count += 1
            for flag in set(args):
                if flag in tracked_flags or flag.startswith(("-march=", "-mcpu=", "-mtune=", "-mfpu=")):
                    codegen_flags[flag] = codegen_flags.get(flag, 0) + 1
            levels = [token for token in args if re.fullmatch(r"-O(?:[0-3szg]|fast)?", token)]
            level = levels[-1] if levels else "(implicit)"
            opt_levels[level] = opt_levels.get(level, 0) + 1
            lto = [token for token in args if token.startswith("-flto") or token == "-fno-lto"]
            thin_compiles += bool(lto and lto[-1] == "-flto=thin")
            mode = lto[-1] if lto else "(implicit)"
            compile_lto_modes[mode] = compile_lto_modes.get(mode, 0) + 1
            tune = [token for token in args if token.startswith("-mtune=")]
            tuning_count += bool(tune and tune[-1] == "-mtune=cortex-a57")
        elif "-shared" in args:
            link_count += 1
            lto = [token for token in args if token.startswith("-flto") or token == "-fno-lto"]
            thin_links += bool(lto and lto[-1] == "-flto=thin")
            mode = lto[-1] if lto else "(implicit)"
            link_lto_modes[mode] = link_lto_modes.get(mode, 0) + 1
            output = args[args.index("-o") + 1] if "-o" in args and args.index("-o") + 1 < len(args) else None
            shared_outputs.append({"output": output, "lto": mode})
    if profile != "baseline" and (not compile_count or not link_count):
        raise ValueError("Candidate lacks auditable C/C++ compile or shared link commands")
    if "o3" in profile and opt_levels != {"-O3": compile_count}:
        raise ValueError(f"Core overrides requested -O3: {opt_levels}")
    if "thinlto" in profile and (thin_compiles != compile_count or thin_links != link_count):
        raise ValueError(f"ThinLTO not effective everywhere: compile {thin_compiles}/{compile_count}, link {thin_links}/{link_count}")
    if profile == "tune-a57" and tuning_count != compile_count:
        raise ValueError(f"Cortex-A57 tuning not effective: {tuning_count}/{compile_count}")
    acceleration = audit_acceleration(commands, core, abi, profile) if core else {}
    return {"compile_commands": compile_count, "shared_link_commands": link_count,
            "optimization_levels": opt_levels, "codegen_flag_occurrences": codegen_flags, "thinlto_compiles": thin_compiles,
            "thinlto_links": thin_links, "cortex_a57_compiles": tuning_count,
            "compile_lto_modes": compile_lto_modes, "link_lto_modes": link_lto_modes,
            "shared_link_outputs": shared_outputs, "acceleration": acceleration}


def prepare_cmake(root, core, profile):
    # SwanStation's pin sets IPO unconditionally for Release, overriding cache flags.
    # Preserve the upstream baseline. In explicit candidates disable only the
    # automatic IPO block; the workflow supplies the requested LTO mode itself.
    if core != "swanstation" or profile == "baseline":
        return
    path = root / "CMakeLists.txt"
    source = path.read_text()
    marker = '# Enable LTO/LTCG on Release builds.\nif(CMAKE_BUILD_TYPE MATCHES "Release")'
    if source.count(marker) != 1 or source.count("set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)") != 1:
        raise ValueError("SwanStation IPO recipe changed; inspect the pinned CMakeLists.txt")
    source = source.replace(marker, '# Explicit Ambient TV experiment: flags are provided by the build policy.\nif(FALSE)')
    path.write_text(source)


def prepare_ndk(root, core, profile):
    if profile != "snes9x2010-threaded":
        return
    if core != "snes9x2010":
        raise ValueError("Threaded renderer recipe requires Snes9x2010")
    path = root / "jni" / "Android.mk"
    source = path.read_text()
    marker = "include $(CORE_DIR)/build/Makefile.common"
    flags = "LOCAL_CFLAGS   := $(COREFLAGS)"
    common = (root / "build" / "Makefile.common").read_text()
    if (source.count(marker) != 1 or source.count(flags) != 1
            or "rthreads/retro_eventcount.c" not in common
            or "rthreads/rthreads.c" not in common
            or "ifneq ($(HAVE_THREADS),0)" not in common):
        raise ValueError("Snes9x2010 threading recipe changed; inspect pinned source")
    # The upstream common recipe adds HAVE_THREADS to CFLAGS, but Android.mk
    # assigns COREFLAGS to LOCAL_CFLAGS and never forwards that define.
    # Explicitly build the upstream opt-in renderer; its core option stays off.
    source = source.replace(marker, "HAVE_THREADS := 1\n" + marker)
    source = source.replace(flags, flags + " -DHAVE_THREADS")
    path.write_text(source)


def ndk_flags(path, profile, cppflags):
    # Keep upstream flags; append rather than replace APP_LDFLAGS from Application.mk.
    cflags, ldflags = PROFILES[profile]
    with path.open("a", encoding="utf-8") as stream:
        stream.write("\n# Ambient TV reproducible core build\n")
        if cppflags:
            stream.write(f"APP_CPPFLAGS += {cppflags}\n")
        if cflags:
            stream.write(f"APP_CFLAGS += {' '.join(cflags)}\n")
        stream.write(f"APP_LDFLAGS += -Wl,-z,max-page-size=16384 {' '.join(ldflags)}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--catalog", type=Path, required=True)
    p.add_argument("--event", required=True)
    p.add_argument("--ref", required=True)
    p.add_argument("--publish", choices=["true", "false", ""], default="")
    p.add_argument("--cores", default="")
    p.add_argument("--abis", default="")
    p.add_argument("--profile", choices=PROFILES, default="baseline")
    p.add_argument("--comparison", choices=("none", "baseline-thinlto", "o3-o3-thinlto"), default="none")
    p = sub.add_parser("prepare-cmake")
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--core", required=True)
    p.add_argument("--profile", choices=PROFILES, required=True)
    p = sub.add_parser("prepare-hotpaths")
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--core", required=True)
    p = sub.add_parser("prepare-ndk")
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--core", required=True)
    p.add_argument("--profile", choices=PROFILES, required=True)
    p = sub.add_parser("ndk-flags")
    p.add_argument("--application", type=Path, required=True)
    p.add_argument("--profile", choices=PROFILES, required=True)
    p.add_argument("--cppflags", default="")
    p = sub.add_parser("select")
    p.add_argument("--nm", required=True)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--pattern", required=True)
    p = sub.add_parser("report")
    p.add_argument("--nm", required=True)
    p.add_argument("--readelf", required=True)
    p.add_argument("--binary", type=Path, required=True)
    p.add_argument("--linked-library", type=Path, required=True)
    p.add_argument("--log", type=Path, required=True)
    p.add_argument("--profile", choices=PROFILES, required=True)
    p.add_argument("--core", required=True)
    p.add_argument("--abi", required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--ndk", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--build-cwd", type=Path, default=Path.cwd())
    args = parser.parse_args()
    if args.action == "plan":
        print(json.dumps(plan(json.loads(args.catalog.read_text()), args.event, args.ref,
                              args.publish == "true", args.cores, args.abis, args.profile, args.comparison),
                         separators=(",", ":")))
    elif args.action == "prepare-cmake":
        prepare_cmake(args.root, args.core, args.profile)
    elif args.action == "prepare-hotpaths":
        prepare_hotpaths(args.root, args.core)
    elif args.action == "prepare-ndk":
        prepare_ndk(args.root, args.core, args.profile)
    elif args.action == "ndk-flags":
        ndk_flags(args.application, args.profile, args.cppflags)
    elif args.action == "select":
        print(select_library(args.nm, args.root, args.pattern))
    elif args.action == "report":
        symbols = exports(args.nm, args.binary)
        missing = REQUIRED_EXPORTS - symbols
        if missing:
            raise ValueError(f"Missing Libretro exports: {sorted(missing)}")
        identity = verify_elf_abi(args.binary, args.abi)
        dependencies = dynamic_dependencies(args.readelf, args.binary)
        build_log = args.log.read_text(errors="replace")
        scoped, link_evidence = target_commands(build_log, args.build_cwd, Path.cwd(), args.linked_library)
        if args.profile != "baseline" and link_evidence["unresolved_local_inputs"]:
            raise ValueError(f"Candidate has unaudited local linker inputs: {link_evidence['unresolved_local_inputs']}")
        report = {**identity, **link_evidence, "needed_libraries": dependencies, "core": args.core, "abi": args.abi, "commit": args.commit,
                  "ndk": args.ndk, "profile": args.profile,
                  "requested_cflags": PROFILES[args.profile][0],
                  "requested_ldflags": PROFILES[args.profile][1],
                  "binary_sha256": file_digest(args.binary),
                  "binary_bytes": args.binary.stat().st_size,
                  "export_count": len(symbols),
                  **audit_commands(build_log, args.profile,
                                   args.build_cwd, Path.cwd(), args.core, args.abi, scoped)}
        args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Core build policy: {error}")
