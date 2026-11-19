#!/usr/bin/env python3
"""Fails unless every PT_LOAD segment of the given ELF libraries is aligned to at least 16 KiB
(the page size of newer Android devices). Usage: check_elf_pages.py lib.so [...]"""
import struct
import sys

MIN_ALIGN = 16384


def load_alignments(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    result = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", data, base)
        if p_type != 1:
            continue
        align = struct.unpack_from(endian + "Q", data, base + 0x30)[0] if is64 else struct.unpack_from(endian + "I", data, base + 0x1C)[0]
        result.append(align)
    return result


def main(paths):
    bad = False
    for path in paths:
        aligns = load_alignments(path)
        low = [a for a in aligns if a < MIN_ALIGN]
        print(f"{path}: PT_LOAD align {sorted(set(aligns))}{' (too small)' if low else ''}")
        bad |= bool(low) or not aligns
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
