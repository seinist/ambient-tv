import tempfile
from pathlib import Path
import shutil
import subprocess
import unittest
from unittest import mock

import build_j2v8_from_source as build
from package_system_modules import ABIS


class SourceBuildTest(unittest.TestCase):
    def test_all_abis_have_pinned_upstream_gn_config(self):
        self.assertEqual(set(build.ABI_CONFIGS), set(ABIS))
        self.assertIn('":v8_monolith"', build.BRIDGE)
        self.assertIn("max-page-size=16384", build.BRIDGE)

    def test_v8_handle_alias_migration_keeps_strict_gn_options(self):
        old = "v8::Handle<v8::Context> a;\n" + "Handle<Value> b;\n" * 121 + "HandleScope scope;"
        old += '\n' + '#pragma comment(lib, "userenv.lib")\n' * 5
        new = build.modernize_bridge(old)
        self.assertNotIn(".lib", new)
        self.assertIn("v8::Local<v8::Context>", new)
        self.assertEqual(new.count("Local<"), 122)
        self.assertIn("HandleScope scope;", new)
        with self.assertRaisesRegex(ValueError, "alias count"):
            build.modernize_bridge("Handle<Value> value;")

    def test_arm64_page_alignment_preserves_host_default(self):
        old = 'ldflags += [ "-Wl,-z,max-page-size=4096" ]'
        patched = build.patch_android_page_alignment(old)
        self.assertIn('if (is_android) {', patched)
        self.assertIn('ldflags += [ "-Wl,-z,max-page-size=16384" ]', patched)
        self.assertIn('} else {', patched)
        self.assertIn(old, patched)
        with self.assertRaises(ValueError):
            build.patch_android_page_alignment("changed")
        self.assertIn("hide_all_but_jni", build.BRIDGE)

    def test_static_runtime_does_not_allow_shared_stl(self):
        self.assertIn("-static-libstdc++", build.BRIDGE)
        self.assertEqual('libs += [ "c++_static" ]',
                         build.patch_android_cxx_runtime('libs += [ "c++_shared" ]'))
        self.assertEqual('libs += [ "log" ]',
                         build.patch_android_cxx_runtime('libs += [ "log" ]'))
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            candidate, legacy = path / "new.so", path / "old.so"
            candidate.write_bytes(b"candidate")
            legacy.write_bytes(b"legacy")
            with mock.patch.object(build, "validate_elf"), mock.patch.object(
                    build, "native_dependencies", return_value={"libc++_shared.so"}):
                with self.assertRaisesRegex(ValueError, "non-platform"):
                    build.verify_candidate(candidate, legacy, "arm64-v8a")

    def test_gn_link_preflight_rejects_bad_effective_flags(self):
        valid = "-static-libstdc++\n-Wl,-z,max-page-size=16384\n"
        build.verify_gn_link_plan("log\nandroid\n", valid)
        for libs, flags in (("c++_shared\n", valid),
                            ("log", valid + "-Wl,-z,max-page-size=4096\n"),
                            ("log", "-Wl,-z,max-page-size=16384"),
                            ("log", "-static-libstdc++")):
            with self.assertRaises(ValueError):
                build.verify_gn_link_plan(libs, flags)

    @unittest.skipUnless(shutil.which("cc") and shutil.which("readelf") and
                         __import__("platform").machine() == "x86_64", "requires x86_64 host C compiler and readelf")
    def test_real_elf_dependency_and_jni_validation(self):
        # Actual ELF binaries, not mocked dependency readers. No Android runtime
        # or V8 is required for testing these packaging/ELF contracts.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def compile_elf(name, body, page=16384, extra=()):
                source = root / (name + ".c")
                source.write_text(body)
                output = root / name
                subprocess.run(["cc", "-shared", "-fPIC", "-nostdlib",
                                f"-Wl,-z,max-page-size={page}", "-Wl,-soname,libj2v8.so",
                                str(source), *extra, "-o", str(output)], check=True)
                return output
            code = "int Java_com_eclipsesource_v8_V8_test(void) { return 42; }"
            legacy = compile_elf("old.so", code)
            valid = compile_elf("new.so", code.replace("42", "43"))
            evidence = build.verify_candidate(valid, legacy, "x86_64")
            self.assertFalse(evidence["matchesLegacy"])
            self.assertEqual(evidence["needed"], [])
            bad_page = compile_elf("bad-page.so", code, page=4096)
            with self.assertRaisesRegex(ValueError, "16-KiB"):
                build.verify_candidate(bad_page, legacy, "x86_64")
            with self.assertRaisesRegex(ValueError, "Wrong ELF"):
                build.verify_candidate(valid, legacy, "arm64-v8a")
            missing = compile_elf("missing.so", code.replace("V8_test", "V8_other"))
            with self.assertRaisesRegex(ValueError, "Missing legacy"):
                build.verify_candidate(missing, legacy, "x86_64")
            (root / "stl.c").write_text("int private_stl(void) { return 1; }")
            subprocess.run(["cc", "-shared", "-fPIC", "-nostdlib",
                            "-Wl,-soname,libc++_shared.so", str(root / "stl.c"),
                            "-o", str(root / "libc++_shared.so")], check=True)
            bad_dep = compile_elf("bad-dep.so",
                "extern int private_stl(void); int Java_com_eclipsesource_v8_V8_test(void) { return private_stl(); }",
                extra=("-L" + str(root), "-l:libc++_shared.so"))
            with self.assertRaisesRegex(ValueError, "non-platform dependencies"):
                build.verify_candidate(bad_dep, legacy, "x86_64")

    def test_missing_jni_and_private_dependencies_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            candidate, legacy = path / "new.so", path / "old.so"
            candidate.write_bytes(b"candidate")
            legacy.write_bytes(b"legacy")
            with mock.patch.object(build, "validate_elf"), mock.patch.object(
                    build, "native_dependencies", return_value={"libprivate.so"}):
                with self.assertRaisesRegex(ValueError, "non-platform"):
                    build.verify_candidate(candidate, legacy, "arm64-v8a")
            with mock.patch.object(build, "validate_elf"), mock.patch.object(
                    build, "native_dependencies", return_value={"libc.so"}), mock.patch.object(
                    build, "jni_exports", side_effect=[{"required"}, {"other"}]):
                with self.assertRaisesRegex(ValueError, "Missing legacy"):
                    build.verify_candidate(candidate, legacy, "arm64-v8a")

    def test_staging_rejects_missing_abi_before_writing_output(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            with self.assertRaisesRegex(ValueError, "Missing source-build evidence"):
                build.stage_candidates(path, path / "staged", path)
            self.assertFalse((path / "staged").exists())

    def test_source_build_verification_does_not_claim_legacy_match(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            candidate, legacy = path / "new.so", path / "old.so"
            candidate.write_bytes(b"candidate")
            legacy.write_bytes(b"legacy")
            with mock.patch.object(build, "validate_elf"), mock.patch.object(
                    build, "native_dependencies", return_value={"libc.so"}), mock.patch.object(
                    build, "jni_exports", return_value={"Java_com_eclipsesource_v8_V8_test"}), mock.patch.object(
                    build, "elf_text_digest", return_value="a" * 64):
                evidence = build.verify_candidate(candidate, legacy, "arm64-v8a")
            self.assertFalse(evidence["matchesLegacy"])
            self.assertEqual(evidence["sha256"], build.sha(candidate))


if __name__ == "__main__":
    unittest.main()
