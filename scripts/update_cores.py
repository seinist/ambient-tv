#!/usr/bin/env python3
"""Check or update only pinned emulator cores; no library or Maven changes."""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.parse

from update_external_libraries import get_bytes, github_repo
from svp_dependency import apply_files

ROOT = Path(__file__).resolve().parents[1]


def cores_path() -> Path:
    return ROOT / "cores" / "cores.json"


def remote_head(repo: str, ref: str = "HEAD") -> str:
    # git ls-remote instead of the GitHub API: one call per core would exhaust the 60/h limit.
    if ref != "HEAD" and not re.fullmatch(r"refs/heads/[A-Za-z0-9._/-]+", ref):
        raise ValueError(f"Invalid core update ref: {ref}")
    out = subprocess.run(["git", "ls-remote", "--exit-code", repo, ref], capture_output=True, text=True, timeout=120,
                         env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    lines = [line.split() for line in out.stdout.splitlines() if line.strip()]
    sha = lines[0][0] if out.returncode == 0 and len(lines) == 1 and len(lines[0]) == 2 and lines[0][1] == ref else ""
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise RuntimeError(f"Could not resolve {ref} of {repo}: {out.stderr.strip() or 'no unique matching ref'}")
    return sha


def core_candidate_compatible(core: dict, revision: str) -> bool:
    """Check all reviewed source anchors before moving a public core pin.

    Only source text is fetched; never execute unreviewed upstream code.
    Native JIT candidates additionally run the exact production source
    patchers on a disposable checkout, without compiling or executing it.
    CI must still build and differentially test the resulting catalog pin.
    """
    from core_hotpath_patches import prepare_hotpaths, SUPPORTED
    from core_hotpath_remaining import PATCHES

    name = core["core"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid candidate core revision")
    if name in {"citra2018", "yabause"}:
        try:
            preflight_jit_core(core, revision)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            print(f"  MANUAL: {name} remains at {core['commit']}: JIT preflight failed for {revision} ({error}).")
            return False
        return True
    if name not in SUPPORTED:
        return True
    if name in PATCHES:
        files = {path for path, _, _ in PATCHES[name]}
    elif name == "bsnes":
        files = {"bsnes/target-libretro/libretro.cpp", "bsnes/target-libretro/program.cpp", "libco/arm.c"}
    elif name == "mesen":
        files = {"Libretro/libretro.cpp"}
    elif name == "sameboy":
        files = {"libretro/libretro.c"}
    elif name in {"mednafen_pce_fast", "mednafen_supergrafx"}:
        files = {"libretro.c" if name == "mednafen_pce_fast" else "libretro.cpp"}
    else:
        files = {"mednafen/state.h", "mednafen/state.cpp" if name == "mednafen_lynx" else "mednafen/state.c",
                 "libretro.cpp" if name in {"mednafen_vb", "mednafen_lynx"} else "libretro.c"}
    upstream = github_repo(core["repo"])
    try:
        with tempfile.TemporaryDirectory(prefix="ambient-core-update-") as directory:
            root = Path(directory)
            for relative in sorted(files):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                url = f"https://raw.githubusercontent.com/{upstream}/{revision}/{urllib.parse.quote(relative, safe='/')}"
                path.write_bytes(get_bytes(url))
            prepare_hotpaths(root, name)
    except (OSError, ValueError) as error:
        print(f"  MANUAL: {name} remains at {core['commit']}: candidate {revision} source preflight failed ({error}).")
        return False
    return True


def preflight_jit_core(core: dict, revision: str) -> None:
    from core_hotpath_patches import prepare_hotpaths
    from core_yabause_arm64 import prepare as prepare_yabause
    from core_citra_arm64 import prepare as prepare_citra

    github_repo(core["repo"])
    with tempfile.TemporaryDirectory(prefix="ambient-jit-update-") as directory:
        root = Path(directory)
        def run(*args):
            return subprocess.check_output(
                ["git", "-c", "core.hooksPath=/dev/null", "-C", str(root), *args],
                text=True, timeout=180, env={**os.environ, "GIT_TERMINAL_PROMPT": "0"}).strip()
        run("init", "-q")
        run("remote", "add", "origin", core["repo"])
        run("fetch", "--depth", "1", "origin", revision)
        run("checkout", "--detach", "FETCH_HEAD")
        if run("rev-parse", "HEAD") != revision:
            raise ValueError("Candidate checkout pin mismatch")
        prepare_hotpaths(root, core["core"])
        (prepare_citra if core["core"] == "citra2018" else prepare_yabause)(root)


def cores_latest(cores: list[dict]) -> dict[str, str]:
    # Resolve each explicit branch separately; shared same-branch variants
    # (VICE) keep one snapshot. Never collapse independently pinned variants.
    def source(c):
        return c["repo"], c.get("update_ref", "HEAD")
    sources = list(dict.fromkeys(source(c) for c in cores))
    def resolve(item):
        repo, ref = item
        return remote_head(repo) if ref == "HEAD" else remote_head(repo, ref)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        heads = dict(zip(sources, pool.map(resolve, sources)))
    pins = {}
    for c in cores:
        pins.setdefault(source(c), set()).add(c["commit"])
    for item, revisions in pins.items():
        if len(revisions) > 1:
            print(f"  MANUAL: {item[0]} {item[1]} has different pins; specify separate update_ref values.")
    candidates = [c for c in cores if len(pins[source(c)]) == 1
                  and c["commit"] != heads[source(c)]]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        compatible = list(pool.map(lambda c: core_candidate_compatible(c, heads[source(c)]), candidates))
    blocked = {source(c) for c, ok in zip(candidates, compatible) if not ok}
    return {c["core"]: c["commit"] if len(pins[source(c)]) > 1 or source(c) in blocked
            else heads[source(c)] for c in cores}


def render_cores(data: dict, commits: dict[str, str]) -> bytes:
    data = json.loads(json.dumps(data))
    for core in data["cores"]:
        core["commit"] = commits.get(core["core"], core["commit"])
    return (json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("cores", nargs="?", default="", help="Optional comma-separated core IDs")
    args = parser.parse_args()
    path = cores_path()
    original = path.read_bytes()
    catalog = json.loads(original)
    entries = catalog["cores"]
    ids = {c["core"] for c in entries}
    requested = args.cores.split(",") if args.cores else list(ids)
    if len(set(requested)) != len(requested) or not set(requested) <= ids:
        parser.error("Unknown, empty or duplicate core ID")
    # Keep variants sharing one upstream ref on the same snapshot. Selecting
    # a VICE variant therefore also checks its sibling variants.
    sources = {(c["repo"], c.get("update_ref", "HEAD")) for c in entries if c["core"] in requested}
    selected = [c for c in entries if (c["repo"], c.get("update_ref", "HEAD")) in sources]
    latest = cores_latest(selected)
    changed = [c["core"] for c in selected if c["commit"] != latest[c["core"]]]
    print("Managed Libretro cores:")
    for c in selected:
        name, old = c["core"], c["commit"]
        new = latest[name]
        print(f"  {name:24} {old} -> {new} [{'UPDATE' if old != new else 'kept'}]")
    if changed and args.apply:
        apply_files({path: render_cores(catalog, latest)}, {path: original})
        print("Updated core pins: " + ", ".join(changed))
        print("No native build performed. Review, commit and push to main for signed releases.")
    elif changed:
        print("Updates available. Apply with: ./build.sh cores-update" + (f" {args.cores}" if args.cores else ""))
    else:
        print("No compatible core pin updates to apply.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
