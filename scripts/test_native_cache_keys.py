import unittest
import native_cache_keys as keys


class NativeCacheKeysTest(unittest.TestCase):
    def key(self, group, files=None, pins="MPV_ANDROID_REF=one\nMONOGRAM_REF=one\nSVPFLOW_REF=one", toolchain="toolchain"):
        files = files or {"build.sh": b"common", "native/ambientmpv/ambientmpv.c": b"mpv",
                          "scripts/build_monogram_native.sh": b"monogram"}
        return keys.compute(group, files, files.__getitem__, toolchain, pins)

    def test_isolated_source_change(self):
        changed = {"build.sh": b"common", "native/ambientmpv/ambientmpv.c": b"new",
                   "scripts/build_monogram_native.sh": b"monogram"}
        self.assertNotEqual(self.key("mpv"), self.key("mpv", changed))
        self.assertEqual(self.key("monogram"), self.key("monogram", changed))

    def test_pins_and_shared_dependencies(self):
        pins = "MPV_ANDROID_REF=one\nMONOGRAM_REF=one\nSVPFLOW_REF=two"
        for group in ("mpv", "vlc", "svp"):
            self.assertNotEqual(self.key(group), self.key(group, pins=pins))
        self.assertEqual(self.key("monogram"), self.key("monogram", pins=pins))

    def test_toolchain_and_unclassified_pin_invalidate(self):
        for group in keys.GROUPS:
            self.assertNotEqual(self.key(group), self.key(group, toolchain="new"))
            self.assertNotEqual(self.key(group), self.key(group, pins="UNKNOWN_PIN=1"))


if __name__ == "__main__":
    unittest.main()
