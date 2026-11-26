"""Pin-checked, opt-in source-level improvements for individual Libretro cores."""
from pathlib import Path


SUPPORTED = {"bsnes"}


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


def prepare_hotpaths(root, core):
    if core == "bsnes":
        prepare_bsnes(root)
    else:
        raise ValueError(f"No reviewed source-level hotpath patch for {core}")
