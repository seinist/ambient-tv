import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import generate_update_manifest as generator


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.paths = []
        for abi in ["arm64-v8a", "armeabi-v7a"]:
            path = self.root / f"app-{abi}.apk"
            path.write_bytes(abi.encode())
            self.paths.append(path)
        self.bad_code = False
        self.unsigned = False
        self.mixed_signers = False

    def run_tool(self, args, **kwargs):
        if args[0] == "apksigner":
            if self.unsigned:
                raise subprocess.CalledProcessError(1, args)
            fingerprint = "b" if self.mixed_signers and "armeabi" in str(args[-1]) else "a"
            return subprocess.CompletedProcess(args, 0, stdout="Signer #1 certificate SHA-256 digest: " + fingerprint * 64 + "\n")
        path = Path(args[-1])
        abi = "arm64-v8a" if "arm64" in path.name else "armeabi-v7a"
        code = 11 if self.bad_code and abi == "armeabi-v7a" else 10
        return subprocess.CompletedProcess(args, 0, stdout=(
            f"package: name='com.ambient.tv' versionCode='{code}' versionName='1.0'\n"
            f"minSdkVersion:'29'\nnative-code: '{abi}'\n"))

    def generate(self, paths=None, expected=None):
        with patch.object(generator.subprocess, "run", side_effect=self.run_tool):
            return generator.generate(paths if paths is not None else self.paths, "aapt2", "apksigner",
                                      expected or ["arm64-v8a", "armeabi-v7a"])

    def test_final_bytes_determine_hash_size_and_version(self):
        result = self.generate()
        self.assertEqual(10, result["versionCode"])
        for apk in result["apks"]:
            data = (self.root / apk["name"]).read_bytes()
            self.assertEqual(len(data), apk["size"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), apk["sha256"])

    def test_different_versions_are_rejected(self):
        self.bad_code = True
        with self.assertRaises(ValueError):
            self.generate()

    def test_unsigned_apks_are_rejected(self):
        self.unsigned = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.generate()

    def test_expected_architecture_cannot_be_omitted(self):
        with self.assertRaises(ValueError):
            self.generate(self.paths[:1])

    def test_duplicate_abi_is_rejected(self):
        with self.assertRaises(ValueError):
            self.generate(self.paths + self.paths[:1])

    def test_missing_apk_is_rejected(self):
        self.paths[0].unlink()
        with self.assertRaises(ValueError):
            self.generate()

    def test_mixed_signing_keys_are_rejected(self):
        self.mixed_signers = True
        with self.assertRaises(ValueError):
            self.generate()

    def test_failed_generation_removes_previous_manifest(self):
        output = self.root / "update.json"
        output.write_text("old manifest")
        with patch("sys.argv", ["generator", "--output", str(output), "--aapt2", "aapt2",
                                 "--apksigner", "apksigner", "--expected-abis", "arm64-v8a"]):
            with self.assertRaises(ValueError):
                generator.main()
        self.assertFalse(output.exists())

    def test_empty_artifact_set_is_rejected(self):
        with self.assertRaises(ValueError):
            self.generate([])


if __name__ == "__main__":
    unittest.main()
