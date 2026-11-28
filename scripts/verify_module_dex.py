"""Validate standalone DEX integrity, class definitions and module ownership.

Mirrors the Android loader's bounded standalone DEX header, map and
descriptor checks. This is format and linkage validation, not a bytecode
security sandbox or a replacement for Android's verifier.
"""
import hashlib
import re
import struct
import zlib

from export_monogram_adapter import ownership


def definitions(data):
    size = len(data)
    if not 112 <= size <= 256 * 1024**2 or bytes(data[:8]) not in {
        b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0", b"dex\n040\0"
    }:
        raise ValueError("Unsupported standalone DEX format")
    def uint(offset):
        if not 0 <= offset <= size - 4:
            raise ValueError("DEX reference outside file")
        return struct.unpack_from("<I", data, offset)[0]

    if uint(32) != size or uint(36) != 112 or uint(40) != 0x12345678:
        raise ValueError("Invalid DEX header")
    if uint(8) != zlib.adler32(memoryview(data)[12:]) or data[12:32] != hashlib.sha1(memoryview(data)[32:]).digest():
        raise ValueError("DEX checksum or signature mismatch")

    for at, width in ((44, 1), (56, 4), (64, 4), (72, 12), (80, 8), (88, 8), (96, 32), (104, 1)):
        count, offset = uint(at), uint(at + 4)
        if (not count and offset) or (count and
                (offset < 112 or offset % 4 or offset > size or count > (size - offset) // width)):
            raise ValueError("DEX table outside file")

    map_offset = uint(52)
    if not 112 <= map_offset <= size - 4 or map_offset % 4:
        raise ValueError("Invalid DEX map offset")
    count = uint(map_offset)
    if not 1 <= count <= 65536 or count > (size - map_offset - 4) // 12:
        raise ValueError("Invalid DEX map size")
    types, last_offset = set(), -1
    for i in range(count):
        kind, reserved, number, offset = struct.unpack_from("<HHII", data, map_offset + 4 + i * 12)
        if kind in types or reserved or not number or offset >= size or offset <= last_offset:
            raise ValueError("Invalid DEX map entry")
        types.add(kind)
        last_offset = offset

    classes = set()
    if uint(96) > 65536:
        raise ValueError("Too many DEX class definitions")
    for i in range(uint(96)):
        index = uint(uint(100) + i * 32)
        if index >= uint(64):
            raise ValueError("Invalid DEX class type")
        string = uint(uint(68) + index * 4)
        if string >= uint(56):
            raise ValueError("Invalid DEX class string")
        offset = uint(uint(60) + string * 4)
        if not 112 <= offset < size:
            raise ValueError("Invalid DEX descriptor offset")
        length = 0
        for shift in range(0, 29, 7):
            if offset >= size:
                raise ValueError("Truncated DEX descriptor")
            byte = data[offset]
            offset += 1
            length |= (byte & 127) << shift
            if not byte & 128:
                break
        else:
            raise ValueError("Invalid DEX descriptor length")
        if not 3 <= length <= 1024 or length >= size - offset or data[offset + length] != 0:
            raise ValueError("Invalid DEX descriptor length")
        descriptor = data[offset:offset + length]
        if not re.fullmatch(rb"L[A-Za-z0-9_/$-]+;", descriptor):
            raise ValueError("Invalid DEX descriptor")
        name = descriptor[1:-1].decode("ascii") + ".class"
        if name in classes:
            raise ValueError("Duplicate DEX definition")
        classes.add(name)
    return classes


def verify_adapter(module, dex):
    expected = {"classes.dex"} | {f"classes{i}.dex" for i in range(2, len(dex) + 1)}
    if set(dex) != expected or len(dex) > 64:
        raise ValueError("Missing or noncontiguous adapter DEX")
    owner, required = ownership(module)
    classes = set()
    for data in dex.values():
        found = definitions(data)
        if classes & found:
            raise ValueError("Duplicate classes across DEX files")
        if not all(owner(name) for name in found):
            raise ValueError("Adapter defines host or foreign classes")
        classes |= found
    if not set(required) <= classes:
        raise ValueError(f"Adapter missing classes: {sorted(set(required) - classes)}")
    if module == "monogram" and not all(any(name.startswith(prefix) for name in classes)
            for prefix in ("uniffi/monogram_mtproto/", "org/rustls/")):
        raise ValueError("Adapter missing required class namespace")
    return classes
