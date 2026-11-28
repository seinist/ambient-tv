import tempfile
import unittest
from pathlib import Path
import zipfile

import export_monogram_adapter as exporter


class MonogramDexExportTest(unittest.TestCase):
    def make_inputs(self, root, extra=None):
        classes = {
            name: b"test" for name in exporter.REQUIRED
        }
        classes.update({
            "uniffi/monogram_mtproto/UniffiLib.class": b"ffi",
            "org/rustls/platformverifier/Verifier.class": b"tls",
            "com/ambient/tv/data/telegram/MonogramModuleFactory.class": b"host",
            "com/ambient/tv/data/telegram/TelegramMedia.class": b"host",
            "kotlin/Unit.class": b"host",
            "com/ambient/tv/data/telegram/MonogramNativeBackend$1.class": b"adapter",
        })
        classes.update(extra or {})
        jar = root / "input.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for name, value in classes.items():
                archive.writestr(name, value)
        return jar

    def test_only_owned_code_enters_program_jar(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.make_inputs(root)
            program, host = root / "program.jar", root / "host.jar"
            exporter.split([source], program, host)
            with zipfile.ZipFile(program) as archive:
                names = archive.namelist()
                self.assertTrue(all(exporter.owns(name) for name in names))
                self.assertIn(exporter.ENTRY, names)
                self.assertIn("com/ambient/tv/data/telegram/MonogramNativeBackend$1.class", names)
            with zipfile.ZipFile(host) as archive:
                self.assertIn("com/ambient/tv/data/telegram/MonogramModuleFactory.class", archive.namelist())
                self.assertIn("kotlin/Unit.class", archive.namelist())

    def test_conflicting_duplicate_class_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.make_inputs(root)
            duplicate = root / "duplicate.jar"
            with zipfile.ZipFile(duplicate, "w") as archive:
                archive.writestr(exporter.ENTRY, b"different")
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                exporter.split([source, duplicate], root / "program.jar", root / "host.jar")

    def test_smarttube_keeps_resource_classes_and_shared_libraries_in_host(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "input.jar"
            classes = {name: b"code" for name in exporter.SMARTTUBE_REQUIRED}
            classes.update({
                "com/liskovsoft/youtubeapi/R$raw.class": b"resource ids",
                "com/eclipsesource/v8/LibraryLoader.class": b"native loader",
                "okhttp3/OkHttpClient.class": b"shared",
                "com/ambient/tv/youtube/SmartTubeEngine.class": b"host",
            })
            with zipfile.ZipFile(source, "w") as archive:
                for name, value in classes.items():
                    archive.writestr(name, value)
            program, host = root / "program.jar", root / "host.jar"
            exporter.split([source], program, host, "smarttube")
            with zipfile.ZipFile(program) as archive:
                self.assertEqual(set(archive.namelist()),
                                 set(exporter.SMARTTUBE_REQUIRED) | {"com/eclipsesource/v8/LibraryLoader.class"})
            with zipfile.ZipFile(host) as archive:
                self.assertIn("com/liskovsoft/youtubeapi/R$raw.class", archive.namelist())
                self.assertIn("okhttp3/OkHttpClient.class", archive.namelist())
                self.assertIn("com/ambient/tv/youtube/SmartTubeEngine.class", archive.namelist())


if __name__ == "__main__":
    unittest.main()
