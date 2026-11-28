import json
import tempfile
import unittest
from pathlib import Path
from core_pgo import digest, verify

class PgoTests(unittest.TestCase):
    def test_requires_matching_identity_and_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            data = path / "ambient.profdata"
            data.write_bytes(b"fixture")
            identity = dict(core="mgba", abi="arm64-v8a", commit="a"*40, ndk="30", source_recipe="b"*64)
            metadata = dict(identity, sha256=digest(data))
            (path / "metadata.json").write_text(json.dumps(metadata))
            self.assertEqual(verify(path, **identity, sha256=metadata["sha256"]), metadata)
            with self.assertRaises(ValueError):
                verify(path, **dict(identity, abi="x86_64"), sha256=metadata["sha256"])
            with self.assertRaises(ValueError):
                verify(path, **dict(identity, source_recipe="c"*64), sha256=metadata["sha256"])
            data.write_bytes(b"modified")
            with self.assertRaises(ValueError):
                verify(path, **identity, sha256=metadata["sha256"])

    def test_recipe_tracks_patch_and_workflow_changes(self):
        from core_pgo import recipe_digest
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/"cores").mkdir()
            (root/"scripts").mkdir()
            (root/".github/workflows").mkdir(parents=True)
            (root/"cores/cores.json").write_text(json.dumps({"cores":[{"core":"mgba","commit":"a"*40}]}))
            patch = root/"scripts/core_hotpath_patches.py"
            patch.write_text("version = 1")
            workflow = root/".github/workflows/cores.yml"
            workflow.write_text("ndk: 30")
            original = recipe_digest(root, "mgba")
            patch.write_text("version = 2")
            self.assertNotEqual(original, recipe_digest(root, "mgba"))
            patch.write_text("version = 1")
            workflow.write_text("ndk: 31")
            self.assertNotEqual(original, recipe_digest(root, "mgba"))
