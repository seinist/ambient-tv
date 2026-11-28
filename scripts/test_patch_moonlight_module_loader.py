import tempfile
import unittest
from pathlib import Path

from patch_moonlight_module_loader import BRIDGE, MARKER, LOAD, REPLACEMENT, patch, binding


class MoonlightModulePreparationTest(unittest.TestCase):
    def prepare(self, root):
        bridge = root / BRIDGE
        bridge.parent.mkdir(parents=True)
        bridge.write_text("public class MoonBridge { static {\n" + LOAD + "\n        init();\n} }")
        (root / MARKER).write_text("android=pin\ncommon-c=pin\nenet=pin\npatch=reviewed\n")
        return bridge

    def test_patch_preserves_native_initialization_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bridge = self.prepare(root)
            with self.assertRaisesRegex(ValueError, "unpatched"):
                binding(root)
            patch(bridge)
            first = bridge.read_text()
            self.assertIn(REPLACEMENT, first)
            self.assertIn("init();", first)
            patch(bridge)
            self.assertEqual(bridge.read_text(), first)
            self.assertEqual(binding(root), binding(root))

    def test_native_optimization_revision_requires_matched_package(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bridge = self.prepare(root)
            patch(bridge)
            first = binding(root)
            (root / MARKER).write_text("android=pin\ncommon-c=pin\nenet=pin\npatch=new-optimization\n")
            self.assertNotEqual(first, binding(root))
            second = binding(root)
            bridge.write_text(bridge.read_text() + "\n// new JNI contract\n")
            self.assertNotEqual(second, binding(root))

    def test_upstream_drift_is_not_silently_patched(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bridge = self.prepare(root)
            original = bridge.read_text().replace(LOAD, '        System.loadLibrary("different");')
            bridge.write_text(original)
            with self.assertRaisesRegex(ValueError, "load site changed"):
                patch(bridge)
            self.assertEqual(bridge.read_text(), original)


if __name__ == "__main__":
    unittest.main()
