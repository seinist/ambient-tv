import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import verify_paired_apk_release as paired


class PairedReleaseTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.full, self.modular = (Path(temp.name) / x for x in ("full", "modular"))
        for directory in (self.full, self.modular):
            directory.mkdir()
            for abi in paired.updates.ABIS:
                with zipfile.ZipFile(directory / f"{directory.name}-{abi}.apk", "w") as archive:
                    archive.writestr(f"lib/{abi}/libc++_shared.so", b"same")
            (directory / ("update.json" if directory == self.full else "update-modular.json")).write_text("{}")
        self.addCleanup(patch.stopall)
        patch.object(paired.updates, "generate", return_value={}).start()
        patch.object(paired.updates, "verify_packaging").start()
        patch.object(paired.preview, "verify", side_effect=lambda path, **_: {path.stem.removeprefix("modular-")}).start()
        patch.object(paired.packages, "native_dependencies", return_value={"libc.so"}).start()

    def inspect(self, path, *_):
        return ("com.ambient.tv", 1, "1.0", 29, ("signer",)), {"abi": path.stem.split("-", 1)[1]}

    def test_matching_pair(self):
        with patch.object(paired.updates, "inspect_apk", side_effect=self.inspect):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")

    def test_signing_identity_mismatch(self):
        def inspect(path, *args):
            identity, entry = self.inspect(path, *args)
            if path.parent == self.modular:
                identity = (*identity[:-1], ("different",))
            return identity, entry
        with patch.object(paired.updates, "inspect_apk", side_effect=inspect), self.assertRaises(ValueError):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")

    def test_shared_library_mismatch(self):
        path = self.modular / "modular-x86.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("lib/x86/libc++_shared.so", b"changed")
        with patch.object(paired.updates, "inspect_apk", side_effect=self.inspect), self.assertRaises(ValueError):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")

    def graph(self, full_names, modular_names, dependencies):
        abi = "x86"
        paths = (self.full / "full-x86.apk", self.modular / "modular-x86.apk")
        for path, names in zip(paths, (full_names, modular_names)):
            with zipfile.ZipFile(path, "w") as archive:
                for name in names:
                    archive.writestr(f"lib/{abi}/{name}", name.encode())
        with zipfile.ZipFile(paths[0]) as full, zipfile.ZipFile(paths[1]) as modular, \
                patch.object(paired.packages, "native_dependencies",
                             side_effect=lambda data, name: dependencies.get(name, {"libc.so"})):
            paired.verify_native_graph(full, modular, abi)

    def test_retained_library_cannot_link_to_removed_optional_engine(self):
        full = {"libmpv.so", "libambientmpv.so", "libaudio.so"}
        with self.assertRaisesRegex(ValueError, "retained library links to omitted payload"):
            self.graph(full, {"libaudio.so"}, {"libaudio.so": {"libmpv.so"}})

    def test_transitive_shared_dependency_closure(self):
        full = {"libmpv.so", "libambientmpv.so", "libavcodec.so", "libavutil.so"}
        deps = {"libambientmpv.so": {"libmpv.so"}, "libmpv.so": {"libavcodec.so"},
                "libavcodec.so": {"libavutil.so"}}
        self.graph(full, {"libavcodec.so", "libavutil.so"}, deps)
        with self.assertRaisesRegex(ValueError, "Different shared library bytes"):
            self.graph(full, {"libavcodec.so"}, deps)
        with self.assertRaisesRegex(ValueError, "missing Full dependency"):
            self.graph(full - {"libavutil.so"}, {"libavcodec.so"}, deps)

    def test_host_dependency_may_not_link_back_into_optional_module(self):
        full = {"libmpv.so", "libambientmpv.so", "libavcodec.so"}
        with self.assertRaisesRegex(ValueError, "host dependency links into optional engine"):
            self.graph(full, {"libavcodec.so"}, {"libmpv.so": {"libavcodec.so"},
                                                "libavcodec.so": {"libmpv.so"}})

    def test_missing_abi(self):
        (self.modular / "modular-x86.apk").unlink()
        with patch.object(paired.updates, "inspect_apk", side_effect=self.inspect), self.assertRaises(ValueError):
            paired.verify(self.full, self.modular, "aapt2", "apksigner")


if __name__ == "__main__":
    unittest.main()
