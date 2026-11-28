import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import verify_paired_apk_release as paired


class PairedReleaseTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.full, self.modular = (Path(temp.name) / x for x in ("full", "modular"))
        for directory in (self.full, self.modular):
            directory.mkdir()
            for abi in paired.updates.ABIS:
                with zipfile.ZipFile(directory / f"{directory.name}-{abi}.apk", "w") as archive:
                    archive.writestr(f"lib/{abi}/libc++_shared.so", b"same")
            (directory / ("update.json" if directory == self.full else "update-modular.json")).write_text("{}")
        self.addCleanup(patch.stopall)
        patch.object(paired.updates, "generate", return_value={}).start()
        patch.object(paired.updates, "verify_packaging").start()
        patch.object(paired.preview, "verify", side_effect=lambda path, **_: {path.stem.removeprefix("modular-")}).start()

    def inspect(self, path, *_):
        return ("com.ambient.tv", 1, "1.0", 29, ("signer",)), {"abi": path.stem.split("-", 1)[1]}

    def test_matching_pair(self):
        with patch.object(paired.updates, "inspect_apk", side_effect=self.inspect):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")

    def test_signing_identity_mismatch(self):
        def inspect(path, *args):
            identity, entry = self.inspect(path, *args)
            if path.parent == self.modular:
                identity = (*identity[:-1], ("different",))
            return identity, entry
        with patch.object(paired.updates, "inspect_apk", side_effect=inspect), self.assertRaises(ValueError):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")

    def test_shared_library_mismatch(self):
        path = self.modular / "modular-x86.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("lib/x86/libc++_shared.so", b"changed")
        with patch.object(paired.updates, "inspect_apk", side_effect=self.inspect), self.assertRaises(ValueError):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")

    def test_missing_abi(self):
        (self.modular / "modular-x86.apk").unlink()
        with patch.object(paired.updates, "inspect_apk", side_effect=self.inspect), self.assertRaises(ValueError):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")


if __name__ == "__main__":
    unittest.main()
