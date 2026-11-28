import tempfile
from pathlib import Path
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
