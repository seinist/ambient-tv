import tempfile
from pathlib import Path
import unittest
import module_corresponding_source as sources


class CorrespondingSourceTest(unittest.TestCase):
    def test_excludes_generated_binary_credentials_and_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("src/main.c", "LICENSE", "build/lib.c", "target/generated.c", "local.properties", "foo.jks", ".git/config", "lib.so"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("test")
            (root / "link").symlink_to(root / "LICENSE")
            self.assertEqual({"src/main.c", "LICENSE"}, {p.as_posix() for _, p in sources.source_files(root)})

    def test_rust_modules_require_resolved_workspace_source(self):
        self.assertEqual({"svp", "monogram", "spotify", "shaders"}, set(sources.CARGO_MANIFESTS))
        self.assertIsNone(sources.vendor_cargo("vlc", Path("unused")))

    def test_all_modules_have_explicit_roots(self):
        self.assertEqual(10, len(sources.ROOTS))
        self.assertTrue(all(paths for paths in sources.ROOTS.values()))


if __name__ == "__main__":
    unittest.main()
