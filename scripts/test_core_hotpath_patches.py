import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from core_hotpath_patches import (BSNES_INPUT, MESEN_OLD, MESEN_NEW, SAMEBOY_OLD, SAMEBOY_NEW,
                                  prepare_hotpaths)


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

    def test_other_pinned_anchors(self):
        for core, relative, old, new in (
                ("mesen", "Libretro/libretro.cpp", MESEN_OLD, MESEN_NEW),
                ("sameboy", "libretro/libretro.c", SAMEBOY_OLD, SAMEBOY_NEW)):
            with self.subTest(core=core), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = root / relative
                path.parent.mkdir(parents=True)
                path.write_text("before\n" + old + "\nafter")
                prepare_hotpaths(root, core)
                self.assertEqual(path.read_text(), "before\n" + new + "\nafter")
                with self.assertRaises(ValueError):
                    prepare_hotpaths(root, core)

    def test_mesen_save_buffer_equivalence(self):
        source = """
#include <algorithm>
#include <cassert>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>
using std::string;
static void original(void* data, size_t size, std::stringstream& ss) {
""" + MESEN_OLD + """
}
static void candidate(void* data, size_t size, std::stringstream& ss) {
""" + MESEN_NEW + """
}
int main() {
    for (size_t length = 0; length < 64; ++length)
        for (size_t size = 0; size < 128; ++size) {
            std::stringstream input;
            for (size_t i = 0; i < length; ++i) input.put(static_cast<char>(i * 13));
            std::vector<char> a(size + 16, 0x55), b = a;
            original(a.data(), size, input);
            candidate(b.data(), size, input);
            assert(a == b);
        }
}
"""
        self.compile_and_run(source, "c++", "c++11", "cpp")

    def test_sameboy_dual_screen_equivalence(self):
        source = """
#include <assert.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
static unsigned w, h, calls;
static unsigned emulated_devices = 2;
static int gameboy[2];
static uint32_t src[2 * 256 * 224], original_out[2 * 256 * 224], candidate_out[2 * 256 * 224];
static uint32_t *frame_buf = src, *frame_buf_copy;
static unsigned GB_get_screen_width(int *gb) { (void)gb; ++calls; return w; }
static unsigned GB_get_screen_height(int *gb) { (void)gb; ++calls; return h; }
static void video_cb(uint32_t *data, unsigned width, unsigned height, size_t pitch) {
    assert(data == frame_buf_copy && width == w * emulated_devices && height == h);
    assert(pitch == width * sizeof(uint32_t));
}
static void original(void) {
""" + SAMEBOY_OLD + """
}
static void candidate(void) {
""" + SAMEBOY_NEW + """
}
int main(void) {
    const unsigned widths[] = {160, 256};
    const unsigned heights[] = {144, 224};
    for (unsigned n = 0; n < 2; ++n) {
        w = widths[n]; h = heights[n];
        for (unsigned i = 0; i < w*h*2; ++i) src[i] = i * 0xabc123u;
        frame_buf_copy = original_out; calls = 0; original();
        unsigned old_calls = calls;
        frame_buf_copy = candidate_out; calls = 0; candidate();
        assert(memcmp(original_out, candidate_out, w*h*2*sizeof(uint32_t)) == 0);
        assert(calls == 2 && old_calls > calls);
    }
}
"""
        self.compile_and_run(source, "cc", "c99", "c", extra=["-Wno-sign-compare"])

    def compile_and_run(self, source, executable, standard, suffix, extra=()):
        compiler = shutil.which(executable)
        self.assertIsNotNone(compiler, f"{executable} is required")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ("test." + suffix)
            binary = root / "test"
            path.write_text(source)
            subprocess.run([compiler, "-std=" + standard, "-Wall", "-Wextra", "-Werror",
                            "-fsanitize=undefined", *extra, str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

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
