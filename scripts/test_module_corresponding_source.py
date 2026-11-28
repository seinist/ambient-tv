import tempfile
from pathlib import Path
import unittest
from unittest import mock
import json
import io
import tarfile
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

    def source_fixture(self, directory, files, counts=(2, 1)):
        path = directory / "corresponding-source.tar.gz"
        inventory = {"schema": 1, "module": "svp", "commit": "a" * 40,
                     "roots": [{"root": "native/ambientsvp", "files": counts[0], "notices": counts[1]}],
                     "reviewRequired": True}
        with tarfile.open(path, "w:gz") as archive:
            sources.add_bytes(archive, "ambient-tv-source.tar", b"host source")
            sources.add_bytes(archive, "source-inventory.json", json.dumps(inventory).encode())
            for name in files:
                sources.add_bytes(archive, name, b"source")
        return path, {**inventory, "asset": path.name, "size": path.stat().st_size, "sha256": sources.digest(path)}

    def test_source_archive_checks_declared_roots_and_counts(self):
        with tempfile.TemporaryDirectory() as temp:
            path, descriptor = self.source_fixture(Path(temp), [
                "upstream/native/ambientsvp/src/lib.rs", "upstream/native/ambientsvp/LICENSE"])
            sources.validate_source_archive(path, descriptor)
            path, descriptor = self.source_fixture(Path(temp), [
                "upstream/native/ambientsvp/src/lib.rs"])
            with self.assertRaisesRegex(ValueError, "count mismatch"):
                sources.validate_source_archive(path, descriptor)

    def test_source_archive_rejects_duplicate_and_unsafe_members(self):
        with tempfile.TemporaryDirectory() as temp:
            for names in (["upstream/native/ambientsvp/LICENSE"] * 2,
                          ["upstream/native/ambientsvp/../LICENSE", "upstream/native/ambientsvp/LICENSE"],
                          ["unexpected/source", "upstream/native/ambientsvp/LICENSE"]):
                path, descriptor = self.source_fixture(Path(temp), names)
                with self.assertRaises(ValueError):
                    sources.validate_source_archive(path, descriptor)

    def test_all_modules_have_explicit_roots(self):
        self.assertEqual(10, len(sources.ROOTS))
        self.assertTrue(all(paths for paths in sources.ROOTS.values()))


if __name__ == "__main__":
    unittest.main()
