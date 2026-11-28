import hashlib
from pathlib import Path
import tempfile
import unittest
from copy import deepcopy

import verify_release_draft as draft


class ReleaseDraftTest(unittest.TestCase):
    def setUp(self):
        self.target = "a" * 40
        self.expected = {"module.zip": (3, "sha256:" + hashlib.sha256(b"zip").hexdigest())}
        self.release = {"tag_name": "modules-test-v1", "draft": True, "target_commitish": self.target,
                        "assets": [{"name": "module.zip", "size": 3, "state": "uploaded",
                                    "digest": self.expected["module.zip"][1]}]}

    def check(self, release):
        draft.verify(release, "modules-test-v1", self.expected, self.target)

    def test_exact_draft(self):
        self.check(self.release)

    def test_mismatched_asset_and_missing_digest_fail_closed(self):
        for key, value in (("size", 4), ("digest", None), ("digest", "sha256:" + "b" * 64),
                           ("state", "starter"), ("name", "other.zip")):
            release = deepcopy(self.release)
            release["assets"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(release)

    def test_wrong_target_published_or_extra_asset_rejected(self):
        for key, value in (("target_commitish", "b" * 40), ("draft", False), ("tag_name", "wrong"),
                           ("assets", []), ("assets", self.release["assets"] * 2)):
            release = deepcopy(self.release)
            release[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(release)

    def test_local_asset_fingerprint_and_duplicate_names(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "module.zip"
            path.write_bytes(b"zip")
            self.assertEqual(self.expected, draft.expected_assets([path]))
            with self.assertRaises(ValueError):
                draft.expected_assets([path, path])
            link = path.with_name("link.zip")
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                draft.expected_assets([link])


if __name__ == "__main__":
    unittest.main()
