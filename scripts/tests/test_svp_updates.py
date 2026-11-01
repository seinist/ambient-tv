import contextlib
import io
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import svp_dependency as svp
import update_external_libraries as updater

NEW = "a" * 40


class SvpUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="svp-update-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.crate = self.root / "native/ambientsvp"
        self.crate.mkdir(parents=True)
        for name in svp.UPDATE_FILES:
            dest = self.crate / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(svp.ROOT / "native/ambientsvp" / name, dest)
        (self.root / "gradle").mkdir()
        self.props = self.root / "gradle/external-versions.properties"
        shutil.copyfile(updater.PROPS, self.props)
        self.catalog = self.root / "gradle/libs.versions.toml"
        self.catalog.write_text('[versions]\nlibvlc = "3.7.5"\n')
        self.old = svp.pinned_revision(self.root)
        self.license = (self.crate / "OPEN-SVPFLOW-LICENSE").read_bytes()
        self.shader = (self.crate / "shaders/smooth.wgsl").read_bytes() + b"\n// test update\n"

    def download(self, url):
        self.assertIn(f"/{NEW}/", url)
        return self.license if url.endswith("/LICENSE") else self.shader

    def resolve_lock(self, crate):
        path = crate / "Cargo.lock"
        path.write_text(path.read_text().replace(self.old, NEW))

    def stage(self):
        with mock.patch.object(svp, "resolve_lock", self.resolve_lock):
            return svp.stage_update(self.root, NEW, self.download)

    def run_updater(self, mode, only=True):
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(updater, "ROOT", self.root))
            stack.enter_context(mock.patch.object(updater, "PROPS", self.props))
            stack.enter_context(mock.patch.object(updater, "CATALOG", self.catalog))
            stack.enter_context(mock.patch.object(updater, "svp_latest", return_value={
                "SVPFLOW_VERSION": "nightly-test", "SVPFLOW_REF": NEW,
            }))
            stack.enter_context(mock.patch.object(updater, "get_bytes", self.download))
            stack.enter_context(mock.patch.object(svp, "resolve_lock", self.resolve_lock))
            stack.enter_context(mock.patch.object(sys, "argv", ["updater", mode] + (["--only", "svp"] if only else [])))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            return updater.main()

    def test_existing_sources_are_consistent(self):
        svp.verify(self.crate, self.old)

    def test_changed_shader_is_rejected(self):
        (self.crate / "shaders/smooth.wgsl").write_bytes(b"stale")
        with self.assertRaisesRegex(RuntimeError, "asset modified"):
            svp.verify(self.crate, self.old)

    def test_mixed_locked_source_is_rejected(self):
        lock = self.crate / "Cargo.lock"
        lock.write_text(lock.read_text().replace(f"#{self.old}", f"#{NEW}", 1))
        with self.assertRaisesRegex(RuntimeError, "Cargo.lock"):
            svp.verify(self.crate, self.old)

    def test_stage_does_not_write_project_and_updates_all_assets_together(self):
        before = {self.crate / p: (self.crate / p).read_bytes() for p in svp.UPDATE_FILES}
        updates = self.stage()
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        self.assertEqual(len(updates), len(svp.UPDATE_FILES))
        svp.apply_files(updates)
        svp.verify(self.crate, NEW)
        self.assertEqual((self.crate / "shaders/smooth.wgsl").read_bytes(), self.shader)

    def test_failed_download_never_changes_project(self):
        before = (self.crate / "Cargo.toml").read_bytes()
        with self.assertRaisesRegex(OSError, "offline"), mock.patch.object(svp, "resolve_lock") as cargo:
            svp.stage_update(self.root, NEW, mock.Mock(side_effect=OSError("offline")))
        cargo.assert_not_called()
        self.assertEqual(before, (self.crate / "Cargo.toml").read_bytes())

    def test_license_change_requires_manual_review(self):
        with self.assertRaisesRegex(RuntimeError, "license changed"):
            svp.stage_update(self.root, NEW, lambda _: b"new license")
        svp.verify(self.crate, self.old)

    def test_failed_lock_resolution_leaves_current_pin_usable(self):
        with mock.patch.object(svp, "resolve_lock", side_effect=RuntimeError("Cargo failed")):
            with self.assertRaisesRegex(RuntimeError, "Cargo failed"):
                svp.stage_update(self.root, NEW, self.download)
        svp.verify(self.crate, self.old)

    def test_apply_io_failure_rolls_back_previous_writes(self):
        a, b = self.crate / "Cargo.toml", self.crate / "NOTICE.txt"
        originals = {p: p.read_bytes() for p in (a, b)}
        replace = svp.os.replace

        def fail_second(src, dst):
            if dst == b:
                raise OSError("disk full")
            replace(src, dst)

        with mock.patch.object(svp.os, "replace", side_effect=fail_second):
            with self.assertRaisesRegex(OSError, "disk full"):
                svp.apply_files({a: b"new manifest", b: b"new notice"})
        self.assertEqual(originals, {p: p.read_bytes() for p in originals})
        self.assertEqual([], list(self.crate.glob(".Cargo.toml.*")))

    def test_apply_preserves_concurrent_user_edits(self):
        path = self.crate / "NOTICE.txt"
        original = path.read_bytes()
        path.write_bytes(b"user edit")
        with self.assertRaisesRegex(RuntimeError, "Concurrent edit"):
            svp.apply_files({path: b"updater edit"}, {path: original})
        self.assertEqual(path.read_bytes(), b"user edit")

    def test_check_is_read_only_and_does_not_query_other_libraries(self):
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with mock.patch.object(updater, "smarttube_latest", side_effect=AssertionError("out of scope")), \
             mock.patch.object(updater, "monogram_latest", side_effect=AssertionError("out of scope")):
            self.assertEqual(self.run_updater("--check"), 0)
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_svp_only_apply_keeps_other_libraries_unchanged(self):
        before = self.props.read_text().splitlines()
        catalog = self.catalog.read_bytes()
        self.assertEqual(self.run_updater("--apply"), 0)
        after = self.props.read_text().splitlines()
        self.assertEqual([p for p in before if not p.startswith("SVPFLOW_")],
                         [p for p in after if not p.startswith("SVPFLOW_")])
        self.assertEqual(self.catalog.read_bytes(), catalog)
        svp.verify(self.crate, svp.pinned_revision(self.root))
        self.assertEqual(svp.pinned_revision(self.root), NEW)

    def test_all_apply_includes_svp_and_preserves_manual_rusty_pin(self):
        props = dict(line.split("=", 1) for line in self.props.read_text().splitlines()
                     if line and not line.startswith("#"))
        with contextlib.ExitStack() as stack:
            for name, prefix in (("smarttube_latest", "SMARTTUBE_"), ("mpv_latest", "MPV_"), ("ffmpeg_latest", "FFMPEG_"), ("projectm_latest", "PROJECTM_")):
                stack.enter_context(mock.patch.object(updater, name, return_value={k: v for k, v in props.items() if k.startswith(prefix)}))
            stack.enter_context(mock.patch.object(updater, "rusty_latest", return_value={"SPOTIFY_RUSTY_REF": "new-jni"}))
            stack.enter_context(mock.patch.object(updater, "libvlc_latest_stable", return_value="3.7.6"))
            stack.enter_context(mock.patch.object(updater, "monogram_latest", return_value={
                k: props[k] for k in (
                    "MONOGRAM_VERSION", "MONOGRAM_REF", "MONOGRAM_MONO_RS_REF",
                    "MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION")
            }))
            self.run_updater("--apply", only=False)
        self.assertIn(f"SPOTIFY_RUSTY_REF={props['SPOTIFY_RUSTY_REF']}", self.props.read_text())
        self.assertIn(f"MONOGRAM_REF={props['MONOGRAM_REF']}", self.props.read_text())
        self.assertIn('libvlc = "3.7.6"', self.catalog.read_text())
        svp.verify(self.crate, NEW)

    def test_monogram_only_apply_updates_monogram_and_keeps_other_pins(self):
        before = self.props.read_text().splitlines()
        new = {
            "MONOGRAM_VERSION": "0.9.1",
            "MONOGRAM_REF": "d" * 40,
            "MONOGRAM_MONO_RS_REF": "e" * 40,
            "MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION": "0.9.0",
        }
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(updater, "ROOT", self.root))
            stack.enter_context(mock.patch.object(updater, "PROPS", self.props))
            stack.enter_context(mock.patch.object(updater, "CATALOG", self.catalog))
            stack.enter_context(mock.patch.object(updater, "monogram_latest", return_value=new))
            stack.enter_context(mock.patch.object(updater, "svp_latest", side_effect=AssertionError("svp")))
            stack.enter_context(mock.patch.object(updater, "smarttube_latest", side_effect=AssertionError("smarttube")))
            stack.enter_context(mock.patch.object(sys, "argv", ["updater", "--apply", "--only", "monogram"]))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            self.assertEqual(updater.main(), 0)
        text = self.props.read_text()
        for key, value in new.items():
            self.assertIn(f"{key}={value}", text)
        kept = lambda lines: [line for line in lines if not line.startswith("MONOGRAM_")]
        self.assertEqual(kept(before), kept(text.splitlines()))
        self.assertEqual(svp.pinned_revision(self.root), self.old)

    def test_fingerprint_changes_on_engine_source_and_stays_location_independent(self):
        (self.root / "scripts").mkdir()
        for name in ("build_svp_android.sh", "vendor_svp_licenses.sh", "svp_dependency.py"):
            shutil.copyfile(SCRIPTS / name, self.root / "scripts" / name)
        (self.crate / "src").mkdir()
        source = self.crate / "src/lib.rs"
        source.write_text("// first\n")
        first = svp.fingerprint(self.root)
        source.write_text("// changed\n")
        second = svp.fingerprint(self.root)
        self.assertNotEqual(first, second)
        with tempfile.TemporaryDirectory() as other:
            clone = Path(other) / "copy"
            shutil.copytree(self.root, clone)
            self.assertEqual(second, svp.fingerprint(clone))

    @unittest.skipUnless(os.environ.get("SVP_UPDATE_NETWORK_TEST") == "1", "explicit network/Cargo resolution test")
    def test_real_upstream_update_resolves_in_isolation(self):
        shutil.copytree(svp.ROOT / "native/ambientsvp/src", self.crate / "src")
        latest = updater.svp_latest()
        updates = svp.stage_update(self.root, latest["SVPFLOW_REF"], updater.get_bytes)
        svp.verify(self.crate, self.old)  # The repository was not changed by staging.
        svp.apply_files(updates)
        svp.verify(self.crate, latest["SVPFLOW_REF"])


class SvpReleaseTest(unittest.TestCase):
    def resolve(self, releases):
        with mock.patch.object(updater, "github", return_value=releases), \
             mock.patch.object(updater, "resolve_tag_commit", return_value=NEW):
            return updater.svp_latest()

    def test_stable_version_preferred_over_nightly(self):
        result = self.resolve([{"tag_name": "nightly-today"}, {"tag_name": "v1.2.3"}])
        self.assertEqual(result["SVPFLOW_VERSION"], "v1.2.3")

    def test_nightly_channel_explicit_when_no_stable_version(self):
        result = self.resolve([{"tag_name": "v2.0.0", "draft": True},
                               {"tag_name": "v1.0.0", "prerelease": True},
                               {"tag_name": "nightly-today"}])
        self.assertEqual(result, {"SVPFLOW_VERSION": "nightly-today", "SVPFLOW_REF": NEW})

    def test_no_release_does_not_silently_switch_to_main(self):
        with self.assertRaisesRegex(RuntimeError, "No published"):
            self.resolve([])


if __name__ == "__main__":
    unittest.main()
