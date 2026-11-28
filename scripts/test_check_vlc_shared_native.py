import subprocess
import tempfile
import unittest
from pathlib import Path
import zipfile

from check_vlc_shared_native import CORE, JNI, require_exports, verify


class SharedNativeExportsTest(unittest.TestCase):
    def library(self, root, exports):
        source = root / "probe.c"
        binary = root / "probe.so"
        source.write_text("\n".join(
            f'__attribute__((visibility("default"))) void {name}(void) {{}}'
            for name in sorted(exports)
        ) + '\n__attribute__((visibility("hidden"))) void hidden_symbol(void) {}\n')
        subprocess.run(["cc", "-shared", "-fPIC", "-fvisibility=hidden", str(source), "-o", str(binary)], check=True)
        return binary.read_bytes()

    def test_requires_defined_visible_symbols_in_each_abi(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            core = self.library(root, CORE)
            jni = self.library(root, JNI)
            aar = root / "vlc.aar"
            with zipfile.ZipFile(aar, "w") as archive:
                archive.writestr("jni/arm64-v8a/libvlc.so", core)
                archive.writestr("jni/arm64-v8a/libvlcjni.so", jni)
            verify(aar, ["arm64-v8a"])
            with self.assertRaisesRegex(ValueError, "missing exports"):
                require_exports(core, {"hidden_symbol"}, "core")
            with self.assertRaises(KeyError):
                verify(aar, ["arm64-v8a", "x86_64"])

    def test_missing_one_registration_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            data = self.library(Path(temp), {"ambient_vlc_bind_svp_v1"})
            with self.assertRaisesRegex(ValueError, "ambient_vlc_bind_pcm_v1"):
                require_exports(data, CORE, "core")


if __name__ == "__main__":
    unittest.main()
