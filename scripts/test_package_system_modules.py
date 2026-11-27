import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest import mock
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

    def test_monogram_binds_all_native_packages_to_exact_jar(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            aar = directory / "monogram.aar"
            notice = directory / "NOTICE"
            notice.write_text("test license")
            jar = b"exact generated classes"
            with zipfile.ZipFile(aar, "w") as archive:
                archive.writestr("classes.jar", jar)
                for abi in packages.ABIS:
                    archive.writestr(f"jni/{abi}/libmonogram_mtproto.so", abi.encode())
            with mock.patch.object(packages, "native_dependencies", return_value={"libc.so"}):
                entries = packages.monogram(directory, 3, aar, [notice])
            self.assertEqual({entry["abi"] for entry in entries}, set(packages.ABIS))
            for entry in entries:
                self.assertEqual(entry["hostBindingSha256"], hashlib.sha256(jar).hexdigest())
                with zipfile.ZipFile(directory / entry["asset"]) as archive:
                    self.assertEqual(archive.read("lib/libmonogram_mtproto.so"), entry["abi"].encode())
                    self.assertNotIn("classes.jar", archive.namelist())

    def test_monogram_rejects_unapproved_dependency(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            aar = directory / "monogram.aar"
            with zipfile.ZipFile(aar, "w") as archive:
                archive.writestr("classes.jar", b"bindings")
                archive.writestr("jni/arm64-v8a/libmonogram_mtproto.so", b"native")
            with mock.patch.object(packages, "native_dependencies", return_value={"libunexpected.so"}):
                with self.assertRaisesRegex(ValueError, "unapproved Monogram"):
                    packages.monogram(directory, 1, aar, [])

    def test_independent_monogram_dex_package_contains_both_native_roots(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            dex = root / "dex"
            dex.mkdir()
            (dex / "host-binding.sha256").write_text("a" * 64)
            (dex / "classes.dex").write_bytes(b"dex\n039\0" + bytes(104))
            notice = root / "NOTICE"
            notice.write_text("test")
            apk = root / "full.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for abi in packages.ABIS:
                    for name in ("libmonogram_mtproto.so", "libjnidispatch.so"):
                        archive.writestr(f"lib/{abi}/{name}", name.encode())
            with mock.patch.object(packages, "native_dependencies", return_value={"libc.so"}):
                entries = packages.monogram_dex(root, 1, dex, [apk], [notice])
            self.assertEqual(len(entries), 4)
            for entry in entries:
                self.assertEqual(entry["hostBindingSha256"], "a" * 64)
                with zipfile.ZipFile(root / entry["asset"]) as archive:
                    self.assertEqual(set(archive.namelist()), {
                        "code/classes.dex", "lib/libmonogram_mtproto.so", "lib/libjnidispatch.so", "licenses/NOTICE",
                    })


if __name__ == "__main__":
    unittest.main()
