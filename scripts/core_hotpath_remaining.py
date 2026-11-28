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
  ],
  "armsx2": [
    [
      "pcsx2-libretro/Main.cpp",
      "static retro_input_state_t input_state_cb;",
      "static retro_input_state_t input_state_cb;\nstatic bool ambient_input_bitmasks;"
    ],
    [
      "pcsx2-libretro/Main.cpp",
      "RETRO_API void retro_init(void)\n{",
      "RETRO_API void retro_init(void)\n{\n\tambient_input_bitmasks = environ_cb && environ_cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);"
    ],
    [
      "pcsx2-libretro/Main.cpp",
      "\t\t\tfor (const auto& m : bmap)\n\t\t\t\tpad->Set(m.ds2, input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, m.retro) ? 1.0f : 0.0f);",
      "\t\t\tconst u16 mask = ambient_input_bitmasks ? static_cast<u16>(input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK)) : 0;\n\t\t\tfor (const auto& m : bmap)\n\t\t\t\tpad->Set(m.ds2, (ambient_input_bitmasks ? ((mask >> m.retro) & 1u) :\n\t\t\t\t\t(input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, m.retro) != 0)) ? 1.0f : 0.0f);"
    ]
  ],
  "cemu": [
    [
      "src/libretro/CemuLibretro.cpp",
      "static retro_input_state_t input_state_cb = nullptr;",
      "static retro_input_state_t input_state_cb = nullptr;\nstatic bool ambient_input_bitmasks = false;"
    ],
    [
      "src/libretro/CemuLibretro.cpp",
      "RETRO_API void retro_init()\n{\n\tif (s_initialized)",
      "RETRO_API void retro_init()\n{\n\tambient_input_bitmasks = environ_cb && environ_cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);\n\tif (s_initialized)"
    ],
    [
      "src/libretro/CemuLibretro.cpp",
      "\t\tfor (uint32_t id = 0; id < 16; ++id)\n\t\t\tpad.buttons[id] = input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, id);",
      "\t\tif (ambient_input_bitmasks)\n\t\t{\n\t\t\tconst uint16_t mask = static_cast<uint16_t>(input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));\n\t\t\tfor (uint32_t id = 0; id < 16; ++id)\n\t\t\t\tpad.buttons[id] = (mask >> id) & 1u;\n\t\t}\n\t\telse\n\t\t\tfor (uint32_t id = 0; id < 16; ++id)\n\t\t\t\tpad.buttons[id] = input_state_cb(port, RETRO_DEVICE_JOYPAD, 0, id);"
    ]
  ],
  "noods": [
    [
      "src/libretro/libretro.cpp",
      "static retro_input_state_t inputStateCallback;",
      "static retro_input_state_t inputStateCallback;\nstatic bool ambientInputBitmasks;\nstatic uint16_t ambientJoypadMask;"
    ],
    [
      "src/libretro/libretro.cpp",
      "  return inputStateCallback(0, RETRO_DEVICE_JOYPAD, 0, id);",
      "  return ambientInputBitmasks ? ((ambientJoypadMask >> id) & 1u) != 0 :\n      inputStateCallback(0, RETRO_DEVICE_JOYPAD, 0, id) != 0;"
    ],
    [
      "src/libretro/libretro.cpp",
      "void retro_init(void)\n{",
      "void retro_init(void)\n{\n  ambientInputBitmasks = envCallback(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);"
    ],
    [
      "src/libretro/libretro.cpp",
      "  inputPollCallback();",
      "  inputPollCallback();\n  if (ambientInputBitmasks)\n    ambientJoypadMask = static_cast<uint16_t>(inputStateCallback(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));"
    ],
    [
      "src/libretro/libretro.cpp",
      "      for (int x = 0; x < sw; ++x)\n      {\n        for (int cx = 0; cx < scaleX; ++cx)\n          *newRow++ = srcRow[x];\n      }",
      "      if (scaleX == 1)\n        memcpy(dstRow, srcRow, sw * sizeof(uint32_t));\n      else if (scaleX == 2)\n        for (int x = 0; x < sw; ++x) {\n          const uint32_t pixel = srcRow[x];\n          *newRow++ = pixel;\n          *newRow++ = pixel;\n        }\n      else\n        for (int x = 0; x < sw; ++x) {\n          const uint32_t pixel = srcRow[x];\n          for (int cx = 0; cx < scaleX; ++cx)\n            *newRow++ = pixel;\n        }"
    ],
    [
      "src/memfile.h",
      "#include <vector>",
      "#include <vector>\n#include <cstring>\n#include <cstddef>"
    ],
    [
      "src/memfile.h",
      "        MemFile() : stream(), file(nullptr) {}",
      "        MemFile() : stream(), file(nullptr) {}\n        // Libretro's saveState methods write sequentially. Bypass the growing\n        // stringstream and write directly into the caller's bounded buffer.\n        MemFile(void* destination, size_t capacity) : stream(), file(nullptr),\n            directMode(true), directData(static_cast<unsigned char*>(destination)),\n            directCapacity(capacity), directFailed(!destination) {}\n        // Loading uses a read-only view over the existing frontend buffer.\n        MemFile(const void* source, size_t length) : stream(), file(nullptr),\n            directMode(true), directReadData(static_cast<const unsigned char*>(source)),\n            directCapacity(length), directFailed(!source) {}"
    ],
    [
      "src/memfile.h",
      "            stream.write(static_cast<const char*>(buffer), size * count);\n            return size;",
      "            if (directMode)\n            {\n                if (directFailed || directReadData) { directFailed = true; return 0; }\n                if (size != 0 && count > (directCapacity - directPos) / size)\n                {\n                    directFailed = true;\n                    return 0;\n                }\n                const size_t bytes = size * count;\n                if (bytes != 0)\n                {\n                    if (!buffer) { directFailed = true; return 0; }\n                    std::memcpy(directData + directPos, buffer, bytes);\n                    directPos += bytes;\n                }\n                return size;\n            }\n            stream.write(static_cast<const char*>(buffer), size * count);\n            return size;"
    ],
    [
      "src/memfile.h",
      "            stream.read(static_cast<char*>(buffer), size * count);",
      "            if (directMode)\n            {\n                if (directFailed || !directReadData ||\n                    (size != 0 && count > (directCapacity - directPos) / size))\n                {\n                    directFailed = true;\n                    return 0;\n                }\n                const size_t bytes = size * count;\n                if (bytes != 0)\n                {\n                    if (!buffer) { directFailed = true; return 0; }\n                    std::memcpy(buffer, directReadData + directPos, bytes);\n                    directPos += bytes;\n                }\n                return size;\n            }\n            stream.read(static_cast<char*>(buffer), size * count);"
    ],
    [
      "src/memfile.h",
      "            std::ios_base::seekdir dir;",
      "            if (directMode)\n            {\n                if (directFailed || !directReadData) { directFailed = true; return -1; }\n                size_t base;\n                switch (origin)\n                {\n                    case SEEK_SET: base = 0; break;\n                    case SEEK_CUR: base = directPos; break;\n                    case SEEK_END: base = directCapacity; break;\n                    default: directFailed = true; return -1;\n                }\n                if (offset < 0)\n                {\n                    const size_t distance = static_cast<size_t>(-(offset + 1)) + 1;\n                    if (distance > base) { directFailed = true; return -1; }\n                    directPos = base - distance;\n                }\n                else\n                {\n                    const size_t distance = static_cast<size_t>(offset);\n                    if (distance > directCapacity - base) { directFailed = true; return -1; }\n                    directPos = base + distance;\n                }\n                return 0;\n            }\n            std::ios_base::seekdir dir;"
    ],
    [
      "src/memfile.h",
      "            return static_cast<long>(stream.tellg());",
      "            if (directMode) return static_cast<long>(directPos);\n            return static_cast<long>(stream.tellg());"
    ],
    [
      "src/memfile.h",
      "    private:\n        std::stringstream stream;\n        FILE* file;",
      "        bool finishDirect()\n        {\n            if (!directMode || !directData || directFailed) return false;\n            // Preserve the serialized prefix and make unused fixed-size bytes deterministic.\n            if (directPos < directCapacity)\n                std::memset(directData + directPos, 0, directCapacity - directPos);\n            return true;\n        }\n\n        bool finishDirectRead() const\n        {\n            return directMode && directReadData && !directFailed;\n        }\n\n    private:\n        std::stringstream stream;\n        FILE* file;\n        bool directMode = false;\n        unsigned char* directData = nullptr;\n        const unsigned char* directReadData = nullptr;\n        size_t directCapacity = 0;\n        size_t directPos = 0;\n        bool directFailed = false;"
    ],
    [
      "src/libretro/savestate.cpp",
      "  if (size == 0) return false;",
      "  if (!data || size < 8) return false;"
    ],
    [
      "src/libretro/savestate.cpp",
      "bool SaveState::save(void* data, size_t& size)\n{\n  // Open the state file and write the header\n  MemFile file;",
      "bool SaveState::save(void* data, size_t& size)\n{\n  if (!data || size < 8) return false;\n  // Serialize the same version-3 byte stream directly into frontend memory.\n  MemFile file(data, size);"
    ],
    [
      "src/libretro/savestate.cpp",
      "  // Write save data to buffer\n  file.read(data, size, 1);\n\n  return true;",
      "  return file.finishDirect();"
    ],
    [
      "src/libretro/libretro.cpp",
      "  // HACK: Usually around 6MB but can vary frame to frame!\n  return 1024 * 1024 * 8;",
      "  // DS keeps its existing 8 MiB state size. DSi has 16 MiB of main RAM\n  // alone, so the former 8 MiB bound silently truncated every DSi state.\n  return 1024 * 1024 * (core && core->dsiMode ? 32 : 8);"
    ],
    [
      "src/libretro/savestate.cpp",
      "  // Open the state file and read past the header\n  MemFile file;\n\n  file.write(data, size, 1);\n  file.seek(8, SEEK_SET);",
      "  if (!data || size < 8) return false;\n  // Read directly from the frontend buffer instead of copying the entire state.\n  MemFile file(data, size);\n  if (file.seek(8, SEEK_SET) != 0) return false;"
    ],
    [
      "src/libretro/savestate.cpp",
      "  core->loadState(file);\n\n  return true;",
      "  core->loadState(file);\n\n  return file.finishDirectRead();"
    ],
    [
      "src/spu.h",
      "    uint32_t *getSamples(int count);",
      "    uint32_t *getSamples(int count);\n    void getSamples(int count, uint32_t *out);"
    ],
    [
      "src/spu.cpp",
      "uint32_t *Spu::getSamples(int count) {\n    // Initialize the buffers",
      "uint32_t *Spu::getSamples(int count) {\n    uint32_t *out = new uint32_t[count];\n    getSamples(count, out);\n    return out;\n}\n\nvoid Spu::getSamples(int count, uint32_t *out) {\n    // Initialize the buffers"
    ],
    [
      "src/spu.cpp",
      "    uint32_t *out = new uint32_t[count];\n\n    if (wait) {",
      "    if (wait) {"
    ],
    [
      "src/spu.cpp",
      "    return out;\n}\n\nvoid Spu::runGbaSample()",
      "}\n\nvoid Spu::runGbaSample()"
    ],
    [
      "src/libretro/libretro.cpp",
      "  uint32_t *original = core->spu.getSamples(547);",
      "  // Reuse caller-owned storage; do not allocate/free an audio block per frame.\n  static uint32_t original[547];\n  core->spu.getSamples(547, original);"
    ],
    [
      "src/libretro/libretro.cpp",
      "  delete[] original;\n\n  uint32_t size",
      "  uint32_t size"
    ]
  ],
  "panda3ds": [
    [
      "src/libretro_core.cpp",
      "static retro_input_state_t inputStateCallback;",
      "static retro_input_state_t inputStateCallback;\nstatic bool ambientInputBitmasks;\nstatic uint16_t ambientJoypadMask;"
    ],
    [
      "src/libretro_core.cpp",
      "static bool getButtonState(uint id) { return inputStateCallback(0, RETRO_DEVICE_JOYPAD, 0, id); }",
      "static bool getButtonState(uint id) {\n\treturn ambientInputBitmasks ? ((ambientJoypadMask >> id) & 1u) != 0 :\n\t\tinputStateCallback(0, RETRO_DEVICE_JOYPAD, 0, id) != 0;\n}"
    ],
    [
      "src/libretro_core.cpp",
      "void retro_init() {",
      "void retro_init() {\n\tambientInputBitmasks = envCallback(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);"
    ],
    [
      "src/libretro_core.cpp",
      "\tinputPollCallback();",
      "\tinputPollCallback();\n\tif (ambientInputBitmasks)\n\t\tambientJoypadMask = static_cast<uint16_t>(inputStateCallback(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK));"
    ]
  ],
  "vice_x128": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_x64": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_x64sc": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_xcbm2": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_xcbm5x0": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_xpet": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_xplus4": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_xscpu64": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "vice_xvic": [
    [
      "libretro/libretro-mapper.c",
      "      /* Key down */\n      if (input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && !kbt[i] && mapper_keys[mk])",
      "      /* Sample each mapped hotkey once; unmapped keys need no callback. */\n      const int key_down = mapper_keys[mk] && input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]);\n      /* Key down */\n      if (key_down && !kbt[i])"
    ],
    [
      "libretro/libretro-mapper.c",
      "      else if (!input_state_cb(0, RETRO_DEVICE_KEYBOARD, 0, mapper_keys[mk]) && kbt[i] && mapper_keys[mk])",
      "      else if (!key_down && kbt[i] && mapper_keys[mk])"
    ]
  ],
  "yabasanshiro": [
    [
      "yabause/src/libretro/libretro.c",
      "static int16_t libretro_input_bitmask[12] = {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};",
      "static int32_t libretro_input_bitmask[12] = {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};"
    ],
    [
      "yabause/src/libretro/libretro.c",
      "libretro_input_bitmask[port] = input_state_cb(port, RETRO_DEVICE_JOYPAD, index, RETRO_DEVICE_ID_JOYPAD_MASK);",
      "libretro_input_bitmask[port] = (uint16_t)input_state_cb(port, RETRO_DEVICE_JOYPAD, index, RETRO_DEVICE_ID_JOYPAD_MASK);"
    ]
  ],
  "yabause": [
    [
      "yabause/src/libretro/libretro.c",
      "static int16_t libretro_input_bitmask[12] = {-1,};",
      "static int32_t libretro_input_bitmask[12] = {-1,};"
    ],
    [
      "yabause/src/libretro/libretro.c",
      "libretro_input_bitmask[port] = input_state_cb(port, RETRO_DEVICE_JOYPAD, index, RETRO_DEVICE_ID_JOYPAD_MASK);",
      "libretro_input_bitmask[port] = (uint16_t)input_state_cb(port, RETRO_DEVICE_JOYPAD, index, RETRO_DEVICE_ID_JOYPAD_MASK);"
    ]
  ],
  "ymir": [
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "    bool bitmasks = false;\n    if (cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, &bitmasks))\n        core.use_input_bitmasks = bitmasks;",
      "    // GET_INPUT_BITMASKS returns support in the return value, not through data.\n    core.use_input_bitmasks = cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);"
    ]
  ],
  "a5200": [
    [
      "libretro/libretro.c",
      "#include <math.h>",
      "#include <math.h>\n#if defined(__ARM_NEON) || defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ],
    [
      "libretro/libretro.c",
      "static void blend_frames_mix(void)\n{\n   uint16_t *curr = video_buffer;\n   uint16_t *prev = video_buffer_prev;\n   size_t x, y;\n\n   for (y = 0; y < A5200_VIDEO_HEIGHT; y++)\n   {\n      for (x = 0; x < A5200_VIDEO_WIDTH; x++)",
      "static void blend_frames_mix(void)\n{\n   uint16_t *curr = video_buffer;\n   uint16_t *prev = video_buffer_prev;\n   size_t x, y;\n\n   for (y = 0; y < A5200_VIDEO_HEIGHT; y++)\n   {\n      x = 0;\n#if defined(__ARM_NEON) || defined(__aarch64__)\n      const uint16x8_t lsb = vdupq_n_u16(0x0821);\n      const uint16x8_t rest = vdupq_n_u16(0xF7DE);\n      for (; x + 8 <= A5200_VIDEO_WIDTH; x += 8) {\n         const uint16x8_t a = vld1q_u16(curr), b = vld1q_u16(prev);\n         const uint16x8_t diff = veorq_u16(a, b);\n         const uint16x8_t mixed = vaddq_u16(vaddq_u16(vandq_u16(a, b), vshrq_n_u16(vandq_u16(diff, rest), 1)), vandq_u16(diff, lsb));\n         vst1q_u16(prev, a); vst1q_u16(curr, mixed);\n         prev += 8; curr += 8;\n      }\n#endif\n      for (; x < A5200_VIDEO_WIDTH; x++)"
    ],
    [
      "libretro/libretro.c",
      "         /* Convert 8u mono to 16s stereo */\n         int16_t sample_16 = (int16_t)((*(samples_ptr++) - 128) << 8);\n\n         /* Apply low-pass filter */",
      "         /* Multiplication avoids a left shift of a negative signed value. */\n         int16_t sample_16 = (int16_t)((*(samples_ptr++) - 128) * 256);\n\n         /* Apply low-pass filter */"
    ],
    [
      "libretro/libretro.c",
      "      for(i = 0; i < A5200_AUDIO_BUFFER_SIZE; i++)\n      {\n         /* Convert 8u mono to 16s stereo */\n         int16_t sample_16 = (int16_t)((*(samples_ptr++) - 128) << 8);\n\n         /* Update output buffer */",
      "      i = 0;\n#if defined(__ARM_NEON) || defined(__aarch64__)\n      for (; i + 8 <= A5200_AUDIO_BUFFER_SIZE; i += 8) {\n         const uint16x8_t raw = vshll_n_u8(vld1_u8(samples_ptr), 8);\n         const int16x8_t samples = vreinterpretq_s16_u16(veorq_u16(raw, vdupq_n_u16(0x8000)));\n         int16x8x2_t stereo; stereo.val[0] = samples; stereo.val[1] = samples;\n         vst2q_s16(out_ptr, stereo);\n         samples_ptr += 8; out_ptr += 16;\n      }\n#endif\n      for(; i < A5200_AUDIO_BUFFER_SIZE; i++)\n      {\n         int16_t sample_16 = (int16_t)((*(samples_ptr++) - 128) * 256);\n\n         /* Update output buffer */"
    ]
  ],
  "citra2018": [
    [
      "src/audio_core/libretro_sink.cpp",
      "    std::vector<s16> buffer(frames * 2);\n\n    this->impl->cb(buffer.data(), buffer.size() / 2);\n\n    LibRetro::SubmitAudio(buffer.data(), buffer.size() / 2);",
      "    // Typical submissions fit on the stack. Preserve the zero-filled buffer\n    // and allocate only for exceptional block sizes; no shared scratch state.\n    s16 local[2048];\n    if (frames <= 1024) {\n        std::fill_n(local, frames * 2, s16{0});\n        this->impl->cb(local, frames);\n        LibRetro::SubmitAudio(local, frames);\n    } else {\n        std::vector<s16> buffer(frames * 2);\n        this->impl->cb(buffer.data(), frames);\n        LibRetro::SubmitAudio(buffer.data(), frames);\n    }"
    ],
    [
      "src/audio_core/libretro_sink.cpp",
      "#include <list>",
      "#include <algorithm>\n#include <list>"
    ]
  ],
  "dolphin": [
    [
      "Source/Core/DolphinLibretro/Input.cpp",
      "#include <array>",
      "#include <array>\n#include <atomic>"
    ],
    [
      "Source/Core/DolphinLibretro/Input.cpp",
      "static retro_input_state_t input_cb = nullptr;",
      "static retro_input_state_t input_cb = nullptr;\nstatic std::atomic<bool> ambient_input_bitmasks{false};\n// One snapshot per port, published by Update() for the emulation thread.\n// A negative value means no snapshot, not an all-buttons-pressed mask.\nstatic std::atomic<int> ambient_joypad[8]{{-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}};\nstatic void AmbientInvalidateJoypads()\n{\n  for (auto& mask : ambient_joypad)\n    mask.store(-1, std::memory_order_relaxed);\n}"
    ],
    [
      "Source/Core/DolphinLibretro/Input.cpp",
      "    ControlState GetState() const override { return input_cb(m_port, m_device, m_index, m_id); }",
      "    ControlState GetState() const override\n    {\n      if (m_device == RETRO_DEVICE_JOYPAD && m_index == 0 && m_id < 16 && m_port < 8)\n      {\n        const int mask = ambient_joypad[m_port].load(std::memory_order_relaxed);\n        if (mask >= 0)\n          return (mask >> m_id) & 1;\n      }\n      return input_cb(m_port, m_device, m_index, m_id);\n    }"
    ],
    [
      "Source/Core/DolphinLibretro/Input.cpp",
      "void Init(const WindowSystemInfo& wsi)\n{",
      "void Init(const WindowSystemInfo& wsi)\n{\n  AmbientInvalidateJoypads();\n  ambient_input_bitmasks = environ_cb && environ_cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);"
    ],
    [
      "Source/Core/DolphinLibretro/Input.cpp",
      "void Shutdown()\n{",
      "void Shutdown()\n{\n  ambient_input_bitmasks.store(false, std::memory_order_relaxed);\n  AmbientInvalidateJoypads();"
    ],
    [
      "Source/Core/DolphinLibretro/Input.cpp",
      "#endif\n\n  for (int i = 0; i < port_max; ++i)\n  {\n    UpdateAccelerometer(i);",
      "#endif\n\n  if (ambient_input_bitmasks && input_cb)\n  {\n    for (int i = 0; i < 8; ++i)\n      ambient_joypad[i].store(i < port_max ? static_cast<uint16_t>(input_cb(i, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK)) : -1,\n                             std::memory_order_relaxed);\n  }\n  else\n    AmbientInvalidateJoypads();\n\n  for (int i = 0; i < port_max; ++i)\n  {\n    UpdateAccelerometer(i);"
    ]
  ],
  "flycast": [
    [
      "shell/libretro/audiostream.cpp",
      "static int16_t *audio_out_buffer = nullptr;",
      "static std::vector<int16_t> audio_out_buffer;"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "\taudio_out_buffer = (int16_t*)malloc(audio_buffer_size * sizeof(int16_t));",
      "\taudio_out_buffer.resize(audio_buffer_size);"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "\tif (audio_out_buffer != nullptr)\r\n\t\tfree(audio_out_buffer);\r\n\r\n\taudio_out_buffer = nullptr;",
      "\tstd::vector<int16_t>().swap(audio_out_buffer);"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "\tfor (size_t i = 0; i < audio_buffer_idx; i++)\r\n\t\taudio_out_buffer[i] = audio_buffer[i];",
      "\t// Both vectors have the same fixed size; transfer ownership instead of copying.\r\n\taudio_out_buffer.swap(audio_buffer);"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "\tint16_t *audio_out_buffer_ptr = audio_out_buffer;",
      "\tint16_t *audio_out_buffer_ptr = audio_out_buffer.data();"
    ]
  ],
  "mednafen_saturn": [
    [
      "mednafen/ss/vdp2_render.c",
      "   const uint64_t mxor = masks[0] ^ masks[1];\n   for(; MDFN_LIKELY(x < WinPieces[piece]); x++)",
      "   const uint64_t mxor = masks[0] ^ masks[1];\n#if defined(__aarch64__)\n   const uint64x2_t vmor = vdupq_n_u64(mor);\n   const uint64x2_t vmxor = vdupq_n_u64(mxor);\n   for (; x + 2 <= WinPieces[piece]; x += 2)\n   {\n    const uint64x2_t spr = vld1q_u64(LB.spr + x);\n    const uint64x2_t sel = vreinterpretq_u64_s64(vshrq_n_s64(vreinterpretq_s64_u64(vshlq_n_u64(spr, 63 - PIX_SWBIT_SHIFT)), 63));\n    const uint64x2_t value = vld1q_u64(buf + x);\n    vst1q_u64(buf + x, vandq_u64(value, veorq_u64(vmor, vandq_u64(sel, vmxor))));\n   }\n#endif\n   for(; MDFN_LIKELY(x < WinPieces[piece]); x++)"
    ],
    [
      "jni/Android.mk",
      "FLAGS                    :=\n\ninclude $(CORE_DIR)/Makefile.common",
      "FLAGS                    :=\n\n# Match the pinned GNU Makefile's DSP-JIT configuration on ARM64.\nifeq ($(TARGET_ARCH_ABI),arm64-v8a)\n  FLAGS += -DWANT_JIT=1\nendif\n\ninclude $(CORE_DIR)/Makefile.common"
    ]
  ],
  "melondsds": [
    [
      "src/libretro/render/software.cpp",
      "    for (uint32_t y = start.y; y < end.y; y++) {\n        for (uint32_t x = start.x; x < end.x; x++) {\n            // TODO: Replace with SIMD (does GLM have a SIMD version of this?)\n            uint32_t& pixel = buffer[uvec2(x, y)];\n            pixel = (0xFFFFFF - pixel) | 0xFF000000;\n        }\n    }",
      "    // Contiguous rows let Clang vectorize the exact RGB inversion without\n    // repeating address calculations. The alpha byte remains forced to 0xff.\n    for (uint32_t y = start.y; y < end.y; y++) {\n        uint32_t* row = buffer[y];\n        for (uint32_t x = start.x; x < end.x; x++)\n            row[x] = ~row[x] | 0xFF000000u;\n    }"
    ],
    [
      "src/libretro/render/software.cpp",
      "    buffer.Clear();\n    ScreenLayout layout = screenLayout.Layout();",
      "    ScreenLayout layout = screenLayout.Layout();\n\n    // A tightly packed full-screen copy overwrites every pixel. Avoid clearing\n    // the same memory first, but preserve blank regions for all other layouts.\n    if ((layout == ScreenLayout::TopOnly || layout == ScreenLayout::BottomOnly) &&\n        LayoutSupportsDirectCopy(layout) &&\n        buffer.Width() == NDS_SCREEN_WIDTH && buffer.Height() == NDS_SCREEN_HEIGHT) {\n        const auto translation = layout == ScreenLayout::TopOnly\n            ? screenLayout.GetTopScreenTranslation() : screenLayout.GetBottomScreenTranslation();\n        if (translation.x == 0 && translation.y == 0) {\n            buffer.CopyDirect(layout == ScreenLayout::TopOnly ? topBuffer.data() : bottomBuffer.data(), translation);\n            return;\n        }\n    }\n\n    buffer.Clear();"
    ]
  ],
  "ppsspp": [
    [
      "libretro/libretro.cpp",
      "} output_audio_buffer = {NULL, 0, 0};",
      "} output_audio_buffer = {NULL, 0, 0};\n// Owned by the libretro thread between swaps; the producer never writes it.\nstatic decltype(output_audio_buffer) ambient_upload_buffer = {NULL, 0, 0};"
    ],
    [
      "libretro/libretro.cpp",
      "      output_audio_buffer.data = NULL;\n      output_audio_buffer.size = 0;\n      output_audio_buffer.capacity = 0;\n      ensure_output_audio_buffer_capacity(capacity);",
      "      free(output_audio_buffer.data);\n      output_audio_buffer = {NULL, 0, 0};\n      free(ambient_upload_buffer.data);\n      ambient_upload_buffer = {NULL, 0, 0};\n      ensure_output_audio_buffer_capacity(capacity);\n      ambient_upload_buffer.data = static_cast<int16_t*>(malloc(capacity * sizeof(int16_t)));\n      if (ambient_upload_buffer.data)\n         ambient_upload_buffer.capacity = capacity;"
    ],
    [
      "libretro/libretro.cpp",
      "      free(output_audio_buffer.data);\n      output_audio_buffer.data = NULL;\n      output_audio_buffer.size = 0;\n      output_audio_buffer.capacity = 0;",
      "      free(output_audio_buffer.data);\n      output_audio_buffer = {NULL, 0, 0};\n      free(ambient_upload_buffer.data);\n      ambient_upload_buffer = {NULL, 0, 0};"
    ],
    [
      "libretro/libretro.cpp",
      "      std::lock_guard<std::mutex> lock(output_audio_buffer_mutex);\n      if (output_audio_buffer.data && output_audio_buffer.size > 0)\n         audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\n      output_audio_buffer.size = 0;",
      "      {\n         std::lock_guard<std::mutex> lock(output_audio_buffer_mutex);\n         if (!output_audio_buffer.data || output_audio_buffer.size <= 0)\n            return;\n         auto filled = output_audio_buffer;\n         output_audio_buffer = ambient_upload_buffer;\n         ambient_upload_buffer = filled;\n         output_audio_buffer.size = 0;\n      }\n      // Do not block the emulation thread while the frontend consumes audio.\n      audio_batch_cb(ambient_upload_buffer.data, ambient_upload_buffer.size / 2);\n      ambient_upload_buffer.size = 0;"
    ]
  ],
  "prosystem": [
    [
      "core/libretro.c",
      "#include <math.h>",
      "#include <math.h>\n#if defined(__ARM_NEON) || defined(__aarch64__)\n#include <arm_neon.h>\n#endif"
    ],
    [
      "core/libretro.c",
      "      for(j = 0; j < tia_size; j++)\n         *(pokey_mix_buf++) = (*(tia_samples_buf++) + *(pokey_samples_buf++)) >> 1;",
      "      j = 0;\n#if defined(__ARM_NEON) || defined(__aarch64__)\n      for (; j + 16 <= tia_size; j += 16) {\n         vst1q_u8(pokey_mix_buf, vhaddq_u8(vld1q_u8(tia_samples_buf), vld1q_u8(pokey_samples_buf)));\n         pokey_mix_buf += 16; tia_samples_buf += 16; pokey_samples_buf += 16;\n      }\n#endif\n      for(; j < tia_size; j++)\n         *(pokey_mix_buf++) = (*(tia_samples_buf++) + *(pokey_samples_buf++)) >> 1;"
    ],
    [
      "core/libretro.c",
      "      for(i = 0; i < tia_size; i++)\n      {\n         int16_t sample_16 = (int16_t)(*(tia_samples_buf++) << 8);\n\n         *(audio_out_buf++) = sample_16;\n         *(audio_out_buf++) = sample_16;\n      }",
      "      i = 0;\n#if defined(__ARM_NEON) || defined(__aarch64__)\n      for (; i + 8 <= tia_size; i += 8) {\n         const int16x8_t samples = vreinterpretq_s16_u16(vshll_n_u8(vld1_u8(tia_samples_buf), 8));\n         int16x8x2_t stereo; stereo.val[0] = samples; stereo.val[1] = samples;\n         vst2q_s16(audio_out_buf, stereo);\n         tia_samples_buf += 8; audio_out_buf += 16;\n      }\n#endif\n      for(; i < tia_size; i++)\n      {\n         int16_t sample_16 = (int16_t)(*(tia_samples_buf++) << 8);\n         *(audio_out_buf++) = sample_16;\n         *(audio_out_buf++) = sample_16;\n      }"
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
