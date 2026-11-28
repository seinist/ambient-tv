import tempfile
from pathlib import Path
import unittest
from unittest import mock
import json
import io
import tarfile
import zipfile
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
                (cwd / "vendor" / "source.rs").write_text("source")
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
        inventory = {"schema": 1, "module": "libretrodroid", "commit": "a" * 40,
                     "roots": [{"root": "third_party/libretrodroid", "files": counts[0], "notices": counts[1]}],
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
                "upstream/third_party/libretrodroid/src/lib.rs", "upstream/third_party/libretrodroid/LICENSE"])
            notices = {}
            sources.validate_source_archive(path, descriptor, notices)
            self.assertEqual(notices, {"source-0/LICENSE": (6, sources.hashlib.sha256(b"source").hexdigest())})
            path, descriptor = self.source_fixture(Path(temp), [
                "upstream/third_party/libretrodroid/src/lib.rs"])
            with self.assertRaisesRegex(ValueError, "count mismatch"):
                sources.validate_source_archive(path, descriptor)

    def test_source_archive_rejects_duplicate_and_unsafe_members(self):
        with tempfile.TemporaryDirectory() as temp:
            for names in (["upstream/third_party/libretrodroid/LICENSE"] * 2,
                          ["upstream/third_party/libretrodroid/../LICENSE", "upstream/third_party/libretrodroid/LICENSE"],
                          ["unexpected/source", "upstream/third_party/libretrodroid/LICENSE"]):
                path, descriptor = self.source_fixture(Path(temp), names)
                with self.assertRaises(ValueError):
                    sources.validate_source_archive(path, descriptor)

    def test_cargo_inventory_matches_all_original_lockfiles(self):
        manifest = sources.CARGO_MANIFESTS["svp"][0]
        lock = str(Path(manifest).with_name("Cargo.lock"))
        original = {lock: "a" * 64}
        rows = [{"workspace": "workspace-0", "manifest": manifest, "lockSha256": "a" * 64}]
        files = {"workspace-0/Cargo.lock": "a" * 64,
                 "workspace-0/cargo-source-config.toml": "b" * 64,
                 "workspace-0/vendor/crate/src/lib.rs": None}
        sources.validate_cargo_inventory("svp", rows, files, original)
        for invalid in ({**files, "workspace-0/Cargo.lock": "c" * 64},
                        {key: value for key, value in files.items() if "/vendor/" not in key},
                        {**files, "workspace-9/vendor/crate/lib.rs": None}):
            with self.assertRaises(ValueError):
                sources.validate_cargo_inventory("svp", rows, invalid, original)
        with self.assertRaises(ValueError):
            sources.validate_cargo_inventory("svp", [], files, original)

    def maven_fixture(self, directory, identities):
        for identity in identities:
            group, artifact = identity.split(":")
            target = directory / group / artifact / "1.2.3"
            target.mkdir(parents=True)
            with zipfile.ZipFile(target / "sources.jar", "w") as archive:
                archive.writestr("org/example/Example.java", "class Example {}")
            with zipfile.ZipFile(target / "binary.jar", "w") as archive:
                archive.writestr("org/example/Example.class", b"binary")
                archive.writestr("META-INF/LICENSE", b"license")

    def test_collect_exact_smarttube_maven_sources_and_binary_notices(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            identities = sources.SMARTTUBE_MAVEN_REQUIRED | {"net.minidev:accessors-smart", "org.ow2.asm:asm"}
            self.maven_fixture(root / "inputs", identities)
            output = sources.collect_smarttube_maven_sources(root / "inputs", root / "sources")
            rows = json.loads((output / "maven-inventory.json").read_text())
            self.assertEqual({row["coordinate"] for row in rows}, {name + ":1.2.3" for name in identities})
            self.assertTrue(all(row["sources"] == 1 and row["binaryNotices"] == 1 for row in rows))
            self.assertEqual(len(list(output.rglob("LICENSE"))), len(identities))
            self.assertFalse(list(output.rglob("*.class")))
            self.assertFalse(list(output.rglob("*.jar")))

    def test_missing_smarttube_maven_sources_stop_collection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.maven_fixture(root / "inputs", {"net.minidev:json-smart"})
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                sources.collect_smarttube_maven_sources(root / "inputs", root / "sources")

    def test_j2v8_text_digest_checks_exact_section(self):
        import hashlib
        output = "  [ 4] .text PROGBITS 0000000000001000 000002 000004 00 AX 0 0 4\n"
        with mock.patch.object(sources.subprocess, "check_output", return_value=output):
            self.assertEqual(sources.elf_text_digest(b"xxcodeyy"), hashlib.sha256(b"code").hexdigest())
            with self.assertRaises(ValueError):
                sources.elf_text_digest(b"xx")
        with mock.patch.object(sources.subprocess, "check_output", return_value="no text"):
            with self.assertRaises(ValueError):
                sources.elf_text_digest(b"xxcodeyy")

    def test_j2v8_provenance_matches_full_apk_and_git_blob(self):
        import hashlib
        from package_system_modules import ABIS
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            shared, dist = root / "shared", root / "dist"
            (shared / "j2v8").mkdir(parents=True)
            (shared / "j2v8/build.gradle").write_text('versionName "6.2.1"\n')
            dist.mkdir()
            for abi in ABIS:
                source = shared / f"j2v8/src/main/jniLibs/{abi}/libj2v8.so"
                source.parent.mkdir(parents=True)
                source.write_bytes(abi.encode())
                with zipfile.ZipFile(dist / f"{abi}.apk", "w") as archive:
                    archive.writestr(f"lib/{abi}/libj2v8.so", abi.encode())
            def git(command, text):
                if command[-1] == "HEAD":
                    return "a" * 40
                data = (shared / command[-1].split(":", 1)[1]).read_bytes()
                return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            with mock.patch.object(sources.subprocess, "check_output", side_effect=git), mock.patch.object(
                    sources, "elf_text_digest", side_effect=lambda data: hashlib.sha256(data).hexdigest()):
                output = sources.collect_j2v8_provenance(shared, dist, root / "evidence")
                evidence = json.loads((output / "j2v8-provenance.json").read_text())
                self.assertEqual(evidence["nativeSourceCorrespondence"], "unverified")
                self.assertEqual({row["abi"] for row in evidence["libraries"]}, set(ABIS))
                with zipfile.ZipFile(dist / "x86.apk", "w") as archive:
                    archive.writestr("lib/x86/libj2v8.so", b"changed")
                with self.assertRaisesRegex(ValueError, "executable text differs"):
                    sources.collect_j2v8_provenance(shared, dist, root / "bad")

    def test_vlc_contrib_source_inventory_checks_recipe_and_git_pins(self):
        import hashlib
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            contrib = root / "contrib"
            tarballs = contrib / "tarballs"
            tarballs.mkdir(parents=True)
            pin = "a" * 40
            ffmpeg = tarballs / f"ffmpeg-{pin}.tar.xz"
            ffmpeg.write_bytes(b"ffmpeg")
            ffmpeg.with_name(f"ffmpeg-{pin}.githash").write_text(f"{pin} {ffmpeg}\n")
            placebo = tarballs / "libplacebo-v1.tar.bz2"
            placebo.write_bytes(b"placebo")
            sums = contrib / "src/libplacebo/SHA512SUMS"
            sums.parent.mkdir(parents=True)
            sums.write_text(f"{hashlib.sha512(b'placebo').hexdigest()}  {placebo.name}\n")
            output = sources.collect_vlc_contrib_provenance(contrib, root / "inventory", pin)
            rows = json.loads((output / "vlc-contrib-inventory.json").read_text())["archives"]
            self.assertEqual({row["recipeVerification"] for row in rows}, {"git-marker", "sha512"})
            placebo.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "SHA512 mismatch"):
                sources.collect_vlc_contrib_provenance(contrib, root / "bad", pin)

    def test_all_modules_have_explicit_roots(self):
        self.assertEqual(10, len(sources.ROOTS))
        self.assertTrue(all(paths for paths in sources.ROOTS.values()))


if __name__ == "__main__":
    unittest.main()
