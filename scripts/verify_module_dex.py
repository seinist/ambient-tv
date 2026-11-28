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


def inspect(data):
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

    # Every class definition is also a type_id. Inspect the complete type
    # table once, including array component types, to catch dangling private
    # references without guessing from arbitrary constant strings.
    if uint(64) > 65536 or uint(96) > 65536:
        raise ValueError("Too many DEX types or class definitions")
    names = []
    for i in range(uint(64)):
        string = uint(uint(68) + i * 4)
        if string >= uint(56):
            raise ValueError("Invalid DEX type string")
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
        if not 1 <= length <= 1024 or length >= size - offset or data[offset + length] != 0:
            raise ValueError("Invalid DEX descriptor length")
        raw = data[offset:offset + length]
        descriptor = raw.lstrip(b"[")
        if len(raw) - len(descriptor) > 255:
            raise ValueError("Invalid DEX array type")
        if re.fullmatch(rb"L[A-Za-z0-9_/$-]+;", descriptor):
            names.append((descriptor[1:-1].decode("ascii") + ".class", raw == descriptor))
        elif descriptor in (b"Z", b"B", b"S", b"C", b"I", b"J", b"F", b"D") or raw == b"V":
            names.append((None, False))
        else:
            raise ValueError("Invalid DEX type descriptor")
    classes = set()
    for i in range(uint(96)):
        index = uint(uint(100) + i * 32)
        if index >= len(names):
            raise ValueError("Invalid DEX class type")
        name, is_class = names[index]
        if not is_class or name in classes:
            raise ValueError("Invalid or duplicate DEX definition")
        classes.add(name)
    return classes, {name for name, _ in names if name is not None}


def definitions(data):
    return inspect(data)[0]


def verify_adapter(module, dex):
    expected = {"classes.dex"} | {f"classes{i}.dex" for i in range(2, len(dex) + 1)}
    if set(dex) != expected or len(dex) > 64:
        raise ValueError("Missing or noncontiguous adapter DEX")
    owner, required = ownership(module)
    classes = set()
    references = set()
    for data in dex.values():
        found, types = inspect(data)
        references.update(types)
        if classes & found:
            raise ValueError("Duplicate classes across DEX files")
        if not all(owner(name) for name in found):
            raise ValueError("Adapter defines host or foreign classes")
        classes |= found
    foreign = [ownership(other)[0] for other in ("monogram", "smarttube", "vlc", "libretrodroid") if other != module]
    missing = {name for name in references if owner(name) and name not in classes}
    if missing:
        raise ValueError(f"Adapter references missing private classes: {sorted(missing)[:10]}")
    if any(check(name) for name in references for check in foreign):
        raise ValueError("Adapter references another isolated module")
    if not set(required) <= classes:
        raise ValueError(f"Adapter missing classes: {sorted(set(required) - classes)}")
    if module == "monogram" and not all(any(name.startswith(prefix) for name in classes)
            for prefix in ("uniffi/monogram_mtproto/", "org/rustls/")):
        raise ValueError("Adapter missing required class namespace")
    return classes
