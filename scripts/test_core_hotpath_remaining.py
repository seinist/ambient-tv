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
        self.assertEqual(len(PATCHES), 39)
        self.assertEqual(sum(map(len, PATCHES.values())), 218)
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

    def test_flycast_ring_wrap_overflow_and_flush(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        changes = PATCHES["flycast"]
        declaration = next(new for _, old, new in changes if "static std::mutex audio_buffer_mutex;" in old)
        lifecycle = next(new for _, old, new in changes if old.startswith("void retro_audio_init"))
        upload = next(new for _, old, new in changes if old.startswith("void retro_audio_upload"))
        tail = next(new for _, old, new in changes if "int16_t *audio_out_buffer_ptr" in old)
        source = r"""
#include <array>
#include <atomic>
#include <limits>
#include <cstdint>
#include <cstddef>
#include <cassert>
#include <vector>
#include <thread>
using s16 = int16_t;
static float audio_samples_per_frame_avg;
static unsigned vsync_swap_interval_last, vsync_swap_interval_conter;
static std::vector<int16_t> submitted;
static size_t audio_batch_cb(const int16_t* data, size_t frames) {
    submitted.insert(submitted.end(), data, data + frames * 2);
    return frames;
}
""" + declaration + lifecycle + upload + tail + r"""
int main() {
    retro_audio_init();
    // Exercise both uint32 counter wrap and the physical ring wrap.
    audio_read_pos.store(0xfffffffc);
    audio_write_pos.store(0xfffffffc);
    for (int i=0; i<10; ++i) WriteSample(i+100, i);
    retro_audio_upload();
    assert(submitted.size() == 20);
    for (int i=0; i<10; ++i) {
        assert(submitted[i*2] == i);
        assert(submitted[i*2+1] == i+100);
    }
    assert(audio_read_pos.load() == audio_write_pos.load());
    submitted.clear();
    for (unsigned i=0; i<=audio_frame_limit; ++i) WriteSample(1,2);
    assert(audio_overflow.load());
    retro_audio_upload();
    assert(submitted.empty() && !audio_overflow.load());
    WriteSample(3,4);
    retro_audio_flush_buffer();
    retro_audio_upload();
    assert(submitted.empty());
    WriteSample(5,6);
    retro_audio_upload();
    assert((submitted == std::vector<int16_t>{6,5}));
    retro_audio_deinit();
    WriteSample(7,8);
    retro_audio_upload();
    assert(submitted.size() == 2);
    retro_audio_init();
    submitted.clear();
    constexpr unsigned count = 60000;
    std::atomic<bool> done{false};
    std::thread producer([&]{
        for (unsigned i=0;i<count;++i) {
            while (audio_write_pos.load() - audio_read_pos.load() >= 512)
                std::this_thread::yield();
            WriteSample(static_cast<int16_t>(i ^ 0x5555), static_cast<int16_t>(i));
        }
        done.store(true, std::memory_order_release);
    });
    while (!done.load(std::memory_order_acquire) || audio_read_pos.load() != audio_write_pos.load())
        retro_audio_upload();
    producer.join();
    assert(submitted.size() == count * 2);
    for (unsigned i=0;i<count;++i) {
        assert(submitted[i*2] == static_cast<int16_t>(i));
        assert(submitted[i*2+1] == static_cast<int16_t>(i ^ 0x5555));
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "flycast.cpp"
            binary = path.with_suffix("")
            path.write_text(source)
            subprocess.run([compiler, "-std=c++11", "-O2", "-fsanitize=undefined",
                            "-pthread", str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_ymir_neon_kernels_compile_without_implicit_vector_casts(self):
        clang = shutil.which("clang")
        self.assertIsNotNone(clang)
        changes = PATCHES["ymir"]
        helper = next(new for _, old, new in changes if old == "FORCE_INLINE static void Color888ShadowMasked(")
        helper = helper.split("FORCE_INLINE static void Color888ShadowMasked(")[0]
        kernels = [new.split("\n", 1)[1] for _, old, new in changes
                   if old.startswith("#elif defined(_M_ARM64)") and "AmbientMask4" in new]
        self.assertEqual(len(kernels), 6)
        source = r"""
#include <stdint.h>
#include <stddef.h>
#include <arm_neon.h>
using uint8 = uint8_t;
using uint32 = uint32_t;
#define FORCE_INLINE inline
namespace std {
inline void memcpy(void* dst, const void* src, size_t n) { __builtin_memcpy(dst, src, n); }
}
template<class T> struct Span {
    T* p;
    size_t n;
    T* data() const { return p; }
    size_t size() const { return n; }
    T& operator[](size_t i) const { return p[i]; }
};
""" + helper
        for i, kernel in enumerate(kernels):
            source += """
void kernel%d(Span<uint32> pixels, Span<uint32> dest, Span<const bool> mask,
             Span<const uint32> topColors, Span<const uint32> btmColors,
             Span<const uint32> src, Span<const uint8> ratios) {
    size_t i = 0;
""" % i + kernel + "\n}\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ymir.cpp"
            output = path.with_suffix(".o")
            path.write_text(source)
            subprocess.run([clang, "--target=aarch64-none-elf", "-ffreestanding",
                            "-flax-vector-conversions=none", "-std=c++11", "-O3",
                            "-c", str(path), "-o", str(output)], check=True)
            self.assertGreater(output.stat().st_size, 0)

    def test_melonds_full_screen_copy_skips_clear_only_when_safe(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        _, candidate = replacement("melondsds", "buffer.Clear();")
        source = r"""
#include <cassert>
#include <cstdint>
#include <vector>
#include <algorithm>
enum class ScreenLayout { TopOnly, BottomOnly, Other, Hybrid, Large, Scaled };
struct Point { unsigned x, y; };
constexpr unsigned NDS_SCREEN_WIDTH = 4, NDS_SCREEN_HEIGHT = 3;
static bool direct = true;
bool LayoutSupportsDirectCopy(ScreenLayout) { return direct; }
bool IsHybridLayout(ScreenLayout l) { return l == ScreenLayout::Hybrid; }
bool IsLargeScreenLayout(ScreenLayout l) { return l == ScreenLayout::Large; }
bool LayoutSupportsSecondaryScreenScale(ScreenLayout l) { return l == ScreenLayout::Scaled; }
struct Layout {
    ScreenLayout layout;
    Point top, bottom;
    ScreenLayout LayoutValue() const { return layout; }
    unsigned SecondaryScreenScale() const { return 50; }
    Point GetTopScreenTranslation() const { return top; }
    Point GetBottomScreenTranslation() const { return bottom; }
};
struct Buffer {
    unsigned width, height, clears, copies;
    std::vector<uint32_t> pixels;
    unsigned Width() const { return width; }
    unsigned Height() const { return height; }
    void Clear() { ++clears; std::fill(pixels.begin(), pixels.end(), 0); }
    void CopyDirect(const uint32_t* src, Point p) {
        assert(p.x == 0 && p.y == 0);
        ++copies;
        std::copy(src, src + NDS_SCREEN_WIDTH * NDS_SCREEN_HEIGHT, pixels.begin());
    }
};
void combine(Buffer& buffer, const Layout& screenLayout,
             const std::vector<uint32_t>& topBuffer, const std::vector<uint32_t>& bottomBuffer) {
    auto CopyScreen = [&](const uint32_t* src, Point p, ScreenLayout) {
        ++buffer.copies;
        for (unsigned y=0;y<NDS_SCREEN_HEIGHT;++y)
            for(unsigned x=0;x<NDS_SCREEN_WIDTH;++x)
                buffer.pixels[(p.y+y)*buffer.width+p.x+x] = src[y*NDS_SCREEN_WIDTH+x];
    };
""" + candidate.replace("screenLayout.Layout()", "screenLayout.LayoutValue()") + r"""
}
int main() {
    const std::vector<uint32_t> top(12, 0x11223344), bottom(12, 0x55667788);
    for (auto layout : {ScreenLayout::TopOnly, ScreenLayout::BottomOnly, ScreenLayout::Other})
        for (unsigned w : {4u, 5u})
            for (unsigned h : {3u, 4u})
                for (unsigned x : {0u, 1u})
                    for (unsigned y : {0u, 1u})
                        for (bool supports : {false, true}) {
                            direct = supports;
                            Buffer b{w, h, 0, 0, std::vector<uint32_t>(w*h, 0xffffffff)};
                            combine(b, Layout{layout, {x,y}, {x,y}}, top, bottom);
                            const bool full = layout != ScreenLayout::Other && w == 4 && h == 3 && !x && !y && supports;
                            assert(b.copies == unsigned(full));
                            assert(b.clears == unsigned(!full));
                            if (full)
                                assert(b.pixels == (layout == ScreenLayout::TopOnly ? top : bottom));
                            else
                                for (auto pixel : b.pixels) assert(pixel == 0);
                        }
    for (auto layout : {ScreenLayout::Other, ScreenLayout::Hybrid, ScreenLayout::Large, ScreenLayout::Scaled}) {
        for (bool horizontal : {false,true}) {
            direct = !horizontal;
            const unsigned w = horizontal ? 8 : 4;
            const unsigned h = horizontal ? 3 : 6;
            for (bool swapped : {false,true}) {
                Point first{0,0}, second{horizontal ? 4u : 0u, horizontal ? 0u : 3u};
                Buffer b{w,h,0,0,std::vector<uint32_t>(w*h,0xffffffff)};
                combine(b, Layout{layout,swapped ? second : first,swapped ? first : second},top,bottom);
                if (layout != ScreenLayout::Other) {
                    assert(b.clears == 1 && b.copies == 0);
                    continue;
                }
                assert(b.clears == 0 && b.copies == 2);
                for (unsigned y=0;y<h;++y)
                    for (unsigned x=0;x<w;++x) {
                        const bool firstScreen = horizontal ? x<4 : y<3;
                        assert(b.pixels[y*w+x] == (firstScreen != swapped ? top[0] : bottom[0]));
                    }
            }
        }
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "melonds.cpp"
            binary = path.with_suffix("")
            path.write_text(source)
            subprocess.run([compiler, "-std=c++11", "-O2", "-fsanitize=undefined",
                            str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_noods_direct_save_reader_and_writer(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        # Construct the actual changed class members from the source patch.
        changes = {old: new for path, old, new in PATCHES["noods"] if path == "src/memfile.h"}
        constructor = changes["        MemFile() : stream(), file(nullptr) {}"]
        writer = changes["            stream.write(static_cast<const char*>(buffer), size * count);\n            return size;"]
        fields = changes["    private:\n        std::stringstream stream;\n        FILE* file;"]
        reader = changes["            stream.read(static_cast<char*>(buffer), size * count);"]
        seek = changes["            std::ios_base::seekdir dir;"]
        tell = changes["            return static_cast<long>(stream.tellg());"]
        source = """
#include <sstream>
#include <cstdio>
#include <cstring>
#include <cassert>
#include <limits>
#include <vector>
class MemFile {
public:
""" + constructor + """
size_t write(const void* buffer, size_t size, size_t count) {
""" + writer + """
}
size_t read(void* buffer, size_t size, size_t count) {
""" + reader + """
    return size;
}
int seek(long offset, int origin) {
""" + seek + """
    return -1;
}
long tell() {
""" + tell + """
}
""" + fields + """
};
int main() {
    unsigned char buf[12];
    memset(buf, 0xcc, sizeof(buf));
    const unsigned char a[] = {1,2,3,4};
    const unsigned char b[] = {5,6};
    MemFile good(buf, 10);
    assert(good.write(a, 2, 2) == 2);
    assert(good.write(b, 1, 2) == 1);
    assert(good.finishDirect());
    for (unsigned i=0;i<6;i++) assert(buf[i] == i+1);
    for (unsigned i=6;i<10;i++) assert(buf[i] == 0);
    assert(buf[10] == 0xcc && buf[11] == 0xcc);
    memset(buf, 0xcc, sizeof(buf));
    MemFile shortbuf(buf, 3);
    assert(shortbuf.write(a, 1, 4) == 0);
    assert(!shortbuf.finishDirect());
    assert(shortbuf.write(b, 1, 2) == 0);
    for (auto v: buf) assert(v == 0xcc);
    MemFile overflow(buf, sizeof(buf));
    assert(overflow.write(a, 2, std::numeric_limits<size_t>::max()) == 0);
    assert(!overflow.finishDirect());
    MemFile invalid(static_cast<void*>(nullptr), 12);
    assert(!invalid.finishDirect());
    const unsigned char state[] = {0,1,2,3,4,5,6,7,8,9,10,11};
    unsigned char out[5] = {};
    MemFile input(state, sizeof(state));
    assert(input.seek(8, SEEK_SET) == 0);
    assert(input.tell() == 8);
    assert(input.read(out, 1, 4) == 1);
    assert(out[0] == 8 && out[3] == 11 && out[4] == 0);
    assert(input.seek(-4, SEEK_END) == 0);
    assert(input.seek(-2, SEEK_CUR) == 0);
    assert(input.tell() == 6);
    assert(input.read(out, 2, 2) == 2);
    assert(out[0] == 6 && out[3] == 9);
    assert(input.finishDirectRead());
    assert(!input.finishDirect());
    assert(input.read(out, 1, 3) == 0);
    assert(!input.finishDirectRead());
    MemFile overflowRead(state, sizeof(state));
    assert(overflowRead.read(out, 2, std::numeric_limits<size_t>::max()) == 0);
    assert(!overflowRead.finishDirectRead());
    MemFile negativeSeek(state, sizeof(state));
    assert(negativeSeek.seek(std::numeric_limits<long>::min(), SEEK_CUR) == -1);
    assert(!negativeSeek.finishDirectRead());
    MemFile readonly(state, sizeof(state));
    assert(readonly.write(a, 1, 1) == 0);
    assert(!readonly.finishDirectRead());
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "direct.cpp"
            binary = path.with_suffix("")
            path.write_text(source)
            subprocess.run([compiler, "-std=c++11", "-O2", "-fsanitize=undefined",
                            str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_extended_neon_kernels_compile(self):
        clang = shutil.which("clang")
        self.assertIsNotNone(clang)
        _, pokey = replacement("prosystem", "for(j = 0; j < tia_size; j++)")
        _, prosystem_audio = replacement("prosystem", "*(audio_out_buf++) = sample_16;")
        _, atari_mix = replacement("a5200", "static void blend_frames_mix(void)")
        _, atari_audio = replacement("a5200", "/* Update output buffer */")
        _, saturn = replacement("mednafen_saturn", "const uint64_t mxor = masks[0] ^ masks[1];")
        source = """
#include <stdint.h>
#include <stddef.h>
#include <arm_neon.h>
void pro_pokey(uint8_t* tia_samples_buf, uint8_t* pokey_samples_buf, uint8_t* pokey_mix_buf, size_t tia_size) {
size_t j;
""" + pokey + """
}
void pro_audio(uint8_t* tia_samples_buf, int16_t* audio_out_buf, size_t tia_size) {
size_t i;
""" + prosystem_audio + """
}
#define A5200_VIDEO_WIDTH 17
#define A5200_VIDEO_HEIGHT 3
static uint16_t *video_buffer, *video_buffer_prev;
""" + atari_mix + """
       {
          uint16_t a = *curr, b = *prev;
          *prev++ = a;
          *curr++ = (a+b+((a^b)&0x821))>>1;
       }
   }
}
#define A5200_AUDIO_BUFFER_SIZE 19
void a5200_audio(uint8_t* samples_ptr, int16_t* out_ptr) {
size_t i;
""" + atari_audio + """
         *out_ptr++ = sample_16;
         *out_ptr++ = sample_16;
      }
}
static struct { uint64_t spr[704]; } LB;
static unsigned WinPieces[5];
#define PIX_SWBIT_SHIFT 56
#define MDFN_LIKELY(x) (x)
void saturn_window(uint64_t* buf, unsigned piece, uint64_t mor, const uint64_t* masks) {
unsigned x = 0;
""" + saturn + """
    {
      const int64_t shifted = (int64_t)(LB.spr[x] << (63 - PIX_SWBIT_SHIFT));
      const uint64_t sel = (uint64_t)(shifted >> 63);
      buf[x] &= mor ^ (sel & mxor);
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "extended.cpp"
            output = path.with_suffix(".o")
            path.write_text(source)
            for target, flags in (("aarch64-none-elf", []), ("armv7a-none-eabi", ["-mfpu=neon", "-mfloat-abi=softfp"])):
                subprocess.run([clang, "--target=" + target, *flags, "-ffreestanding", "-std=c++11",
                                "-O3", "-c", str(path), "-o", str(output)], check=True)
                self.assertGreater(output.stat().st_size, 0)

    def test_extended_bitmask_and_audio_equivalence(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        _, cemu = replacement("cemu", "pad.buttons[id] = input_state_cb")
        _, panda = replacement("panda3ds", "static bool getButtonState(uint id)")
        _, ppsspp_decl = replacement("ppsspp", "} output_audio_buffer = {NULL, 0, 0};")
        _, ppsspp_upload = replacement("ppsspp", "audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2)")
        _, ppsspp_init = replacement("ppsspp", "ensure_output_audio_buffer_capacity(capacity);")
        _, ppsspp_free = replacement("ppsspp", "free(output_audio_buffer.data);")
        source = """
#include <cstdint>
#include <cstdlib>
#include <cassert>
#include <cstring>
#include <mutex>
#include <vector>
using uint = unsigned;
#define RETRO_DEVICE_JOYPAD 1
#define RETRO_DEVICE_ID_JOYPAD_MASK 256
static unsigned callbacks;
static uint16_t buttons;
static int16_t input_state_cb(unsigned, unsigned, unsigned, unsigned id) {
    ++callbacks;
    return id == RETRO_DEVICE_ID_JOYPAD_MASK ? static_cast<int16_t>(buttons) : ((buttons >> id) & 1);
}
static auto inputStateCallback = input_state_cb;
static bool ambient_input_bitmasks, ambientInputBitmasks;
static uint16_t ambientJoypadMask;
struct Pad { int16_t buttons[16]; };
void read_cemu(Pad& pad, unsigned port) {
""" + cemu + """
}
""" + panda + """
static struct { int16_t* data; int32_t size; int32_t capacity;
""" + ppsspp_decl + """
static std::mutex output_audio_buffer_mutex;
static bool ensure_output_audio_buffer_capacity(int32_t capacity) {
    output_audio_buffer.data = static_cast<int16_t*>(realloc(output_audio_buffer.data, capacity * sizeof(int16_t)));
    output_audio_buffer.capacity = capacity;
    return output_audio_buffer.data != nullptr;
}
void init_buffer(int32_t capacity) {
    std::lock_guard<std::mutex> lock(output_audio_buffer_mutex);
""" + ppsspp_init + """
}
void free_buffer() {
    std::lock_guard<std::mutex> lock(output_audio_buffer_mutex);
""" + ppsspp_free + """
}
static std::vector<int16_t> submitted;
static unsigned submissions;
void audio_batch_cb(const int16_t* data, size_t frames) {
    // The producer can acquire its lock during a frontend submission. A
    // blocking regression here would deadlock; try_lock fails immediately.
    assert(output_audio_buffer_mutex.try_lock());
    submitted.insert(submitted.end(), data, data + frames * 2);
    if (++submissions == 1) {
        output_audio_buffer.data[0] = 33;
        output_audio_buffer.data[1] = 44;
        output_audio_buffer.size = 2;
    }
    output_audio_buffer_mutex.unlock();
}
void upload_buffer() {
""" + ppsspp_upload + """
}
int main() {
    Pad pad{};
    for (unsigned m=0;m<65536;m++) {
        buttons = static_cast<uint16_t>(m);
        ambient_input_bitmasks = false;
        callbacks = 0;
        read_cemu(pad, 0);
        assert(callbacks == 16);
        for (unsigned i=0;i<16;i++) assert(pad.buttons[i] == ((m>>i)&1));
        ambient_input_bitmasks = true;
        callbacks = 0;
        read_cemu(pad, 0);
        assert(callbacks == 1);
        for (unsigned i=0;i<16;i++) assert(pad.buttons[i] == ((m>>i)&1));
        ambientInputBitmasks = true;
        ambientJoypadMask = buttons;
        callbacks = 0;
        for (unsigned i=0;i<16;i++) assert(getButtonState(i) == bool((m>>i)&1));
        assert(callbacks == 0);
        // 0xffff must not collide with a signed -1 "not sampled" sentinel.
        const int32_t cached = static_cast<uint16_t>(static_cast<int16_t>(buttons));
        assert(cached >= 0);
    }
    for (uint32_t x=0;x<0x1000000;x+=251) {
        for (uint32_t alpha: {0u, 1u, 127u, 255u}) {
            uint32_t pixel = (alpha<<24) | x;
            assert(((0xffffffu-pixel)|0xff000000u) == (~pixel|0xff000000u));
        }
    }
    init_buffer(16);
    output_audio_buffer.data[0] = 11;
    output_audio_buffer.data[1] = 22;
    output_audio_buffer.size = 2;
    upload_buffer();
    upload_buffer();
    upload_buffer();
    assert((submitted == std::vector<int16_t>{11,22,33,44}));
    assert(submissions == 2);
    free_buffer();
    assert(!output_audio_buffer.data && !ambient_upload_buffer.data);
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "extended.cpp"
            binary = path.with_suffix("")
            path.write_text(source)
            subprocess.run([compiler, "-std=c++11", "-O2", "-fsanitize=undefined", "-pthread",
                            str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

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
