import struct
import unittest

import prepare_native_module_release as release


class NativeModuleReleaseTest(unittest.TestCase):
    def test_license_archive_matches_source_notice_bytes(self):
        import hashlib
        import io
        import json
        import zipfile
        source = {"module": "svp"}
        notices = {"source-0/LICENSE": (7, hashlib.sha256(b"license").hexdigest())}
        def archive(content=b"license", name="source-0/LICENSE", extra=None):
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as out:
                out.writestr("Ambient-TV-LICENSE", (release.ROOT / "LICENSE").read_bytes())
                out.writestr("corresponding-source.json", json.dumps(source))
                out.writestr(name, content)
                if extra:
                    out.writestr(extra, b"extra")
            return data.getvalue()
        valid = archive()
        self.assertEqual(release.verify_notices(valid, source, notices), hashlib.sha256(valid).hexdigest())
        for data in (archive(b"changed"), archive(name="../LICENSE"), archive(extra="source-0/OTHER"),
                     archive(extra="source-0/license")):
            with self.assertRaises(ValueError):
                release.verify_notices(data, source, notices)

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
