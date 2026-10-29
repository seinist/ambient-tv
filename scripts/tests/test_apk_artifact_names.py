"""Exercise build.sh's actual labels and APK copy/signing paths without a native build."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


BUILD_SCRIPT = Path(__file__).resolve().parents[2] / "build.sh"
ABIS = {
    "ARM64": "arm64-v8a",
    "ARMV7": "armeabi-v7a",
    "X86": "x86",
    "X86_64": "x86_64",
}


class ApkArtifactNamesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "dist").mkdir()
        self.write_version("1.0", 353)

    def write_version(self, name: str, code: int | str) -> None:
        (self.root / "version.properties").write_text(
            f"VERSION_NAME={name}\nVERSION_CODE={code}\n"
        )

    def run_shell(self, body: str, overrides: dict[str, str] | None = None) -> subprocess.CompletedProcess:
        env = os.environ.copy()
        env.pop("ORG_GRADLE_PROJECT_versionName", None)
        env.pop("ORG_GRADLE_PROJECT_versionCode", None)
        env.update(overrides or {})
        return subprocess.run(
            [
                "bash", "-c",
                'source "$1"\nROOT_DIR="$2"\nDIST_DIR="$ROOT_DIR/dist"\n' + body,
                "test-apk-names", str(BUILD_SCRIPT), str(self.root),
            ],
            env=env, text=True, capture_output=True, timeout=15,
        )

    def assert_shell_success(self, result: subprocess.CompletedProcess) -> None:
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_stable_prerelease_metadata_and_already_numbered_labels(self) -> None:
        for name, expected in [
            ("1.0", "1.0.353"),
            ("1.0.2", "1.0.2.353"),
            ("1.0-beta", "1.0.353-beta"),
            ("1.0-alpha.2", "1.0.353-alpha.2"),
            ("1.0-rc.1", "1.0.353-rc.1"),
            ("1.0+local", "1.0.353+local"),
            ("1.0.353", "1.0.353"),
            ("1.0.353-beta", "1.0.353-beta"),
            ("1.0.353-rc.1+local", "1.0.353-rc.1+local"),
            ("1.0.3530", "1.0.3530.353"),
        ]:
            with self.subTest(name=name):
                result = self.run_shell(f'apk_version_label "{name}" 353')
                self.assert_shell_success(result)
                self.assertEqual(expected, result.stdout.strip())

    def test_packaged_version_wins_over_source_files_and_environment(self) -> None:
        self.prepare_release_outputs(unsigned=False, name="2.0-rc.1", code=777)
        self.write_version("1.0", 352)
        (self.root / "gradle.properties").write_text("versionCode=777\nversionName=2.0-rc.1\n")
        result = self.run_shell('package_release_variant_output ARM64 "$ROOT_DIR/app/build/outputs/apk/release"', {
            "ORG_GRADLE_PROJECT_versionName": " 9.0 ",
            "ORG_GRADLE_PROJECT_versionCode": " 999 ",
        })
        self.assert_shell_success(result)
        self.assertEqual(
            ["ambient-tv-arm64-2.0.777-rc.1.apk"],
            sorted(path.name for path in (self.root / "dist").iterdir()),
        )

    def test_debug_copy_uses_the_code_after_gradle_increment(self) -> None:
        self.write_version("1.0", 352)
        gradlew = self.root / "gradlew"
        gradlew.write_text("""#!/usr/bin/env python3
import json
from pathlib import Path
root = Path(__file__).resolve().parent
version = root / "version.properties"
version.write_text(version.read_text().replace("VERSION_CODE=352", "VERSION_CODE=353"))
output = root / "app/build/outputs/apk/debug/app-debug.apk"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(b"fresh debug APK")
(output.parent / "output-metadata.json").write_text(json.dumps({"elements": [{
    "outputFile": output.name, "filters": [], "versionName": "1.0", "versionCode": 353,
}]}))
(output.parent / "stale-debug.apk").write_bytes(b"stale")
""")
        gradlew.chmod(0o755)
        result = self.run_shell("""
prepare_nnedi3_quality_ladder() { :; }
ensure_spotify() { :; }
ensure_youtube_engine() { :; }
ensure_mpv() { :; }
ensure_vlc_vulkan() { :; }
ensure_projectm() { :; }
verify_gradle_build_graph() { :; }
build_debug
""")
        self.assert_shell_success(result)
        self.assertEqual(
            ["ambient-tv-debug-1.0.353.apk"],
            sorted(path.name for path in (self.root / "dist").iterdir()),
        )
        self.assertEqual(b"fresh debug APK", (self.root / "dist/ambient-tv-debug-1.0.353.apk").read_bytes())

    def prepare_release_outputs(self, unsigned: bool, name: str = "1.0", code: int | str = 353) -> None:
        output = self.root / "app/build/outputs/apk/release"
        output.mkdir(parents=True)
        elements = []
        for variant, abi in ABIS.items():
            filename = f"app-{abi}-release{'-unsigned' if unsigned else ''}.apk"
            (output / filename).write_bytes(variant.encode())
            elements.append({
                "filters": [{"filterType": "ABI", "value": abi}],
                "outputFile": filename,
                "versionName": name,
                "versionCode": code,
            })
        (output / "output-metadata.json").write_text(json.dumps({"elements": elements}))
        (output / "stale-arm64-release.apk").write_bytes(b"stale")

    def assert_release_copies(self, unsigned: bool, sign: bool = False) -> None:
        self.prepare_release_outputs(unsigned)
        result = self.run_shell(f"""
signing_available() {{ {'true' if sign else 'false'}; }}
sign_release_apk() {{ cp "$1" "$2"; }}
for variant in ARM64 ARMV7 X86 X86_64; do
    package_release_variant_output "$variant" "$ROOT_DIR/app/build/outputs/apk/release"
    [[ "$LAST_BUILT_APK_SIGNED" == {'0' if unsigned and not sign else '1'} ]]
done
""")
        self.assert_shell_success(result)
        marker = "-unsigned" if unsigned and not sign else ""
        expected = {
            f"ambient-tv-{variant.lower()}{marker}-1.0.353.apk": variant.encode()
            for variant in ABIS
        }
        self.assertEqual(expected, {path.name: path.read_bytes() for path in (self.root / "dist").iterdir()})

    def test_all_signed_release_abis_include_build_number(self) -> None:
        self.assert_release_copies(unsigned=False)

    def test_all_unsigned_release_abis_include_build_number(self) -> None:
        self.assert_release_copies(unsigned=True)

    def test_signing_destination_includes_build_number(self) -> None:
        self.assert_release_copies(unsigned=True, sign=True)

    def test_invalid_code_stops_packaging_instead_of_emitting_an_unnumbered_apk(self) -> None:
        self.prepare_release_outputs(unsigned=False, code="invalid")
        result = self.run_shell('package_release_variant_output ARM64 "$ROOT_DIR/app/build/outputs/apk/release"')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing or invalid APK version", result.stderr)
        self.assertEqual([], list((self.root / "dist").iterdir()))


if __name__ == "__main__":
    unittest.main()
