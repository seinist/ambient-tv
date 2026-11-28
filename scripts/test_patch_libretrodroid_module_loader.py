import tempfile
import unittest
from pathlib import Path
from patch_libretrodroid_module_loader import patch, LOAD, REPLACEMENT


class LibretroLoaderTest(unittest.TestCase):
    def test_exact_load_site_is_patched_once(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "LibretroDroid.java"
            path.write_text("class LibretroDroid { static {\n" + LOAD + "\n} }")
            patch(path)
            first = path.read_text()
            self.assertIn(REPLACEMENT, first)
            patch(path)
            self.assertEqual(first, path.read_text())

    def test_changed_upstream_fails_without_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "LibretroDroid.java"
            path.write_text("class LibretroDroid {}")
            with self.assertRaisesRegex(ValueError, "load site changed"):
                patch(path)
            self.assertEqual(path.read_text(), "class LibretroDroid {}")


if __name__ == "__main__":
    unittest.main()
