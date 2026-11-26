"""Reproducible Libretro build experiments; never publish an unvalidated candidate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

PROFILES = {
    "baseline": ((), ()),
    "o3": (("-O3",), ()),
    "thinlto": (("-flto=thin",), ("-flto=thin",)),
    "o3-thinlto": (("-O3", "-flto=thin"), ("-flto=thin",)),
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


def plan(catalog, event, ref, publish, cores="", abis="", profile="baseline"):
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
    chosen_cores = selection(cores, ids, "core")
    chosen_abis = selection(abis, declared_abis, "ABI")
    profiles = [profile]
    if event == "pull_request":
        if publish:
            raise ValueError("PRs may never publish cores")
        # Exercise one CMake and one ndk-build project on both ARM ABIs.
        chosen_cores = selection("mgba,snes9x", ids, "core")
        chosen_abis = selection("arm64-v8a,armeabi-v7a", declared_abis, "ABI")
        profiles = ["baseline", "thinlto"]
    if event == "push" or publish:
        if ref != "refs/heads/main" or profile != "baseline" or set(chosen_cores) != set(ids) or set(chosen_abis) != set(declared_abis):
            raise ValueError("Release requires the full baseline catalog on main; use publish=false for experiments.")
    matrix = []
    for abi in chosen_abis:
        for entry in entries:
            if entry["core"] not in chosen_cores:
                continue
            for variant in profiles:
                cflags, ldflags = PROFILES[variant]
                matrix.append({**entry, "abi": abi, "profile": variant,
                               "cflags": " ".join(cflags), "ldflags": " ".join(ldflags),
                               "artifact": ("core" if variant == "baseline" else "candidate-" + variant)
                                           + "-" + entry["core"] + "-" + abi})
    if not matrix or len(matrix) > 256:
        raise ValueError("Build matrix is empty or exceeds the Actions limit")
    return {"include": matrix}


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


def audit_commands(log, profile):
    """Check actual verbose driver invocations, not only requested CMake/Make settings.

    Assembly and non-compiler commands are deliberately ignored. If response files hide
    candidate flags, fail closed; the corresponding recipe needs explicit support.
    """
    compile_count = 0
    link_count = 0
    opt_levels = {}
    thin_compiles = 0
    thin_links = 0
    for line in log.splitlines():
        try:
            tokens = shlex.split(line)
        except ValueError:
            continue
        driver = next((i for i, token in enumerate(tokens)
                       if re.fullmatch(r"(?:[\w.-]+-)?clang(?:\+\+)?(?:-\d+)?", Path(token).name)), None)
        if driver is None:
            continue
        args = tokens[driver + 1:]
        if "-c" in args and any(re.search(r"\.(?:c|cc|cpp|cxx|C)$", token) for token in args):
            compile_count += 1
            levels = [token for token in args if re.fullmatch(r"-O(?:[0-3szg]|fast)?", token)]
            level = levels[-1] if levels else "(implicit)"
            opt_levels[level] = opt_levels.get(level, 0) + 1
            lto = [token for token in args if token.startswith("-flto") or token == "-fno-lto"]
            thin_compiles += bool(lto and lto[-1] == "-flto=thin")
        elif "-shared" in args:
            link_count += 1
            lto = [token for token in args if token.startswith("-flto") or token == "-fno-lto"]
            thin_links += bool(lto and lto[-1] == "-flto=thin")
    if profile != "baseline" and (not compile_count or not link_count):
        raise ValueError("Candidate lacks auditable C/C++ compile or shared link commands")
    if "o3" in profile and opt_levels != {"-O3": compile_count}:
        raise ValueError(f"Core overrides requested -O3: {opt_levels}")
    if "thinlto" in profile and (thin_compiles != compile_count or thin_links != link_count):
        raise ValueError(f"ThinLTO not effective everywhere: compile {thin_compiles}/{compile_count}, link {thin_links}/{link_count}")
    return {"compile_commands": compile_count, "shared_link_commands": link_count,
            "optimization_levels": opt_levels, "thinlto_compiles": thin_compiles,
            "thinlto_links": thin_links}


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
    p.add_argument("--binary", type=Path, required=True)
    p.add_argument("--log", type=Path, required=True)
    p.add_argument("--profile", choices=PROFILES, required=True)
    p.add_argument("--core", required=True)
    p.add_argument("--abi", required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--ndk", required=True)
    p.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "plan":
        print(json.dumps(plan(json.loads(args.catalog.read_text()), args.event, args.ref,
                              args.publish == "true", args.cores, args.abis, args.profile),
                         separators=(",", ":")))
    elif args.action == "ndk-flags":
        ndk_flags(args.application, args.profile, args.cppflags)
    elif args.action == "select":
        print(select_library(args.nm, args.root, args.pattern))
    elif args.action == "report":
        symbols = exports(args.nm, args.binary)
        missing = REQUIRED_EXPORTS - symbols
        if missing:
            raise ValueError(f"Missing Libretro exports: {sorted(missing)}")
        report = {"core": args.core, "abi": args.abi, "commit": args.commit,
                  "ndk": args.ndk, "profile": args.profile,
                  "requested_cflags": PROFILES[args.profile][0],
                  "requested_ldflags": PROFILES[args.profile][1],
                  "binary_sha256": file_digest(args.binary),
                  "binary_bytes": args.binary.stat().st_size,
                  "export_count": len(symbols),
                  **audit_commands(args.log.read_text(errors="replace"), args.profile)}
        args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Core build policy: {error}")
