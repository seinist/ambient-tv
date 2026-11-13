"""Regression guard for the wordmark's TV badge across exports and intro."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SVG_NS = {"s": "http://www.w3.org/2000/svg"}


def _translate(value):
    match = re.search(r"translate\(\s*([-+\d.]+)[ ,]+([-+\d.]+)", value)
    if not match:
        raise AssertionError(f"Missing translate: {value}")
    return float(match.group(1)), float(match.group(2))


class BrandTvBadgeAlignmentTest(unittest.TestCase):
    def test_master_badge_is_geometrically_centered(self):
        root = ET.parse(ROOT / "branding/svg/ambient-tv-combo.svg").getroot()
        badge = root.find(".//s:rect[@id='brand-tv-badge']", SVG_NS)
        label = root.find(".//s:g[@id='brand-tv-label']", SVG_NS)
        self.assertIsNotNone(badge)
        self.assertIsNotNone(label)
        x, y = _translate(label.get("transform", ""))
        badge_x = float(badge.get("x"))
        badge_y = float(badge.get("y"))
        badge_width = float(badge.get("width"))
        badge_height = float(badge.get("height"))

        # Existing T ink spans x=4..598, V spans -5..734 at 0.01x;
        # the second path is offset 6.02 units horizontally.
        glyph_left = min(4 * .01, 6.02 - 5 * .01)
        glyph_right = max(598 * .01, 6.02 + 734 * .01)
        self.assertAlmostEqual(x + (glyph_left + glyph_right) / 2,
                               badge_x + badge_width / 2, places=3)
        self.assertAlmostEqual(y - 700 * .01 / 2,
                               badge_y + badge_height / 2, places=3)

    def test_svg_derivatives_match_master(self):
        root = ET.parse(ROOT / "branding/svg/ambient-tv-combo.svg").getroot()
        label = root.find(".//s:g[@id='brand-tv-label']", SVG_NS)
        expected = _translate(label.get("transform", ""))
        variants = [
            "branding/svg/ambient-tv-wordmark.svg",
            "branding/svg/app/ambient-tv-combo.svg",
            "branding/svg/app/ambient-tv-wordmark.svg",
            "app/src/main/assets/branding/ambient-tv-combo.svg",
            "app/src/main/assets/branding/ambient-tv-wordmark.svg",
            "docs/assets/wordmark.svg",
        ]
        for path in variants:
            with self.subTest(path=path):
                doc = ET.parse(ROOT / path).getroot()
                copy = doc.find(".//s:g[@id='brand-tv-label']", SVG_NS)
                self.assertIsNotNone(copy)
                self.assertEqual(expected, _translate(copy.get("transform", "")))
        banner = (ROOT / "branding/svg/ambient-tv-banner.svg").read_text()
        self.assertIn(f'translate({expected[0]:.9f} {expected[1]})', banner)
        preview = (ROOT / "branding/preview/branding-overview.svg").read_text()
        self.assertEqual(3, preview.count(f'translate({expected[0]:.9f}'))

    def test_android_vectors_and_intros_match_master(self):
        root = ET.parse(ROOT / "branding/svg/ambient-tv-combo.svg").getroot()
        label = root.find(".//s:g[@id='brand-tv-label']", SVG_NS)
        label_x, label_y = _translate(label.get("transform", ""))
        for path in [
            "app/src/main/res/drawable/ic_brand_wordmark.xml",
            "app/src/main/res/drawable/app_banner.xml",
        ]:
            with self.subTest(path=path):
                content = (ROOT / path).read_text()
                self.assertIn(f'android:translateX="{label_x}"', content)
                self.assertIn(f'android:translateY="{label_y}"', content)

        # The intro's drawing coordinates are relative to the (49, 28)
        # wordmark origin, so the same geometry must be used in each intro.
        intro = (ROOT / "app/src/main/java/com/ambient/tv/ui/intro/IntroAnimationOverlay.kt").read_text()
        match = re.search(r'LABEL_ORIGIN\s*=\s*Offset\(([-+\d.]+)f,\s*([-+\d.]+)f\)', intro)
        self.assertIsNotNone(match)
        self.assertAlmostEqual(float(match.group(1)), label_x - 49, places=3)
        self.assertAlmostEqual(float(match.group(2)), label_y - 28, places=3)


if __name__ == "__main__":
    unittest.main()
