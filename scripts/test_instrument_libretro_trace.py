import unittest

from instrument_libretro_trace import instrument


SOURCE = """#include <android/trace.h>
void LibretroDroid::step() {
    if (rewinding) {
        core->retro_run();
        checkVulkanFrameError();
    }
    for (size_t i = 0; i < frames; ++i) {
        core->retro_run();
        checkVulkanFrameError();
    }
}
void LibretroDroid::stepForNetplay() {
    core->retro_run();
}
bool LibretroDroid::showRewindState(const uint8_t* data, size_t size) {
    core->retro_run();
    return true;
}
"""


class CoreTracingTest(unittest.TestCase):
    def test_all_paths(self):
        result = instrument(SOURCE)
        for label in ("rewind", "retro_run", "netplay", "rewind_seek"):
            self.assertEqual(result.count(f"AmbientTV:Libretro/{label}"), 1)
        self.assertEqual(result.count("core->retro_run();"), 4)
        self.assertEqual(result.count("ATrace_isEnabled()"), 4)
        self.assertEqual(result.count("checkVulkanFrameError();"), 2)
        with self.assertRaises(ValueError):
            instrument(result)

    def test_context_drift(self):
        changed = SOURCE.replace("checkVulkanFrameError();", "publishContentFrameSize();")
        self.assertEqual(instrument(changed).count("ATrace_beginSection("), 4)

    def test_fails_closed(self):
        for changed in (
            SOURCE.replace("    core->retro_run();", "    core->retro_run();\n    core->retro_run();", 1),
            SOURCE.replace("core->retro_run();", "core->retro_run(); // changed", 1),
            SOURCE.replace("stepForNetplay", "stepForSomethingElse"),
            SOURCE + "\nvoid LibretroDroid::other() {\n    core->retro_run();\n}\n",
        ):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                instrument(changed)


if __name__ == "__main__":
    unittest.main()
