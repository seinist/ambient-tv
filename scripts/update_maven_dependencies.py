#!/usr/bin/env python3
"""Check or update Maven pins independently of external/native engine updates."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import tempfile
import urllib.request

import maven_dependency as maven

CATALOG = Path(__file__).resolve().parents[1] / "gradle/libs.versions.toml"


def get_bytes(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ambient-tv-maven-updater/1.0"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def apply_catalog(path: Path, original: bytes, updated: bytes) -> None:
    if original == updated:
        return
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=f".{path.name}.", delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(updated)
            output.close()
            temporary.chmod(path.stat().st_mode & 0o777)
            if path.read_bytes() != original:
                raise RuntimeError("Concurrent catalog edit detected; update aborted")
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true")
    action.add_argument("--apply", action="store_true")
    parser.add_argument("key", nargs="?", help="Optional version key or inline library alias (libraries.<alias>)")
    args = parser.parse_args()
    original = CATALOG.read_bytes()
    text = original.decode("utf-8")
    channel = os.environ.get("MAVEN_CHANNEL", "stable")
    media3_channel = os.environ.get("MEDIA3_CHANNEL")
    major = os.environ.get("MAVEN_ALLOW_MAJOR", "0")
    if major not in ("0", "1"):
        parser.error("MAVEN_ALLOW_MAJOR must be 0 or 1")
    findings = maven.plan(text, get_bytes, key=args.key, channel=channel,
                          media3_channel=media3_channel, allow_major=major == "1")
    print(f"Maven catalog (channel={channel}, Media3={media3_channel or channel}):")
    for finding in findings:
        pin = finding.pin
        print(f"  {pin.key:32} {pin.current:22} -> {finding.candidate:22} [{finding.status}]"
              + (f" {finding.reason}" if finding.reason else ""))
    updates = [finding for finding in findings if finding.status == "UPDATE"]
    if args.apply and updates:
        updated = maven.render_catalog(text, findings).encode("utf-8")
        apply_catalog(CATALOG, original, updated)
        print(f"\nUpdated {len(updates)} catalog pin(s). Review the diff and rebuild/test before committing.")
    elif updates:
        print("\nApply with ./build.sh deps-update" + (f" {args.key}" if args.key else ""))
    else:
        print("\nNo automatically applicable updates.")
    if any(f.status in ("MANUAL", "UNAVAILABLE") for f in findings):
        print("MANUAL and UNAVAILABLE pins were not changed; review them separately.")
    return 1 if args.key and any(f.status == "UNAVAILABLE" for f in findings) else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
