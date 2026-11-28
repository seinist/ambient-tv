import tempfile
import unittest
from pathlib import Path
import zipfile

from export_monogram_adapter import SMARTTUBE_REQUIRED
from strip_smarttube_classes import strip


class StripSmartTubeTest(unittest.TestCase):
    def source(self, root, extra=None):
        path = root / "input.jar"
        classes = {name: b"engine" for name in SMARTTUBE_REQUIRED}
        classes.update({
            "com/liskovsoft/youtubeapi/R$raw.class": b"resource",
            "com/liskovsoft/sharedutils/R.class": b"resource",
            "com/ambient/tv/youtube/SmartTubeEngine.class": b"host",
            "com/ambient/tv/modules/SystemModuleEntrypoint.class": b"host",
            "okhttp3/OkHttpClient.class": b"shared",
        })
        classes.update(extra or {})
        with zipfile.ZipFile(path, "w") as jar:
            for name, data in classes.items():
                jar.writestr(name, data)
        return path

    def test_strips_executable_code_but_preserves_host_classes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.source(root, {"com/eclipsesource/v8/LibraryLoader.class": b"engine"})
            output = root / "stripped.jar"
            strip([source], output)
            with zipfile.ZipFile(output) as jar:
                self.assertEqual(set(jar.namelist()), {
                    "com/liskovsoft/youtubeapi/R$raw.class",
                    "com/liskovsoft/sharedutils/R.class",
                    "com/ambient/tv/youtube/SmartTubeEngine.class",
                    "com/ambient/tv/modules/SystemModuleEntrypoint.class",
                    "okhttp3/OkHttpClient.class",
                })

    def test_host_reference_to_private_library_blocks_stripping(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.source(root, {"host/Bad.class": b"\xca\xfe\xba\xbeLcom/grack/nanojson/JsonParser;"})
            output = root / "stripped.jar"
            with self.assertRaisesRegex(ValueError, "host class references private"):
                strip([source], output)
            self.assertFalse(output.exists())

    def test_incomplete_graph_does_not_replace_existing_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "incomplete.jar"
            with zipfile.ZipFile(source, "w") as jar:
                jar.writestr("host/Only.class", b"host")
            output = root / "stripped.jar"
            output.write_bytes(b"previous")
            with self.assertRaisesRegex(ValueError, "incomplete SmartTube"):
                strip([source], output)
            self.assertEqual(output.read_bytes(), b"previous")

    def test_conflicting_host_class_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.source(root)
            duplicate = root / "duplicate.jar"
            with zipfile.ZipFile(duplicate, "w") as jar:
                jar.writestr("okhttp3/OkHttpClient.class", b"conflict")
            output = root / "stripped.jar"
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                strip([source, duplicate], output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
