"""Synthetic regressions for Monogram native and per-ABI APK-size budget checks."""
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "monogram_size_audit.py"
SPEC = importlib.util.spec_from_file_location("monogram_size_audit", SCRIPT)
assert SPEC and SPEC.loader
size = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(size)


def write_zip(path, files):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, payload in files.items():
            archive.writestr(name, payload)


class MonogramSizeAuditTests(unittest.TestCase):
    def test_raw_native_size_is_not_misrepresented_as_apk_growth(self):
        with tempfile.TemporaryDirectory() as tmp:
            aar = Path(tmp) / "native.aar"
            write_zip(aar, {"jni/arm64-v8a/libmonogram_mtproto.so": b"x" * 100, "classes.jar": b"a"})
            result, prose = size.native_audit(aar, "arm64-v8a", 1)
            self.assertEqual(result["mtproto_elf_bytes"], 100)
            self.assertFalse(result["apk_growth_measured"])
            self.assertIn("Not an APK delta", prose)

    def test_full_apk_delta_and_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, mono = Path(tmp) / "base.apk", Path(tmp) / "monogram.apk"
            write_zip(base, {"lib/arm64-v8a/libapp.so": b"x" * 50, "classes.dex": b"x" * 20})
            write_zip(mono, {"lib/arm64-v8a/libapp.so": b"x" * 50,
                             "lib/arm64-v8a/libmonogram_mtproto.so": b"z" * 500,
                             "classes.dex": b"x" * 30})
            report, output = size.apk_audit(base, mono, "arm64-v8a", 0.002)
            self.assertTrue(report["passes_apk_budget"])
            self.assertEqual(report["zip_contribution_delta_bytes"]["Native libraries"], 500)
            self.assertIn("APK growth", output)
            report, _ = size.apk_audit(base, mono, "arm64-v8a", 0.0001)
            self.assertFalse(report["passes_apk_budget"])

    def test_mismatched_abi_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, mono = Path(tmp) / "base.apk", Path(tmp) / "monogram.apk"
            write_zip(base, {"lib/x86/libapp.so": b"x"})
            write_zip(mono, {"lib/arm64-v8a/libmonogram_mtproto.so": b"z"})
            with self.assertRaisesRegex(ValueError, "single-ABI"):
                size.apk_audit(base, mono, "arm64-v8a", 15)

    def test_baseline_with_monogram_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, mono = Path(tmp) / "base.apk", Path(tmp) / "monogram.apk"
            files = {"lib/arm64-v8a/libmonogram_mtproto.so": b"x"}
            write_zip(base, files)
            write_zip(mono, files)
            with self.assertRaisesRegex(ValueError, "only in opt-in"):
                size.apk_audit(base, mono, "arm64-v8a", 15)


if __name__ == "__main__":
    unittest.main()
