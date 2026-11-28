"""Guard the CI gate and test-only workflow triggers."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"


class CoreCIContract(unittest.TestCase):
    def test_artifact_matrices_depend_on_shared_gate(self):
        for workflow in ("core-catalog-validation.yml", "core-jit-candidates.yml"):
            content = (WORKFLOWS / workflow).read_text()
            with self.subTest(workflow=workflow):
                self.assertIn("uses: ./.github/workflows/core-policy-validation.yml", content)
                self.assertIn("needs: core-policy", content)
                self.assertIn("shared_policy_validated: true", content)
                self.assertIn("publish: false", content)
                self.assertIn("publish_experimental: false", content)
                self.assertIn("- scripts/test_core_*.py", content)
                self.assertIn("- scripts/check_core_policy.sh", content)
        single = (WORKFLOWS / "cores.yml").read_text()
        self.assertIn("!inputs.shared_policy_validated || inputs.publish || inputs.publish_experimental", single)
        self.assertIn("run: bash scripts/check_core_policy.sh", single)
        pr = (WORKFLOWS / "pr-checks.yml").read_text()
        self.assertIn("run: bash scripts/check_core_policy.sh", pr)
        unit_job = pr.split("\n  unit-tests:\n", 1)[1].split("\n    steps:", 1)[0]
        self.assertNotIn("needs:", unit_job)

    def test_citra_test_artifact_is_executed_only_on_trusted_device(self):
        single = (WORKFLOWS / "cores.yml").read_text()
        device = (WORKFLOWS / "core-device-validation.yml").read_text()
        self.assertIn("name: citra-arm64-jit-native-tests", single)
        self.assertIn("path: build/bin/tests", single)
        self.assertIn("if: github.ref == 'refs/heads/main'", device)
        self.assertIn(".head_sha==$sha", device)
        self.assertIn('check_run "$JIT_RUN" core-jit-candidates.yml', device)
        self.assertIn('gh run download "$JIT_RUN" -n citra-arm64-jit-native-tests', device)
        self.assertIn("./tests --reporter compact", device)
