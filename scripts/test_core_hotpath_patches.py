import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from core_hotpath_patches import BSNES_INPUT, prepare_hotpaths


class CoreHotpathTests(unittest.TestCase):
    def test_bsnes_pinned_anchors_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "bsnes/target-libretro"
            base.mkdir(parents=True)
            libretro = base / "libretro.cpp"
            program = base / "program.cpp"
            original = ('#include "program.cpp"\n'
                        'RETRO_API void retro_init()\n{\n}\n'
                        'RETRO_API void retro_deinit()\n{\n}\n'
                        'RETRO_API void retro_run()\n{\n\tinput_poll();\n}\n')
            dispatch = "return input_state(libretro_port, libretro_device, libretro_index, libretro_id);"
            libretro.write_text(original)
            program.write_text("changed")
            with self.assertRaises(ValueError):
                prepare_hotpaths(root, "bsnes")
            self.assertEqual(libretro.read_text(), original)
            program.write_text(dispatch)
            prepare_hotpaths(root, "bsnes")
            self.assertIn("AmbientJoypadFrame ambientFrame(ambientJoypad)", libretro.read_text())
            self.assertIn("ambientJoypad.state(input_state,", program.read_text())
            with self.assertRaises(ValueError):
                prepare_hotpaths(root, "bsnes")
            with self.assertRaises(ValueError):
                prepare_hotpaths(root, "unknown")

    def test_bsnes_snapshot_equivalence(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler, "C++ compiler required for native snapshot test")
        preamble = """
#include <cstdint>
#include <cassert>
using retro_environment_t = bool(*)(unsigned, void*);
using retro_input_state_t = int16_t(*)(unsigned, unsigned, unsigned, unsigned);
constexpr unsigned RETRO_ENVIRONMENT_GET_INPUT_BITMASKS = 51;
constexpr unsigned RETRO_DEVICE_JOYPAD = 1;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_MASK = 256;
"""
        test = """
static unsigned calls = 0, maskCalls = 0;
static uint16_t masks[5] = {};
static bool capability = true;
static bool environment(unsigned cmd, void*) {
    assert(cmd == RETRO_ENVIRONMENT_GET_INPUT_BITMASKS);
    return capability;
}
static int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    ++calls;
    if (device != RETRO_DEVICE_JOYPAD || index || port >= 5 || (id >= 16 && id != 256))
        return -123;
    if (id == 256) { ++maskCalls; return static_cast<int16_t>(masks[port]); }
    return (masks[port] >> id) & 1;
}
int main() {
    AmbientJoypadSnapshot snapshot;
    snapshot.negotiate(environment);
    for (unsigned mask = 0; mask <= 65535; ++mask) {
        AmbientJoypadFrame frame(snapshot);
        const unsigned port = mask % 5;
        masks[port] = static_cast<uint16_t>(mask);
        unsigned previous = calls;
        for (unsigned id = 0; id < 16; ++id)
            assert(snapshot.state(input, port, 1, 0, id) == ((mask >> id) & 1));
        assert(calls == previous + 1);
    }
    assert(maskCalls == 65536);
    {
        AmbientJoypadFrame frame(snapshot);
        masks[0] = 0x8000;
        masks[4] = 0x4000;
        assert(snapshot.state(input, 0, 1, 0, 15) == 1);
        assert(snapshot.state(input, 4, 1, 0, 14) == 1);
        masks[0] = 0;
        assert(snapshot.state(input, 0, 1, 0, 15) == 1);
        assert(snapshot.state(input, 0, 2, 0, 0) == -123);
        assert(snapshot.state(input, 0, 1, 1, 0) == -123);
        assert(snapshot.state(input, 5, 1, 0, 0) == -123);
    }
    assert(snapshot.state(input, 0, 1, 0, 15) == 0);
    capability = false;
    snapshot.negotiate(environment);
    AmbientJoypadFrame frame(snapshot);
    const unsigned previous = calls;
    for (unsigned id = 0; id < 16; ++id)
        assert(snapshot.state(input, 0, 1, 0, id) == 0);
    assert(calls == previous + 16);
}
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "test.cpp"
            binary = root / "test"
            source.write_text(preamble + BSNES_INPUT + test)
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                            "-fsanitize=undefined", str(source), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
