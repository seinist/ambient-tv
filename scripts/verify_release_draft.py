#!/usr/bin/env python3
"""Verify uploaded release draft assets against the exact local candidate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote


def expected_assets(paths):
    expected = {}
    for path in paths:
        if path.is_symlink() or not path.is_file() or not re.fullmatch(r"[A-Za-z0-9._+-]+", path.name) or path.name in expected:
            raise ValueError(f"Invalid or duplicate release asset: {path.name}")
        size = path.stat().st_size
        if size <= 0:
            raise ValueError(f"Empty release asset: {path.name}")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        expected[path.name] = (size, "sha256:" + digest)
    if not expected:
        raise ValueError("Missing release assets")
    return expected


def verify(release, tag, expected, target=None):
    if not isinstance(release, dict) or release.get("tag_name") != tag or release.get("draft") is not True:
        raise ValueError("Release is not the expected draft")
    if target is not None and release.get("target_commitish") != target:
        raise ValueError("Release draft targets a different commit")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != len(expected):
        raise ValueError("Release draft asset count differs")
    seen = set()
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError("Malformed release draft asset")
        name = asset.get("name")
        if not isinstance(name, str) or name in seen or name not in expected:
            raise ValueError("Unexpected or duplicate release draft asset")
        seen.add(name)
        size, digest = expected[name]
        if asset.get("state") != "uploaded" or type(asset.get("size")) is not int or asset["size"] != size or asset.get("digest") != digest:
            raise ValueError(f"Uploaded release asset differs from candidate: {name}")
    if seen != set(expected):
        raise ValueError("Missing release draft asset")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--target")
    parser.add_argument("assets", type=Path, nargs="+")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo) or not re.fullmatch(r"[A-Za-z0-9._+-]{1,128}", args.tag):
        raise ValueError("Invalid release identity")
    if args.target is not None and not re.fullmatch(r"[a-f0-9]{40}", args.target):
        raise ValueError("Invalid release target")
    expected = expected_assets(args.assets)
    raw = subprocess.check_output(["gh", "api", f"repos/{args.repo}/releases/tags/{quote(args.tag, safe='')}"], text=True)
    verify(json.loads(raw), args.tag, expected, args.target)
    print(f"Verified draft: {args.tag}, {len(expected)} exact uploaded assets")


if __name__ == "__main__":
    main()
