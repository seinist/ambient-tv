"""Pin-checked additional core hotpaths; no changes to emulated clocks or default core options."""

PATCHES = {
  "nestopia": [
    [
      "libretro/libretro.cpp",
      "   std::string state = ss.str();\n   if (state.size() + tracked_input_state_size_bytes > size)\n      return false;\n\n   std::copy(state.begin(), state.end(), reinterpret_cast<char*>(data));",
      "   // Read the existing stream buffer directly into frontend storage.\n   ss.seekg(0, std::ios::end);\n   const std::streamoff length = ss.tellg();\n   if (!data || length < 0 || size < tracked_input_state_size_bytes ||\n       static_cast<uint64_t>(length) > size - tracked_input_state_size_bytes)\n      return false;\n   const size_t state_length = static_cast<size_t>(length);\n   ss.seekg(0, std::ios::beg);\n   if (!ss.read(reinterpret_cast<char*>(data), length))\n      return false;"
    ],
    [
      "libretro/libretro.cpp",
      "reinterpret_cast<unsigned char*>(data) + state.size();",
      "reinterpret_cast<unsigned char*>(data) + state_length;"
    ],
    [
      "libretro/libretro.cpp",
      "size - state.size() - 8",
      "size - state_length - 8"
    ]
  ],
  "snes9x": [
    [
      "libretro/libretro.cpp",
      "                uint16 l, r;\n\n                for (int x = 0; x < (width >> 1); x++)\n                {\n                    l = *input++;\n                    r = *input++;\n                    *output++ = AVERAGE_565 (l, r);\n                }",
      "                int x = 0;\n#if defined(__aarch64__)\n                const uint16x8_t mask = vdupq_n_u16(0xF7DE);\n                for (; x + 8 <= (width >> 1); x += 8)\n                {\n                    const uint16x8x2_t pairs = vld2q_u16(input);\n                    const uint16x8_t common = vandq_u16(pairs.val[0], pairs.val[1]);\n                    const uint16x8_t diff = vandq_u16(veorq_u16(pairs.val[0], pairs.val[1]), mask);\n                    vst1q_u16(output, vaddq_u16(common, vshrq_n_u16(diff, 1)));\n                    input += 16;\n                    output += 8;\n                }\n#endif\n                for (; x < (width >> 1); x++)\n                {\n                    uint16 l = *input++, r = *input++;\n                    *output++ = AVERAGE_565 (l, r);\n                }"
    ],
    [
      "libretro/libretro.cpp",
      "#include \"libretro.h\"",
      "#include \"libretro.h\"\n#if defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ]
  ],
  "mgba": [
    [
      "src/platform/libretro/libretro.c",
      "\tmColor *srcCurr = outputBuffer;\n\tmColor *srcPrev = outputBufferPrev1;\n\tmColor *dst     = ppOutputBuffer;\n\tsize_t x, y;\n\n\tfor (y = 0; y < height; y++) {",
      "\tmColor *srcCurr = outputBuffer;\n\tmColor *srcPrev = outputBufferPrev1;\n\tmColor *dst     = ppOutputBuffer;\n\tsize_t x, y;\n\n\t// Separate the invariant no-colour-correction case from the LUT loop.\n\tif (!colorCorrectionEnabled) {\n\t\tfor (y = 0; y < height; y++) {\n\t\t\tfor (x = 0; x < width; x++) {\n\t\t\t\tconst mColor a = srcCurr[x], b = srcPrev[x];\n\t\t\t\tsrcPrev[x] = a;\n\t\t\t\tdst[x] = (a + b + ((a ^ b) & 0x821)) >> 1;\n\t\t\t}\n\t\t\tsrcCurr += VIDEO_WIDTH_MAX;\n\t\t\tsrcPrev += VIDEO_WIDTH_MAX;\n\t\t\tdst += VIDEO_WIDTH_MAX;\n\t\t}\n\t\treturn;\n\t}\n\n\tfor (y = 0; y < height; y++) {"
    ],
    [
      "src/platform/libretro/libretro.c",
      "*(dst + x)      = colorCorrectionEnabled ?\n\t\t\t\t\t*(ccLUT + rgbMix) : rgbMix;",
      "*(dst + x)      = *(ccLUT + rgbMix);"
    ]
  ],
  "handy": [
    [
      "libretro/libretro.cpp",
      "static void lcd_ghosting_apply_2frames_555(void)\n{\n   LCD_GHOSTING_APPLY_2FRAMES(uint16_t, COLOR_MIX_LSB_555);\n}",
      "static void lcd_ghosting_apply_2frames_555(void)\n{\n#if defined(__aarch64__)\n   uint16_t *a = (uint16_t*)framebuffer;\n   uint16_t *b = (uint16_t*)framebuffer_history_1;\n   const size_t count = HANDY_SCREEN_WIDTH * HANDY_SCREEN_WIDTH;\n   const uint16x8_t mask = vdupq_n_u16((uint16_t)~COLOR_MIX_LSB_555);\n   size_t i = 0;\n   for (; i + 8 <= count; i += 8)\n   {\n      const uint16x8_t curr = vld1q_u16(a + i), prev = vld1q_u16(b + i);\n      const uint16x8_t mix = vaddq_u16(vandq_u16(curr, prev),\n          vshrq_n_u16(vandq_u16(veorq_u16(curr, prev), mask), 1));\n      vst1q_u16(a + i, mix);\n      vst1q_u16(b + i, curr);\n   }\n   for (; i < count; ++i)\n   {\n      const uint16_t curr = a[i], prev = b[i];\n      a[i] = (curr + prev - ((curr ^ prev) & COLOR_MIX_LSB_555)) >> 1;\n      b[i] = curr;\n   }\n#else\n   LCD_GHOSTING_APPLY_2FRAMES(uint16_t, COLOR_MIX_LSB_555);\n#endif\n}"
    ],
    [
      "libretro/libretro.cpp",
      "static void lcd_ghosting_apply_2frames_565(void)\n{\n   LCD_GHOSTING_APPLY_2FRAMES(uint16_t, COLOR_MIX_LSB_565);\n}",
      "static void lcd_ghosting_apply_2frames_565(void)\n{\n#if defined(__aarch64__)\n   uint16_t *a = (uint16_t*)framebuffer;\n   uint16_t *b = (uint16_t*)framebuffer_history_1;\n   const size_t count = HANDY_SCREEN_WIDTH * HANDY_SCREEN_WIDTH;\n   const uint16x8_t mask = vdupq_n_u16((uint16_t)~COLOR_MIX_LSB_565);\n   size_t i = 0;\n   for (; i + 8 <= count; i += 8)\n   {\n      const uint16x8_t curr = vld1q_u16(a + i), prev = vld1q_u16(b + i);\n      const uint16x8_t mix = vaddq_u16(vandq_u16(curr, prev),\n          vshrq_n_u16(vandq_u16(veorq_u16(curr, prev), mask), 1));\n      vst1q_u16(a + i, mix);\n      vst1q_u16(b + i, curr);\n   }\n   for (; i < count; ++i)\n   {\n      const uint16_t curr = a[i], prev = b[i];\n      a[i] = (curr + prev - ((curr ^ prev) & COLOR_MIX_LSB_565)) >> 1;\n      b[i] = curr;\n   }\n#else\n   LCD_GHOSTING_APPLY_2FRAMES(uint16_t, COLOR_MIX_LSB_565);\n#endif\n}"
    ],
    [
      "libretro/libretro.cpp",
      "#include \"libretro.h\"",
      "#include \"libretro.h\"\n#if defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ]
  ],
  "pcsx_rearmed": [
    [
      "frontend/libretro.c",
      "      if (type == PSE_PAD_TYPE_NONE)\n         continue;\n\n      if (libretro_supports_bitmasks)",
      "      if (type == PSE_PAD_TYPE_NONE)\n         continue;\n\n      // These peripheral handlers query their own device, not the RetroPad.\n      if (type == PSE_PAD_TYPE_GUNCON || type == PSE_PAD_TYPE_GUN || type == PSE_PAD_TYPE_MOUSE)\n      {\n         if (type == PSE_PAD_TYPE_GUNCON)\n            update_input_guncon(i, 0);\n         else if (type == PSE_PAD_TYPE_GUN)\n            update_input_justifier(i, 0);\n         else\n            update_input_mouse(i, 0);\n         continue;\n      }\n\n      if (libretro_supports_bitmasks)"
    ]
  ],
  "race": [
    [
      "libretro/libretro.c",
      "      for (i = 0; i < samplesPerFrame; i++)\n      {\n         p[0] = sampleBuffer[i];\n         p[1] = sampleBuffer[i];\n         p += 2;\n      }",
      "      i = 0;\n#if defined(__aarch64__)\n      for (; i + 8 <= samplesPerFrame; i += 8, p += 16)\n      {\n         const int16x8_t samples = vld1q_s16(sampleBuffer + i);\n         const int16x8x2_t stereo = {{samples, samples}};\n         vst2q_s16(p, stereo);\n      }\n#endif\n      for (; i < samplesPerFrame; i++)\n      {\n         p[0] = sampleBuffer[i];\n         p[1] = sampleBuffer[i];\n         p += 2;\n      }"
    ],
    [
      "libretro/libretro.c",
      "#include \"libretro.h\"",
      "#include \"libretro.h\"\n#if defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ]
  ],
  "swanstation": [
    [
      "src/libretro/libretro_host_interface.cpp",
      "  const std::string path = no_disc ? std::string() : info.image_paths[info.image_index];",
      "  static const std::string empty_path;\n  const std::string& path = no_disc ? empty_path : info.image_paths[info.image_index];"
    ]
  ],
  "mednafen_psx_hw": [
    [
      "libretro.c",
      "      for (bi = 0; bi < sizeof(vcd_ids) / sizeof(vcd_ids[0]); bi++)\n         if (input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, vcd_ids[bi]))\n            vcd_pad |= (uint16_t)(1u << bi);",
      "      if (libretro_supports_bitmasks)\n      {\n         const uint16_t mask = (uint16_t)input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK);\n         for (bi = 0; bi < sizeof(vcd_ids) / sizeof(vcd_ids[0]); bi++)\n            if (mask & (1u << vcd_ids[bi]))\n               vcd_pad |= (uint16_t)(1u << bi);\n      }\n      else\n      {\n         for (bi = 0; bi < sizeof(vcd_ids) / sizeof(vcd_ids[0]); bi++)\n            if (input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, vcd_ids[bi]))\n               vcd_pad |= (uint16_t)(1u << bi);\n      }"
    ]
  ],
  "gambatte": [
    [
      "libgambatte/libretro/libretro.cpp",
      "   gambatte::video_pixel_t *curr = video_buf;\n   gambatte::video_pixel_t *prev = video_buf_prev_1;\n   size_t x, y;\n\n   for (y = 0; y < VIDEO_HEIGHT; y++)\n   {\n      for (x = 0; x < VIDEO_WIDTH; x++)",
      "   gambatte::video_pixel_t *curr = video_buf;\n   gambatte::video_pixel_t *prev = video_buf_prev_1;\n   size_t x, y;\n\n   for (y = 0; y < VIDEO_HEIGHT; y++)\n   {\n      x = 0;\n#if defined(__aarch64__) && defined(VIDEO_RGB565)\n      const uint16x8_t lsb = vdupq_n_u16(0x0821);\n      const uint16x8_t other = vdupq_n_u16(0xF7DE);\n      for (; x + 8 <= VIDEO_WIDTH; x += 8)\n      {\n         const uint16x8_t a = vld1q_u16(curr + x), b = vld1q_u16(prev + x);\n         const uint16x8_t diff = veorq_u16(a, b);\n         const uint16x8_t result = vaddq_u16(vaddq_u16(vandq_u16(a, b),\n             vshrq_n_u16(vandq_u16(diff, other), 1)), vandq_u16(diff, lsb));\n         vst1q_u16(prev + x, a);\n         vst1q_u16(curr + x, result);\n      }\n#endif\n      for (; x < VIDEO_WIDTH; x++)"
    ],
    [
      "libgambatte/libretro/libretro.cpp",
      "#include <stdlib.h>",
      "#include <stdlib.h>\n#if defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ]
  ],
  "genesis_plus_gx": [
    [
      "core/sound/sound.c",
      "    if (!audio_hard_disable)\r\n    {\r\n      /* flush FM samples */\r\n      if (config.hq_fm)\r\n      {\r\n        /* high-quality Band-Limited synthesis */\r\n        do\r\n        {\r\n          /* left & right channels */\r\n          l = ((*ptr++ * preamp) / 100);\r\n          r = ((*ptr++ * preamp) / 100);\r\n          blip_add_delta(snd.blips[0], time, l - prev_l, r - prev_r);\r\n          prev_l = l;\r\n          prev_r = r;\r\n\r\n          /* increment time counter */\r\n          time += fm_cycles_ratio;\r\n        } while (time < cycles);\r\n      }\r\n      else\r\n      {\r\n        /* faster Linear Interpolation */\r\n        do\r\n        {\r\n          /* left & right channels */\r\n          l = ((*ptr++ * preamp) / 100);\r\n          r = ((*ptr++ * preamp) / 100);\r\n          blip_add_delta_fast(snd.blips[0], time, l - prev_l, r - prev_r);\r\n          prev_l = l;\r\n          prev_r = r;\r\n\r\n          /* increment time counter */\r\n          time += fm_cycles_ratio;\r\n        } while (time < cycles);\r\n      }\r\n    }",
      "    if (!audio_hard_disable)\r\n    {\r\n      /* At unity gain the FM samples need no multiply/divide. */\r\n      if (preamp == 100)\r\n      {\r\n        /* flush FM samples */\r\n        if (config.hq_fm)\r\n        {\r\n          /* high-quality Band-Limited synthesis */\r\n          do\r\n          {\r\n            /* left & right channels */\r\n            l = *ptr++;\r\n            r = *ptr++;\r\n            blip_add_delta(snd.blips[0], time, l - prev_l, r - prev_r);\r\n            prev_l = l;\r\n            prev_r = r;\r\n  \r\n            /* increment time counter */\r\n            time += fm_cycles_ratio;\r\n          } while (time < cycles);\r\n        }\r\n        else\r\n        {\r\n          /* faster Linear Interpolation */\r\n          do\r\n          {\r\n            /* left & right channels */\r\n            l = *ptr++;\r\n            r = *ptr++;\r\n            blip_add_delta_fast(snd.blips[0], time, l - prev_l, r - prev_r);\r\n            prev_l = l;\r\n            prev_r = r;\r\n  \r\n            /* increment time counter */\r\n            time += fm_cycles_ratio;\r\n          } while (time < cycles);\r\n        }\r\n      }\r\n      else\r\n      {\r\n        /* flush FM samples */\r\n        if (config.hq_fm)\r\n        {\r\n          /* high-quality Band-Limited synthesis */\r\n          do\r\n          {\r\n            /* left & right channels */\r\n            l = ((*ptr++ * preamp) / 100);\r\n            r = ((*ptr++ * preamp) / 100);\r\n            blip_add_delta(snd.blips[0], time, l - prev_l, r - prev_r);\r\n            prev_l = l;\r\n            prev_r = r;\r\n  \r\n            /* increment time counter */\r\n            time += fm_cycles_ratio;\r\n          } while (time < cycles);\r\n        }\r\n        else\r\n        {\r\n          /* faster Linear Interpolation */\r\n          do\r\n          {\r\n            /* left & right channels */\r\n            l = ((*ptr++ * preamp) / 100);\r\n            r = ((*ptr++ * preamp) / 100);\r\n            blip_add_delta_fast(snd.blips[0], time, l - prev_l, r - prev_r);\r\n            prev_l = l;\r\n            prev_r = r;\r\n  \r\n            /* increment time counter */\r\n            time += fm_cycles_ratio;\r\n          } while (time < cycles);\r\n        }\r\n      }\r\n    }"
    ]
  ],
  "stella2014": [
    [
      "libretro.cxx",
      "    std::string s = state.get();\n    if(size < s.size() + AF_TRAILER_SIZE)\n        return false;\n    memcpy(data, s.data(), s.size());\n\n    uint8_t *trailer = (uint8_t*)data + s.size();",
      "    const uint32_t state_size = state.size();\n    if (!data || !state_size || size < AF_TRAILER_SIZE ||\n        state_size > size - AF_TRAILER_SIZE)\n        return false;\n    try\n    {\n        state.setPosition(0);\n        state.getByteArray((uint8_t*)data, state_size);\n    }\n    catch (...)\n    {\n        return false;\n    }\n\n    uint8_t *trailer = (uint8_t*)data + state_size;"
    ]
  ],
  "mupen64plus_next_gles3": [
    [
      "custom/mupen64plus-core/plugin/emulate_game_controller_via_libretro.c",
      "   // Convert cartesian coordinate analog stick to polar coordinates\n   radius = sqrt(analogX * analogX + analogY * analogY);\n   angle = atan2(analogY, analogX);\n\n   if (radius > astick_deadzone)\n   {",
      "   // An idle stick needs neither sqrt nor atan2. Widen before squaring\n   // so the two -32768 inputs cannot overflow a signed 32-bit sum.\n   const double radius_squared = (double)analogX * analogX + (double)analogY * analogY;\n   if (astick_deadzone < 0 || radius_squared > (double)astick_deadzone * astick_deadzone)\n   {\n      radius = sqrt(radius_squared);\n      angle = atan2(analogY, analogX);"
    ]
  ],
  "fceumm": [
    [
      "src/drivers/libretro/libretro.c",
      "               if (input_cb(player, RETRO_DEVICE_JOYPAD, 0, turbomap[i].retro) || input_cb(player, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R3))",
      "               if (libretro_supports_bitmasks ?\n                     (ret & ((1u << turbomap[i].retro) | (1u << RETRO_DEVICE_ID_JOYPAD_R3))) != 0 :\n                     (input_cb(player, RETRO_DEVICE_JOYPAD, 0, turbomap[i].retro) ||\n                      input_cb(player, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R3)))"
    ]
  ],
  "gpsp": [
    [
      "libretro/libretro.c",
      "   for (i = 0; i < npx; i++)\n   {\n      uint16_t rgb_curr = src_curr[i];\n      uint16_t rgb_prev = src_prev[i];\n\n      /* Mix colours:",
      "   i = 0;\n#if defined(__aarch64__)\n   {\n      const uint16x8_t lsb = vdupq_n_u16(0x0821);\n      const uint16x8_t other = vdupq_n_u16(0xF7DE);\n      for (; i + 8 <= npx; i += 8)\n      {\n         const uint16x8_t a = vld1q_u16(src_curr + i), b = vld1q_u16(src_prev + i);\n         const uint16x8_t diff = veorq_u16(a, b);\n         const uint16x8_t mix = vaddq_u16(vaddq_u16(vandq_u16(a, b),\n             vshrq_n_u16(vandq_u16(diff, other), 1)), vandq_u16(diff, lsb));\n         vst1q_u16(dst + i, mix);\n      }\n   }\n#endif\n   for (; i < npx; i++)\n   {\n      uint16_t rgb_curr = src_curr[i];\n      uint16_t rgb_prev = src_prev[i];\n\n      /* Mix colours:"
    ],
    [
      "libretro/libretro.c",
      "#include <stdio.h>",
      "#include <stdio.h>\n#if defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ]
  ],
  "parallel_n64": [
    [
      "mupen64plus-core/src/plugin/emulate_game_controller_via_libretro.c",
      "   // Integer stick radius (replaces the polar sqrt/atan2 round-trip)\n   radius = analog_isqrt64( (uint64_t)((int64_t)x * x)\n                          + (uint64_t)((int64_t)y * y) );",
      "   // floor(sqrt(r2)) <= deadzone iff r2 < (deadzone+1)^2.\n   const uint64_t r2 = (uint64_t)((int64_t)x * x) + (uint64_t)((int64_t)y * y);\n   if (astick_deadzone >= 0)\n   {\n      const uint64_t limit = (uint64_t)astick_deadzone + 1;\n      if (r2 < limit * limit)\n      {\n         *outX = 0;\n         *outY = 0;\n         return;\n      }\n   }\n   radius = analog_isqrt64(r2);"
    ]
  ]
}


def prepare_remaining(root, core, replace_once):
    pending = {}
    for relative, old, new in PATCHES[core]:
        path = root / relative
        source = pending[path] if path in pending else path.read_bytes().decode("utf-8")
        pending[path] = replace_once(source, old, new, f"{core}: {relative}")
    for path, source in pending.items():
        path.write_bytes(source.encode("utf-8"))
