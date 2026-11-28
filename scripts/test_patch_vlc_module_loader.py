import tempfile
import unittest
from pathlib import Path

from patch_vlc_module_loader import patch, patch_probe, MARKER, HELPER, PROBE_MARKER, LEGACY_HELPER


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

    def test_namespace_probe_is_idempotent_and_upgrades_legacy_helper(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            java = directory / "VLCUtil.java"
            java.write_text('class VLCUtil { void load() { System.loadLibrary("vlcjni"); } }')
            marker = directory / MARKER
            marker.parent.mkdir(parents=True)
            marker.write_text(LEGACY_HELPER)
            patch(directory)
            self.assertIn("public static native boolean vulkanModules();", marker.read_text())
            self.assertNotIn(r"{}\n", marker.read_text())
            native = directory / "libvlcjni-mediaplayer.c"
            native.write_text("void Java_org_videolan_libvlc_MediaPlayer_nativeSetTime() {}\n")
            patch_probe(directory)
            first = native.read_text()
            self.assertIn(PROBE_MARKER, first)
            patch_probe(directory)
            self.assertEqual(first, native.read_text())

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
