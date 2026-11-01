#!/usr/bin/env python3
"""Explicit, reviewable Monogram pin updates (not automatic with unrelated libraries)."""
from __future__ import annotations
import argparse
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROPS = ROOT / "gradle" / "external-versions.properties"
REPO = "monogram-android/monogram"

def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ambient-tv-monogram-pin/1.0"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()

def main():
    parser = argparse.ArgumentParser()
    options = parser.add_mutually_exclusive_group(required=True)
    options.add_argument("--check", action="store_true")
    options.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    current = PROPS.read_text()
    old = re.search(r"(?m)^MONOGRAM_REF=([0-9a-f]{40})$", current)
    if not old:
        raise RuntimeError("MONOGRAM_REF not found")
    commit = json.loads(get(f"https://api.github.com/repos/{REPO}/commits/develop"))["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise RuntimeError("Invalid remote SHA")
    lock = get(f"https://raw.githubusercontent.com/{REPO}/{commit}/native/Cargo.lock").decode()
    version = re.search(r'(?s)name = "rustls-platform-verifier-android"\s+version = "([^"]+)"', lock)
    if not version:
        raise RuntimeError("Upstream Cargo.lock lacks rustls-platform-verifier-android")
    print(f"Monogram (GPL-3.0-only): {old.group(1)} -> {commit} [{'CURRENT' if commit == old.group(1) else 'REVIEW REQUIRED'}]")
    print(f"rustls-platform-verifier-android: {version.group(1)}")
    if args.apply and commit != old.group(1):
        updated = current.replace(f"MONOGRAM_REF={old.group(1)}", f"MONOGRAM_REF={commit}")
        updated = re.sub(r"(?m)^MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION=.*$",
                         f"MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION={version.group(1)}", updated)
        PROPS.write_text(updated)
        print("Source pin updated. Rebuild and review native ABI, auth, streaming and license before distributing.")
    elif args.apply:
        print("Already current; no changes.")
    else:
        print("Use ./build.sh monogram-update to explicitly move the pin, then rebuild/test.")
if __name__ == "__main__":
    main()
