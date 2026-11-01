"""Regressions for the opt-in, minimal Monogram MTProto build."""
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "merge_monogram_aars.py"
SPEC = importlib.util.spec_from_file_location("monogram_aar", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def make_aar(path: Path, abi: str, common=b"same-bytecode", native=b"native"):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", b"manifest")
        z.writestr("classes.jar", common)
        z.writestr(f"jni/{abi}/libmonogram_mtproto.so", native)


class MinimalMonogramTests(unittest.TestCase):
    def test_only_selected_abis_and_identical_kotlin_are_merged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            arm = root / "arm.aar"
            emu = root / "emu.aar"
            output = root / "combined.aar"
            make_aar(arm, "arm64-v8a", native=b"arm")
            make_aar(emu, "x86_64", native=b"emu")
            module.merge(output, [f"arm64-v8a={arm}", f"x86_64={emu}"])
            with zipfile.ZipFile(output) as z:
                self.assertEqual(
                    set(z.namelist()),
                    {
                        "AndroidManifest.xml",
                        "classes.jar",
                        "jni/arm64-v8a/libmonogram_mtproto.so",
                        "jni/x86_64/libmonogram_mtproto.so",
                    },
                )
                self.assertEqual(z.read("classes.jar"), b"same-bytecode")

    def test_mismatched_kotlin_bytecode_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            arm = root / "arm.aar"
            emu = root / "emu.aar"
            make_aar(arm, "arm64-v8a")
            make_aar(emu, "x86_64", common=b"different")
            with self.assertRaisesRegex(ValueError, "Non-native AAR content differs"):
                module.merge(root / "out.aar", [f"arm64-v8a={arm}", f"x86_64={emu}"])

    def test_missing_native_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wrong = root / "wrong.aar"
            make_aar(wrong, "arm64-v8a")
            with self.assertRaisesRegex(ValueError, "lacks"):
                module.merge(root / "out.aar", [f"x86={wrong}"])

    def test_application_api_keys_are_runtime_only_and_encrypted(self):
        gradle = (ROOT / "app" / "build.gradle.kts").read_text()
        backend = (ROOT / "app/src/monogram/java/com/ambient/tv/data/telegram/MonogramNativeBackend.kt").read_text()
        repository = (ROOT / "app/src/main/java/com/ambient/tv/data/telegram/TelegramRepository.kt").read_text()
        token_store = (ROOT / "app/src/main/java/com/ambient/tv/data/local/TokenStore.kt").read_text()
        settings = (ROOT / "app/src/main/java/com/ambient/tv/ui/settings/MonogramAccountSettings.kt").read_text()
        # The optional native ABI workflow is proposed separately; check it only when present.
        workflow_file = ROOT / ".github/workflows/monogram-native-abi.yml"
        workflow = workflow_file.read_text() if workflow_file.is_file() else ""

        for forbidden in ("MONOGRAM_API_ID", "MONOGRAM_API_HASH"):
            self.assertNotIn(forbidden, gradle)
            self.assertNotIn(forbidden, workflow)
        self.assertIn("tokenStore.monogramCredentials()", backend)
        self.assertIn("TokenStore::class.java", repository)
        self.assertIn("@EncryptedPrefs private val prefs", token_store)
        self.assertIn('putString("monogram_api_id"', token_store)
        self.assertIn('putString("monogram_api_hash"', token_store)
        self.assertIn("PasswordVisualTransformation()", settings)
        self.assertIn("apiCredentialsReady", settings)
        self.assertIn("resetForCredentials()", backend)

    def test_leave_channel_patch_is_pinned_and_native_only(self):
        shell = (ROOT / "scripts" / "build_monogram_native.sh").read_text()
        gradle = (ROOT / "app" / "build.gradle.kts").read_text()
        patch = (ROOT / "scripts" / "patch_monogram_leave.py").read_text()
        self.assertIn('patch=$patch_digest', shell)
        self.assertIn('patch_monogram_leave.py', gradle)
        self.assertIn('ChannelsLeaveChannelRequest', patch)
        self.assertIn('nativeLeaveChat', patch)
        self.assertIn('mono_proto_client::dispatch::leave_chat', patch)

    def test_every_patched_upstream_file_gets_a_dated_gpl_modification_notice(self):
        import re
        patch = (ROOT / "scripts" / "patch_monogram_leave.py").read_text()
        self.assertRegex(patch, r'MODIFICATION_DATE = "\d{4}-\d{2}-\d{2}"')
        self.assertIn('NOTICE_MARKER = "Modified by Ambient TV, "', patch)
        # Idempotent: added only when the marker is absent.
        self.assertIn("if NOTICE_MARKER not in result:", patch)
        notices = patch[patch.index("MODIFICATIONS = {"):patch.index("def modification_notice")]
        patched = set(re.findall(r'^\w+ = "((?:native|vendor)/[^"]+)"$', patch, re.M))
        self.assertTrue(patched)
        for relative in patched:
            self.assertIn(f'"{relative}"', notices)

    def test_unofficial_client_notice_and_gpl_scope_are_documented(self):
        for values, text in (("values", "Unofficial client, uses the Telegram API."),
                             ("values-en", "Unofficial client, uses the Telegram API."),
                             ("values-de", "Inoffizieller Client, nutzt die Telegram-API.")):
            strings = (ROOT / "app/src/main/res" / values / "strings.xml").read_text()
            self.assertIn(f'<string name="monogram_unofficial_notice">{text}</string>', strings)
        settings = (ROOT / "app/src/main/java/com/ambient/tv/ui/settings/MonogramAccountSettings.kt").read_text()
        self.assertIn("R.string.monogram_unofficial_notice", settings)
        self.assertIn("Unofficial client, uses the Telegram API.", (ROOT / "README.md").read_text())
        self.assertIn("Inoffizieller Client, nutzt die Telegram-API.", (ROOT / "README.de.md").read_text())
        self.assertIn("GPL-3.0-only", (ROOT / "README.md").read_text())
        self.assertIn("Unofficial client, uses the Telegram API.",
                      (ROOT / "docs/monogram-integration.md").read_text())

    def test_monogram_is_pinned_to_a_release_tag_with_a_verified_commit(self):
        import re
        props = (ROOT / "gradle" / "external-versions.properties").read_text()
        shell = (ROOT / "scripts" / "build_monogram_native.sh").read_text()
        gradle = (ROOT / "app" / "build.gradle.kts").read_text()
        updater = (ROOT / "scripts" / "update_monogram_pin.py").read_text()
        self.assertRegex(props, r"(?m)^MONOGRAM_VERSION=v?\d+(\.\d+)+$")
        self.assertRegex(props, r"(?m)^MONOGRAM_REF=[0-9a-f]{40}$")
        self.assertRegex(props, r"(?m)^MONOGRAM_MONO_RS_REF=[0-9a-f]{40}$")
        # The tag is fetched (not a bare commit) and a moved tag fails the build.
        self.assertIn('"+refs/tags/$version:refs/tags/$version"', shell)
        self.assertIn('[[ "$tag_commit" == "$ref" ]]', shell)
        self.assertIn('[[ "$submodule_commit" == "$mono_rs_ref" ]]', shell)
        self.assertNotIn('fetch --depth=1 origin "$ref"', shell)
        self.assertIn("version=%s", shell)
        self.assertIn('"version=${externalVersion("MONOGRAM_VERSION")}"', gradle)
        # Pin updates follow GitHub releases, pre-releases included, never a branch head.
        self.assertIn("/releases", updater)
        self.assertNotIn("commits/develop", updater)

    def test_latest_release_includes_pre_releases_but_not_drafts(self):
        spec = importlib.util.spec_from_file_location(
            "monogram_pin", ROOT / "scripts" / "update_monogram_pin.py")
        pin = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pin)
        releases = [
            {"tag_name": "0.4.2", "prerelease": True, "draft": False, "published_at": "2026-10-17T10:19:53Z"},
            {"tag_name": "0.5.0", "prerelease": False, "draft": True, "published_at": None},
            {"tag_name": "0.4.3", "prerelease": True, "draft": False, "published_at": "2026-11-01T12:02:16Z"},
        ]
        self.assertEqual("0.4.3", pin.latest_release(releases)["tag_name"])

    def test_unused_sticker_qr_and_waveform_native_code_is_pruned(self):
        shell = (ROOT / "scripts" / "build_monogram_native.sh").read_text()
        patch = (ROOT / "scripts" / "patch_monogram_leave.py").read_text()
        # The media-only build retains core MTProto/UniFFI but not the sticker
        # renderer, the separate native QR stack or audio-waveform helpers.
        self.assertIn("vendor/mono-rs", shell)
        self.assertNotIn("vendor/tlottie vendor/mono-rs", shell)
        self.assertIn("'rxing = { version = ", patch)
        self.assertIn("'flate2 = \"1\"\\ntlottie = {", patch)
        self.assertIn("pub fn create_lottie(", patch)
        self.assertIn("pub fn decode_vpx_alpha_packet(", patch)
        self.assertIn("LottieNative.kt", patch)
        self.assertIn("VpxNative.kt", patch)
        self.assertIn("source.count(start) != 1", patch)

    def test_opt_in_and_native_module_only_build_graph(self):
        gradle = (ROOT / "app" / "build.gradle.kts").read_text()
        shell = (ROOT / "scripts" / "build_monogram_native.sh").read_text()
        self.assertIn('orElse("0").get()', gradle)
        self.assertIn('if (monogramEnabled) {', gradle)
        self.assertIn('sourceSets.getByName("main").java.srcDir("src/monogram/java")', gradle)
        self.assertIn(':native:mtproto:assembleRelease', shell)
        self.assertNotIn(':native:markup:assemble', shell)
        self.assertNotIn(':app:assemble', shell)
        self.assertNotIn(':network:bridge:assemble', shell)


if __name__ == "__main__":
    unittest.main()
