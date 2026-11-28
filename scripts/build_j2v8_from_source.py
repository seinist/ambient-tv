#!/usr/bin/env python3
"""Build a non-publishable J2V8 candidate entirely from pinned sources.

V8's own DEPS selects depot_tools, GN, Clang and Android inputs. Compile
the JNI bridge inside the same GN toolchain as the monolithic V8 library,
avoiding an untracked prebuilt V8 archive or a mixed C++ runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from j2v8_source_audit import SOURCE_PINS, MAX_SO
from module_corresponding_source import elf_text_digest
from prepare_native_module_release import validate_elf
from package_system_modules import native_dependencies, PLATFORM

DEPOT_URL = "https://chromium.googlesource.com/chromium/tools/depot_tools.git"
DEPOT_COMMIT = "a806594b95a39141fdbf1f359087a44ffb2deaaf"
ABI_CONFIGS = {
    "arm64-v8a": "android-arm64", "armeabi-v7a": "android-arm",
    "x86": "android-ia32", "x86_64": "android-x64",
}
BRIDGE = """
if (is_android && current_toolchain == default_toolchain) {
  shared_library("ambient_j2v8") {
    output_name = "j2v8"
    sources = [
      "ambient_j2v8/com_eclipsesource_v8_V8Impl.cpp",
      "ambient_j2v8/com_eclipsesource_v8_V8Impl.h",
    ]
    configs += [ ":external_config" ]
    deps = [ ":v8_monolith" ]
    cflags_cc = [ "-Wno-error", "-Wno-unknown-pragmas" ]
    ldflags = [ "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384" ]
    libs = [ "log", "android", "dl" ]
  }
}
"""


def run(args, cwd=None, env=None, capture=False):
    return subprocess.run([str(x) for x in args], cwd=cwd, env=env, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def checkout(path, url, commit):
    run(["git", "init", "-q", path])
    run(["git", "-C", path, "remote", "add", "origin", url])
    run(["git", "-C", path, "fetch", "--depth=1", "origin", commit])
    run(["git", "-C", path, "checkout", "--detach", "FETCH_HEAD"])
    if run(["git", "-C", path, "rev-parse", "HEAD"], capture=True).strip() != commit:
        raise ValueError(f"Unexpected checkout revision: {path}")


def sha(path):
    # The 2021 V8 inspector generator needs Python <= 3.9. Keep this
    # driver compatible instead of mixing modern and legacy interpreters.
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def modernize_bridge(source):
    """V8 9.3 aliases Handle<T> to Local<T> only without imminent-deprecation warnings.

    GN enables those warnings for the entire V8 build. Keep the toolchain
    configuration intact and migrate the pinned JNI source's obsolete alias.
    """
    updated, count = re.subn(r"\bHandle(?=\s*<)", "Local", source)
    if count != 122:
        raise ValueError(f"Unexpected pinned J2V8 Handle alias count: {count}")
    return updated


def jni_exports(path, readelf="readelf"):
    text = run([readelf, "--dyn-syms", "--wide", path], capture=True)
    symbols = set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[0].endswith(":") and fields[6] != "UND":
            symbol = fields[7].split("@", 1)[0]
            if symbol.startswith("Java_com_eclipsesource_v8_") or symbol in {"JNI_OnLoad", "JNI_OnUnload"}:
                symbols.add(symbol)
    if not any(symbol.startswith("Java_com_eclipsesource_v8_V8_") for symbol in symbols):
        raise ValueError("No J2V8 JNI exports found")
    return symbols


def verify_candidate(candidate, legacy, abi):
    if any(not p.is_file() or p.is_symlink() or not 0 < p.stat().st_size <= MAX_SO for p in (candidate, legacy)):
        raise ValueError("Missing or oversized J2V8 binary")
    data = candidate.read_bytes()
    validate_elf(data, abi)
    needed = native_dependencies(data, "libj2v8.so")
    if set(needed) - PLATFORM:
        raise ValueError(f"J2V8 candidate has non-platform dependencies: {sorted(set(needed) - PLATFORM)}")
    expected = jni_exports(legacy)
    actual = jni_exports(candidate)
    missing = expected - actual
    if missing:
        raise ValueError(f"Missing legacy J2V8 JNI exports: {sorted(missing)}")
    return {"abi": abi, "sha256": sha(candidate), "textSha256": elf_text_digest(data),
            "legacySha256": sha(legacy), "jniExports": sorted(actual),
            "needed": sorted(needed), "matchesLegacy": sha(candidate) == sha(legacy)}


def build(work, output, shared, abi, jobs):
    if sys.version_info[:2] != (3, 9):
        raise ValueError("Use isolated Python 3.9 for the pinned V8 9.3 source build")
    work, output, shared = work.resolve(), output.resolve(), shared.resolve()
    if work.exists() or output.exists() or not 1 <= jobs <= 64:
        raise ValueError("Use fresh source/build/output directories and 1-64 jobs")
    work.mkdir(parents=True)
    depot = work / "depot_tools"
    j2v8 = work / "j2v8"
    v8 = work / "v8"
    checkout(depot, DEPOT_URL, DEPOT_COMMIT)
    checkout(j2v8, *SOURCE_PINS["j2v8"][:2])
    checkout(v8, *SOURCE_PINS["v8"][:2])
    deps = (v8 / "DEPS").read_text()
    if not re.search(r"/chromium/tools/depot_tools[.]git['\"]\s*\+\s*['\"]@['\"]\s*\+\s*['\"]" + DEPOT_COMMIT + r"['\"]", deps):
        raise ValueError("Pinned depot_tools revision differs from V8 DEPS")
    env = {**os.environ, "PATH": str(depot) + os.pathsep + os.environ["PATH"],
           "DEPOT_TOOLS_UPDATE": "0", "DEPOT_TOOLS_METRICS": "0"}
    (work / ".gclient").write_text("solutions = " + repr([{
        "name": "v8", "url": SOURCE_PINS["v8"][0], "managed": False,
        "custom_deps": {}, "custom_vars": {}
    }]) + "\ntarget_os = ['android']\n")
    run([depot / "gclient", "sync", "--no-history", "--revision",
         "v8@" + SOURCE_PINS["v8"][1]], cwd=work, env=env)
    if run(["git", "-C", v8, "rev-parse", "HEAD"], capture=True).strip() != SOURCE_PINS["v8"][1]:
        raise ValueError("V8 sync changed the pinned revision")
    actual_deps = run([depot / "gclient", "revinfo", "--actual"], cwd=work, env=env, capture=True)
    if not actual_deps or SOURCE_PINS["v8"][1] not in actual_deps:
        raise ValueError("Missing resolved V8 dependency inventory")
    bridge = v8 / "ambient_j2v8"
    bridge.mkdir()
    cpp = "com_eclipsesource_v8_V8Impl.cpp"
    original_bridge = (j2v8 / "jni" / cpp).read_text()
    (bridge / cpp).write_text(modernize_bridge(original_bridge))
    shutil.copyfile(j2v8 / "jni/com_eclipsesource_v8_V8Impl.h",
                    bridge / "com_eclipsesource_v8_V8Impl.h")
    build_file = v8 / "BUILD.gn"
    build_file.write_text(build_file.read_text() + BRIDGE)
    out = v8 / "out" / abi
    out.mkdir(parents=True)
    args = (j2v8 / "v8" / ABI_CONFIGS[abi] / "args.gn").read_text()
    (out / "args.gn").write_text(args)
    run([depot / "gn", "gen", out], cwd=v8, env=env)
    run([depot / "ninja", "-C", out, "-j", jobs, "ambient_j2v8"], cwd=v8, env=env)
    candidate = out / "libj2v8.so"
    legacy = shared / f"j2v8/src/main/jniLibs/{abi}/libj2v8.so"
    row = verify_candidate(candidate, legacy, abi)
    output.mkdir(parents=True)
    shutil.copyfile(candidate, output / "libj2v8.so")
    (output / "args.gn").write_text(args)
    (output / "bridge.gn").write_text(BRIDGE)
    shutil.copyfile(bridge / cpp, output / cpp)
    shutil.copyfile(bridge / "com_eclipsesource_v8_V8Impl.h", output / "com_eclipsesource_v8_V8Impl.h")
    (output / "v8-deps.txt").write_text(actual_deps)
    shutil.copyfile(v8 / "DEPS", output / "V8-DEPS")
    (output / "source-build.json").write_text(json.dumps({
        "schema": 1, "kind": "experimental-source-build", "j2v8Commit": SOURCE_PINS["j2v8"][1],
        "v8Commit": SOURCE_PINS["v8"][1], "depotToolsCommit": DEPOT_COMMIT,
        "pythonVersion": sys.version.split()[0],
        "depsSha256": sha(output / "V8-DEPS"), "resolvedDepsSha256": sha(output / "v8-deps.txt"),
        "argsSha256": sha(output / "args.gn"), "bridgeSha256": sha(output / "bridge.gn"),
        "jniSourceSha256": sha(output / cpp),
        "jniHeaderSha256": sha(output / "com_eclipsesource_v8_V8Impl.h"),
        "jniCompatibilityPatch": "v8-handle-to-local-122",
        "native": row, "deviceValidated": False, "publicationApproved": False,
    }, indent=2, sort_keys=True) + "\n")


def stage_candidates(inputs, output, shared):
    """Prepare an isolated JNI directory from all four same-run candidates.

    Never modify the legacy source checkout. Repeat validation instead of
    trusting artifact filenames or their self-reported ABI and hashes.
    """
    if output.exists():
        raise ValueError("Candidate staging directory must be fresh")
    verified = {}
    for abi in ABI_CONFIGS:
        root = inputs / f"experimental-j2v8-source-{abi}"
        evidence_path = root / "source-build.json"
        if not evidence_path.is_file() or evidence_path.is_symlink() or evidence_path.stat().st_size > 512 * 1024:
            raise ValueError(f"Missing source-build evidence for {abi}")
        evidence = json.loads(evidence_path.read_text())
        if (evidence.get("schema") != 1 or evidence.get("kind") != "experimental-source-build"
                or evidence.get("j2v8Commit") != SOURCE_PINS["j2v8"][1]
                or evidence.get("v8Commit") != SOURCE_PINS["v8"][1]
                or evidence.get("depotToolsCommit") != DEPOT_COMMIT
                or evidence.get("deviceValidated") is not False
                or evidence.get("publicationApproved") is not False):
            raise ValueError(f"Invalid source-build identity for {abi}")
        for name, field in (("V8-DEPS", "depsSha256"), ("v8-deps.txt", "resolvedDepsSha256"),
                            ("args.gn", "argsSha256"), ("bridge.gn", "bridgeSha256"),
                            ("com_eclipsesource_v8_V8Impl.cpp", "jniSourceSha256"),
                            ("com_eclipsesource_v8_V8Impl.h", "jniHeaderSha256")):
            path = root / name
            if (not path.is_file() or path.is_symlink() or not 0 < path.stat().st_size <= 16 * 1024**2
                    or sha(path) != evidence.get(field)):
                raise ValueError(f"Build evidence mismatch: {abi}/{name}")
        if (evidence.get("jniCompatibilityPatch") != "v8-handle-to-local-122"
                or re.search(r"\bHandle\s*<", (root / "com_eclipsesource_v8_V8Impl.cpp").read_text())):
            raise ValueError(f"Unexpected JNI source compatibility patch: {abi}")
        if (root / "bridge.gn").read_text() != BRIDGE:
            raise ValueError(f"Unexpected JNI build recipe: {abi}")
        candidate = root / "libj2v8.so"
        legacy = shared / f"j2v8/src/main/jniLibs/{abi}/libj2v8.so"
        if verify_candidate(candidate, legacy, abi) != evidence.get("native"):
            raise ValueError(f"J2V8 candidate differs from build evidence: {abi}")
        verified[abi] = candidate
    output.mkdir(parents=True)
    for abi, candidate in verified.items():
        dest = output / abi
        dest.mkdir()
        shutil.copyfile(candidate, dest / "libj2v8.so")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABI_CONFIGS)
    parser.add_argument("--work", type=Path)
    parser.add_argument("--stage", type=Path, help="Directory containing all four CI artifacts")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--shared", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.stage:
        if args.work or args.abi:
            parser.error("--stage is exclusive with --work and --abi")
        stage_candidates(args.stage, args.output, args.shared)
    else:
        if not args.work or not args.abi:
            parser.error("build requires --work and --abi")
        build(args.work, args.output, args.shared, args.abi, args.jobs)


if __name__ == "__main__":
    main()
