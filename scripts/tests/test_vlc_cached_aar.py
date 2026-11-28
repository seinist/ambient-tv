"""Exercise the actual cache verifier for all ABIs and negative provenance cases."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

VERIFIER = Path(__file__).resolve().parents[1] / "verify_vlc_cached_aar.py"
ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}


def elf_header(abi):
    elf_class, machine = ABIS[abi]
    result = bytearray(52 if elf_class == 1 else 64)
    result[:7] = b"\x7fELF" + bytes([elf_class, 1, 1])
    result[16:18] = (3).to_bytes(2, "little")
    result[18:20] = machine.to_bytes(2, "little")
    return bytes(result)


class VlcCachedAarTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "--allow-empty", "-qm", "fixture"], cwd=self.root, check=True)
        self.head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.directory = self.root / "native/vlc-aar/build/experimental"
        self.directory.mkdir(parents=True)
        self.aar = self.directory / "libvlc-vulkan-ahb-experimental.aar"
        self.receipt = self.directory / "native-link-receipt.txt"

    def fixture(self, abi, actual_abi=None, bad_digest=False):
        data = elf_header(actual_abi or abi)
        with zipfile.ZipFile(self.aar, "w") as archive:
            archive.writestr("assets/ambient-vlc-extended-v1", b"AHB DOVI FEL HDR10PLUS\n")
            for name in ("libvlc.so", "libvlcjni.so"):
                archive.writestr(f"jni/{abi}/{name}", data)
        digest = hashlib.sha256(data).hexdigest()
        self.receipt.write_text("source_hash=source\ntoolchain_hash=toolchain\nambient_head=original\n"
                                f"abi={abi}\n{digest}  libvlc.so\n"
                                f"{'0'*64 if bad_digest else digest}  libvlcjni.so\n"
                                f"aar_sha256={hashlib.sha256(self.aar.read_bytes()).hexdigest()}\n")

    def verify(self, abi, source="source", toolchain="toolchain"):
        return subprocess.run([sys.executable, str(VERIFIER), abi, source, toolchain],
                              cwd=self.root, capture_output=True, text=True)

    def test_accepts_all_four_abis_and_updates_provenance(self):
        for abi in ABIS:
            with self.subTest(abi=abi):
                self.fixture(abi)
                result = self.verify(abi)
                self.assertEqual(0, result.returncode, result.stderr)
                lines = self.receipt.read_text().splitlines()
                self.assertIn("ambient_head=" + self.head, lines)
                self.assertIn("cached_from_head=original", lines)

    def test_rejects_mislabeled_elf_even_with_matching_digests(self):
        for abi, actual in (("x86", "armeabi-v7a"), ("arm64-v8a", "x86_64"),
                            ("x86_64", "x86"), ("armeabi-v7a", "arm64-v8a")):
            with self.subTest(abi=abi, actual=actual):
                self.fixture(abi, actual)
                before = self.receipt.read_bytes()
                result = self.verify(abi)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("ELF ABI mismatch", result.stderr)
                self.assertEqual(before, self.receipt.read_bytes())

    def test_rejects_wrong_source_toolchain_and_digest(self):
        self.fixture("x86")
        for args, message in ((("x86", "different"), "source identity mismatch"),
                              (("x86", "source", "different"), "toolchain identity mismatch")):
            result = self.verify(*args)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(message, result.stderr)
        self.fixture("x86", bad_digest=True)
        result = self.verify("x86")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("digest mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
