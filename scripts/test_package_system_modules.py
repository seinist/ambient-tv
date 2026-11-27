import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

import package_system_modules as packages


class SystemModulePackageTest(unittest.TestCase):
    def test_deterministic_regular_file_zip_and_digest(self):
        with tempfile.TemporaryDirectory() as root:
            a = Path(root) / "a"
            b = Path(root) / "b"
            a.mkdir()
            b.mkdir()
            files = {"assets/other presets/a.slang": b"shader", "licenses/NOTICE": b"notice"}
            first = packages.write_package(a, "shaders", "any", 1, files)
            second = packages.write_package(b, "shaders", "any", 1, dict(reversed(list(files.items()))))
            self.assertEqual(first, second)
            raw = (a / first["asset"]).read_bytes()
            self.assertEqual(raw, (b / second["asset"]).read_bytes())
            self.assertEqual(first["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(first["size"], len(raw))
            with zipfile.ZipFile(a / first["asset"]) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.comment, b"")
                self.assertTrue(all(info.external_attr >> 16 == 0o100444 for info in archive.infolist()))

    def test_reject_unsafe_paths(self):
        with tempfile.TemporaryDirectory() as root:
            for files in (
                {"assets/../bad": b"x"},
                {"assets/foo ": b"x"},
                {"assets/A": b"x", "assets/a": b"x"},
                {"assets/foo\\bar": b"x"},
            ):
                with self.assertRaises(ValueError):
                    packages.write_package(Path(root), "shaders", "any", 1, files)

    def test_missing_native_abi_fails(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                packages.native_module(Path(root), "projectm", 1, [], [])


if __name__ == "__main__":
    unittest.main()
