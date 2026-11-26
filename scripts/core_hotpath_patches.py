"""Pin-checked, opt-in source-level improvements for individual Libretro cores."""
from pathlib import Path
import re


SUPPORTED = {"bsnes", "mesen", "sameboy", "mednafen_pce_fast", "mednafen_supergrafx", "mednafen_ngp",
             "mednafen_wswan", "mednafen_vb", "mednafen_lynx"}


def replace_once(source, old, new, label):
    if source.count(old) != 1:
        raise ValueError(f"{label}: expected exactly one matching upstream anchor")
    return source.replace(old, new)


BSNES_INPUT = """// Cache a digital pad once per input_poll/retro_run, not once per button.
class AmbientJoypadSnapshot {
    bool supported = false;
    bool active = false;
    unsigned valid = 0;
    uint16_t masks[5] = {};
public:
    void negotiate(retro_environment_t environment) {
        supported = environment && environment(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);
        active = false;
        valid = 0;
    }
    void begin() { valid = 0; active = true; }
    void end() { active = false; valid = 0; }
    int16_t state(retro_input_state_t callback, unsigned port, unsigned device,
                  unsigned index, unsigned id) {
        if (!supported || !active || port >= 5 || device != RETRO_DEVICE_JOYPAD
                || index != 0 || id >= 16)
            return callback(port, device, index, id);
        const unsigned bit = 1u << port;
        if (!(valid & bit)) {
            masks[port] = static_cast<uint16_t>(
                callback(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));
            valid |= bit;
        }
        return (masks[port] >> id) & 1u;
    }
};

class AmbientJoypadFrame {
    AmbientJoypadSnapshot& snapshot;
public:
    explicit AmbientJoypadFrame(AmbientJoypadSnapshot& s) : snapshot(s) { snapshot.begin(); }
    ~AmbientJoypadFrame() { snapshot.end(); }
    AmbientJoypadFrame(const AmbientJoypadFrame&) = delete;
    AmbientJoypadFrame& operator=(const AmbientJoypadFrame&) = delete;
};
"""


def prepare_bsnes(root):
    libretro = root / "bsnes/target-libretro/libretro.cpp"
    program = libretro.with_name("program.cpp")
    source = libretro.read_bytes().decode("utf-8")
    platform = program.read_bytes().decode("utf-8")
    # Keep every other callback, device mapping, run-ahead and emulator path unchanged.
    source = replace_once(source, '#include "program.cpp"',
                          BSNES_INPUT + '\nstatic AmbientJoypadSnapshot ambientJoypad;\n\n#include "program.cpp"',
                          "bsnes snapshot declaration")
    source = replace_once(source, "RETRO_API void retro_init()\n{\n",
                          "RETRO_API void retro_init()\n{\n\tambientJoypad.negotiate(environ_cb);\n",
                          "bsnes capability negotiation")
    source = replace_once(source, "RETRO_API void retro_deinit()\n{\n",
                          "RETRO_API void retro_deinit()\n{\n\tambientJoypad.negotiate(nullptr);\n",
                          "bsnes lifecycle")
    source = replace_once(source, "\tinput_poll();\n",
                          "\tinput_poll();\n\tAmbientJoypadFrame ambientFrame(ambientJoypad);\n",
                          "bsnes input polling boundary")
    platform = replace_once(platform,
                            "return input_state(libretro_port, libretro_device, libretro_index, libretro_id);",
                            "return ambientJoypad.state(input_state, libretro_port, libretro_device, libretro_index, libretro_id);",
                            "bsnes device dispatch")
    libretro.write_bytes(source.encode("utf-8"))
    program.write_bytes(platform.encode("utf-8"))


MESEN_OLD = """\t\tstring saveStateData = ss.str();
\t\tmemset(data, 0, size);
\t\tmemcpy(data, saveStateData.c_str(), std::min(size, saveStateData.size()));"""
MESEN_NEW = """\t\tstring saveStateData = ss.str();
\t\tconst size_t copied = std::min(size, saveStateData.size());
\t\tif (copied)
\t\t\tmemcpy(data, saveStateData.c_str(), copied);
\t\tif (copied < size)
\t\t\tmemset(static_cast<char*>(data) + copied, 0, size - copied);"""

SAMEBOY_OLD = """            unsigned pitch = GB_get_screen_width(&gameboy[0]) * emulated_devices;
            unsigned pixels_per_device = GB_get_screen_width(&gameboy[0]) * GB_get_screen_height(&gameboy[0]);
            for (int y = 0; y < GB_get_screen_height(&gameboy[0]); y++) {
                for (unsigned i = 0; i < emulated_devices; i++) {
                    memcpy(frame_buf_copy + y * pitch + GB_get_screen_width(&gameboy[0]) * i,
                           frame_buf + pixels_per_device * i + y * GB_get_screen_width(&gameboy[0]),
                           GB_get_screen_width(&gameboy[0]) * sizeof(uint32_t));
                }
            }

            video_cb(frame_buf_copy, GB_get_screen_width(&gameboy[0]) * emulated_devices, GB_get_screen_height(&gameboy[0]), GB_get_screen_width(&gameboy[0]) * emulated_devices * sizeof(uint32_t));"""
SAMEBOY_NEW = """            const unsigned width = GB_get_screen_width(&gameboy[0]);
            const unsigned height = GB_get_screen_height(&gameboy[0]);
            const unsigned devices = emulated_devices;
            const size_t row_bytes = (size_t)width * sizeof(uint32_t);
            const size_t pitch = (size_t)width * devices;
            const size_t pixels_per_device = (size_t)width * height;
            for (unsigned y = 0; y < height; y++) {
                uint32_t *dst = frame_buf_copy + y * pitch;
                const uint32_t *src = frame_buf + (size_t)y * width;
                for (unsigned i = 0; i < devices; i++) {
                    memcpy(dst + (size_t)i * width, src + (size_t)i * pixels_per_device, row_bytes);
                }
            }

            video_cb(frame_buf_copy, width * devices, height, pitch * sizeof(uint32_t));"""


PCE_OLD = "if (input_state_cb(j, RETRO_DEVICE_JOYPAD, 0, map[i]))"
PCE_NEW = """if (libretro_supports_bitmasks ?
                   (joy_bits[j] & (1u << map[i])) != 0 :
                   input_state_cb(j, RETRO_DEVICE_JOYPAD, 0, map[i]) != 0)"""

NGP_OLD = """      if (update_video)
      {
         memset(&system_av_info, 0, sizeof(system_av_info));
         environ_cb(RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO, &system_av_info);
      }

      retro_get_system_av_info(&system_av_info);"""
NGP_NEW = """      /* Publish only the fully initialized geometry and timing. */
      retro_get_system_av_info(&system_av_info);"""

SGX_OLD = """         // select
         if (input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT))
            button_state |= BIT(2);

         // start
         if (input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START) ||
             input_state_cb(port, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_MIDDLE))
            button_state |= BIT(3);"""
SGX_NEW = """         // Sample both digital mouse-mapped buttons together if supported.
         const unsigned pad = r_input.use_bitmasks ?
            (uint16_t)input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) : 0;
         if (r_input.use_bitmasks ? (pad & BIT(RETRO_DEVICE_ID_JOYPAD_SELECT)) != 0 :
             input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT) != 0)
            button_state |= BIT(2);

         if ((r_input.use_bitmasks ? (pad & BIT(RETRO_DEVICE_ID_JOYPAD_START)) != 0 :
             input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START) != 0) ||
             input_state_cb(port, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_MIDDLE))
            button_state |= BIT(3);"""


# The ordinary StateMem layout is deliberately unchanged. Existing dynamically
# allocated state writers continue to use their existing ownership contract.
# The fixed-buffer operation marks only its own StateMem on this API thread.
FIXED_STATE_DECL = """static __thread StateMem *ambient_fixed_state;
static __thread bool ambient_fixed_failed;

"""
FIXED_WRITE_OLD = """   if ((len + st->loc) > st->malloced)
   {
"""
FIXED_WRITE_NEW = """   if (st->loc > st->malloced || len > st->malloced - st->loc)
   {
      /* Never realloc or free the caller-owned libretro buffer. The sticky
       * flag is checked even when an upstream state action ignores a short
       * write. Normal successful fixed-buffer writes take the existing path. */
      if (st == ambient_fixed_state)
      {
         ambient_fixed_failed = true;
         return 0;
      }
      if (len > UINT32_MAX - st->loc)
         return 0;
"""
FIXED_GROW_OLD = """      while(newsize < (len + st->loc))
         newsize *= 2;
      st->data = (uint8_t *)realloc(st->data, newsize);
      st->malloced = newsize;"""
FIXED_GROW_NEW = """      while(newsize < (len + st->loc))
      {
         if (newsize > UINT32_MAX / 2)
         {
            newsize = len + st->loc;
            break;
         }
         newsize *= 2;
      }
      {
         uint8_t *replacement = (uint8_t *)realloc(st->data, newsize);
         if (!replacement)
            return 0;
         st->data = replacement;
      }
      st->malloced = newsize;"""

FIXED_SAVE = """
/* Direct serialization into frontend-owned storage. A size change is reported
 * as a failed snapshot, not an allocation, truncated success or out-of-bounds
 * copy. Zero the optional padding to keep rewind comparisons deterministic. */
bool AmbientSaveFixed(void *data, uint32_t capacity)
{
   StateMem st;
   bool result;
   if (!data || capacity < 32 || ambient_fixed_state)
      return false;
   memset(&st, 0, sizeof(st));
   st.data = (uint8_t *)data;
   st.malloced = capacity;
   ambient_fixed_state = &st;
   ambient_fixed_failed = false;
#ifdef __cplusplus
   struct Scope
   {
      ~Scope() { ambient_fixed_state = NULL; ambient_fixed_failed = false; }
   } scope;
#endif
   result = MDFNSS_SaveSM(&st, 0, 0, NULL, NULL, NULL) &&
      !ambient_fixed_failed && st.data == data && st.len >= 32 && st.len <= capacity;
   ambient_fixed_state = NULL;
   ambient_fixed_failed = false;
   if (result && st.len < capacity)
      memset(st.data + st.len, 0, capacity - st.len);
   return result;
}
"""

FIXED_RETRO_OLD = """bool retro_serialize(void *data, size_t size)
{
   StateMem st;
   bool ret          = false;
   uint8_t *_dat     = (uint8_t*)malloc(size);

   if (!_dat)
      return false;

   /* Mednafen can realloc the buffer so we need to ensure this is safe. */
   st.data           = _dat;
   st.loc            = 0;
   st.len            = 0;
   st.malloced       = size;
   st.initial_malloc = 0;

   ret = MDFNSS_SaveSM(&st, 0, 0, NULL, NULL, NULL);

   memcpy(data, st.data, size);
   free(st.data);

   return ret;
}"""
FIXED_RETRO_NEW = """bool retro_serialize(void *data, size_t size)
{
   if ((uint64_t)size > UINT32_MAX)
      return false;
   return AmbientSaveFixed(data, (uint32_t)size);
}"""


def prepare_fixed_states(root, core):
    state = root / ("mednafen/state.cpp" if core == "mednafen_lynx" else "mednafen/state.c")
    header = root / "mednafen/state.h"
    entry = root / ("libretro.cpp" if core in {"mednafen_vb", "mednafen_lynx"} else "libretro.c")
    source = state.read_bytes().decode("utf-8")
    declarations = header.read_bytes().decode("utf-8")
    frontend = entry.read_bytes().decode("utf-8")
    declaration = re.findall(r"^int MDFNSS_SaveSM\([^\n]+\);$", declarations, re.M)
    if len(declaration) != 1:
        raise ValueError(f"{core}: unexpected state API declaration")
    declarations = replace_once(declarations, declaration[0],
                                declaration[0] + "\nbool AmbientSaveFixed(void *data, uint32_t capacity);",
                                core + " fixed state declaration")
    signature = "static int32_t smem_write(StateMem *st, void *buffer, uint32_t len)\n{\n"
    source = replace_once(source, signature, FIXED_STATE_DECL + signature, core + " fixed state writer")
    source = replace_once(source, FIXED_WRITE_OLD, FIXED_WRITE_NEW, core + " bounds guard")
    source = replace_once(source, FIXED_GROW_OLD, FIXED_GROW_NEW, core + " allocation guard")
    old = FIXED_RETRO_OLD
    if core == "mednafen_vb":
        old = old.replace("   st.initial_malloc = 0;\n", "")
    frontend = replace_once(frontend, old, FIXED_RETRO_NEW, core + " zero-copy serialize")
    state.write_bytes((source + FIXED_SAVE).encode("utf-8"))
    header.write_bytes(declarations.encode("utf-8"))
    entry.write_bytes(frontend.encode("utf-8"))


def prepare_hotpaths(root, core):
    if core == "bsnes":
        prepare_bsnes(root)
    elif core == "mesen":
        path = root / "Libretro/libretro.cpp"
        source = path.read_bytes().decode("utf-8")
        path.write_bytes(replace_once(source, MESEN_OLD, MESEN_NEW, "Mesen save-state copy").encode("utf-8"))
    elif core == "sameboy":
        path = root / "libretro/libretro.c"
        source = path.read_bytes().decode("utf-8")
        path.write_bytes(replace_once(source, SAMEBOY_OLD, SAMEBOY_NEW, "SameBoy dual-screen blit").encode("utf-8"))
    elif core in {"mednafen_pce_fast", "mednafen_supergrafx", "mednafen_ngp"}:
        relative, old, new = {
            "mednafen_pce_fast": ("libretro.c", PCE_OLD, PCE_NEW),
            "mednafen_supergrafx": ("libretro.cpp", SGX_OLD, SGX_NEW),
            "mednafen_ngp": ("libretro.c", NGP_OLD, NGP_NEW),
        }[core]
        path = root / relative
        source = path.read_bytes().decode("utf-8")
        path.write_bytes(replace_once(source, old, new, core).encode("utf-8"))
    elif core not in {"mednafen_wswan", "mednafen_vb", "mednafen_lynx"}:
        raise ValueError(f"No reviewed source-level hotpath patch for {core}")
    if core in {"mednafen_ngp", "mednafen_wswan", "mednafen_vb", "mednafen_lynx"}:
        prepare_fixed_states(root, core)

