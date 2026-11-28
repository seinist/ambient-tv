import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import update_external_libraries as updater

class MoonlightUpdateTest(unittest.TestCase):
    def test_latest_stable_numeric_tag(self):
        with mock.patch.object(updater, "github", return_value=[
            {"tag_name": "v12.2"}, {"tag_name": "v99.0", "prerelease": True},
            {"tag_name": "v12.10"}, {"tag_name": "v100.0", "draft": True}
        ]), mock.patch.object(updater, "resolve_tag_commit", return_value="a"*40):
            result = updater.moonlight_latest(updater.load_props())
        self.assertEqual(result["MOONLIGHT_ANDROID_TAG"], "v12.10")
        self.assertEqual(result["MOONLIGHT_ANDROID_REF"], "a"*40)

    def test_prepare_timeout_is_failure(self):
        with mock.patch.object(updater.subprocess, "run", side_effect=subprocess.TimeoutExpired("git", 600)):
            self.assertFalse(updater.prepare_moonlight())

    def test_check_and_rollback_are_scoped(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.properties"
            original = updater.PROPS.read_bytes()
            path.write_bytes(original)
            new = {"MOONLIGHT_ANDROID_TAG": "v99.1", "MOONLIGHT_ANDROID_REF": "a"*40}
            with contextlib.ExitStack() as stack:
                stack.enter_context(mock.patch.object(updater, "PROPS", path))
                stack.enter_context(mock.patch.object(updater, "moonlight_latest", return_value=new))
                prepare = stack.enter_context(mock.patch.object(updater, "prepare_moonlight", side_effect=[False, True]))
                stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
                with mock.patch.object(sys, "argv", ["updater", "--check", "--only", "moonlight"]):
                    updater.main()
                self.assertEqual(path.read_bytes(), original)
                prepare.assert_not_called()
                with mock.patch.object(sys, "argv", ["updater", "--apply", "--only", "moonlight"]):
                    self.assertEqual(updater.main(), 0)
                self.assertEqual(prepare.call_count, 2)
                self.assertEqual(path.read_bytes(), original)

if __name__ == "__main__":
    unittest.main()
