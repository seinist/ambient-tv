#!/usr/bin/env python3
"""Verify 16-KiB Android ELF LOAD alignment without relying on GNU awk.

Pass a path to the NDK llvm-readelf executable followed by one or more .so files.
Fail closed if a binary has no LOAD segments or if readelf exits with an error.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


LOAD = re.compile(r"^\s*LOAD\s+.*?\s(0x[0-9A-Fa-f]+)\s*$")
MIN_ALIGNMENT = 16 * 1024


def alignments(program_headers: str) -> list[int]:
    result: list[int] = []
    for line in program_headers.splitlines():
        match = LOAD.match(line)
        if match:
            result.append(int(match.group(1), 16))
    return result


def validate(program_headers: str) -> bool:
    values = alignments(program_headers)
    return bool(values) and all(value >= MIN_ALIGNMENT for value in values)


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("Usage: check_elf_page_alignment.py /path/to/llvm-readelf lib.so [lib.so ...]", file=sys.stderr)
        return 2
    readelf = argv[1]
    bad = False
    for filename in argv[2:]:
        lib = Path(filename)
        if not lib.is_file():
            print(f"ERROR: missing native library: {lib}", file=sys.stderr)
            bad = True
            continue
        try:
            command = subprocess.run(
                [readelf, "--program-headers", "--wide", str(lib)],
                check=True, capture_output=True, text=True,
            )
        except (subprocess.CalledProcessError, OSError) as error:
            print(f"ERROR: llvm-readelf failed for {lib}: {error}", file=sys.stderr)
            bad = True
            continue
        values = alignments(command.stdout)
        if not values or any(x < MIN_ALIGNMENT for x in values):
            print(f"ERROR: {lib}: LOAD alignments {values!r}; require >= {MIN_ALIGNMENT}", file=sys.stderr)
            bad = True
        else:
            print(f"OK: {lib}: LOAD alignments {values!r}")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
