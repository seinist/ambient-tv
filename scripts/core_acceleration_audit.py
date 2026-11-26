"""Inspect emitted compiler commands for architecture-specific core acceleration."""
from pathlib import Path
import re
import shlex

DRIVER = re.compile(r"(?:[\w.-]+-)?clang(?:\+\+)?(?:-\d+)?")
SOURCE = re.compile(r"\.(?:c|cc|cpp|cxx|C|s|S|asm)$")
AUDITED = {"gpsp", "pcsx_rearmed", "mupen64plus_next_gles3", "picodrive"}


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


def compiler_commands(log, cwd=None, root=None, strict=False):
    cwd = Path(cwd or Path.cwd()).resolve()
    root = Path(root or Path.cwd()).resolve()
    for line in log.splitlines():
        try:
            tokens = shlex.split(line)
        except ValueError:
            if strict and re.search(r"\bclang(?:\+\+)?\b", line):
                raise ValueError("Malformed compiler command in build log")
            continue
        driver = next((i for i, token in enumerate(tokens)
                       if DRIVER.fullmatch(Path(token).name)), None)
        if driver is None:
            continue
        try:
            args = expand_response(tokens[driver + 1:], cwd, root)
        except (ValueError, OSError):
            if strict:
                raise
            continue
        # Ignore shell segments after the compiler invocation (CMake/Ninja).
        end = next((i for i, token in enumerate(args) if token in {"&&", ";", "||"}), len(args))
        yield args[:end]


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
            result[key] = value or "1"
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
        return result
    if core == "picodrive" and (abi == "armeabi-v7a" or profile == "picodrive-sh2"):
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
