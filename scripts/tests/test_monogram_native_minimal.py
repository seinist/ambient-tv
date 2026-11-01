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
        workflow = (ROOT / ".github/workflows/monogram-native-abi.yml").read_text()

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
