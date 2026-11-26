import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core_build_policy import plan
from core_release_incremental import fingerprint, key, select, stage_reuse


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
