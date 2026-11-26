import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core_build_policy import plan
from core_release_incremental import fingerprint, key, select, stage_reuse, signed_history


class IncrementalCoreReleaseTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((Path(__file__).resolve().parents[1] / "cores/cores.json").read_text())
        self.ndk = "30.0.16248370"
        self.matrix = plan(self.catalog, "push", "refs/heads/main", False)
        self.records = {key(j): {"tag": "cores-1", "entry": {
            "core": j["core"], "abi": j["abi"], "fingerprint": fingerprint(j, self.ndk),
        }} for j in self.matrix["include"]}
        self.keys = set(self.records)

    def test_no_changes_skips_entire_release(self):
        result = select(self.matrix, self.records, self.keys, "baseline", self.ndk)
        self.assertFalse(result["publish"])
        self.assertEqual(result["rebuilt"], 0)
        self.assertEqual(len(result["reuse"]), len(self.keys))

    def test_single_pin_change_builds_only_affected_core(self):
        changed = {"include": [dict(j, commit="0" * 40) if j["core"] == "mgba" else j
                               for j in self.matrix["include"]]}
        result = select(changed, self.records, self.keys, "baseline", self.ndk)
        self.assertTrue(result["publish"])
        self.assertEqual({j["core"] for j in result["matrix"]["include"]}, {"mgba"})
        self.assertEqual(result["rebuilt"], len(self.catalog["abis"]))
        self.assertEqual(len(result["reuse"]) + result["rebuilt"], len(self.keys))

    def test_failed_or_missing_build_is_retried(self):
        missing = dict(self.records)
        k = ("mgba", "arm64-v8a")
        missing[k] = None
        result = select(self.matrix, missing, self.keys, "baseline", self.ndk)
        self.assertEqual([key(j) for j in result["matrix"]["include"]], [k])

    def test_force_ndk_change_and_legacy_manifest(self):
        for records, ndk, force in ((self.records, self.ndk, True),
                                    (self.records, "new-ndk", False),
                                    ({k: {"entry": {}} for k in self.keys}, self.ndk, False)):
            with self.subTest(ndk=ndk, force=force):
                result = select(self.matrix, records, self.keys, "baseline", ndk, force)
                self.assertEqual(result["rebuilt"], len(self.keys))
                self.assertEqual(result["reuse"], [])

    def test_removal_only_publishes_complete_remaining_snapshot(self):
        matrix = {"include": [j for j in self.matrix["include"] if j["core"] != "mgba"]}
        result = select(matrix, self.records, self.keys, "baseline", self.ndk)
        self.assertTrue(result["publish"])
        self.assertEqual(result["rebuilt"], 0)
        self.assertEqual(len(result["reuse"]), len(matrix["include"]))

    def test_experimental_only_publishes_changed_assets(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False,
                      "mgba,snes9x", "arm64-v8a", "core-hotpaths")
        records = {key(j): {"tag": "experiment", "entry": {"fingerprint": fingerprint(j, self.ndk)}}
                   for j in matrix["include"] if j["core"] == "mgba"}
        result = select(matrix, records, set(records), "core-hotpaths", self.ndk)
        self.assertEqual([j["core"] for j in result["matrix"]["include"]], ["snes9x"])
        self.assertEqual(result["reuse"], [])

    def test_signed_history_does_not_skip_missing_latest_experimental_asset(self):
        profile = "core-hotpaths"
        tag = f"core-experiments-{profile}--20261126-2-1"
        older = f"core-experiments-{profile}--20261126-1-1"
        entry = {"core": "mgba", "abi": "arm64-v8a",
                 "asset": "arm64-v8a--mgba_libretro_android.so.zip",
                 "sha256": "a" * 64, "size": 20}
        releases = [
            {"tag_name": tag, "id": 2, "draft": False, "prerelease": True,
             "published_at": "2026-11-26T20:00:00Z", "assets": []},
            {"tag_name": older, "id": 1, "draft": False, "prerelease": True,
             "published_at": "2026-11-26T19:00:00Z",
             "assets": [{"name": entry["asset"], "size": 20}]},
        ]
        def fake_gh(*args):
            if args[0] == "api":
                return json.dumps([releases])
            self.assertEqual(args[2], tag)
            directory = Path(args[args.index("--dir") + 1])
            (directory / "manifest.json").write_text(json.dumps({
                "version": 1, "tag": tag, "profile": profile, "cores": [entry]}))
            return ""
        with patch("core_release_incremental.gh", side_effect=fake_gh), \
                patch("core_release_incremental.subprocess.run") as verify:
            records, latest = signed_history("owner/repo", profile, {key(entry)})
        self.assertEqual(records, {key(entry): None})
        self.assertEqual(latest, {key(entry)})
        verify.assert_called_once()

    def test_fingerprint_ignores_catalog_order_and_release_metadata(self):
        matrix = plan(self.catalog, "push", "refs/heads/main", False)
        job = matrix["include"][0]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "scripts").mkdir()
            source = Path(__file__).resolve().parents[1]
            from core_release_incremental import SHARED
            for name in SHARED:
                (root / "scripts" / name).write_bytes((source / "scripts" / name).read_bytes())
            workflow = root / ".github/workflows/cores.yml"
            workflow.write_text((source / ".github/workflows/cores.yml").read_text())
            before = fingerprint(job, self.ndk, root)
            workflow.write_text(workflow.read_text() + "\\n# release title only\\n")
            self.assertEqual(before, fingerprint(job, self.ndk, root))
            workflow.write_text(workflow.read_text().replace("android-29", "android-30"))
            self.assertNotEqual(before, fingerprint(job, self.ndk, root))

    def test_reuse_rejects_tampered_asset(self):
        record = {"tag": "cores-1", "entry": {"core": "mgba", "abi": "arm64-v8a",
                  "asset": "arm64-v8a--mgba_libretro_android.so.zip", "sha256": "0"*64, "size": 3}}
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / record["entry"]["asset"]).write_bytes(b"bad")
            with patch("core_release_incremental.gh"):
                with self.assertRaisesRegex(ValueError, "hash/size"):
                    stage_reuse("owner/repo", [record], directory)


if __name__ == "__main__":
    unittest.main()
