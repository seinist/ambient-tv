import unittest
from copy import deepcopy
from unittest.mock import patch

import incremental_system_modules as incremental


class IncrementalSystemModulesTest(unittest.TestCase):
    def manifest(self, version=1):
        return {"schema": 1, "tag": "modules-mpv-v" + str(version), "modules": [
            {"module": "mpv", "abi": abi, "profile": "baseline", "version": version,
             "asset": f"mpv--{abi}--baseline.zip", "sha256": "a" * 64, "size": 42,
             "hostApi": 1, "minSdk": 29, "hostNativeSha256": {"libavcodec.so": "b" * 64}}
            for abi in incremental.ABIS]}

    def parse(self, manifest):
        return incremental.entries(manifest, "mpv", manifest["tag"])

    def test_identical_payload_does_not_publish_new_version(self):
        old = self.parse(self.manifest())
        new = self.manifest(2)
        new["modules"].reverse()
        self.assertFalse(incremental.should_publish(self.parse(new), old))
        self.assertTrue(incremental.should_publish(self.parse(new), None))

    def test_binding_shared_library_and_payload_changes_publish(self):
        old = self.parse(self.manifest())
        for key, value in (("sha256", "c" * 64), ("hostBindingSha256", "d" * 64),
                           ("hostNativeSha256", {"libavcodec.so": "c" * 64}), ("hostApi", 2)):
            new = self.manifest(2)
            new["modules"][0][key] = value
            self.assertTrue(incremental.should_publish(self.parse(new), old))

    def test_stale_and_conflicting_versions_rejected(self):
        old = self.parse(self.manifest(2))
        with self.assertRaises(ValueError):
            incremental.should_publish(self.parse(self.manifest()), old)
        new = self.manifest(2)
        new["modules"][0]["sha256"] = "c" * 64
        with self.assertRaises(ValueError):
            incremental.should_publish(self.parse(new), old)

    def test_incomplete_catalog_cannot_suppress_build(self):
        new = self.manifest()
        new["modules"].pop()
        with self.assertRaises(ValueError):
            self.parse(new)

    def test_invalid_signature_stops_comparison(self):
        import subprocess
        import tempfile
        from pathlib import Path
        import json
        m = self.manifest()
        raw = json.dumps(m)
        release = {"tag_name": m["tag"], "draft": False, "prerelease": False,
                   "assets": [{"name": "manifest.json", "size": len(raw)}, {"name": "manifest.json.sig", "size": 1}]}
        def fake_run(args, **kwargs):
            if args[0] == "gh":
                root = Path(args[args.index("--dir") + 1])
                (root / "manifest.json").write_text(raw)
                (root / "manifest.json.sig").write_bytes(b"x")
            else:
                raise subprocess.CalledProcessError(1, args)
        with patch.object(incremental, "run", return_value=json.dumps([release])), patch.object(incremental.subprocess, "run", side_effect=fake_run):
            with self.assertRaises(subprocess.CalledProcessError):
                incremental.previous_catalog("owner/repo", "mpv", Path("key"))


if __name__ == "__main__":
    unittest.main()
