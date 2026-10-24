import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import baseline_profile as profile


class BaselineProfileMergeTest(unittest.TestCase):
    def test_only_app_rules_are_added_sorted_and_deduplicated(self) -> None:
        existing = [
            "HSPLcom/ambient/tv/AmbientTvApplication;->**(**)**",
            "HSPLcom/ambient/tv/ui/home/HomeViewModel;->load()V",
            "",
        ]
        captured = [
            "Lcom/ambient/tv/ui/detail/DetailScreenKt;",
            "HSPLcom/ambient/tv/ui/home/HomeViewModel;->load()V",
            "PLcom/ambient/tv/ui/detail/DetailScreenKt;->DetailScreen(Landroidx/compose/runtime/Composer;I)V",
            "HSPLandroidx/compose/runtime/ComposerImpl;->start()V",
            "Ljava/lang/String;",
            "HSPLcom/ambient/tv/ui/detail/DetailScreenKt;->DetailScreen(Landroidx/compose/runtime/Composer;I)V",
        ]
        merged, added = profile.merge(existing, captured)
        self.assertEqual(3, added)
        self.assertEqual(existing[:2], merged[:2])
        self.assertEqual([
            "HSPLcom/ambient/tv/ui/detail/DetailScreenKt;->DetailScreen(Landroidx/compose/runtime/Composer;I)V",
            "Lcom/ambient/tv/ui/detail/DetailScreenKt;",
            "PLcom/ambient/tv/ui/detail/DetailScreenKt;->DetailScreen(Landroidx/compose/runtime/Composer;I)V",
        ], merged[2:])

    def test_rule_recognition(self) -> None:
        self.assertTrue(profile.app_rule("HSPLcom/ambient/tv/Foo;->bar()V"))
        self.assertTrue(profile.app_rule("Lcom/ambient/tv/Foo;"))
        self.assertFalse(profile.app_rule("HSPLandroidx/compose/Foo;->bar()V"))
        self.assertFalse(profile.app_rule("# comment"))


if __name__ == "__main__":
    unittest.main()
