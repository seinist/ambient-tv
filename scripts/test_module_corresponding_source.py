import tempfile
from pathlib import Path
import unittest
from unittest import mock
import json
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

    def test_vendors_each_lockfile_and_records_workspace_mapping(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            candidates = sources.CARGO_MANIFESTS["monogram"]
            for index, relative in enumerate(candidates):
                manifest = root / relative
                manifest.parent.mkdir(parents=True, exist_ok=True)
                manifest.write_text("[workspace]")
                manifest.with_name("Cargo.lock").write_text(f"lock-{index}")
            def vendor(command, cwd, text):
                self.assertIn("--locked", command)
                self.assertEqual(command[-1], "vendor")
                (cwd / "vendor").mkdir()
                return f'directory = "{cwd / "vendor"}"\n'
            with mock.patch.object(sources, "ROOT", root), mock.patch.object(
                    sources.subprocess, "check_output", side_effect=vendor) as run:
                output = sources.vendor_cargo("monogram", root / "resolved")
                self.assertEqual(run.call_count, len(candidates))
            entries = json.loads((output / "workspaces.json").read_text())
            self.assertEqual([entry["manifest"] for entry in entries], list(candidates))
            for index, entry in enumerate(entries):
                workspace = output / entry["workspace"]
                self.assertEqual((workspace / "Cargo.lock").read_text(), f"lock-{index}")
                self.assertEqual((workspace / "cargo-source-config.toml").read_text(), 'directory = "vendor"\n')

    def test_all_modules_have_explicit_roots(self):
        self.assertEqual(10, len(sources.ROOTS))
        self.assertTrue(all(paths for paths in sources.ROOTS.values()))


if __name__ == "__main__":
    unittest.main()
