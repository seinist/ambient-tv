import tempfile
import unittest
from pathlib import Path

from patch_vlc_module_loader import patch, MARKER, HELPER


class VlcModuleLoaderPatchTest(unittest.TestCase):
    def test_patch_is_idempotent_and_preserves_shared_runtime(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            java = directory / "VLCUtil.java"
            java.write_text('class VLCUtil { void load() { System.loadLibrary("c++_shared"); System.loadLibrary("vlc"); System.loadLibrary("vlcjni"); } }')
            patch(directory)
            first = java.read_text()
            self.assertIn('System.loadLibrary("c++_shared")', first)
            self.assertIn('AmbientNativeLibraryLoader.load("vlc")', first)
            self.assertIn('AmbientNativeLibraryLoader.load("vlcjni")', first)
            self.assertEqual((directory / MARKER).read_text(), HELPER)
            patch(directory)
            self.assertEqual(java.read_text(), first)

    def test_unrecognized_upstream_load_site_fails_without_modifying_sources(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            java = directory / "LibVLC.java"
            original = 'class LibVLC { void load() { System.loadLibrary("vlc"); } }'
            java.write_text(original)
            with self.assertRaisesRegex(ValueError, "JNI load site"):
                patch(directory)
            self.assertEqual(java.read_text(), original)
            self.assertFalse((directory / MARKER).exists())


if __name__ == "__main__":
    unittest.main()
