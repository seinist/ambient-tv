import gzip
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_moonlight_sources as package


class MoonlightSourcesTest(unittest.TestCase):
    def test_archive_contains_only_tracked_source_and_validates_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            out = Path(directory) / "out"
            out.mkdir()
            def git(*args):
                subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)
            git("init", "-q")
            (root / "source.c").write_text("int source;")
            git("add", "source.c")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "source")
            (root / "private.txt").write_text("not tracked")
            pin = package.git(root, "rev-parse", "HEAD")
            result = package.archive_checkout(root, pin, "fixture", out)
            with tarfile.open(out / result["archive"]) as archive:
                self.assertIn("fixture/source.c", archive.getnames())
                self.assertNotIn("fixture/private.txt", archive.getnames())
            self.assertEqual(result["sha256"], package.digest(out / result["archive"]))
            with self.assertRaises(ValueError):
                package.archive_checkout(root, "0"*40, "fixture", out)

    def test_complete_abi_and_notice_set_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def apk(abi, notices=True):
                with zipfile.ZipFile(root / (abi + ".apk"), "w") as archive:
                    archive.writestr(f"lib/{abi}/libmoonlight-core.so", b"fixture")
                    if notices:
                        for notice in package.NOTICES:
                            archive.writestr("assets/moonlight-licenses/" + notice, b"fixture")
            for abi in package.ABIS:
                apk(abi)
            self.assertEqual(set(package.collect_apks(root)), package.ABIS)
            apk("x86", False)
            with self.assertRaises(ValueError):
                package.collect_apks(root)
            apk("x86")
            (root / "x86.apk").unlink()
            with self.assertRaises(ValueError):
                package.collect_apks(root)


if __name__ == "__main__":
    unittest.main()
