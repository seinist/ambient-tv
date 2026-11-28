import tempfile
from pathlib import Path
import unittest

import reconcile_moonlight_module_host as moonlight


class MoonlightModuleHostTest(unittest.TestCase):
    def fixture(self, root):
        for relative, changes in moonlight.CHANGES.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join(before for before, _ in changes))

    def test_integration_is_idempotent_and_checkable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            with self.assertRaises(ValueError):
                moonlight.reconcile(root, check=True)
            self.assertEqual(len(moonlight.CHANGES), moonlight.reconcile(root))
            self.assertEqual(0, moonlight.reconcile(root, check=True))
            self.assertEqual(0, moonlight.reconcile(root))

    def test_transport_operations_cannot_bypass_module_availability(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            moonlight.reconcile(root)
            path = root / "app/src/moonlight/java/com/ambient/tv/moonlight/MoonlightSystemTransport.kt"
            text = path.read_text()
            self.assertEqual(5, text.count("requireAvailable()") - 1)
            self.assertIn("available && backend.quitRemoteApp", text)
            self.assertIn("else MoonlightPairResult.Unavailable", text)
            # Even one regressed guard invalidates the complete build seam.
            path.write_text(text.replace("requireAvailable(); return backend.loadArtwork", "return backend.loadArtwork"))
            with self.assertRaises(ValueError):
                moonlight.reconcile(root, check=True)

    def test_drift_never_partially_modifies_other_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            paths = [root / path for path in moonlight.CHANGES]
            paths[-1].write_text("upstream changed")
            before = {path: path.read_bytes() for path in paths}
            with self.assertRaises(ValueError):
                moonlight.reconcile(root)
            self.assertEqual(before, {path: path.read_bytes() for path in paths})


if __name__ == "__main__":
    unittest.main()
