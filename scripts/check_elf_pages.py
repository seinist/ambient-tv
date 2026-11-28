#!/usr/bin/env python3
"""Fails unless every PT_LOAD segment of the given ELF libraries is aligned to at least 16 KiB
(the page size of newer Android devices). Usage: check_elf_pages.py lib.so [...]"""
import struct
import sys

MIN_ALIGN = 16384


def load_alignments(path):
    # Read only ELF/program headers. Large emulator DSOs do not need to be
    # allocated again just to verify segment boundaries.
    with open(path, "rb") as f:
        f.seek(0, 2)
        file_size = f.tell()
        f.seek(0)
        header = f.read(64)
        if len(header) < 52 or header[:4] != b"\x7fELF" or header[4] not in (1, 2) or header[5] not in (1, 2) or header[6] != 1:
            raise ValueError(f"{path}: invalid ELF identification")
        is64 = header[4] == 2
        endian = "<" if header[5] == 1 else ">"
        header_size, entry_size = (64, 56) if is64 else (52, 32)
        if len(header) < header_size:
            raise ValueError(f"{path}: truncated ELF header")
        if is64:
            phoff, = struct.unpack_from(endian + "Q", header, 0x20)
            phentsize, phnum = struct.unpack_from(endian + "HH", header, 0x36)
        else:
            phoff, = struct.unpack_from(endian + "I", header, 0x1C)
            phentsize, phnum = struct.unpack_from(endian + "HH", header, 0x2A)
        if (phentsize != entry_size or not phnum or phnum == 0xffff or
                phoff < header_size or phoff + phentsize * phnum > file_size):
            raise ValueError(f"{path}: invalid ELF program header table")
        f.seek(phoff)
        result = []
        for _ in range(phnum):
            entry = f.read(phentsize)
            if len(entry) != phentsize:
                raise ValueError(f"{path}: truncated ELF program header")
            if is64:
                p_type, _, offset, vaddr, _, filesz, memsz, align = struct.unpack(endian + "IIQQQQQQ", entry)
            else:
                p_type, offset, vaddr, _, filesz, memsz, _, align = struct.unpack(endian + "IIIIIIII", entry)
            if p_type != 1:
                continue
            if (filesz > memsz or (filesz and (offset > file_size or filesz > file_size - offset)) or
                    vaddr + memsz > (1 << (64 if is64 else 32))):
                raise ValueError(f"{path}: invalid PT_LOAD file/memory bounds")
            if align > 1 and (align & (align - 1) or (offset - vaddr) % align):
                raise ValueError(f"{path}: invalid PT_LOAD alignment/congruence")
            result.append(align)
        return result

def main(paths):
    bad = False
    for path in paths:
        try:
            aligns = load_alignments(path)
        except (ValueError, OSError) as exc:
            print(exc, file=sys.stderr)
            bad = True
            continue
        low = [a for a in aligns if a < MIN_ALIGN]
        print(f"{path}: PT_LOAD align {sorted(set(aligns))}{' (too small)' if low else ''}")
        bad |= bool(low) or not aligns
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
