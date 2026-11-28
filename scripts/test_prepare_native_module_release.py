import struct
import unittest

import prepare_native_module_release as release


class NativeModuleReleaseTest(unittest.TestCase):
    def test_identity(self):
        self.assertEqual(release.identity("svp", "123", "a" * 40), ("modules-svp-v123", 123))
        for module, run, sha in (("unsupported", "123", "a" * 40), ("svp", "../1", "a" * 40), ("svp", "123", "main")):
            with self.assertRaises(ValueError):
                release.identity(module, run, sha)

    def test_elf_alignment_and_machine(self):
        data = bytearray(128)
        data[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HHI", data, 16, 3, 183, 1)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HHH", data, 52, 64, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 128, 128, 16384)
        release.validate_elf(data, "arm64-v8a")
        with self.assertRaises(ValueError):
            release.validate_elf(data, "x86_64")
        struct.pack_into("<Q", data, 112, 4096)
        with self.assertRaises(ValueError):
            release.validate_elf(data, "arm64-v8a")


if __name__ == "__main__":
    unittest.main()
