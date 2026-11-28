"""Check the real Android contrib Make fragment with synthetic compiler flags."""
from pathlib import Path
import subprocess
import tempfile
import unittest

PATCH = Path(__file__).resolve().parents[2] / "native/vlc-aar/patches/android-contrib-toolchain-probes.patch"
GUARDED = "--start-no-unused-arguments -Wl,-z,max-page-size=16384 --end-no-unused-arguments"


class VlcContribFlagsTest(unittest.TestCase):
    def test_page_alignment_is_link_only_and_other_flags_survive(self):
        lines = PATCH.read_text().split("diff --git a/contrib/src/gcrypt/rules.mak", 1)[0].splitlines()
        added = [line[1:] for line in lines if line.startswith("+") and not line.startswith("+++")]
        fragment = "\n".join(added[added.index("ifdef HAVE_ANDROID"):])
        with tempfile.TemporaryDirectory() as temporary:
            makefile = Path(temporary) / "Makefile"
            makefile.write_text(
                f"CPPFLAGS := -DNDEBUG {GUARDED} -I/test\n"
                f"CFLAGS := -O2 {GUARDED} -Werror\n"
                f"CXXFLAGS := -O3 {GUARDED} -fexceptions\n"
                + fragment + "\nall:\n"
                + "\t@printf '%s\\n' '$(CPPFLAGS)' '$(CFLAGS)' '$(CXXFLAGS)' '$(LDFLAGS)'\n")
            for original_link_flags in ("", "-z max-page-size=16384"):
                # Supply the default through the environment rather than a
                # command-line assignment, which GNU make treats as immutable.
                import os
                result = subprocess.check_output(
                    ["make", "-f", str(makefile), "HAVE_ANDROID=1"],
                    env={**os.environ, "LDFLAGS": original_link_flags}, text=True).splitlines()
                self.assertTrue(all("max-page-size" not in line and "--start-no-unused" not in line
                                    for line in result[:3]))
                self.assertEqual(1, result[3].count("max-page-size=16384"))
                self.assertIn("-Werror", result[1])
                self.assertIn("-fexceptions", result[2])
            result = subprocess.check_output(["make", "-f", str(makefile), "HAVE_ANDROID="],
                                             text=True).splitlines()
            self.assertTrue(all(GUARDED in line for line in result[:3]))


if __name__ == "__main__":
    unittest.main()
