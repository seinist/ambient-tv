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

    def test_invalid_signed_fields_cannot_suppress_publication(self):
        for field, value in (("size", 0), ("size", 256 * 1024**2 + 1), ("hostApi", True),
                             ("minSdk", 28), ("version", 2**63), ("hostBindingSha256", "bad"),
                             ("hostNativeSha256", {"libbad.so": "bad"}),
                             ("dependencies", [{"module": "mpv", "version": 1}]),
                             ("unknown", "value")):
            new = self.manifest(2)
            new["modules"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.parse(new)
        mixed = self.manifest(2)
        mixed["modules"][0]["version"] = 1
        with self.assertRaisesRegex(ValueError, "Mixed"):
            self.parse(mixed)

    def test_optional_defaults_do_not_trigger_unnecessary_publication(self):
        old = self.parse(self.manifest())
        new = self.manifest(2)
        for row in new["modules"]:
            row.update(dependencies=[], hostBindingSha256=None)
        self.assertFalse(incremental.should_publish(self.parse(new), old))

    def test_incomplete_catalog_cannot_suppress_build(self):
        new = self.manifest()
        new["modules"].pop()
        with self.assertRaises(ValueError):
            self.parse(new)

    def test_legacy_shader_catalog_cannot_replace_native(self):
        manifest = self.manifest(2)
        manifest["tag"] = "modules-shaders-v2"
        for row in manifest["modules"]:
            row["module"] = "shaders"
            row["asset"] = f"shaders--{row['abi']}--baseline.zip"
        native = incremental.entries(manifest, "shaders", manifest["tag"])
        legacy = deepcopy(manifest)
        legacy["tag"] = "modules-shaders-v3"
        legacy["modules"] = [legacy["modules"][0]]
        legacy["modules"][0].update(abi="any", asset="shaders--any--baseline.zip", version=3)
        with self.assertRaisesRegex(ValueError, "Legacy shader"):
            incremental.should_publish(incremental.entries(legacy, "shaders", legacy["tag"]), native)
        legacy["modules"][0]["version"] = 1
        self.assertTrue(incremental.should_publish(native,
            incremental.entries(legacy, "shaders", legacy["tag"])))

    def test_history_uses_highest_authenticated_version_and_rejects_equivocation(self):
        from pathlib import Path
        import json
        manifests = [self.manifest(1), self.manifest(3), self.manifest(2)]
        def releases(rows):
            return json.dumps([{"tag_name": m["tag"], "draft": False, "prerelease": False,
                    "assets": [{"name": "manifest.json", "size": len(json.dumps(m))},
                               {"name": "manifest.json.sig", "size": 1}]} for m in rows])
        def fake_run(args, **kwargs):
            if args[0] == "gh":
                m = next(m for m in manifests if m["tag"] == args[3])
                root = Path(args[args.index("--dir") + 1])
                (root / "manifest.json").write_text(json.dumps(m))
                (root / "manifest.json.sig").write_bytes(b"x")
        with patch.object(incremental, "run", side_effect=lambda *a: releases(manifests)), patch.object(
                incremental.subprocess, "run", side_effect=fake_run):
            self.assertEqual(incremental.previous_catalog("owner/repo", "mpv", Path("key"))[0], 3)
            conflicting = self.manifest(3)
            conflicting["tag"] = "modules-mpv-v3-alternate"
            conflicting["modules"][0]["sha256"] = "c" * 64
            manifests.append(conflicting)
            with self.assertRaisesRegex(ValueError, "Conflicting signed"):
                incremental.previous_catalog("owner/repo", "mpv", Path("key"))

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
