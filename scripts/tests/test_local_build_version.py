"""Exercise local version transactions without Android SDK or native builds."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / "local_build_version.py"
README = ("<!-- version-badge:start -->\n\n[![Version](old)](#)\n\n"
          "<!-- version-badge:end -->\n<!-- version:start -->"
          + chr(96) + "1.0" + chr(96) + " (451)<!-- version:end -->\n")


class LocalBuildVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version = self.root / "version.properties"
        self.version.write_text("VERSION_NAME=1.0\nVERSION_CODE=451\n")
        for filename in ("README.md", "README.de.md"):
            (self.root / filename).write_text(README)
        (self.root / "build.sh").write_text(
            '#!/usr/bin/env bash\nset -eu\n'
            'grep -q "VERSION_CODE=$(($AMBIENT_TV_LOCAL_BUILD_CODE - 1))" version.properties\n'
            'printf "%s\\n" "$AMBIENT_TV_LOCAL_BUILD_CODE" >> observed\n'
            'if [[ "$1" == fail ]]; then exit 7; fi\n'
            'sleep 0.1\n'
        )

    def run_build(self, mode):
        env = dict(os.environ)
        env.pop("ORG_GRADLE_PROJECT_versionName", None)
        return subprocess.run([sys.executable, str(HELPER), str(self.root), mode],
                              env=env, capture_output=True, text=True, timeout=15)

    def test_failed_build_does_not_consume_version(self):
        result = self.run_build("fail")
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertIn("VERSION_CODE=451", self.version.read_text())
        self.assertEqual((self.root / "README.md").read_text(), README)
        self.assertEqual((self.root / "README.de.md").read_text(), README)
        result = self.run_build("all")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "observed").read_text().splitlines(), ["452", "452"])
        self.assertIn("VERSION_CODE=452", self.version.read_text())
        for filename in ("README.md", "README.de.md"):
            contents = (self.root / filename).read_text()
            self.assertIn("(452)", contents)
            self.assertIn("%28452%29", contents)

    def test_parallel_builds_have_unique_versions(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(self.run_build, ("release-arm", "debug")))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "observed").read_text().splitlines(), ["452", "453"])
        self.assertIn("VERSION_CODE=453", self.version.read_text())

    def test_invalid_readme_does_not_partially_update_versions(self):
        (self.root / "README.de.md").write_text("missing markers\n")
        result = self.run_build("release-arm")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("VERSION_CODE=451", self.version.read_text())
        self.assertEqual((self.root / "README.md").read_text(), README)


if __name__ == "__main__":
    unittest.main()
