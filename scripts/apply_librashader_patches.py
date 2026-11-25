#!/usr/bin/env python3
"""Apply the same small pinned-runtime fixes to Android and host librashader.

The checkout must already be at LIBRASHADER_COMMIT. Applying an already-applied
patch is safe; a source mismatch fails the build instead of silently skipping it.
--fingerprint hashes patch names and bytes for source and binary cache stamps.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def patches() -> list[Path]:
    result = sorted((ROOT / "scripts/patches/librashader").glob("*.patch"))
    if not result:
        raise SystemExit("No pinned librashader patches found")
    return result


def fingerprint(files: list[Path]) -> str:
    digest = hashlib.sha256()
    for patch in files:
        digest.update(patch.name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(patch.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_dir", nargs="?", type=Path)
    parser.add_argument("--fingerprint", action="store_true")
    args = parser.parse_args()
    files = patches()
    if args.fingerprint:
        print(fingerprint(files))
        return
    if args.source_dir is None:
        parser.error("source_dir is required unless --fingerprint is requested")
    source = args.source_dir.resolve(strict=True)
    properties = dict(line.split("=", 1) for line in
                      (ROOT / "gradle/external-versions.properties").read_text().splitlines()
                      if line and not line.startswith("#") and "=" in line)
    pin = properties["LIBRASHADER_COMMIT"]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if head != pin:
        raise SystemExit(f"librashader checkout is {head}, expected pinned {pin}")
    for patch in files:
        check = subprocess.run(["git", "apply", "--check", str(patch)], cwd=source,
                               text=True, capture_output=True)
        if check.returncode == 0:
            subprocess.run(["git", "apply", str(patch)], cwd=source, check=True)
            print(f"Applied librashader patch: {patch.name}")
            continue
        reverse = subprocess.run(["git", "apply", "--reverse", "--check", str(patch)],
                                 cwd=source, text=True, capture_output=True)
        if reverse.returncode != 0:
            raise SystemExit(f"Cannot apply or verify librashader patch {patch.name}:\n{check.stderr}")
        print(f"Verified librashader patch: {patch.name}")


if __name__ == "__main__":
    main()
