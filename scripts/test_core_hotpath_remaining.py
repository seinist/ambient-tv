"""Regression checks for the fifteen additional pin-specific core patches."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from core_hotpath_patches import prepare_hotpaths
from core_hotpath_remaining import PATCHES


def replacement(core, needle):
    matches = [(old, new) for _, old, new in PATCHES[core] if needle in old]
    if len(matches) != 1:
        raise AssertionError(f"Expected one {core} replacement for {needle!r}")
    return matches[0]


class RemainingCoreHotpathTests(unittest.TestCase):
    def test_exact_anchor_application_and_atomic_rejection(self):
        self.assertEqual(len(PATCHES), 15)
        self.assertEqual(sum(map(len, PATCHES.values())), 24)
        for core, changes in PATCHES.items():
            with self.subTest(core=core), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                files = {}
                expected = {}
                for relative, old, new in changes:
                    files[relative] = files.get(relative, "") + "\n/* fixture */\n" + old + "\n"
                    expected[relative] = expected.get(relative, "") + "\n/* fixture */\n" + new + "\n"
                for relative, source in files.items():
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(source.encode())
                # A missing late anchor must not leave the earlier edits applied.
                relative, old, _ = changes[-1]
                changed = files[relative].replace(old, "/* upstream drift */", 1)
                (root / relative).write_bytes(changed.encode())
                snapshot = {name: (root / name).read_bytes() for name in files}
                with self.assertRaises(ValueError):
                    prepare_hotpaths(root, core)
                self.assertEqual(snapshot, {name: (root / name).read_bytes() for name in files})
                (root / relative).write_bytes(files[relative].encode())
                prepare_hotpaths(root, core)
                self.assertEqual(expected, {name: (root / name).read_bytes().decode() for name in files})
                with self.assertRaises(ValueError):
                    prepare_hotpaths(root, core)

    def test_neon_kernels_compile_for_aarch64(self):
        clang = shutil.which("clang")
        self.assertIsNotNone(clang, "Clang is required for the ARM64 candidate syntax check")
        _, snes = replacement("snes9x", "uint16 l, r;")
        _, handy555 = replacement("handy", "static void lcd_ghosting_apply_2frames_555")
        _, handy565 = replacement("handy", "static void lcd_ghosting_apply_2frames_565")
        _, gambatte = replacement("gambatte", "gambatte::video_pixel_t *curr")
        _, gpsp = replacement("gpsp", "Mix colours:")
        _, race = replacement("race", "p[0] = sampleBuffer[i]")
        source = """
#include <stdint.h>
#include <stddef.h>
#include <arm_neon.h>
using uint16 = uint16_t;
#define AVERAGE_565(a,b) (((a)&(b))+((((a)^(b))&0xF7DE)>>1))
void snes_merge(uint16* input, uint16* output, int width) {
""" + snes + """
}
static void* framebuffer;
static void* framebuffer_history_1;
#define HANDY_SCREEN_WIDTH 160
#define COLOR_MIX_LSB_555 0x0421
#define COLOR_MIX_LSB_565 0x0821
""" + handy555 + "\n" + handy565 + """
namespace gambatte { using video_pixel_t = uint16_t; }
#define VIDEO_RGB565 1
#define VIDEO_WIDTH 160
#define VIDEO_HEIGHT 144
#define VIDEO_PITCH 256
static gambatte::video_pixel_t *video_buf, *video_buf_prev_1;
void gambatte_mix() {
""" + gambatte + """
      {
         const uint16_t a = curr[x], b = prev[x];
         prev[x] = a;
         curr[x] = (a + b + ((a ^ b) & 0x821)) >> 1;
      }
      curr += VIDEO_PITCH;
      prev += VIDEO_PITCH;
   }
}
void gpsp_mix(const uint16_t *src_curr, const uint16_t *src_prev, uint16_t *dst, size_t npx) {
    size_t i;
""" + gpsp + """
       */
       dst[i] = (rgb_curr + rgb_prev + ((rgb_curr ^ rgb_prev) & 0x821)) >> 1;
   }
}
void race_stereo(const int16_t *sampleBuffer, int16_t *p, unsigned samplesPerFrame) {
   unsigned i;
""" + race + """
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "kernels.cpp"
            output = path.with_suffix(".o")
            path.write_text(source)
            subprocess.run([clang, "--target=aarch64-none-elf", "-ffreestanding",
                            "-std=c++11", "-O2", "-c", str(path), "-o", str(output)],
                           check=True)
            self.assertGreater(output.stat().st_size, 0)

    def test_packed_pixel_math_and_deadzone_equivalence(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        source = r"""
#include <cassert>
#include <cmath>
#include <cstdint>
static unsigned floor_mix(unsigned a, unsigned b, unsigned lsb) {
    return (a & b) + (((a ^ b) & (0xffff ^ lsb)) >> 1);
}
static unsigned ceil_mix(unsigned a, unsigned b, unsigned lsb) {
    return floor_mix(a, b, lsb) + ((a ^ b) & lsb);
}
static uint32_t rng = 0x175c1u;
static uint32_t random_value() {
    rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return rng;
}
int main() {
    const unsigned masks[] = {0x0421, 0x0821};
    // Every value against edge cases, plus arbitrary independent pixel pairs.
    for (unsigned lsb : masks) {
        for (unsigned a = 0; a <= 65535; ++a) {
            unsigned partners[] = {0u, 65535u, a, 65535u-a, random_value() & 65535u};
            for (unsigned b : partners) {
                assert(floor_mix(a,b,lsb) == ((a+b-((a^b)&lsb))>>1));
                assert(ceil_mix(a,b,lsb) == ((a+b+((a^b)&lsb))>>1));
            }
        }
        for (unsigned i=0; i<1000000; ++i) {
            unsigned a=random_value()&65535u, b=random_value()&65535u;
            assert(floor_mix(a,b,lsb) == ((a+b-((a^b)&lsb))>>1));
            assert(ceil_mix(a,b,lsb) == ((a+b+((a^b)&lsb))>>1));
        }
    }
    const int deadzones[] = {0, 1, 327, 3276, 6553, 16384, 32767};
    for (int d : deadzones) {
        for (int x=-32768; x<=32767; ++x) {
            int ys[] = {0, -32768, 32767, x, static_cast<int>(random_value()%65536)-32768};
            for (int y : ys) {
                const uint64_t r2 = uint64_t(int64_t(x)*x) + uint64_t(int64_t(y)*y);
                const double r = std::sqrt(static_cast<double>(r2));
                assert((r <= d) == (r2 <= uint64_t(d)*d));
                const uint64_t limit = uint64_t(d)+1;
                assert((static_cast<uint64_t>(r) <= uint64_t(d)) == (r2 < limit*limit));
            }
        }
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "equivalence.cpp"
            binary = path.with_suffix("")
            path.write_text(source)
            subprocess.run([compiler, "-std=c++11", "-O2", "-fsanitize=undefined",
                            str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_genesis_audio_and_snapshot_copy_equivalence(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        old_fm, new_fm = replacement("genesis_plus_gx", "/* flush FM samples */")
        old_nes, new_nes = replacement("nestopia", "std::string state = ss.str();")
        source = """
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>
static struct { bool hq_fm; } config;
static struct { int blips[1]; } snd;
static bool audio_hard_disable = false;
static std::vector<int> events;
static void blip_add_delta(int, int t, int l, int r) {
    events.insert(events.end(), {t,l,r});
}
static void blip_add_delta_fast(int, int t, int l, int r) {
    events.insert(events.end(), {-t,l,r});
}
static std::vector<int> fm_original(int preamp, const int* ptr) {
    int time=0, prev_l=0, prev_r=0, l, r;
    const int cycles=128, fm_cycles_ratio=4;
    events.clear();
""" + old_fm + """
    events.insert(events.end(), {time,prev_l,prev_r});
    return events;
}
static std::vector<int> fm_candidate(int preamp, const int* ptr) {
    int time=0, prev_l=0, prev_r=0, l, r;
    const int cycles=128, fm_cycles_ratio=4;
    events.clear();
""" + new_fm + """
    events.insert(events.end(), {time,prev_l,prev_r});
    return events;
}
static bool nes_original(std::stringstream& ss, void *data, size_t size) {
    const size_t tracked_input_state_size_bytes=12;
""" + old_nes + """
    return true;
}
static bool nes_candidate(std::stringstream& ss, void *data, size_t size) {
    const size_t tracked_input_state_size_bytes=12;
""" + new_nes + """
    return true;
}
int main() {
    int audio[64];
    for (int i=0;i<64;i++) audio[i]=(i*7717)%65536-32768;
    const int gains[]={0,25,100,150,200};
    for (int gain : gains)
        for(int quality=0;quality<2;quality++) {
            config.hq_fm=quality;
            assert(fm_original(gain,audio)==fm_candidate(gain,audio));
        }
    for (size_t length=0;length<64;length++)
        for (size_t size=0;size<96;size++) {
            std::stringstream a,b;
            for(size_t i=0;i<length;i++) { a.put(char(i*13)); b.put(char(i*13)); }
            std::vector<char> x(size+16,0x55),y=x;
            const bool original=nes_original(a,x.data(),size);
            const bool candidate=nes_candidate(b,y.data(),size);
            assert(original==candidate && x==y);
        }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state_audio.cpp"
            binary = path.with_suffix("")
            path.write_text(source)
            subprocess.run([compiler, "-std=c++11", "-O2", "-fsanitize=undefined",
                            str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
