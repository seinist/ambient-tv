import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from core_hotpath_patches import (BSNES_INPUT, MESEN_OLD, MESEN_NEW, SAMEBOY_OLD, SAMEBOY_NEW,
                                  PCE_OLD, PCE_NEW, SGX_OLD, SGX_NEW, NGP_OLD, NGP_NEW,
                                  FIXED_STATE_DECL, FIXED_WRITE_OLD, FIXED_WRITE_NEW,
                                  FIXED_GROW_OLD, FIXED_GROW_NEW, FIXED_SAVE,
                                  FIXED_RETRO_OLD, FIXED_RETRO_NEW, prepare_hotpaths,
                                  replace_once)


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
                ("sameboy", "libretro/libretro.c", SAMEBOY_OLD, SAMEBOY_NEW),
                ("mednafen_pce_fast", "libretro.c", PCE_OLD, PCE_NEW),
                ("mednafen_supergrafx", "libretro.cpp", SGX_OLD, SGX_NEW)):
            with self.subTest(core=core), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = root / relative
                path.parent.mkdir(parents=True)
                path.write_text("before\n" + old + "\nafter")
                prepare_hotpaths(root, core)
                self.assertEqual(path.read_text(), "before\n" + new + "\nafter")
                with self.assertRaises(ValueError):
                    prepare_hotpaths(root, core)

    def test_fixed_state_source_transform(self):
        for core in ("mednafen_ngp", "mednafen_wswan", "mednafen_vb", "mednafen_lynx"):
            with self.subTest(core=core), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "mednafen").mkdir()
                writer = root / ("mednafen/state.cpp" if core == "mednafen_lynx" else "mednafen/state.c")
                header = root / "mednafen/state.h"
                entry = root / ("libretro.cpp" if core in {"mednafen_vb", "mednafen_lynx"} else "libretro.c")
                header.write_text("int MDFNSS_SaveSM(void *st, int, int, const void*, const void*, const void*);\n")
                writer.write_text("static int32_t smem_write(StateMem *st, void *buffer, uint32_t len)\n{\n"
                                  + FIXED_WRITE_OLD + FIXED_GROW_OLD + "\n   }\n}\n")
                old = FIXED_RETRO_OLD.replace("   st.initial_malloc = 0;\n", "") if core == "mednafen_vb" else FIXED_RETRO_OLD
                entry.write_text((NGP_OLD + "\n" if core == "mednafen_ngp" else "") + old)
                prepare_hotpaths(root, core)
                self.assertIn(FIXED_RETRO_NEW, entry.read_text())
                self.assertIn(FIXED_WRITE_NEW, writer.read_text())
                self.assertIn(FIXED_GROW_NEW, writer.read_text())
                self.assertIn(FIXED_SAVE, writer.read_text())
                self.assertIn("bool AmbientSaveFixed", header.read_text())
                if core == "mednafen_ngp":
                    self.assertIn(NGP_NEW, entry.read_text())
                with self.assertRaises(ValueError):
                    prepare_hotpaths(root, core)

    def test_fixed_state_bounds_and_equivalence(self):
        # Exercise the actual replacement writer and wrapper under UBSan. The
        # mock upstream serializer intentionally ignores short writes as NGP does.
        source = """
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
typedef struct {
    uint8_t *data;
    uint32_t loc, len, malloced, initial_malloc;
} StateMem;
""" + FIXED_STATE_DECL + """
static int32_t smem_write(StateMem *st, void *buffer, uint32_t len)
{
""" + FIXED_WRITE_NEW + """
      uint32_t newsize = st->malloced >= 32768 ? st->malloced : 32768;
""" + FIXED_GROW_NEW + """
   }
   memcpy(st->data + st->loc, buffer, len);
   st->loc += len;
   if (st->loc > st->len) st->len = st->loc;
   return len;
}
static uint8_t payload[96];
static uint32_t payload_len = 96;
static bool force_failure = false;
static int MDFNSS_SaveSM(void *ptr, int a, int b, const void *c, const void *d, const void *e) {
    (void)a; (void)b; (void)c; (void)d; (void)e;
    StateMem *st = (StateMem*)ptr;
    uint8_t header[32] = {0};
    memcpy(header, "MDFNSVST", 8);
    smem_write(st, header, sizeof(header));
    smem_write(st, payload, payload_len);
    uint32_t length = st->loc;
    st->loc = 20;
    smem_write(st, &length, sizeof(length));
    return !force_failure;
}
""" + FIXED_SAVE + """
int main(void) {
    for (unsigned i = 0; i < sizeof(payload); ++i) payload[i] = i * 7;
    uint8_t data[160], original[128];
    memset(data, 0x55, sizeof(data));
    StateMem dynamic;
    memset(&dynamic, 0, sizeof(dynamic));
    assert(MDFNSS_SaveSM(&dynamic, 0, 0, NULL, NULL, NULL));
    assert(dynamic.len == 128);
    memcpy(original, dynamic.data, 128);
    free(dynamic.data);
    for (unsigned cap = 0; cap <= sizeof(data) - 16; ++cap) {
        memset(data, 0x55, sizeof(data));
        bool result = AmbientSaveFixed(data, cap);
        assert(result == (cap >= 128));
        for (unsigned i = cap; i < sizeof(data); ++i) assert(data[i] == 0x55);
        if (result) {
            assert(memcmp(data, original, 128) == 0);
            for (unsigned i = 128; i < cap; ++i) assert(data[i] == 0);
        }
        assert(!ambient_fixed_state && !ambient_fixed_failed);
    }
    assert(!AmbientSaveFixed(NULL, 128));
    force_failure = true;
    assert(!AmbientSaveFixed(data, 128));
    force_failure = false;
    assert(AmbientSaveFixed(data, 128));
}
"""
        self.compile_and_run(source, "cc", "c99", "c")
        self.compile_and_run(source, "c++", "c++11", "cpp")

    def test_pce_input_snapshot(self):
        source = """
#include <stdint.h>
#include <stdbool.h>
#include <assert.h>
enum { RETRO_DEVICE_JOYPAD = 1 };
static unsigned calls;
static uint16_t mask;
static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    assert(port == 0 && device == 1 && index == 0);
    ++calls;
    return (mask >> id) & 1;
}
static bool original(unsigned i, int16_t* joy_bits, bool libretro_supports_bitmasks) {
    (void)joy_bits; (void)libretro_supports_bitmasks;
    const unsigned j = 0;
    const unsigned map[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
""" + PCE_OLD + """ return true;
    return false;
}
static bool candidate(unsigned i, int16_t* joy_bits, bool libretro_supports_bitmasks) {
    const unsigned j = 0;
    const unsigned map[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
""" + PCE_NEW + """ return true;
    return false;
}
int main(void) {
    for (unsigned m = 0; m <= 65535; ++m) {
        mask = m;
        int16_t sample = (int16_t)m;
        for (unsigned i = 0; i < 16; ++i) {
            bool old = original(i, &sample, true);
            calls = 0;
            assert(candidate(i, &sample, true) == old && calls == 0);
            assert(candidate(i, &sample, false) == old && calls == 1);
        }
    }
}
"""
        self.compile_and_run(source, "cc", "c99", "c")

    def test_sgx_mouse_button_equivalence(self):
        source = """
#include <stdint.h>
#include <stdbool.h>
#include <assert.h>
#define BIT(x) (1u << (x))
enum { RETRO_DEVICE_JOYPAD = 1, RETRO_DEVICE_MOUSE = 2,
       RETRO_DEVICE_ID_JOYPAD_SELECT = 2, RETRO_DEVICE_ID_JOYPAD_START = 3,
       RETRO_DEVICE_ID_JOYPAD_MASK = 256, RETRO_DEVICE_ID_MOUSE_MIDDLE = 6 };
static struct { bool use_bitmasks; } r_input;
static unsigned digital_calls, mouse_calls;
static uint16_t mask;
static bool middle;
static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    assert(port == 0 && index == 0);
    if (device == RETRO_DEVICE_JOYPAD) {
        ++digital_calls;
        return id == RETRO_DEVICE_ID_JOYPAD_MASK ? (int16_t)mask : (mask >> id) & 1;
    }
    assert(device == RETRO_DEVICE_MOUSE && id == RETRO_DEVICE_ID_MOUSE_MIDDLE);
    ++mouse_calls;
    return middle;
}
static unsigned original() {
    unsigned port = 0, button_state = 0;
""" + SGX_OLD + """
    return button_state;
}
static unsigned candidate() {
    unsigned port = 0, button_state = 0;
""" + SGX_NEW + """
    return button_state;
}
int main() {
    for (unsigned m = 0; m <= 65535; ++m)
        for (unsigned mid = 0; mid < 2; ++mid) {
            mask = m; middle = mid;
            digital_calls = mouse_calls = 0;
            unsigned old = original();
            unsigned old_mouse_calls = mouse_calls;
            for (unsigned supported = 0; supported < 2; ++supported) {
                r_input.use_bitmasks = supported;
                digital_calls = mouse_calls = 0;
                assert(candidate() == old && mouse_calls == old_mouse_calls);
                assert(digital_calls == (supported ? 1u : 2u));
            }
        }
}
"""
        self.compile_and_run(source, "c++", "c++11", "cpp")

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
