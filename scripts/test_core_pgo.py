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
            identity = dict(core="mgba", abi="arm64-v8a", commit="a"*40, ndk="30")
            metadata = dict(identity, sha256=digest(data))
            (path / "metadata.json").write_text(json.dumps(metadata))
            self.assertEqual(verify(path, **identity, sha256=metadata["sha256"]), metadata)
            with self.assertRaises(ValueError):
                verify(path, **dict(identity, abi="x86_64"), sha256=metadata["sha256"])
            data.write_bytes(b"modified")
            with self.assertRaises(ValueError):
                verify(path, **identity, sha256=metadata["sha256"])
