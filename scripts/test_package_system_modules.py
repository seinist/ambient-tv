import hashlib
import io
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
                {"assets/libc++_shared.so": b"x"},
                {"lib/libother+runtime.so": b"x"},
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

    def test_smarttube_packages_dex_and_j2v8_for_each_abi(self):
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
                    archive.writestr(f"lib/{abi}/libj2v8.so", abi.encode())
            with mock.patch.object(packages, "native_dependencies", return_value={"libc.so"}):
                entries = packages.adapter_dex(root, "smarttube", 1, dex, [apk], [notice])
            self.assertEqual(len(entries), 4)
            for entry in entries:
                self.assertEqual(entry["module"], "smarttube")
                self.assertEqual(entry["hostBindingSha256"], "a" * 64)
                with zipfile.ZipFile(root / entry["asset"]) as archive:
                    self.assertEqual(set(archive.namelist()), {
                        "code/classes.dex", "lib/libj2v8.so", "licenses/NOTICE",
                    })

    def test_vlc_dex_has_namespace_local_cpp_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            dex = root / "dex"
            dex.mkdir()
            (dex / "host-binding.sha256").write_text("a" * 64)
            (dex / "classes.dex").write_bytes(b"dex\n039\0" + bytes(104))
            apk = root / "full.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for abi in packages.ABIS:
                    for name in (*packages.NATIVE_ROOTS["vlc"], "libc++_shared.so"):
                        archive.writestr(f"lib/{abi}/{name}", name.encode())
            def dependencies(data, name):
                return {"libvlc.so", "libc++_shared.so"} if name == "libvlcjni.so" else {"libc.so"}
            with mock.patch.object(packages, "native_dependencies", side_effect=dependencies):
                entries = packages.adapter_dex(root, "vlc", 2, dex, [apk], [])
            self.assertEqual(len(entries), 4)
            for entry in entries:
                self.assertEqual(entry["hostNativeSha256"], {})
                with zipfile.ZipFile(root / entry["asset"]) as archive:
                    self.assertEqual(set(archive.namelist()), {
                        "code/classes.dex", "lib/libvlc.so", "lib/libvlcjni.so", "lib/libc++_shared.so",
                    })

    def test_libretro_dex_packages_only_transitive_private_native_dependencies(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            dex = root / "dex"
            dex.mkdir()
            (dex / "host-binding.sha256").write_text("a" * 64)
            (dex / "classes.dex").write_bytes(b"dex\n039\0" + bytes(104))
            apk = root / "full.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for abi in packages.ABIS:
                    for name in ("liblibretrodroid.so", "liboboe.so", "libc++_shared.so", "libadrenotools.so"):
                        archive.writestr(f"lib/{abi}/{name}", name.encode())
            def dependencies(data, name):
                return {"liboboe.so"} if name == "liblibretrodroid.so" else {"libc++_shared.so"} if name == "liboboe.so" else {"libc.so"}
            with mock.patch.object(packages, "native_dependencies", side_effect=dependencies):
                entries = packages.adapter_dex(root, "libretrodroid", 1, dex, [apk], [])
            self.assertEqual(len(entries), 4)
            for entry in entries:
                with zipfile.ZipFile(root / entry["asset"]) as archive:
                    self.assertEqual(set(archive.namelist()), {
                        "code/classes.dex", "lib/liblibretrodroid.so", "lib/liboboe.so", "lib/libc++_shared.so",
                    })

    def test_vlc_pins_patched_bindings_and_shared_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            aar = root / "vlc.aar"
            apk = root / "full.apk"
            notice = root / "NOTICE"
            notice.write_text("test")
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("org/videolan/libvlc/AmbientNativeLibraryLoader.class", b"loader")
            with zipfile.ZipFile(aar, "w") as archive:
                archive.writestr("classes.jar", jar.getvalue())
                for abi in packages.ABIS:
                    for name in packages.NATIVE_ROOTS["vlc"]:
                        archive.writestr(f"jni/{abi}/{name}", name.encode())
            binding = packages.vlc_binding(aar)
            self.assertEqual(binding, hashlib.sha256(jar.getvalue()).hexdigest())
            with zipfile.ZipFile(apk, "w") as archive:
                for abi in packages.ABIS:
                    for name in (*packages.NATIVE_ROOTS["vlc"], "libc++_shared.so"):
                        archive.writestr(f"lib/{abi}/{name}", name.encode())
            def dependencies(data, name):
                return {"libvlc.so", "libc++_shared.so"} if name == "libvlcjni.so" else {"libc.so"}
            with mock.patch.object(packages, "native_dependencies", side_effect=dependencies):
                entries = packages.native_module(root, "vlc", 1, [apk], [notice], binding)
            self.assertEqual(len(entries), 4)
            for entry in entries:
                self.assertEqual(entry["hostBindingSha256"], binding)
                self.assertEqual(entry["hostNativeSha256"], {
                    "libc++_shared.so": hashlib.sha256(b"libc++_shared.so").hexdigest(),
                })
                with zipfile.ZipFile(root / entry["asset"]) as archive:
                    self.assertEqual(set(archive.namelist()), {
                        "lib/libvlc.so", "lib/libvlcjni.so", "licenses/NOTICE",
                    })
            with self.assertRaisesRegex(ValueError, "binding fingerprint"):
                packages.native_module(root, "vlc", 2, [apk], [notice])

    def test_svp_libretro_and_shaders_require_matching_native_contract(self):
        for module in ("svp", "libretrodroid", "shaders", "moonlight"):
            with self.subTest(module=module), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                apk = root / "full.apk"
                notice = root / "NOTICE"
                notice.write_text("test")
                name = packages.NATIVE_ROOTS[module][0]
                with zipfile.ZipFile(apk, "w") as archive:
                    for abi in packages.ABIS:
                        archive.writestr(f"lib/{abi}/{name}", abi.encode())
                with self.assertRaisesRegex(ValueError, "binding fingerprint"):
                    packages.native_module(root, module, 1, [apk], [notice])
                with mock.patch.object(packages, "native_dependencies", return_value={"libc.so"}), \
                     mock.patch.object(packages, "shader_assets", return_value={"assets/games/slang/a.slangp": b"preset"}):
                    entries = packages.native_module(root, module, 1, [apk], [notice], "b" * 64)
                self.assertEqual(len(entries), 4)
                for entry in entries:
                    self.assertEqual(entry["hostBindingSha256"], "b" * 64)
                    with zipfile.ZipFile(root / entry["asset"]) as archive:
                        expected = {"lib/" + name, "licenses/NOTICE"}
                        if module == "shaders":
                            expected.add("assets/games/slang/a.slangp")
                        self.assertEqual(set(archive.namelist()), expected)


if __name__ == "__main__":
    unittest.main()
