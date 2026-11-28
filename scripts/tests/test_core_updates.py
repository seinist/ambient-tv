import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import update_cores as updater

NEW = "b" * 40


class CoreUpdateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="libretro-update-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "cores").mkdir()
        self.cores = self.root / "cores/cores.json"
        shutil.copyfile(updater.ROOT / "cores/cores.json", self.cores)

    def run_updater(self, mode, heads, compatible=lambda core, revision: True, selected=""):
        def head(repo, ref="HEAD"):
            return heads(repo, ref)
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(updater, "ROOT", self.root))
            stack.enter_context(mock.patch.object(updater, "remote_head", side_effect=head))
            stack.enter_context(mock.patch.object(updater, "core_candidate_compatible", side_effect=compatible))
            stack.enter_context(mock.patch.object(sys, "argv", ["updater", mode] + ([selected] if selected else [])))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            return updater.main()

    @staticmethod
    def new_head(repo, ref="HEAD"):
        return "d"*40 if ref == "refs/heads/yabasanshiro" else NEW

    def test_build_sh_dispatches_independent_updaters(self):
        # Exercise the actual shell entrypoint with stub Python scripts:
        # no network, Gradle invocation or source checkout is needed.
        shutil.copyfile(updater.ROOT / "build.sh", self.root / "build.sh")
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("update_cores.py", "update_external_libraries.py", "update_maven_dependencies.py"):
            (scripts / name).write_text(
                "import json, sys; from pathlib import Path; print(json.dumps([Path(__file__).name, *sys.argv[1:]]))\n")
        cases = [
            (["cores-check"], ["update_cores.py", "--check"]),
            (["cores-update", "ppsspp,citra2018"], ["update_cores.py", "--apply", "ppsspp,citra2018"]),
            (["libs-check"], ["update_external_libraries.py", "--check"]),
            (["libs-update", "libretro"], ["update_external_libraries.py", "--apply", "--only", "libretro"]),
            (["deps-check", "media3"], ["update_maven_dependencies.py", "--check", "media3"]),
            (["deps-update"], ["update_maven_dependencies.py", "--apply", "--validate"]),
            (["update-deps", "media3"], ["update_maven_dependencies.py", "--apply", "--validate", "media3"]),
            (["libs-check", "cores"], ["update_cores.py", "--check"]),
            (["build-deps"], ["update_maven_dependencies.py", "--apply", "--validate"]),
            (["check-deps"], ["update_maven_dependencies.py", "--check"]),
            (["libs-update", "cores"], ["update_cores.py", "--apply"]),
        ]
        for argv, expected in cases:
            with self.subTest(argv=argv):
                result = subprocess.run(["bash", str(self.root / "build.sh"), *argv],
                                        text=True, capture_output=True, check=True)
                output = next(line for line in result.stdout.splitlines() if line.startswith("["))
                self.assertEqual(json.loads(output), expected)

    def test_check_is_read_only(self):
        before = self.cores.read_bytes()
        self.assertEqual(self.run_updater("--check", self.new_head), 0)
        self.assertEqual(before, self.cores.read_bytes())

    def test_apply_moves_every_core_and_keeps_format(self):
        original = json.loads(self.cores.read_text())
        self.assertEqual(self.run_updater("--apply", self.new_head), 0)
        updated = json.loads(self.cores.read_text())
        for before, after in zip(original["cores"], updated["cores"]):
            self.assertEqual({**before, "commit": self.new_head(before["repo"], before.get("update_ref", "HEAD"))}, after)
        self.assertEqual(self.cores.read_text(), json.dumps(updated, indent=2, ensure_ascii=False) + "\n")

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

    def test_missing_native_jit_patcher_retains_pin_without_blocking_others(self):
        for name, module in (("citra2018", "core_citra_arm64"), ("yabause", "core_yabause_arm64")):
            core = {"core": name, "repo": "https://github.com/a/b", "commit": "a"*40}
            with self.subTest(core=name), mock.patch.dict(sys.modules, {module: None}), mock.patch(
                    "subprocess.check_output") as checkout:
                self.assertFalse(updater.core_candidate_compatible(core, NEW))
                checkout.assert_not_called()

    def test_jit_preflight_loads_only_requested_patcher_and_skips_unavailable_hotpaths(self):
        for name, module, other in (("citra2018", "core_citra_arm64", "core_yabause_arm64"),
                                    ("yabause", "core_yabause_arm64", "core_citra_arm64")):
            core = {"core": name, "repo": "https://github.com/a/b", "commit": "a"*40}
            fake = types.ModuleType(module)
            fake.prepare = mock.Mock()
            with self.subTest(core=name), mock.patch.dict(sys.modules, {module: fake, other: None}), mock.patch(
                    "subprocess.check_output", side_effect=["", "", "", "", NEW]), mock.patch(
                    "core_hotpath_patches.SUPPORTED", set()), mock.patch(
                    "core_hotpath_patches.prepare_hotpaths") as hotpaths, mock.patch(
                    "core_build_policy.prepare_cmake") as cmake:
                self.assertTrue(updater.core_candidate_compatible(core, NEW))
                fake.prepare.assert_called_once()
                hotpaths.assert_not_called()
                if name == "citra2018":
                    cmake.assert_called_once_with(mock.ANY, name, "citra-arm64-jit")
                else:
                    cmake.assert_not_called()

    def test_selected_core_updates_only_its_source_group(self):
        before = json.loads(self.cores.read_text())
        selected = next(c for c in before["cores"] if c["core"] == "mgba")
        self.assertEqual(self.run_updater("--apply", self.new_head, selected=selected["core"]), 0)
        after = json.loads(self.cores.read_text())
        source = (selected["repo"], selected.get("update_ref", "HEAD"))
        for old, new in zip(before["cores"], after["cores"]):
            self.assertEqual(new["commit"], NEW if (old["repo"], old.get("update_ref", "HEAD")) == source else old["commit"])

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

    def test_invalid_catalog_fails_before_network(self):
        from copy import deepcopy
        original = json.loads(self.cores.read_text())
        for fields in ({"repo": "file:///tmp/evil"}, {"update_ref": "refs/heads/a..b"},
                       {"commit": "HEAD"}, {"abis": ["invalid"]}, {"build": "unknown"}):
            catalog = deepcopy(original)
            catalog["cores"][-1].update(fields)
            self.cores.write_text(json.dumps(catalog))
            with mock.patch.object(updater, "ROOT", self.root), mock.patch.object(
                    updater, "remote_head") as remote, mock.patch.object(
                    sys, "argv", ["updater", "--check", original["cores"][0]["core"]]):
                with self.assertRaises(ValueError):
                    updater.main()
                remote.assert_not_called()

    def test_blocked_candidates_are_reported_separately(self):
        cores = [{"core": "first", "repo": "https://github.com/a/b", "commit": "a"*40}]
        withheld = {}
        with mock.patch.object(updater, "remote_head", return_value=NEW), mock.patch.object(
                updater, "core_candidate_compatible", return_value=False):
            self.assertEqual(updater.cores_latest(cores, withheld), {"first": "a"*40})
        self.assertEqual(withheld, {"first": NEW})

    def test_build_system_preflight_runs_production_helpers(self):
        from core_hotpath_patches import SUPPORTED
        for name, jni, required, helper in (
                ("mupen64plus_next_gles3", "libretro", {"libretro/jni/Android.mk", "libretro-common/libco/amd64.c"}, "prepare_ndk"),
                ("snes9x2010", ".", {"jni/Android.mk", "build/Makefile.common"}, "prepare_ndk"),
                ("panda3ds", ".", {"CMakeLists.txt"}, "prepare_cmake"),
                ("swanstation", ".", {"CMakeLists.txt"}, "prepare_cmake")):
            core = {"core": name, "jni": jni, "repo": "https://github.com/a/b", "commit": "a"*40}
            with mock.patch.object(updater, "get_bytes", return_value=b"source") as fetch, mock.patch(
                    "core_hotpath_patches.prepare_hotpaths") as hotpaths, mock.patch(
                    "core_build_policy."+helper, side_effect=ValueError("incompatible build recipe")) as prepare:
                self.assertFalse(updater.core_candidate_compatible(core, NEW))
                self.assertTrue(required <= {call.args[0].split(NEW+"/", 1)[1] for call in fetch.call_args_list})
                prepare.assert_called_once()
                if name in SUPPORTED:
                    hotpaths.assert_called_once()
                else:
                    hotpaths.assert_not_called()

    def test_candidate_anchor_preflight(self):
        # FCEUmm has a single reviewed source anchor in the production patch table.
        # PicoDrive's SH2 profile uses build flags, not this patch table.
        from core_hotpath_remaining import PATCHES
        path, old, _ = PATCHES["fceumm"][0]
        core = {"core": "fceumm", "repo": "https://github.com/a/b", "commit": "a"*40}
        with mock.patch.object(updater, "get_bytes", return_value=old.encode()) as fetch:
            self.assertTrue(updater.core_candidate_compatible(core, NEW))
            fetch.assert_called_once_with(f"https://raw.githubusercontent.com/a/b/{NEW}/{path}")
        with mock.patch.object(updater, "get_bytes", return_value=b"incompatible"):
            self.assertFalse(updater.core_candidate_compatible(core, NEW))

    def test_unresolvable_core_retains_pin_and_updates_independent_sources(self):
        before = json.loads(self.cores.read_text())

        def head(repo, ref="HEAD"):
            if repo.endswith("/snes9x"):
                raise RuntimeError("Could not resolve HEAD")
            return NEW

        self.assertEqual(self.run_updater("--apply", head), 1)
        after = json.loads(self.cores.read_text())
        for old, new in zip(before["cores"], after["cores"]):
            self.assertEqual(new["commit"], old["commit"] if old["repo"].endswith("/snes9x") else NEW)

    def test_github_repo_parsing(self):
        self.assertEqual(updater.github_repo("https://github.com/rommapp/argosy-launcher"), "rommapp/argosy-launcher")
        self.assertEqual(updater.github_repo("https://github.com/a/b.git"), "a/b")
        with self.assertRaises(RuntimeError):
            updater.github_repo("https://gitlab.com/a/b")


if __name__ == "__main__":
    unittest.main()
