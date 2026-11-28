import contextlib
import io
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import update_external_libraries as updater

NEW = "b" * 40

class LibretroUpdateTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "gradle").mkdir()
        (self.root / "cores").mkdir()
        self.props = self.root / "gradle/external-versions.properties"
        self.catalog = self.root / "cores/cores.json"
        shutil.copyfile(updater.PROPS, self.props)
        self.catalog.write_text('{"cores": []}\n')
        self.original = self.props.read_bytes()

    def run_updater(self, mode, prepare=True):
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(updater, "ROOT", self.root))
            stack.enter_context(mock.patch.object(updater, "PROPS", self.props))
            stack.enter_context(mock.patch.object(updater, "libretrodroid_latest", return_value={"LIBRETRODROID_COMMIT": NEW}))
            self.prepare = stack.enter_context(mock.patch.object(updater, "prepare_libretrodroid", side_effect=[prepare, True]))
            stack.enter_context(mock.patch.object(sys, "argv", ["updater", mode, "--only", "libretro"]))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            return updater.main()

    def test_library_check_is_read_only(self):
        self.assertEqual(self.run_updater("--check"), 0)
        self.assertEqual(self.props.read_bytes(), self.original)
        self.prepare.assert_not_called()

    def test_library_update_never_updates_core_catalog(self):
        before = self.catalog.read_bytes()
        self.assertEqual(self.run_updater("--apply"), 0)
        self.assertEqual(self.catalog.read_bytes(), before)
        self.assertIn(f"LIBRETRODROID_COMMIT={NEW}", self.props.read_text())
        self.prepare.assert_called_once()

    def test_library_patch_failure_keeps_old_pin(self):
        before = self.catalog.read_bytes()
        self.assertEqual(self.run_updater("--apply", False), 0)
        self.assertEqual(self.props.read_bytes(), self.original)
        self.assertEqual(self.catalog.read_bytes(), before)
        self.assertEqual(self.prepare.call_count, 2)

if __name__ == "__main__":
    unittest.main()
