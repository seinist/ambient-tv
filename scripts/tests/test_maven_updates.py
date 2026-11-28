"""Offline regression tests for the independent Maven dependency updater."""
import contextlib
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import maven_dependency as maven
import update_maven_dependencies as cli
import update_external_libraries as external

FIXTURE = """[versions]
media3 = "1.11.1" # keep comment
okhttp = "5.5.0"
agp = "9.4.1"
composeBom = "2026.09.00"
libvlc = "3.7.8"

[libraries]
player = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
session = { module = "androidx.media3:media3-session", version.ref = "media3" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
ui = { module = "androidx.compose.ui:ui" }
vlc = { module = "org.videolan.android:libvlc-all", version.ref = "libvlc" }
inline = { module = "example:inline", version = "1.2" }
shorthand = "example:shorthand:1.3"

[plugins]
android = { id = "com.android.application", version.ref = "agp" }
"""


def metadata(*versions):
    entries = "".join(f"<version>{v}</version>" for v in versions)
    return f"<metadata><versioning><versions>{entries}</versions></versioning></metadata>".encode()


def pom(url):
    from urllib.parse import urlsplit
    artifact = urlsplit(url).path.split("/")[-3]
    return f'<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>{artifact}</artifactId></project>'.encode()


def repository(*versions):
    return lambda url: pom(url) if url.endswith(".pom") else metadata(*versions)


class MavenUpdatesTest(unittest.TestCase):
    def test_numeric_and_release_stage_order(self):
        versions = ["1.12.0-rc01", "1.11.1", "1.12.0-beta10", "1.12.0-beta2",
                    "1.12.0-alpha01", "1.12.0", "1.12.0-beta1", "1.9.10", "1.12.0-rc02"]
        self.assertEqual(sorted(versions, key=maven.version_key), [
            "1.9.10", "1.11.1", "1.12.0-alpha01", "1.12.0-beta1", "1.12.0-beta2",
            "1.12.0-beta10", "1.12.0-rc01", "1.12.0-rc02", "1.12.0",
        ])
        self.assertIsNone(maven.version_key("1.12.0-SNAPSHOT"))
        self.assertIsNone(maven.version_key("4.0.0-eap1"))
        self.assertEqual(maven.version_key("1.12.0.Final"), maven.version_key("1.12.0"))
        self.assertGreater(maven.version_key("1.12.0-M1"), maven.version_key("1.12.0-beta99"))
        self.assertGreater(maven.version_key("1.12.0-rc01"), maven.version_key("1.12.0-M1"))
        self.assertGreater(maven.version_key("2026.10.00"), maven.version_key("2026.09.00"))

    def test_shared_pins_and_bom(self):
        pins = maven.catalog_pins(FIXTURE)
        self.assertEqual({a.name for a in pins["media3"].artifacts}, {"media3-exoplayer", "media3-session"})
        self.assertEqual(pins["libraries.inline"].current, "1.2")
        self.assertEqual(pins["libraries.shorthand"].current, "1.3")
        self.assertEqual(pins["agp"].artifacts[0].coordinate,
                         "com.android.application:com.android.application.gradle.plugin")
        self.assertNotIn("libraries.ui", pins)

    def test_media3_release_channel_and_partial_publication(self):
        def download(url):
            if url.endswith(".pom"):
                return pom(url)
            if "media3-exoplayer/" in url:
                return metadata("1.11.1", "1.12.0-rc01", "1.12.0")
            if "media3-session/" in url:
                return metadata("1.11.1", "1.12.0-rc01")
            raise AssertionError(url)
        stable, = maven.plan(FIXTURE, download, key="media3")
        self.assertEqual((stable.candidate, stable.status), ("1.11.1", "ok"))
        preview, = maven.plan(FIXTURE, download, key="media3", media3_channel="prerelease")
        self.assertEqual((preview.candidate, preview.status), ("1.12.0-rc01", "UPDATE"))
        stable, = maven.plan(FIXTURE, repository("1.11.1", "1.12.0-rc01", "1.12.0"), key="media3")
        self.assertEqual((stable.candidate, stable.status), ("1.12.0", "UPDATE"))

    def test_metadata_without_all_release_poms_never_updates(self):
        def download(url):
            if url.endswith(".pom"):
                if "media3-session/" in url:
                    raise urllib.error.HTTPError(url, 404, "not published", None, None)
                return pom(url)
            return metadata("1.11.1", "1.12.0")
        result, = maven.plan(FIXTURE, download, key="media3")
        self.assertEqual(result.status, "UNAVAILABLE")
        self.assertIn("media3-session", result.reason)
        self.assertEqual(maven.render_catalog(FIXTURE, [result]), FIXTURE)

    def test_wrong_pom_aborts_update(self):
        def download(url):
            if url.endswith(".pom"):
                return b"<project><artifactId>wrong</artifactId></project>"
            return metadata("1.11.1", "1.12.0")
        with self.assertRaisesRegex(ValueError, "Mismatched Maven POM"):
            maven.plan(FIXTURE, download, key="media3")

    def test_legacy_libvlc_updater_respects_shared_catalog_pin(self):
        with mock.patch.object(external, "get_bytes", return_value=metadata("3.7.8", "4.0.0")):
            self.assertEqual(external.libvlc_latest_stable("3.8.0-rc01"), "3.8.0-rc01")
            self.assertEqual(external.libvlc_latest_stable("3.7.9"), "3.7.9")
            self.assertEqual(external.libvlc_latest_stable("3.7.7"), "3.7.8")
        with mock.patch.object(external, "get_bytes", return_value=metadata("3.8.0", "4.0.0")):
            self.assertEqual(external.libvlc_latest_stable("3.8.0-rc01"), "3.8.0")

    def test_no_downgrade_and_major_review(self):
        pins = maven.catalog_pins(FIXTURE)
        preview = maven.Pin("media3", "1.12.0-rc01", pins["media3"].artifacts)
        self.assertEqual(maven.find_update(preview, {"1.11.1"}, "stable").candidate, "1.12.0-rc01")
        self.assertEqual(maven.find_update(pins["agp"], {"9.5.0"}, "stable").status, "UPDATE")
        self.assertEqual(maven.find_update(pins["libvlc"], {"4.0.0"}, "stable", True).status, "MANUAL")
        self.assertEqual(maven.find_update(pins["okhttp"], {"6.0.0"}, "stable").status, "MANUAL")
        self.assertEqual(maven.find_update(pins["okhttp"], {"6.0.0", "5.6.0"}, "stable").candidate, "5.6.0")
        self.assertEqual(maven.find_update(pins["okhttp"], {"6.0.0"}, "stable", True).status, "UPDATE")
        self.assertEqual(maven.find_update(pins["composeBom"], {"2027.01.00"}, "stable").status, "UPDATE")

    def test_catalog_render_merges_multiple_pins_and_preserves_comments(self):
        pins = maven.catalog_pins(FIXTURE)
        updates = [
            maven.Finding(pins["media3"], "1.12.0", "UPDATE"),
            maven.Finding(pins["okhttp"], "5.6.0", "UPDATE"),
            maven.Finding(pins["agp"], "9.5.0", "UPDATE"),
            maven.Finding(pins["libraries.inline"], "1.4", "UPDATE"),
            maven.Finding(pins["libraries.shorthand"], "1.5", "UPDATE"),
        ]
        actual = maven.render_catalog(FIXTURE, updates)
        expected = (FIXTURE.replace('media3 = "1.11.1"', 'media3 = "1.12.0"')
                    .replace('okhttp = "5.5.0"', 'okhttp = "5.6.0"')
                    .replace('agp = "9.4.1"', 'agp = "9.5.0"')
                    .replace('version = "1.2"', 'version = "1.4"')
                    .replace('example:shorthand:1.3', 'example:shorthand:1.5'))
        self.assertEqual(actual, expected)

    def test_repository_fallback_only_for_not_found(self):
        artifact = maven.Artifact("androidx.media3", "media3-exoplayer")
        def missing(url):
            if url.startswith(maven.GOOGLE):
                raise urllib.error.HTTPError(url, 404, "missing", None, None)
            return metadata("1.11.1")
        self.assertEqual(maven.artifact_versions(artifact, missing), {"1.11.1"})
        with self.assertRaises(OSError):
            maven.artifact_versions(artifact, mock.Mock(side_effect=OSError("offline")))

    def test_cli_check_apply_scope_and_network_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            catalog = Path(folder) / "libs.versions.toml"
            catalog.write_text(FIXTURE, encoding="utf-8")
            with mock.patch.object(cli, "CATALOG", catalog), \
                 mock.patch.dict(os.environ, {"MAVEN_CHANNEL": "stable", "MAVEN_ALLOW_MAJOR": "0"}, clear=True), \
                 mock.patch.object(cli, "get_bytes", side_effect=repository("1.11.1", "1.12.0", "1.13.0-beta01")), \
                 contextlib.redirect_stdout(io.StringIO()):
                with mock.patch.object(sys, "argv", ["updater", "--check", "media3"]):
                    self.assertEqual(cli.main(), 0)
                self.assertEqual(catalog.read_text(), FIXTURE)
                with mock.patch.object(sys, "argv", ["updater", "--apply", "media3"]):
                    self.assertEqual(cli.main(), 0)
                expected = FIXTURE.replace('media3 = "1.11.1"', 'media3 = "1.12.0"')
                self.assertEqual(catalog.read_text(), expected)
                with mock.patch.object(sys, "argv", ["updater", "--apply", "media3"]), \
                     mock.patch.object(cli, "get_bytes", side_effect=OSError("offline")):
                    with self.assertRaises(OSError):
                        cli.main()
                self.assertEqual(catalog.read_text(), expected)

    def test_atomic_catalog_update_rejects_concurrent_edit(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "catalog.toml"
            path.write_bytes(b"user edit")
            with self.assertRaisesRegex(RuntimeError, "Concurrent catalog edit"):
                cli.apply_catalog(path, b"original", b"updated")
            self.assertEqual(path.read_bytes(), b"user edit")
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_real_catalog_tracks_all_media3_modules_and_coupled_pins(self):
        pins = maven.catalog_pins(cli.CATALOG.read_text(encoding="utf-8"))
        self.assertEqual({a.name for a in pins["media3"].artifacts}, {
            "media3-exoplayer", "media3-exoplayer-hls", "media3-exoplayer-dash",
            "media3-ui", "media3-common", "media3-effect", "media3-session",
            "media3-datasource-okhttp", "media3-datasource-cronet",
        })
        self.assertEqual({a.name for a in pins["chromiumCronet"].artifacts}, {
            "cronet",
        })
        self.assertEqual({a.name for a in pins["okhttp"].artifacts}, {
            "okhttp", "logging-interceptor", "okhttp-dnsoverhttps", "okhttp-brotli",
        })
        self.assertEqual({a.name for a in pins["bouncycastle"].artifacts}, {
            "bcprov-jdk18on", "bcpkix-jdk18on",
        })
        self.assertEqual({a.name for a in pins["smarttubeSlf4j"].artifacts}, {
            "slf4j-api", "slf4j-nop",
        })
        self.assertNotIn("smarttubeCronetAdapter", pins)
        self.assertNotIn("smarttubeCoreKtx", pins)
        self.assertNotIn("smarttubeCoroutines", pins)
        catalog = maven.tomllib.loads(cli.CATALOG.read_text(encoding="utf-8"))
        coordinates = []
        for entry in catalog["libraries"].values():
            coordinates.append(entry.get("module") or (entry["group"] + ":" + entry["name"]))
        self.assertEqual(len(coordinates), len(set(coordinates)), "duplicate Maven coordinates")
        for key in ("agp", "ksp", "bouncycastle", "chromiumCronet", "junrar",
                    "smarttubeDnsjava", "smarttubeJsonPath", "smarttubeSlf4j"):
            self.assertNotIn(key, maven.MANUAL)
        cronet = maven.Pin("chromiumCronet", "143.7445.0", pins["chromiumCronet"].artifacts)
        self.assertEqual(maven.find_update(cronet, {"500.1.0"}, "stable").status, "UPDATE")
        self.assertIn("hilt", maven.MANUAL)
        self.assertIn("jna", maven.MANUAL)

    def test_root_compat_template_and_recovery_share_cronet_substitution(self):
        root = cli.CATALOG.parents[1]
        canonical = (root / "build.gradle.kts").read_text(encoding="utf-8")
        template = (root / "gradle/root.build.compat.gradle.kts").read_text(encoding="utf-8")
        recovery = (root / "build.sh").read_text(encoding="utf-8").split("<<'EOF_ROOT_BUILD'\n", 1)[1].split("\nEOF_ROOT_BUILD", 1)[0] + "\n"
        self.assertEqual(canonical, template)
        self.assertEqual(canonical, recovery)
        self.assertIn('module("org.chromium.net:cronet:$ambientChromiumCronetVersion")', canonical)
        self.assertNotIn('libs.smarttube.core.ktx', (root / "smarttube_wrappers/sharedutils/build.gradle.kts").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
