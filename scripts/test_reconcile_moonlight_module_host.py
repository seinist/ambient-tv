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
