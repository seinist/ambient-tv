"""Plan incremental signed core releases against verified publication history."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HEX = re.compile(r"[0-9a-f]{64}")
SHARED = ("core_build_policy.py", "core_acceleration_audit.py",
          "core_link_evidence.py", "check_elf_pages.py")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def key(entry):
    return entry["core"], entry["abi"]


def syntax(path):
    return ast.parse(path.read_text()).body


def semantic(nodes):
    return digest(ast.dump(ast.Module(body=nodes, type_ignores=[]), include_attributes=False).encode())


def named(node):
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return node.name
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
        return node.targets[0].id
    return None


def policy_fingerprint(job, root):
    nodes = syntax(root / "scripts/core_build_policy.py")
    # Planner output is already included as the complete selected job.
    # Shared verification and dispatch code remains a conservative dependency.
    isolated = {"prepare_mupen_offsets", "prepare_snes_threading",
                "prepare_citra_cmake", "prepare_panda_cmake", "prepare_swan_cmake"}
    omitted = isolated | {"plan", "selection", "PROFILES"}
    common = [n for n in nodes if named(n) not in omitted]
    selected = set()
    profile, core = job["profile"], job["core"]
    if core == "citra2018":
        selected.add("prepare_citra_cmake")
    if profile in {"o3", "thinlto", "o3-thinlto", "pgo-generate", "pgo-use", "optimized", "optimized-a57"}:
        if core == "panda3ds":
            selected.add("prepare_panda_cmake")
        elif core == "swanstation":
            selected.add("prepare_swan_cmake")
    if core == "mupen64plus_next_gles3" and "-flto=thin" in job["cflags"].split():
        selected.add("prepare_mupen_offsets")
    if core == "snes9x2010" and profile in {"snes9x2010-threaded", "optimized", "optimized-a57", "pgo-generate", "pgo-use"}:
        selected.add("prepare_snes_threading")
    helpers = [n for n in nodes if named(n) in selected]
    if {named(n) for n in helpers} != selected:
        raise ValueError("Missing selected core recipe in fingerprint")
    return semantic(common + helpers)


def hotpath_fingerprint(core, root):
    primary = syntax(root / "scripts/core_hotpath_patches.py")
    remaining = syntax(root / "scripts/core_hotpath_remaining.py")
    tables = [n for n in remaining if named(n) == "PATCHES"]
    if len(tables) != 1 or not isinstance(tables[0].value, ast.Dict):
        raise ValueError("Unexpected core patch table")
    table = tables[0].value
    entries = {ast.literal_eval(k): v for k, v in zip(table.keys, table.values)}
    # Common patch execution is shared; only the selected entry's source
    # replacement data is hashed, never the entire unrelated PATCHES table.
    common = [n for n in primary if named(n) in {"replace_once", "prepare_hotpaths", "patched_sources"} or isinstance(n, (ast.Import, ast.ImportFrom))]
    if core in entries:
        return digest(json.dumps({"dispatch": semantic(common),
                                  "executor": semantic([n for n in remaining if named(n) != "PATCHES"]),
                                  "patch": ast.dump(entries[core], include_attributes=False)}, sort_keys=True).encode())
    prefixes = {"bsnes": ("BSNES_", "prepare_bsnes"), "mesen": ("MESEN_",),
                "sameboy": ("SAMEBOY_",), "mednafen_pce_fast": ("PCE_",),
                "mednafen_supergrafx": ("SGX_",),
                "mednafen_ngp": ("FIXED_", "NGP_", "prepare_fixed_states"),
                "mednafen_wswan": ("FIXED_", "prepare_fixed_states"),
                "mednafen_vb": ("FIXED_", "prepare_fixed_states"),
                "mednafen_lynx": ("FIXED_", "prepare_fixed_states")}
    if core in prefixes:
        return semantic(common + [n for n in primary if named(n) and named(n).startswith(prefixes[core])])
    # No current patch (e.g. PicoDrive): keep conservative evidence for the
    # dispatch so a newly added recipe cannot silently reuse the old binary.
    return semantic(primary)


def acceleration_fingerprint(job, root):
    # Evaluate only the rule table and its audited-core set from this exact
    # recipe snapshot. Other parser/verification code remains shared and hashed.
    # This intentionally fails closed if rule evaluation gains other dependencies.
    nodes = syntax(root / "scripts/core_acceleration_audit.py")
    rule_nodes = [n for n in nodes if named(n) in {"requirements", "AUDITED"}]
    if {named(n) for n in rule_nodes} != {"requirements", "AUDITED"}:
        raise ValueError("Unexpected acceleration rules")
    namespace = {}
    exec(compile(ast.Module(body=rule_nodes, type_ignores=[]), "<core-rules>", "exec"), namespace)
    rules = namespace["requirements"](job["core"], job["abi"], job["profile"])
    common = [n for n in nodes if named(n) not in {"requirements", "AUDITED"}]
    return digest(json.dumps({"shared": semantic(common),
                              "audited": job["core"] in namespace["AUDITED"],
                              "rules": rules}, sort_keys=True).encode())


def fingerprint(job, ndk, root=ROOT):
    # Shared toolchain/build/audit changes invalidate affected signed builds.
    # Core-specific source patches and NDK helpers are scoped to their core.
    workflow = (root / ".github/workflows/cores.yml").read_text()
    start = workflow.index("      - name: Install NDK")
    end = workflow.index("      - uses: actions/upload-artifact", start)
    files = {p: digest((root / "scripts" / p).read_bytes()) for p in SHARED
             if p not in {"core_build_policy.py", "core_acceleration_audit.py"}}
    files["core_build_policy.py"] = policy_fingerprint(job, root)
    files["core_acceleration_audit.py"] = acceleration_fingerprint(job, root)
    if job["core"] == "noods" or job["profile"] in {"core-hotpaths", "optimized", "optimized-a57", "citra-arm64-jit",
                          "yabause-arm64-jit", "picodrive-sh2", "snes9x2010-threaded",
                          "pgo-generate", "pgo-use"}:
        files["hotpath"] = hotpath_fingerprint(job["core"], root)
    if job["profile"] in {"citra-arm64-jit", "yabause-arm64-jit"}:
        kind = "citra" if job["profile"].startswith("citra") else "yabause"
        files["jit_recipe"] = digest((root/"scripts"/f"core_{kind}_arm64.py").read_bytes())
        headers = (root/"scripts/native_core_candidates").glob("citra_*.h" if kind=="citra" else "sh2_*.h")
        for path in sorted(headers):
            files[path.name] = digest(path.read_bytes())
    build_recipe = workflow[start:end]
    if not (job["core"] == "mupen64plus_next_gles3" and "-flto=thin" in job["cflags"].split()):
        # This source-specific launcher does not change any other build recipe.
        build_recipe = re.sub(r"(?m)^            # BEGIN MUPEN NATIVE OFFSET LAUNCHER\n"
                              r"[\s\S]*?^            # END MUPEN NATIVE OFFSET LAUNCHER\n", "", build_recipe)
        build_recipe = build_recipe.replace('NDK_CCACHE="$ndk_ccache"', 'NDK_CCACHE="${CORE_CCACHE:-}"')
    # The matrix can already carry its publication fingerprint. Do not
    # hash the fingerprint recursively when deriving a compiler-cache key.
    job = {k: v for k, v in job.items() if k not in {"fingerprint", "update_ref"}}
    payload = {"schema": 2, "ndk": ndk, "job": job,
               "build": digest(build_recipe.encode()), "files": files}
    return digest(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode())


def profile_snapshot(root, profile, ndk):
    """Use the planner belonging to each checkout; compare the entire channel."""
    workflow = (root / ".github/workflows/cores.yml").read_text()
    versions = [match.group(1) for line in workflow.splitlines()
                if (match := re.fullmatch(r'  NDK_VERSION: "([^"]+)"\s*', line))]
    if versions != [ndk]:
        raise ValueError("Core NDK version changed on main")
    matrix = json.loads(subprocess.check_output(
        [sys.executable, str(root / "scripts/core_build_policy.py"), "plan",
         "--catalog", str(root / "cores/cores.json"), "--event",
         "workflow_dispatch" if profile in {"citra-arm64-jit", "yabause-arm64-jit"} else "push",
         "--ref", "refs/heads/main", "--publish", "true" if profile == "baseline" else "false",
         "--profile", profile], cwd=root,
        env={"PATH": os.defpath, "LC_ALL": "C", "PYTHONDONTWRITEBYTECODE": "1"}, text=True))
    jobs = matrix["include"]
    if not jobs or len({key(j) for j in jobs}) != len(jobs) or any(j["profile"] != profile for j in jobs):
        raise ValueError("Invalid current core profile catalog")
    return {key(j): fingerprint(j, ndk, root) for j in jobs}


def assert_current_recipe(root, current, profile, ndk):
    """Permit scheduling-only changes, never stale binaries or changed trust rules."""
    for path in ("cores/signing-public.pem", "scripts/core_release_incremental.py"):
        if (root / path).read_bytes() != (current / path).read_bytes():
            raise ValueError(f"Core release trust/planning rules changed on main: {path}")
    before = (root / ".github/workflows/cores.yml").read_text()
    after = (current / ".github/workflows/cores.yml").read_text()
    # Protect runner/checkout and publication logic outside the per-job fingerprint.
    for start, end in (("\n  build:\n", "      - name: Install NDK"),
                       ("      - uses: actions/upload-artifact", "\n  publish:\n")):
        def section(source):
            pos = source.index(start)
            return source[pos:source.index(end, pos)]
        if section(before) != section(after):
            raise ValueError("Core build runner/artifact contract changed on main")
    if before[before.index("\n  publish:\n"):] != after[after.index("\n  publish:\n"):]:
        raise ValueError("Core signing/publication rules changed on main")
    original = profile_snapshot(root, profile, ndk)
    latest = profile_snapshot(current, profile, ndk)
    changed = sorted(k for k in original.keys() | latest.keys() if original.get(k) != latest.get(k))
    if changed:
        details = ", ".join(f"{core}/{abi}" for core, abi in changed)
        raise ValueError(f"Core release inputs changed on main ({profile}): {details}; dispatch a fresh release")


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def signed_history(repo, profile, desired, root=ROOT):
    pages = json.loads(gh("api", "--paginate", "--slurp",
                          f"repos/{repo}/releases?per_page=100"))
    releases = [r for page in pages for r in page if not r["draft"] and
                ((profile == "baseline" and not r["prerelease"] and r["tag_name"].startswith("cores-"))
                 or (profile != "baseline" and r["prerelease"] and
                     r["tag_name"].startswith(f"core-experiments-{profile}--")))]
    releases.sort(key=lambda r: (r.get("published_at") or "", r["id"]), reverse=True)
    records = {}
    latest_keys = None
    for release in releases:
        tag = release["tag_name"]
        assets = {a["name"]: a for a in release["assets"]}
        if len(assets) != len(release["assets"]):
            raise ValueError(f"{tag}: duplicate release assets")
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            gh("release", "download", tag, "--repo", repo, "--dir", tmp,
               "--pattern", "manifest.json", "--pattern", "manifest.json.sig")
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(root / "cores/signing-public.pem"),
                            "-signature", str(directory / "manifest.json.sig"),
                            str(directory / "manifest.json")], check=True, stdout=subprocess.DEVNULL)
            manifest = json.loads((directory / "manifest.json").read_text())
        if manifest.get("version") != 1 or manifest.get("tag") != tag or manifest.get("profile", "baseline") != profile:
            raise ValueError(f"{tag}: signed manifest channel mismatch")
        entries = manifest["cores"]
        keys = {key(e) for e in entries}
        if len(keys) != len(entries):
            raise ValueError(f"{tag}: duplicate signed core entries")
        if latest_keys is None:
            latest_keys = keys
        for e in entries:
            k = key(e)
            if k not in desired or k in records:
                continue
            # Remember the newest signed entry even if its binary was removed;
            # never quietly reuse an older version of that core.
            expected = f"{k[1]}--{k[0]}_libretro_android.so.zip"
            asset = assets.get(expected)
            if (e.get("asset") != expected or not HEX.fullmatch(e.get("sha256", ""))
                    or not isinstance(e.get("size"), int) or e["size"] <= 0):
                raise ValueError(f"{tag}: invalid signed asset")
            records[k] = ({"tag": tag, "entry": e} if asset and asset["size"] == e["size"] else None)
        if profile == "baseline" or desired <= records.keys():
            break
    return records, latest_keys


def select(matrix, records, latest_keys, profile, ndk, force=False, root=ROOT):
    desired = {key(j) for j in matrix["include"]}
    build, reuse = [], []
    complete_snapshot = profile in {"baseline", "optimized", "optimized-a57", "citra-arm64-jit", "yabause-arm64-jit"}
    for job in matrix["include"]:
        job = dict(job)
        fp = fingerprint(job, ndk, root)
        job["fingerprint"] = fp
        previous = records.get(key(job))
        if not force and previous and previous["entry"].get("fingerprint") == fp:
            if complete_snapshot:
                reuse.append(previous)
        else:
            build.append(job)
    # Public snapshots retain the entire current catalog; partial legacy history
    # is consolidated even if all fingerprints can be reused without builds.
    changed = bool(build) or (complete_snapshot and latest_keys != desired)
    return {"matrix": {"include": build}, "reuse": reuse, "publish": changed,
            "total": len(desired), "rebuilt": len(build)}


def verify_build_artifacts(matrix, directory, ndk):
    """Match the exact downloaded bytes to the successful build's evidence."""
    from core_build_policy import verify_elf_abi, REQUIRED_EXPORTS, ALLOWED_NEEDED
    from check_elf_pages import load_alignments, MIN_ALIGN

    jobs = matrix["include"]
    if len({key(job) for job in jobs}) != len(jobs):
        raise ValueError("Duplicate planned artifact")
    evidence = {}
    for job in jobs:
        core, abi = key(job)
        artifact = job["artifact"]
        expected_name = ("core" if job["profile"] == "baseline" else "candidate-" + job["profile"]) + f"-{core}-{abi}"
        if artifact != expected_name or Path(artifact).name != artifact:
            raise ValueError("Unexpected planned artifact identity")
        folder = directory / artifact
        binary = folder / f"{core}_libretro_android.so"
        report_path = folder / "build.json"
        if not binary.is_file() or not report_path.is_file():
            raise ValueError(f"{core}/{abi}: missing binary or build report")
        report_bytes = report_path.read_bytes()
        report = json.loads(report_bytes)
        expected = {field: job[field] for field in ("core", "abi", "commit", "profile", "fingerprint")}
        expected["ndk"] = ndk
        if not HEX.fullmatch(job["fingerprint"]) or any(report.get(k) != val for k, val in expected.items()):
            raise ValueError(f"{core}/{abi}: build report recipe mismatch")
        with binary.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if report.get("binary_sha256") != actual or report.get("binary_bytes") != binary.stat().st_size:
            raise ValueError(f"{core}/{abi}: build report binary hash/size mismatch")
        identity = verify_elf_abi(binary, abi)
        aligns = load_alignments(binary)
        if not aligns or any(a < MIN_ALIGN for a in aligns) or any(report.get(k) != val for k, val in identity.items()):
            raise ValueError(f"{core}/{abi}: ELF identity or alignment mismatch")
        if (report.get("export_count", 0) < len(REQUIRED_EXPORTS) or
                report.get("linked_compile_commands", 0) < 1 or report.get("unresolved_local_inputs") != [] or
                not isinstance(report.get("needed_libraries"), list) or
                not set(report["needed_libraries"]) <= ALLOWED_NEEDED):
            raise ValueError(f"{core}/{abi}: incomplete native verification evidence")
        # Retain exact UTF-8 bytes as a JSON string so independent auditors
        # can reproduce the signed report digest without guessing formatting.
        evidence[artifact] = {"binary_sha256": actual, "build_report_sha256": digest(report_bytes),
                              "report_json": report_bytes.decode("utf-8")}
    return evidence


def stage_reuse(repo, reuse, destination):
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    groups = {}
    for record in reuse:
        entry = record["entry"]
        name = entry["asset"]
        if name != f"{entry['abi']}--{entry['core']}_libretro_android.so.zip" or Path(name).name != name:
            raise ValueError("Invalid reuse asset")
        groups.setdefault(record["tag"], []).append(entry)
    for tag, group in groups.items():
        patterns = [arg for entry in group for arg in ("--pattern", entry["asset"])]
        gh("release", "download", tag, "--repo", repo, "--dir", str(destination), *patterns)
        for entry in group:
            name = entry["asset"]
            target = destination / name
            with target.open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual != entry["sha256"] or target.stat().st_size != entry["size"]:
                raise ValueError(f"Reused asset failed signature-bound hash/size: {name}")
            entries.append(entry)
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("plan")
    for flag in ("repo", "profile", "ndk", "matrix"):
        p.add_argument("--" + flag, required=True)
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("cache-key")
    p.add_argument("--job", required=True)
    p.add_argument("--ndk", required=True)
    p.add_argument("--pgo-sha256", default="")
    p = sub.add_parser("assert-current")
    p.add_argument("--profile", required=True, choices=("baseline", "optimized", "optimized-a57", "citra-arm64-jit", "yabause-arm64-jit"))
    p.add_argument("--ndk", required=True)
    p.add_argument("--current-root", type=Path, required=True)
    p = sub.add_parser("verify-artifacts")
    p.add_argument("--matrix", required=True)
    p.add_argument("--directory", type=Path, required=True)
    p.add_argument("--ndk", required=True)
    p = sub.add_parser("reuse")
    p.add_argument("--repo", required=True)
    p.add_argument("--records", required=True)
    p.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "verify-artifacts":
        print(json.dumps(verify_build_artifacts(json.loads(args.matrix), args.directory, args.ndk),
                         separators=(",", ":"), sort_keys=True))
        return
    if args.action == "cache-key":
        job = json.loads(args.job)
        fp = fingerprint(job, args.ndk)
        if job["profile"] == "pgo-use":
            if not HEX.fullmatch(args.pgo_sha256):
                raise ValueError("PGO cache requires the exact profile digest")
            fp = digest((fp + ":" + args.pgo_sha256).encode())
        print(fp)
        return
    if args.action == "assert-current":
        assert_current_recipe(ROOT, args.current_root.resolve(), args.profile, args.ndk)
        print("Current profile build fingerprints and release trust rules match.")
        return
    if args.action == "plan":
        matrix = json.loads(args.matrix)
        desired = {key(j) for j in matrix["include"]}
        records, latest = ({}, None) if args.force else signed_history(args.repo, args.profile, desired)
        result = select(matrix, records, latest, args.profile, args.ndk, args.force)
    else:
        result = stage_reuse(args.repo, json.loads(args.records), args.destination)
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Core release: {error}")
