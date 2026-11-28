import hashlib
import struct
import unittest
import zlib

from export_monogram_adapter import LIBRETRO_REQUIRED, VLC_REQUIRED
from verify_module_dex import definitions, verify_adapter


def fixture(names):
    count = len(names)
    strings, types, classes = 112, 112 + count * 4, 112 + count * 8
    map_offset = classes + count * 32
    data = bytearray(map_offset + 16)
    data[:8] = b"dex\n039\0"
    for at, value in ((36, 112), (40, 0x12345678), (52, map_offset),
                      (56, count), (60, strings), (64, count), (68, types),
                      (96, count), (100, classes)):
        struct.pack_into("<I", data, at, value)
    struct.pack_into("<IHHII", data, map_offset, 1, 0x1000, 0, 1, map_offset)
    for i, name in enumerate(names):
        desc = ("L" + name.removesuffix(".class") + ";").encode()
        assert len(desc) < 128
        struct.pack_into("<I", data, strings + i * 4, len(data))
        struct.pack_into("<I", data, types + i * 4, i)
        struct.pack_into("<I", data, classes + i * 32, i)
        data.extend(bytes([len(desc)]) + desc + b"\0")
    struct.pack_into("<I", data, 32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]))
    return bytes(data)


class ModuleDexTest(unittest.TestCase):
    def test_module_ownership_and_required_classes(self):
        for module, required in (("vlc", VLC_REQUIRED), ("libretrodroid", LIBRETRO_REQUIRED)):
            self.assertEqual(verify_adapter(module, {"classes.dex": fixture(required)}), set(required))
            for invalid in (required[:-1], (*required, "com/ambient/tv/Host.class")):
                with self.assertRaises(ValueError):
                    verify_adapter(module, {"classes.dex": fixture(invalid)})

    def test_rejects_duplicate_and_noncontiguous_dex(self):
        valid = fixture(VLC_REQUIRED)
        for invalid in ({"classes.dex": valid, "classes2.dex": valid}, {"classes2.dex": valid}):
            with self.assertRaises(ValueError):
                verify_adapter("vlc", invalid)

    def test_checksum_and_duplicate_definitions(self):
        data = bytearray(fixture(VLC_REQUIRED))
        data[-1] = 1
        with self.assertRaisesRegex(ValueError, "checksum"):
            definitions(data)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            definitions(fixture([VLC_REQUIRED[0], VLC_REQUIRED[0]]))


if __name__ == "__main__":
    unittest.main()
