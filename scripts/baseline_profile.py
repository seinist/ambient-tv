#!/usr/bin/env python3
"""Merge a captured ART profile into app/src/main/baseline-prof.txt.

Input is the human-readable profile written by
`adb shell pm dump-profiles --dump-classes-and-methods <package>`. Only rules for the app's own
classes are taken (libraries such as Compose ship their own profiles). Existing rules, including
the hand-written wildcards and comments, stay first and unchanged; new rules are appended sorted,
without duplicates. See scripts/capture_baseline_profile.sh.
"""
import argparse
import re
import sys
from pathlib import Path

APP_PREFIX = "Lcom/ambient/tv/"
# Optional flags (Hot, Startup, Post-startup), then a class descriptor.
RULE = re.compile(r"^([HSP]*)(L[^;\s]+;)(.*)$")


def app_rule(line: str, prefix: str = APP_PREFIX) -> bool:
    match = RULE.match(line.strip())
    return bool(match) and match.group(2).startswith(prefix)


def merge(existing: list[str], captured: list[str], prefix: str = APP_PREFIX) -> tuple[list[str], int]:
    """Returns the merged lines and how many rules were added."""
    present = {line.strip() for line in existing if line.strip()}
    added = sorted({line.strip() for line in captured if app_rule(line, prefix)} - present)
    base = [line.rstrip("\n") for line in existing]
    while base and not base[-1].strip():
        base.pop()
    return base + added, len(added)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("captured", type=Path, help="pm dump-profiles output (*.prof.txt)")
    parser.add_argument("profile", type=Path, help="baseline-prof.txt to update")
    parser.add_argument("--prefix", default=APP_PREFIX, help="class descriptor prefix to keep")
    args = parser.parse_args(argv)
    existing = args.profile.read_text().splitlines() if args.profile.exists() else []
    merged, added = merge(existing, args.captured.read_text().splitlines(), args.prefix)
    args.profile.write_text("\n".join(merged) + "\n")
    print(f"{added} new rules for {args.prefix} added to {args.profile}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
