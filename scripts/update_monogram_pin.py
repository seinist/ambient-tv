#!/usr/bin/env python3
"""Explicit, reviewable Monogram pin updates (not automatic with unrelated libraries).

Monogram is pinned to an upstream GitHub *release* tag, not to a branch head.
Upstream publishes every release as a pre-release, so pre-releases are included;
drafts are not. The commit the tag points to and the mono-rs submodule commit at
that tag are recorded too, so the native build fails if the tag is moved later.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROPS = ROOT / "gradle" / "external-versions.properties"
REPO = "monogram-android/monogram"
API = f"https://api.github.com/repos/{REPO}"
SHA = re.compile(r"[0-9a-f]{40}")


def get(url: str) -> bytes:
    headers = {"User-Agent": "ambient-tv-monogram-pin/1.0"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()


def get_json(url: str):
    return json.loads(get(url))


def latest_release(releases: list[dict]) -> dict:
    """Newest published, non-draft release; pre-releases count (upstream has only those)."""
    published = [r for r in releases if not r.get("draft") and r.get("published_at")]
    if not published:
        raise RuntimeError("Upstream has no published release")
    return max(published, key=lambda r: r["published_at"])


def tag_commit(tag: str) -> str:
    ref = get_json(f"{API}/git/ref/tags/{tag}")["object"]
    while ref["type"] == "tag":  # annotated tag -> its target
        ref = get_json(f"{API}/git/tags/{ref['sha']}")["object"]
    if ref["type"] != "commit" or not SHA.fullmatch(ref["sha"]):
        raise RuntimeError(f"Release tag {tag} does not resolve to a commit")
    return ref["sha"]


def submodule_commit(commit: str, path: str = "vendor/mono-rs") -> str:
    entry = get_json(f"{API}/contents/{path}?ref={commit}")
    if entry.get("type") != "submodule" or not SHA.fullmatch(entry.get("sha", "")):
        raise RuntimeError(f"{path} is not a submodule at {commit}")
    return entry["sha"]


def prop(text: str, key: str) -> str:
    match = re.search(rf"(?m)^{key}=(.*)$", text)
    if not match:
        raise RuntimeError(f"{key} not found")
    return match.group(1).strip()


def main():
    parser = argparse.ArgumentParser()
    options = parser.add_mutually_exclusive_group(required=True)
    options.add_argument("--check", action="store_true")
    options.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    current = PROPS.read_text()
    old = {key: prop(current, key) for key in
           ("MONOGRAM_VERSION", "MONOGRAM_REF", "MONOGRAM_MONO_RS_REF")}

    release = latest_release(get_json(f"{API}/releases?per_page=30"))
    tag = release["tag_name"]
    kind = "pre-release" if release.get("prerelease") else "release"
    commit = tag_commit(tag)
    mono_rs = submodule_commit(commit)
    lock = get(f"https://raw.githubusercontent.com/{REPO}/{commit}/native/Cargo.lock").decode()
    version = re.search(r'(?s)name = "rustls-platform-verifier-android"\s+version = "([^"]+)"', lock)
    if not version:
        raise RuntimeError("Upstream Cargo.lock lacks rustls-platform-verifier-android")

    print(f"Latest upstream GitHub release: {tag} ({kind}, published {release['published_at']})")
    if old["MONOGRAM_VERSION"] == tag and old["MONOGRAM_REF"] != commit:
        print(f"WARNING: tag {tag} moved: {old['MONOGRAM_REF']} -> {commit}")
    new = {"MONOGRAM_VERSION": tag, "MONOGRAM_REF": commit, "MONOGRAM_MONO_RS_REF": mono_rs}
    state = "CURRENT" if new == old else "REVIEW REQUIRED"
    print(f"Monogram (GPL-3.0-only): {old['MONOGRAM_VERSION']} @ {old['MONOGRAM_REF']}"
          f" -> {tag} @ {commit} [{state}]")
    print(f"mono-rs submodule: {mono_rs}")
    print(f"rustls-platform-verifier-android: {version.group(1)}")
    if args.apply and state != "CURRENT":
        updated = current
        for key, value in new.items():
            updated = re.sub(rf"(?m)^{key}=.*$", f"{key}={value}", updated)
        updated = re.sub(r"(?m)^MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION=.*$",
                         f"MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION={version.group(1)}", updated)
        PROPS.write_text(updated)
        print("Release pin updated. Check that scripts/patch_monogram_leave.py still applies, rebuild "
              "the AAR (./build.sh monogram) and review native ABI, auth, streaming and license "
              "before distributing.")
    elif args.apply:
        print("Already current; no changes.")
    else:
        print("Use ./build.sh monogram-update to explicitly move the pin, then rebuild/test.")


if __name__ == "__main__":
    main()
