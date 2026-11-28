import tempfile
import struct
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

    def test_definitions_not_references_control_monogram_exclusion(self):
        def dex(descriptor, defined):
            text = descriptor.encode("ascii")
            data = bytearray(152 + 1 + len(text) + 1)
            data[:8] = b"dex\n039\0"
            for offset, value in ((32, len(data)), (36, 112), (40, 0x12345678),
                                  (56, 1), (60, 112), (64, 1), (68, 116),
                                  (96, int(defined)), (100, 120 if defined else 0),
                                  (112, 152), (116, 0), (120, 0)):
                struct.pack_into("<I", data, offset, value)
            data[152] = len(text)
            data[153:153 + len(text)] = text
            return bytes(data)
        module = "Lcom/ambient/tv/data/telegram/MonogramNativeBackend;"
        preview.verify_no_optional_classes(dex(module, False))
        preview.verify_no_optional_classes(dex("Lcom/ambient/tv/data/telegram/MonogramModuleFactory;", True))
        with self.assertRaisesRegex(ValueError, "bundled Monogram"):
            preview.verify_no_optional_classes(dex(module, True))
        with self.assertRaisesRegex(ValueError, "bundled Monogram"):
            preview.verify_no_optional_classes(dex("Lcom/sun/jna/Native;", True))

        for name in ("Lcom/liskovsoft/youtubeapi/service/YouTubeServiceManager;",
                     "Lcom/eclipsesource/v8/V8;",
                     "Lcom/ambient/tv/youtube/SmartTubeModuleAdapter;"):
            preview.verify_no_optional_classes(dex(name, False))
            with self.assertRaisesRegex(ValueError, "bundled SmartTube"):
                preview.verify_no_optional_classes(dex(name, True))
        preview.verify_no_optional_classes(dex("Lcom/liskovsoft/youtubeapi/R$raw;", True))

    def test_vlc_custom_build_must_not_ship_native_roots(self):
        with tempfile.TemporaryDirectory() as root:
            for name in preview.VLC_EXTERNAL:
                apk = self.make_apk(root, ["lib/arm64-v8a/" + name])
                self.assertEqual(preview.verify(apk), {"arm64-v8a"})
                with self.assertRaises(ValueError):
                    preview.verify(apk, expect_vlc_external=True)
            self.assertEqual(preview.verify(self.make_apk(root), expect_vlc_external=True), {"arm64-v8a"})


if __name__ == "__main__":
    unittest.main()
