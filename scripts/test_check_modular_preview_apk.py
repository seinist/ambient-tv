import tempfile
import unittest
from pathlib import Path
import zipfile

import check_modular_preview_apk as preview


class ModularPreviewTest(unittest.TestCase):
    def make_apk(self, root, extra=(), omit=()):
        path = Path(root) / "preview.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name in sorted(preview.SHARED - set(omit)):
                archive.writestr("lib/arm64-v8a/" + name, b"native")
            for name in extra:
                archive.writestr(name, b"unexpected")
        return path

    def test_shared_runtime_remains_in_preview(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(preview.verify(self.make_apk(root)), {"arm64-v8a"})

    def test_excluded_payloads_fail(self):
        with tempfile.TemporaryDirectory() as root:
            for name in preview.EXTERNAL:
                with self.assertRaises(ValueError):
                    preview.verify(self.make_apk(root, ["lib/arm64-v8a/" + name]))
            with self.assertRaises(ValueError):
                preview.verify(self.make_apk(root, ["assets/games/slang/a.slang"]))

    def test_missing_shared_library_fails(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                preview.verify(self.make_apk(root, omit=["libavcodec.so"]))


if __name__ == "__main__":
    unittest.main()
