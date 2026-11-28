"""Run the actual CI Gradle retry wrapper with deterministic fake Gradle commands."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


WRAPPER = Path(__file__).resolve().parents[1] / "ci_gradle_rate_limit_retry.sh"


class GradleRateLimitRetryTest(unittest.TestCase):
    def run_wrapper(self, mode):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradlew = root / "gradlew"
            gradlew.write_text("""#!/usr/bin/env bash
set -eu
n=0
[[ ! -f attempts ]] || n=$(cat attempts)
n=$((n + 1))
echo "$n" > attempts
[[ "$#" == 2 && "$1" == ':app:testDebugUnitTest' && "$2" == '--continue' ]] || exit 99
case "$MODE" in
  ok) echo success ;;
  compile) echo 'e: compilation failed'; exit 7 ;;
  transient)
    if (( n < 3 )); then echo 'Received status code 429 from server: Too Many Requests' >&2; exit 1; fi
    echo success ;;
  permanent) echo 'HTTP 429' >&2; exit 9 ;;
esac
""")
            gradlew.chmod(0o755)
            sleep = root / "sleep"
            sleep.write_text('#!/bin/sh\necho "$1" >> delays\n')
            sleep.chmod(0o755)
            result = subprocess.run(
                ["bash", str(WRAPPER), ":app:testDebugUnitTest", "--continue"],
                cwd=root, env={**os.environ, "MODE": mode, "PATH": str(root) + os.pathsep + os.environ["PATH"]},
                text=True, capture_output=True, timeout=10,
            )
            delays = (root / "delays").read_text().splitlines() if (root / "delays").exists() else []
            return result, int((root / "attempts").read_text()), [int(delay) for delay in delays]

    def test_success_without_retry(self):
        result, attempts, delays = self.run_wrapper("ok")
        self.assertEqual((result.returncode, attempts, delays), (0, 1, []))

    def test_compile_error_is_not_retried_or_masked(self):
        result, attempts, delays = self.run_wrapper("compile")
        self.assertEqual((result.returncode, attempts, delays), (7, 1, []))
        self.assertIn("compilation failed", result.stdout)

    def test_transient_rate_limit_recovers_with_backoff(self):
        result, attempts, delays = self.run_wrapper("transient")
        self.assertEqual((result.returncode, attempts), (0, 3))
        self.assertEqual(len(delays), 2)
        self.assertTrue(30 <= delays[0] <= 44)
        self.assertTrue(60 <= delays[1] <= 74)
        self.assertIn("success", result.stdout)

    def test_persistent_rate_limit_preserves_failure(self):
        result, attempts, delays = self.run_wrapper("permanent")
        self.assertEqual((result.returncode, attempts, len(delays)), (9, 3, 2))
        self.assertIn("HTTP 429", result.stdout)


if __name__ == "__main__":
    unittest.main()
