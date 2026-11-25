"""Regression checks for the curated Slang dependency/provenance validator."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check_game_slang.py"
SPEC = importlib.util.spec_from_file_location("check_game_slang", SCRIPT)
slang = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(slang)


class SlangAssetValidationTest(unittest.TestCase):
    def test_every_selectable_preset_has_its_complete_pinned_dependency_closure(self):
        sources, size = slang.validate_assets()
        self.assertEqual(len(slang.PRESETS), 17)
        self.assertEqual(len(sources), 25)
        self.assertLessEqual(size, slang.MAX_TOTAL_ASSETS)

    def test_upstream_relative_reference_can_reuse_a_shared_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            preset = root / "upstream/scalefx/scalefx.slangp"
            preset.parent.mkdir(parents=True)
            (root / "upstream/stock.slang").write_text("shader")
            resolved = slang.resolve_source(preset, "../stock.slang", root)
            self.assertEqual(resolved, "upstream/stock")

    def test_dependency_cannot_escape_the_asset_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "assets"
            root.mkdir()
            external = Path(folder) / "external.slang"
            external.write_text("shader")
            preset = root / "preset.slangp"
            for reference in ("../external.slang", str(external)):
                with self.subTest(reference=reference), self.assertRaises(AssertionError):
                    slang.resolve_source(preset, reference, root)

    def test_unreviewed_imports_or_duplicate_directives_are_rejected(self):
        for preset in ('#reference "external.slangp"', "shaders = 1\nshaders = 2"):
            with self.subTest(preset=preset), self.assertRaises(AssertionError):
                slang.parse_preset(preset)

    def test_pinned_scalefx_preserves_float_passes_aliases_and_3x_output(self):
        path = slang.SHADERS / "upstream/edge-smoothing/scalefx/scalefx.slangp"
        values = slang.parse_preset(path.read_text())
        self.assertEqual(values["alias0"], "refpass")
        self.assertEqual(values["alias1"], "scalefx_pass0")
        self.assertEqual(values["float_framebuffer1"], "true")
        self.assertEqual(values["float_framebuffer2"], "true")
        self.assertEqual(values["scale5"], "3.0")


if __name__ == "__main__":
    unittest.main()
