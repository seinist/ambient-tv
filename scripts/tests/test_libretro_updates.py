import contextlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import update_external_libraries as updater

NEW = "b" * 40


class LibretroUpdateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="libretro-update-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "gradle").mkdir()
        (self.root / "cores").mkdir()
        self.props = self.root / "gradle/external-versions.properties"
        shutil.copyfile(updater.PROPS, self.props)
        self.cores = self.root / "cores/cores.json"
        shutil.copyfile(updater.ROOT / "cores/cores.json", self.cores)
        self.old_commit = updater.load_props()["LIBRETRODROID_COMMIT"]

    def run_updater(self, mode, heads, prepare=True, compatible=lambda core, revision: True, only="libretro"):
        def head(repo, ref="HEAD"):
            return heads(repo, ref) if ref != "HEAD" else heads(repo)
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(updater, "ROOT", self.root))
            stack.enter_context(mock.patch.object(updater, "PROPS", self.props))
            stack.enter_context(mock.patch.object(updater, "libretrodroid_latest",
                                                  return_value={"LIBRETRODROID_COMMIT": NEW}))
            stack.enter_context(mock.patch.object(updater, "remote_head", side_effect=head))
            stack.enter_context(mock.patch.object(updater, "core_candidate_compatible", side_effect=compatible))
            self.prepare = stack.enter_context(mock.patch.object(
                updater, "prepare_libretrodroid", side_effect=lambda: prepare or self.reverted()))
            stack.enter_context(mock.patch.object(updater, "smarttube_latest", side_effect=AssertionError("scope")))
            stack.enter_context(mock.patch.object(sys, "argv", ["updater", mode, "--only", only]))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            return updater.main()

    def reverted(self):
        # The second preparation (after rollback) runs against the old pin and succeeds.
        return f"LIBRETRODROID_COMMIT={self.old_commit}" in self.props.read_text()

    @staticmethod
    def new_head(repo, ref="HEAD"):
        return "d"*40 if ref == "refs/heads/yabasanshiro" else NEW

    def test_check_is_read_only(self):
        before = (self.props.read_bytes(), self.cores.read_bytes())
        self.assertEqual(self.run_updater("--check", self.new_head), 0)
        self.assertEqual(before, (self.props.read_bytes(), self.cores.read_bytes()))
        self.prepare.assert_not_called()

    def test_apply_moves_every_core_and_keeps_format(self):
        original = json.loads(self.cores.read_text())
        self.assertEqual(self.run_updater("--apply", self.new_head), 0)
        updated = json.loads(self.cores.read_text())
        for before, after in zip(original["cores"], updated["cores"]):
            self.assertEqual({**before, "commit": self.new_head(before["repo"], before.get("update_ref", "HEAD"))}, after)
        self.assertEqual(self.cores.read_text(), json.dumps(updated, indent=2, ensure_ascii=False) + "\n")
        self.assertIn(f"LIBRETRODROID_COMMIT={NEW}", self.props.read_text())
        self.prepare.assert_called_once()

    def test_unchanged_cores_are_not_rewritten(self):
        # A remote HEAD has one value per repository. Normalize the fixture
        # first: the production catalog may deliberately pin shared repos
        # to different revisions, which is not an unchanged-HEAD scenario.
        data = json.loads(self.cores.read_text())
        pins = {(c["repo"], c.get("update_ref", "HEAD")): c["commit"] for c in data["cores"]}
        self.cores.write_bytes(updater.render_cores(data, {
            c["core"]: pins[c["repo"], c.get("update_ref", "HEAD")] for c in data["cores"]
        }))
        before = self.cores.read_bytes()
        self.assertEqual(self.run_updater("--apply", lambda repo, ref="HEAD": pins[repo, ref]), 0)
        self.assertEqual(before, self.cores.read_bytes())

    def test_shared_repository_resolved_once(self):
        cores = [
            {"core": "first", "repo": "https://github.com/a/shared", "commit": "a" * 40},
            {"core": "second", "repo": "https://github.com/a/shared", "commit": "c" * 40},
        ]
        with mock.patch.object(updater, "remote_head", return_value=NEW) as head:
            self.assertEqual(updater.cores_latest(cores), {"first": "a" * 40, "second": "c" * 40})
            head.assert_called_once_with(cores[0]["repo"])

    def test_incompatible_shared_candidate_preserves_all_variants(self):
        cores = [
            {"core": "first", "repo": "https://github.com/a/shared", "commit": "a" * 40},
            {"core": "second", "repo": "https://github.com/a/shared", "commit": "a" * 40},
            {"core": "other", "repo": "https://github.com/a/other", "commit": "c" * 40},
        ]
        with mock.patch.object(updater, "remote_head", return_value=NEW), mock.patch.object(
                updater, "core_candidate_compatible", side_effect=lambda core, rev: core["core"] != "second"):
            self.assertEqual(updater.cores_latest(cores),
                             {"first": "a"*40, "second": "a"*40, "other": NEW})

    def test_native_jit_candidates_require_production_patch_preflight(self):
        for core in ("citra2018", "yabause"):
            candidate = {"core": core, "commit": "a"*40}
            with mock.patch.object(updater, "preflight_jit_core") as preflight:
                self.assertTrue(updater.core_candidate_compatible(candidate, NEW))
                preflight.assert_called_once_with(candidate, NEW)
            with mock.patch.object(updater, "preflight_jit_core", side_effect=ValueError("changed anchor")):
                self.assertFalse(updater.core_candidate_compatible(candidate, NEW))

    def test_core_only_mode_does_not_change_libretrodroid(self):
        props = self.props.read_bytes()
        self.assertEqual(self.run_updater("--apply", self.new_head, only="cores"), 0)
        self.assertEqual(self.props.read_bytes(), props)
        self.prepare.assert_not_called()
        catalog = json.loads(self.cores.read_text())
        self.assertTrue(all(c["commit"] == self.new_head(c["repo"], c.get("update_ref", "HEAD"))
                            for c in catalog["cores"]))

    def test_branch_specific_updates(self):
        cores = [
            {"core": "yabause", "repo": "https://github.com/libretro/yabause", "commit": "a"*40,
             "update_ref": "refs/heads/master"},
            {"core": "yabasanshiro", "repo": "https://github.com/libretro/yabause", "commit": "c"*40,
             "update_ref": "refs/heads/yabasanshiro"},
        ]
        with mock.patch.object(updater, "remote_head", side_effect=self.new_head) as head, \
                mock.patch.object(updater, "core_candidate_compatible", return_value=True):
            self.assertEqual(updater.cores_latest(cores), {"yabause": NEW, "yabasanshiro": "d"*40})
            self.assertEqual(head.call_count, 2)

    def test_candidate_anchor_preflight(self):
        from core_hotpath_remaining import PATCHES
        path, old, _ = PATCHES["picodrive"][0]
        core = {"core": "picodrive", "repo": "https://github.com/a/b", "commit": "a"*40}
        with mock.patch.object(updater, "get_bytes", return_value=old.encode()) as fetch:
            self.assertTrue(updater.core_candidate_compatible(core, NEW))
            fetch.assert_called_once_with(f"https://raw.githubusercontent.com/a/b/{NEW}/{path}")
        with mock.patch.object(updater, "get_bytes", return_value=b"incompatible"):
            self.assertFalse(updater.core_candidate_compatible(core, NEW))

    def test_patch_failure_keeps_old_libretrodroid_but_updates_cores(self):
        self.assertEqual(self.run_updater("--apply", self.new_head, prepare=False), 0)
        self.assertIn(f"LIBRETRODROID_COMMIT={self.old_commit}", self.props.read_text())
        self.assertEqual(self.prepare.call_count, 2)
        updated = json.loads(self.cores.read_text())["cores"]
        self.assertTrue(any(c["commit"] == NEW for c in updated))
        self.assertNotEqual(next(c["commit"] for c in updated if c["core"] == "yabause"),
                            next(c["commit"] for c in updated if c["core"] == "yabasanshiro"))

    def test_unresolvable_core_aborts_without_changes(self):
        before = (self.props.read_bytes(), self.cores.read_bytes())

        def head(repo, ref="HEAD"):
            if repo.endswith("/snes9x"):
                raise RuntimeError("Could not resolve HEAD")
            return NEW

        with self.assertRaisesRegex(RuntimeError, "Could not resolve"):
            self.run_updater("--apply", head)
        self.assertEqual(before, (self.props.read_bytes(), self.cores.read_bytes()))

    def test_github_repo_parsing(self):
        self.assertEqual(updater.github_repo("https://github.com/rommapp/argosy-launcher"), "rommapp/argosy-launcher")
        self.assertEqual(updater.github_repo("https://github.com/a/b.git"), "a/b")
        with self.assertRaises(RuntimeError):
            updater.github_repo("https://gitlab.com/a/b")


if __name__ == "__main__":
    unittest.main()
