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
    ],
    [
      "src/Cafe/HW/Latte/Renderer/Vulkan/VulkanPipelineStableCache.cpp",
      "#include <openssl/sha.h>",
      "#include <openssl/sha.h>\n#ifdef __ANDROID__\n#include <sched.h>\n#endif"
    ],
    [
      "src/Cafe/HW/Latte/Renderer/Vulkan/VulkanPipelineStableCache.cpp",
      "\tuint32 cpuCoreCount = GetPhysicalCoreCount();\n\tm_numCompilationThreads = std::clamp(cpuCoreCount, 1u, 8u);",
      "\tuint32 cpuCoreCount = std::max(1u, GetPhysicalCoreCount());\n#ifdef __ANDROID__\n\tcpu_set_t available;\n\tCPU_ZERO(&available);\n\tif (sched_getaffinity(0, sizeof(available), &available) == 0) {\n\t\tint count = CPU_COUNT(&available);\n\t\tif (count > 0) cpuCoreCount = std::min(cpuCoreCount, static_cast<uint32>(count));\n\t}\n\t// Preserve emulation and presentation headroom on Android TV.\n\tcpuCoreCount = cpuCoreCount > 2 ? cpuCoreCount - 2 : 1;\n#endif\n\tm_numCompilationThreads = std::clamp(cpuCoreCount, 1u, 8u);"
    ],
    [
      "src/Cafe/HW/Latte/Renderer/Vulkan/VulkanPipelineStableCache.cpp",
      "\twhile (g_vkCacheState.pipelineLoadIndex <= g_vkCacheState.pipelineMaxFileIndex)\n\t{",
      "\t// Refill a bounded batch instead of scheduling only one pipeline per UI tick.\n\tconst uint32 batch = std::clamp(m_numCompilationThreads.load() * 2u, 2u, 16u);\n\tuint32 queued = 0, scanned = 0;\n\twhile (g_vkCacheState.pipelineLoadIndex <= g_vkCacheState.pipelineMaxFileIndex)\n\t{\n\t\tif (queued >= batch || scanned++ >= 64) return true;"
    ],
    [
      "src/Cafe/HW/Latte/Renderer/Vulkan/VulkanPipelineStableCache.cpp",
      "\t\t\tg_vkCacheState.pipelineLoadIndex++;\n\t\t\treturn true;\n\t\t}\n\t\tg_vkCacheState.pipelineLoadIndex++;",
      "\t\t\t++queued;\n\t\t}\n\t\tg_vkCacheState.pipelineLoadIndex++;"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
    ],
    [
      "libretro/libretro-core.c",
      "static void ensure_output_audio_buffer_capacity(int32_t capacity)\r\n{\r\n   if (capacity <= output_audio_buffer.capacity) {\r\n      return;\r\n   }\r\n\r\n   output_audio_buffer.data = realloc(output_audio_buffer.data, capacity * sizeof(*output_audio_buffer.data));\r\n   output_audio_buffer.capacity = capacity;\r\n   log_cb(RETRO_LOG_DEBUG, \"Output audio buffer capacity set to %d\\n\", capacity);\r\n}",
      "static bool ensure_output_audio_buffer_capacity(int32_t additional)\r\n{\r\n   if (additional < 0 || output_audio_buffer.size > INT32_MAX - additional)\r\n      return false;\r\n   int32_t required = output_audio_buffer.size + additional;\r\n   if (required <= output_audio_buffer.capacity)\r\n      return true;\r\n   int32_t growth = output_audio_buffer.capacity / 2;\r\n   int32_t capacity = required;\r\n   if (output_audio_buffer.capacity <= INT32_MAX - growth &&\r\n       output_audio_buffer.capacity + growth > capacity)\r\n      capacity = output_audio_buffer.capacity + growth;\r\n   if ((size_t)capacity > SIZE_MAX / sizeof(*output_audio_buffer.data))\r\n      return false;\r\n   int16_t *data = realloc(output_audio_buffer.data, (size_t)capacity * sizeof(*data));\r\n   if (!data)\r\n      return false;\r\n   output_audio_buffer.data = data;\r\n   output_audio_buffer.capacity = capacity;\r\n   return true;\r\n}"
    ],
    [
      "libretro/libretro-core.c",
      "   audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;",
      "   if (output_audio_buffer.size > 0)\r\n      audio_batch_cb(output_audio_buffer.data, output_audio_buffer.size / 2);\r\n   output_audio_buffer.size = 0;"
    ],
    [
      "libretro/libretro-core.c",
      "   if (output_audio_buffer.capacity - output_audio_buffer.size < samples)\r\n      ensure_output_audio_buffer_capacity((output_audio_buffer.capacity + samples) * 1.5);\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, samples * sizeof(*output_audio_buffer.data));",
      "   if (!data || !ensure_output_audio_buffer_capacity(samples))\r\n      return;\r\n   memcpy(output_audio_buffer.data + output_audio_buffer.size, data, (size_t)samples * sizeof(*output_audio_buffer.data));"
    ],
    [
      "libretro/libretro-core.c",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif",
      "#elif ARCHDEP_SOUND_OUTPUT_MODE == SOUND_OUTPUT_MONO\r\n#ifdef RETRO_AUDIO_BATCH\r\n   if (!data || samples > INT32_MAX / 2 || !ensure_output_audio_buffer_capacity(samples * 2))\r\n      return;\r\n   int16_t *dest = output_audio_buffer.data + output_audio_buffer.size;\r\n   for (int32_t x = 0; x < samples; ++x) {\r\n      dest[2 * x] = data[x];\r\n      dest[2 * x + 1] = data[x];\r\n   }\r\n   output_audio_buffer.size += samples * 2;\r\n#else\r\n   for (int x = 0; x < samples; x++) audio_cb(data[x], data[x]);\r\n#endif\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_word_array(snapshot_stream_t *f, const uint16_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_word_array(snapshot_stream_t *f, uint16_t *w_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*w_return) ||\r\n            snapshot_read(f, w_return, (size_t)num * sizeof(*w_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_write_dword_array(snapshot_stream_t *f, const uint32_t *data, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    /* Android's little-endian arrays already match the snapshot wire format.\r\n       One stream operation also makes size-only snapshots O(1) per array. */\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*data) ||\r\n            snapshot_write(f, data, (size_t)num * sizeof(*data)) != 1) {\r\n            snapshot_error = SNAPSHOT_WRITE_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
    ],
    [
      "retrodep/snapshot_stream.c",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);",
      "static int snapshot_read_dword_array(snapshot_stream_t *f, uint32_t *dw_return, unsigned int num)\r\n{\r\n    unsigned int i;\r\n\r\n    current_fpos = snapshot_ftell(f);\r\n#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__\r\n    if (f->ops == &snapshot_memory_ops) {\r\n        if (num == 0)\r\n            return 0;\r\n        if ((size_t)num > SIZE_MAX / sizeof(*dw_return) ||\r\n            snapshot_read(f, dw_return, (size_t)num * sizeof(*dw_return)) != 1) {\r\n            snapshot_error = SNAPSHOT_READ_EOF_ERROR;\r\n            return -1;\r\n        }\r\n        return 0;\r\n    }\r\n#endif"
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
      "static int32_t libretro_input_bitmask[12] = {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};"
    ],
    [
      "yabause/src/libretro/libretro.c",
      "libretro_input_bitmask[port] = input_state_cb(port, RETRO_DEVICE_JOYPAD, index, RETRO_DEVICE_ID_JOYPAD_MASK);",
      "libretro_input_bitmask[port] = (uint16_t)input_state_cb(port, RETRO_DEVICE_JOYPAD, index, RETRO_DEVICE_ID_JOYPAD_MASK);"
    ],
    [
      "yabause/src/sh2int.c",
      "   while(context->cycles < cycles)\n   {\n      int cycles_before = context->cycles;\n      int cycles_diff = 0;\n      // Fetch Instruction\n\n      if (yabsys.sh2_cache_enabled)\n      {\n         if ((context->regs.PC & 0xC0000000) == 0xC0000000)\n            context->instruction = DataArrayReadWord(context, context->regs.PC);\n         else\n            context->instruction = ((fetchfunc *)context->fetchlist)[(context->regs.PC >> 20) & 0x0FF](context, context->regs.PC);\n      }\n      else\n      {\n         context->instruction = ((fetchfunc *)context->fetchlist)[(context->regs.PC >> 20) & 0x0FF](context, context->regs.PC);\n      }\n\n      // Execute it\n      ((opcodefunc *)context->opcodes)[context->instruction](context);\n\n      cycles_diff = context->cycles - cycles_before;\n\n      if (context->model == SHMT_SH1)\n         sh1_dma_exec(cycles_diff);\n   }",
      "   // The CPU model is fixed for the lifetime of a context. Keep the CD\n   // controller's SH1 DMA accounting out of both Saturn SH2 dispatch loops.\n   if (context->model != SHMT_SH1)\n   {\n      while (context->cycles < cycles)\n      {\n         const u32 pc = context->regs.PC;\n         if (yabsys.sh2_cache_enabled && (pc & 0xC0000000) == 0xC0000000)\n            context->instruction = DataArrayReadWord(context, pc);\n         else\n            context->instruction = ((fetchfunc *)context->fetchlist)[(pc >> 20) & 0x0FF](context, pc);\n         ((opcodefunc *)context->opcodes)[context->instruction](context);\n      }\n   }\n   else\n   {\n      while (context->cycles < cycles)\n      {\n         const int cycles_before = context->cycles;\n         const u32 pc = context->regs.PC;\n         if (yabsys.sh2_cache_enabled && (pc & 0xC0000000) == 0xC0000000)\n            context->instruction = DataArrayReadWord(context, pc);\n         else\n            context->instruction = ((fetchfunc *)context->fetchlist)[(pc >> 20) & 0x0FF](context, pc);\n         ((opcodefunc *)context->opcodes)[context->instruction](context);\n         sh1_dma_exec(context->cycles - cycles_before);\n      }\n   }"
    ]
  ],
  "ymir": [
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "    bool bitmasks = false;\n    if (cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, &bitmasks))\n        core.use_input_bitmasks = bitmasks;",
      "    // GET_INPUT_BITMASKS returns support in the return value, not through data.\n    core.use_input_bitmasks = cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, nullptr);"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#include <cassert>\n#include <limits>",
      "#include <cassert>\n#include <cstring>\n#include <limits>"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "FORCE_INLINE static void Color888ShadowMasked(",
      "#if defined(_M_ARM64) || defined(__aarch64__)\n// Load exactly four bytes (unaligned-safe), expand each into an all-bit mask.\nFORCE_INLINE static uint8x16_t AmbientMask4(const bool* mask) {\n    uint32 packed;\n    std::memcpy(&packed, mask, sizeof(packed));\n    const uint16x8_t wide16 = vmovl_u8(vcreate_u8(packed));\n    const uint32x4_t wide32 = vmovl_u16(vget_low_u16(wide16));\n    return vreinterpretq_u8_u32(vcgtq_u32(wide32, vdupq_n_u32(0)));\n}\n#endif\n\nFORCE_INLINE static void Color888ShadowMasked("
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "(vmaxvq_u8(vec64.val[0]) != 0u) || (vmaxvq_u8(vec64.val[1]) != 0u) || (vmaxvq_u8(vec64.val[2]) != 0u) ||\n            (vmaxvq_u8(vec64.val[3]) != 0u)) {\n            return false;",
      "vmaxvq_u8(vorrq_u8(vorrq_u8(vec64.val[0], vec64.val[1]), vorrq_u8(vec64.val[2], vec64.val[3]))) != 0u) {\n            return false;"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "(vmaxvq_u8(vec64.val[0]) != 0u) || (vmaxvq_u8(vec64.val[1]) != 0u) || (vmaxvq_u8(vec64.val[2]) != 0u) ||\n            (vmaxvq_u8(vec64.val[3]) != 0u)) {\n            return true;",
      "vmaxvq_u8(vorrq_u8(vorrq_u8(vec64.val[0], vec64.val[1]), vorrq_u8(vec64.val[2], vec64.val[3]))) != 0u) {\n            return true;"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "(vminvq_u8(vec64.val[0]) == 0u) || (vminvq_u8(vec64.val[1]) == 0u) || (vminvq_u8(vec64.val[2]) == 0u) ||\n            (vminvq_u8(vec64.val[3]) == 0u)",
      "vminvq_u8(vandq_u8(vandq_u8(vec64.val[0], vec64.val[1]), vandq_u8(vec64.val[2], vec64.val[3]))) == 0u"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time\n    for (; (i + 4) < pixels.size(); i += 4) {\n        // Load four mask values and expand each byte into 32-bit 000... or 111...\n        uint32x4_t mask_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(mask.data() + i), vdupq_n_u32(0), 0);\n        mask_x4 = vmovl_u16(vget_low_u16(vmovl_u8(vget_low_u8(mask_x4))));\n        mask_x4 = vnegq_s32(mask_x4);\n\n        const uint32x4_t pixel_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&pixels[i]));\n        const uint32x4_t shadowed_x4 = vshrq_n_u8(pixel_x4, 1);\n\n        // Blend with mask\n        const uint32x4_t dstColor_x4 = vbslq_u32(mask_x4, shadowed_x4, pixel_x4);\n\n        // Write\n        vst1q_u32(reinterpret_cast<uint32 *>(&pixels[i]), dstColor_x4);\n    }",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time; byte lanes avoid implicit vector-type conversions.\n    for (; i + 4 <= pixels.size(); i += 4) {\n        const uint8x16_t mask_x4 = AmbientMask4(mask.data() + i);\n        const uint8x16_t pixel_x4 = vld1q_u8(reinterpret_cast<const uint8 *>(&pixels[i]));\n        vst1q_u8(reinterpret_cast<uint8 *>(&pixels[i]), vbslq_u8(mask_x4, vshrq_n_u8(pixel_x4, 1), pixel_x4));\n    }"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time\n    for (; (i + 4) < dest.size(); i += 4) {\n        // Load four mask values and expand each byte into 32-bit 000... or 111...\n        uint32x4_t mask_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(mask.data() + i), vdupq_n_u32(0), 0);\n        mask_x4 = vmovl_u16(vget_low_u16(vmovl_u8(vget_low_u8(mask_x4))));\n        mask_x4 = vnegq_s32(mask_x4);\n\n        const uint32x4_t topColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&topColors[i]));\n        const uint32x4_t btmColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&btmColors[i]));\n\n        // Saturated add\n        const uint32x4_t add_x4 = vqaddq_u8(topColor_x4, btmColor_x4);\n\n        // Blend with mask\n        const uint32x4_t dstColor_x4 = vbslq_u32(mask_x4, add_x4, topColor_x4);\n\n        // Write\n        vst1q_u32(reinterpret_cast<uint32 *>(&dest[i]), dstColor_x4);\n    }",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time; byte lanes avoid implicit vector-type conversions.\n    for (; i + 4 <= dest.size(); i += 4) {\n        const uint8x16_t mask_x4 = AmbientMask4(mask.data() + i);\n        const uint8x16_t top = vld1q_u8(reinterpret_cast<const uint8 *>(&topColors[i]));\n        const uint8x16_t bottom = vld1q_u8(reinterpret_cast<const uint8 *>(&btmColors[i]));\n        vst1q_u8(reinterpret_cast<uint8 *>(&dest[i]), vbslq_u8(mask_x4, vqaddq_u8(top, bottom), top));\n    }"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time\n    for (; (i + 4) < dest.size(); i += 4) {\n        // Load four mask values and expand each byte into 32-bit 000... or 111...\n        uint32x4_t mask_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(mask.data() + i), vdupq_n_u32(0), 0);\n        mask_x4 = vmovl_u16(vget_low_u16(vmovl_u8(vget_low_u8(mask_x4))));\n        mask_x4 = vnegq_s32(mask_x4);\n\n        const uint32x4_t topColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&topColors[i]));\n        const uint32x4_t btmColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&btmColors[i]));\n\n        // Blend with mask\n        const uint32x4_t dstColor_x4 = vbslq_u32(mask_x4, btmColor_x4, topColor_x4);\n\n        // Write\n        vst1q_u32(reinterpret_cast<uint32 *>(&dest[i]), dstColor_x4);\n    }",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time; byte lanes avoid implicit vector-type conversions.\n    for (; i + 4 <= dest.size(); i += 4) {\n        const uint8x16_t mask_x4 = AmbientMask4(mask.data() + i);\n        const uint8x16_t top = vld1q_u8(reinterpret_cast<const uint8 *>(&topColors[i]));\n        const uint8x16_t bottom = vld1q_u8(reinterpret_cast<const uint8 *>(&btmColors[i]));\n        vst1q_u8(reinterpret_cast<uint8 *>(&dest[i]), vbslq_u8(mask_x4, bottom, top));\n    }"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time\n    for (; (i + 4) < dest.size(); i += 4) {\n        // Load four mask values and expand each byte into 32-bit 000... or 111...\n        uint32x4_t mask_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(mask.data() + i), vdupq_n_u32(0), 0);\n        mask_x4 = vmovl_u16(vget_low_u16(vmovl_u8(vget_low_u8(mask_x4))));\n        mask_x4 = vnegq_s32(mask_x4);\n\n        const uint32x4_t color0_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&src[i]));\n        const uint32x4_t color1_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&src[i + 1]));\n        const uint32x4_t color2_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&src[i + 2]));\n\n        // Halving average\n        const uint32x4_t blend01_x4 = vhaddq_u8(color0_x4, color1_x4);\n        const uint32x4_t blend2_x4 = vhaddq_u8(blend01_x4, color2_x4);\n\n        // Blend with mask\n        const uint32x4_t dstColor_x4 = vbslq_u32(mask_x4, color2_x4, blend2_x4);\n\n        // Write\n        vst1q_u32(reinterpret_cast<uint32 *>(&dest[i]), dstColor_x4);\n    }",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time; byte lanes avoid implicit vector-type conversions.\n    for (; i + 4 <= dest.size(); i += 4) {\n        const uint8x16_t mask_x4 = AmbientMask4(mask.data() + i);\n        const uint8x16_t color0 = vld1q_u8(reinterpret_cast<const uint8 *>(&src[i]));\n        const uint8x16_t color1 = vld1q_u8(reinterpret_cast<const uint8 *>(&src[i + 1]));\n        const uint8x16_t color2 = vld1q_u8(reinterpret_cast<const uint8 *>(&src[i + 2]));\n        const uint8x16_t blended = vhaddq_u8(vhaddq_u8(color0, color1), color2);\n        vst1q_u8(reinterpret_cast<uint8 *>(&dest[i]), vbslq_u8(mask_x4, blended, color2));\n    }"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time\n    for (; (i + 4) < dest.size(); i += 4) {\n        // Load four mask values and expand each byte into 32-bit 000... or 111...\n        uint32x4_t mask_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(mask.data() + i), vdupq_n_u32(0), 0);\n        mask_x4 = vmovl_u16(vget_low_u16(vmovl_u8(vget_low_u8(mask_x4))));\n        mask_x4 = vnegq_s32(mask_x4);\n\n        const uint32x4_t topColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&topColors[i]));\n        const uint32x4_t btmColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&btmColors[i]));\n\n        // Halving average\n        const uint32x4_t average_x4 = vhaddq_u8(topColor_x4, btmColor_x4);\n\n        // Blend with mask\n        const uint32x4_t dstColor_x4 = vbslq_u32(mask_x4, average_x4, topColor_x4);\n\n        // Write\n        vst1q_u32(reinterpret_cast<uint32 *>(&dest[i]), dstColor_x4);\n    }",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time; byte lanes avoid implicit vector-type conversions.\n    for (; i + 4 <= dest.size(); i += 4) {\n        const uint8x16_t mask_x4 = AmbientMask4(mask.data() + i);\n        const uint8x16_t top = vld1q_u8(reinterpret_cast<const uint8 *>(&topColors[i]));\n        const uint8x16_t bottom = vld1q_u8(reinterpret_cast<const uint8 *>(&btmColors[i]));\n        vst1q_u8(reinterpret_cast<uint8 *>(&dest[i]), vbslq_u8(mask_x4, vhaddq_u8(top, bottom), top));\n    }"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time\n    for (; (i + 4) < dest.size(); i += 4) {\n        // Load four mask values and expand each byte into 32-bit 000... or 111...\n        uint32x4_t mask_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(mask.data() + i), vdupq_n_u32(0), 0);\n        mask_x4 = vmovl_u16(vget_low_u16(vmovl_u8(vget_low_u8(mask_x4))));\n        mask_x4 = vnegq_s32(mask_x4);\n\n        // Load four ratios and splat each byte into 32-bit lanes\n        uint32x4_t ratio_x4 = vld1q_lane_u32(reinterpret_cast<const uint32 *>(ratios.data() + i), vdupq_n_u32(0), 0);\n        // 8 -> 16\n        ratio_x4 = vzip1q_u8(ratio_x4, ratio_x4);\n        // 16 -> 32\n        ratio_x4 = vzip1q_u16(ratio_x4, ratio_x4);\n\n        const uint32x4_t topColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&topColors[i]));\n        const uint32x4_t btmColor_x4 = vld1q_u32(reinterpret_cast<const uint32 *>(&btmColors[i]));\n\n        const uint16x8_t topColor16lo = vmovl_u8(vget_low_u8(topColor_x4));\n        const uint16x8_t btmColor16lo = vmovl_u8(vget_low_u8(btmColor_x4));\n\n        const uint16x8_t topColor16hi = vmovl_high_u8(topColor_x4);\n        const uint16x8_t btmColor16hi = vmovl_high_u8(btmColor_x4);\n\n        // Composite\n        int16x8_t composite16lo = vsubq_s16(topColor16lo, btmColor16lo);\n        int16x8_t composite16hi = vsubq_s16(topColor16hi, btmColor16hi);\n\n        composite16lo = vmulq_u16(composite16lo, vmovl_u8(vget_low_s8(ratio_x4)));\n        composite16hi = vmulq_u16(composite16hi, vmovl_high_u8(ratio_x4));\n\n        composite16lo = vsraq_n_s16(vmovl_s8(vget_low_s8(btmColor_x4)), composite16lo, 5);\n        composite16hi = vsraq_n_s16(vmovl_high_s8(btmColor_x4), composite16hi, 5);\n\n        int8x16_t composite_x4 = vmovn_high_s16(vmovn_s16(composite16lo), composite16hi);\n\n        // Blend with mask\n        const uint32x4_t dstColor_x4 = vbslq_u32(mask_x4, composite_x4, topColor_x4);\n\n        // Write\n        vst1q_u32(reinterpret_cast<uint32 *>(&dest[i]), dstColor_x4);\n    }",
      "#elif defined(_M_ARM64) || defined(__aarch64__)\n    // Four pixels at a time; byte lanes avoid implicit vector-type conversions.\n    for (; i + 4 <= dest.size(); i += 4) {\n        const uint8x16_t mask_x4 = AmbientMask4(mask.data() + i);\n        const uint8x16_t top = vld1q_u8(reinterpret_cast<const uint8 *>(&topColors[i]));\n        const uint8x16_t bottom = vld1q_u8(reinterpret_cast<const uint8 *>(&btmColors[i]));\n        uint32 packed;\n        std::memcpy(&packed, ratios.data() + i, sizeof(packed));\n        const uint8x16_t ratio = vreinterpretq_u8_u32(vmulq_n_u32(\n            vmovl_u16(vget_low_u16(vmovl_u8(vcreate_u8(packed)))), 0x01010101u));\n        const uint16x8_t bottomLo = vmovl_u8(vget_low_u8(bottom));\n        const uint16x8_t bottomHi = vmovl_high_u8(bottom);\n        // Match the x86 lane arithmetic, including wrap and truncation.\n        const uint16x8_t resultLo = vaddq_u16(bottomLo, vshrq_n_u16(vmulq_u16(\n            vsubq_u16(vmovl_u8(vget_low_u8(top)), bottomLo),\n            vmovl_u8(vget_low_u8(ratio))), 5));\n        const uint16x8_t resultHi = vaddq_u16(bottomHi, vshrq_n_u16(vmulq_u16(\n            vsubq_u16(vmovl_high_u8(top), bottomHi), vmovl_high_u8(ratio)), 5));\n        const uint8x16_t blended = vmovn_high_u16(vmovn_u16(resultLo), resultHi);\n        vst1q_u8(reinterpret_cast<uint8 *>(&dest[i]), vbslq_u8(mask_x4, blended, top));\n    }"
    ],
    [
      "libs/ymir-core/src/ymir/hw/vdp/renderer/vdp_renderer_sw.cpp",
      "_mm_or_si128(_mm_and_si128(color2_x4, blend2_x4), _mm_andnot_si128(mask_x4, color2_x4))",
      "_mm_or_si128(_mm_and_si128(mask_x4, blend2_x4), _mm_andnot_si128(mask_x4, color2_x4))"
    ],
    [
      "libs/ymir-core/include/ymir/hw/scsp/scsp.hpp",
      "#include <array>",
      "#include <array>\n#include <vector>"
    ],
    [
      "libs/ymir-core/include/ymir/hw/scsp/scsp.hpp",
      "    void SetSampleCallback(CBOutputSample callback) {\n        m_cbOutputSample = callback;\n    }",
      "    void SetSampleCallback(CBOutputSample callback) {\n        SyncSCSPThread();\n        m_bufferedSampleOutput = nullptr;\n        m_cbOutputSample = callback;\n    }\n\n    // Libretro consumes a whole frame at a time. The caller must synchronize\n    // SCSP before reading/clearing this buffer and keep it alive until teardown.\n    void SetBufferedSampleOutput(std::vector<sint16>& buffer) {\n        SyncSCSPThread();\n        m_bufferedSampleOutput = &buffer;\n    }"
    ],
    [
      "libs/ymir-core/include/ymir/hw/scsp/scsp.hpp",
      "    CBOutputSample m_cbOutputSample;",
      "    CBOutputSample m_cbOutputSample;\n    std::vector<sint16>* m_bufferedSampleOutput = nullptr;"
    ],
    [
      "libs/ymir-core/src/ymir/hw/scsp/scsp.cpp",
      "        m_cbOutputSample(m_out[0], m_out[1]);\n        m_out.fill(0);",
      "        if (m_bufferedSampleOutput) {\n            m_bufferedSampleOutput->push_back(static_cast<sint16>(m_out[0]));\n            m_bufferedSampleOutput->push_back(static_cast<sint16>(m_out[1]));\n        } else {\n            m_cbOutputSample(m_out[0], m_out[1]);\n        }\n        m_out.fill(0);"
    ],
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "static void on_audio_sample(sint16 left, sint16 right, void *) {\n    core.audio_buffer.push_back(left);\n    core.audio_buffer.push_back(right);\n}\n",
      ""
    ],
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "    core.audio_buffer.reserve(882 * 2); // PAL worst case per frame",
      "    core.audio_buffer.reserve(2048 * 2); // Reserve headroom for PAL frames and scheduler jitter"
    ],
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "    core.saturn->SCSP.SetSampleCallback({on_audio_sample});",
      "    core.saturn->SCSP.SetBufferedSampleOutput(core.audio_buffer);"
    ],
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "    // Clear per-frame state\n    core.frame_ready = false;\n    core.audio_buffer.clear();",
      "    // No SCSP worker may still write while the frontend clears its buffer.\n    core.saturn->SCSP.SyncSCSPThreadPublic();\n    // Clear per-frame state\n    core.frame_ready = false;\n    core.audio_buffer.clear();"
    ],
    [
      "apps/ymir-libretro/src/libretro.cpp",
      "    // Run one frame\n    core.saturn->RunFrame();",
      "    // Run one frame and publish all audio before handing the buffer to libretro.\n    core.saturn->RunFrame();\n    core.saturn->SCSP.SyncSCSPThreadPublic();"
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
    ],
    [
      "src/video_core/renderer_opengl/gl_shader_util.cpp",
      "// Copyright 2014 Citra Emulator Project\n// Licensed under GPLv2 or any later version\n// Refer to the license.txt file included.\n\n#include <vector>\n#include <glad/glad.h>\n#include \"common/assert.h\"\n#include \"common/logging/log.h\"\n#include \"video_core/renderer_opengl/gl_shader_util.h\"\n\nnamespace GLShader {\n\nGLuint LoadShader(const char* source, GLenum type) {\n    const char* debug_type;\n    switch (type) {\n    case GL_VERTEX_SHADER:\n        debug_type = \"vertex\";\n        break;\n    case GL_GEOMETRY_SHADER:\n        debug_type = \"geometry\";\n        break;\n    case GL_FRAGMENT_SHADER:\n        debug_type = \"fragment\";\n        break;\n    default:\n        UNREACHABLE();\n    }\n\n    GLuint shader_id = glCreateShader(type);\n    glShaderSource(shader_id, 1, &source, nullptr);\n    LOG_DEBUG(Render_OpenGL, \"Compiling {} shader...\", debug_type);\n    glCompileShader(shader_id);\n\n    GLint result = GL_FALSE;\n    GLint info_log_length;\n    glGetShaderiv(shader_id, GL_COMPILE_STATUS, &result);\n    glGetShaderiv(shader_id, GL_INFO_LOG_LENGTH, &info_log_length);\n\n    if (info_log_length > 1) {\n        std::vector<char> shader_error(info_log_length);\n        glGetShaderInfoLog(shader_id, info_log_length, nullptr, &shader_error[0]);\n        if (result == GL_TRUE) {\n            LOG_DEBUG(Render_OpenGL, \"{}\", &shader_error[0]);\n        } else {\n            LOG_ERROR(Render_OpenGL, \"Error compiling {} shader:\\n{}\", debug_type,\n                      &shader_error[0]);\n            LOG_ERROR(Render_OpenGL, \"Shader source code:\\n{}\", source);\n        }\n    }\n    return shader_id;\n}\n\nGLuint LoadProgram(bool separable_program, const std::vector<GLuint>& shaders) {\n    // Link the program\n    LOG_DEBUG(Render_OpenGL, \"Linking program...\");\n\n    GLuint program_id = glCreateProgram();\n\n    for (GLuint shader : shaders) {\n        if (shader != 0) {\n            glAttachShader(program_id, shader);\n        }\n    }\n\n    if (separable_program) {\n        glProgramParameteri(program_id, GL_PROGRAM_SEPARABLE, GL_TRUE);\n    }\n\n    glLinkProgram(program_id);\n\n    // Check the program\n    GLint result = GL_FALSE;\n    GLint info_log_length;\n    glGetProgramiv(program_id, GL_LINK_STATUS, &result);\n    glGetProgramiv(program_id, GL_INFO_LOG_LENGTH, &info_log_length);\n\n    if (info_log_length > 1) {\n        std::vector<char> program_error(info_log_length);\n        glGetProgramInfoLog(program_id, info_log_length, nullptr, &program_error[0]);\n        if (result == GL_TRUE) {\n            LOG_DEBUG(Render_OpenGL, \"{}\", &program_error[0]);\n        } else {\n            LOG_ERROR(Render_OpenGL, \"Error linking shader:\\n{}\", &program_error[0]);\n        }\n    }\n\n    ASSERT_MSG(result == GL_TRUE, \"Shader not linked\");\n\n    for (GLuint shader : shaders) {\n        if (shader != 0) {\n            glDetachShader(program_id, shader);\n        }\n    }\n\n    return program_id;\n}\n\n} // namespace GLShader\n",
      "// Copyright 2014 Citra Emulator Project\n// Licensed under GPLv2 or any later version\n// Refer to the license.txt file included.\n\n#include <vector>\n#include <glad/glad.h>\n#ifdef __ANDROID__\n#include <cstdint>\n#include <cstdio>\n#include <cstdlib>\n#include <string>\n#include <unistd.h>\n#include \"common/file_util.h\"\n#endif\n#include \"common/assert.h\"\n#include \"common/logging/log.h\"\n#include \"video_core/renderer_opengl/gl_shader_util.h\"\n\nnamespace GLShader {\n\nstatic GLuint CreateShaderSource(const char* source, GLenum type) {\n    GLuint shader_id = glCreateShader(type);\n    glShaderSource(shader_id, 1, &source, nullptr);\n    return shader_id;\n}\n\nstatic void CompileShader(GLuint shader_id, const char* source, GLenum type) {\n    const char* debug_type;\n    switch (type) {\n    case GL_VERTEX_SHADER:\n        debug_type = \"vertex\";\n        break;\n    case GL_GEOMETRY_SHADER:\n        debug_type = \"geometry\";\n        break;\n    case GL_FRAGMENT_SHADER:\n        debug_type = \"fragment\";\n        break;\n    default:\n        UNREACHABLE();\n    }\n\n    LOG_DEBUG(Render_OpenGL, \"Compiling {} shader...\", debug_type);\n    glCompileShader(shader_id);\n\n    GLint result = GL_FALSE;\n    GLint info_log_length;\n    glGetShaderiv(shader_id, GL_COMPILE_STATUS, &result);\n    glGetShaderiv(shader_id, GL_INFO_LOG_LENGTH, &info_log_length);\n\n    if (info_log_length > 1) {\n        std::vector<char> shader_error(info_log_length);\n        glGetShaderInfoLog(shader_id, info_log_length, nullptr, &shader_error[0]);\n        if (result == GL_TRUE) {\n            LOG_DEBUG(Render_OpenGL, \"{}\", &shader_error[0]);\n        } else {\n            LOG_ERROR(Render_OpenGL, \"Error compiling {} shader:\\n{}\", debug_type,\n                      &shader_error[0]);\n            LOG_ERROR(Render_OpenGL, \"Shader source code:\\n{}\", source);\n        }\n    }\n}\n\nGLuint LoadShader(const char* source, GLenum type) {\n    GLuint shader_id = CreateShaderSource(source, type);\n    CompileShader(shader_id, source, type);\n    return shader_id;\n}\n\n#ifdef __ANDROID__\nnamespace {\n// Bound cache size (128 slots, each at most 1 MiB) even for a game emitting\n// unbounded shader variants. Exact keys are checked, so slot collisions miss.\nconstexpr size_t AmbientCacheLimit = 1024 * 1024;\nconstexpr unsigned AmbientCacheSlots = 128;\n\nuint64_t AmbientHash(const char* bytes, size_t count) {\n    uint64_t hash = 14695981039346656037ull;\n    for (size_t i = 0; i < count; ++i) {\n        hash ^= static_cast<unsigned char>(bytes[i]);\n        hash *= 1099511628211ull;\n    }\n    return hash;\n}\n\nbool AmbientProgramCacheKey(bool separable, const std::vector<GLuint>& shaders, std::string& key) {\n    if (!glGetProgramBinary || !glProgramBinary || !glGetShaderSource || !glProgramParameteri)\n        return false;\n    GLint formats = 0;\n    glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, &formats);\n    if (formats <= 0)\n        return false;\n    key = \"ambient-citra-program-v1:\";\n    key += separable ? '1' : '0';\n    auto append = [&](const char* bytes, size_t count) {\n        if (count > AmbientCacheLimit || key.size() > AmbientCacheLimit - count ||\n            key.size() + count > AmbientCacheLimit - 32)\n            return false;\n        key += std::to_string(count);\n        key += ':';\n        key.append(bytes, count);\n        return true;\n    };\n    for (GLenum field : {GL_VENDOR, GL_RENDERER, GL_VERSION, GL_SHADING_LANGUAGE_VERSION}) {\n        const auto* value = reinterpret_cast<const char*>(glGetString(field));\n        if (!value || !append(value, std::char_traits<char>::length(value)))\n            return false;\n    }\n    for (GLuint shader : shaders) {\n        if (!shader)\n            continue;\n        GLint length = 0, type = 0;\n        glGetShaderiv(shader, GL_SHADER_SOURCE_LENGTH, &length);\n        glGetShaderiv(shader, GL_SHADER_TYPE, &type);\n        if (length <= 1 || static_cast<size_t>(length) > AmbientCacheLimit)\n            return false;\n        std::string source(static_cast<size_t>(length), '\\0');\n        GLsizei written = 0;\n        glGetShaderSource(shader, length, &written, &source[0]);\n        if (written <= 0 || written >= length)\n            return false;\n        if (!append(reinterpret_cast<const char*>(&type), sizeof(type)) ||\n            !append(source.data(), static_cast<size_t>(written)))\n            return false;\n    }\n    return !shaders.empty();\n}\n\nstd::string AmbientProgramCachePath(const std::string& key) {\n    const auto& base = FileUtil::GetUserPath(FileUtil::UserPath::CacheDir);\n    if (base.empty() || base.front() != '/')\n        return {};\n    const auto dir = base + \"ambient-gl-program-v1/\";\n    if (!FileUtil::CreateFullPath(dir))\n        return {};\n    return dir + std::to_string(AmbientHash(key.data(), key.size()) % AmbientCacheSlots) + \".bin\";\n}\n\nbool AmbientLoadProgram(GLuint program, const std::string& path, const std::string& key) {\n    FileUtil::IOFile file(path, \"rb\");\n    if (!file.IsOpen() || file.GetSize() > AmbientCacheLimit || file.GetSize() < 24)\n        return false;\n    uint32_t header[4] = {};\n    uint64_t checksum = 0;\n    if (file.ReadArray(header, 4) != 4 || file.ReadArray(&checksum, 1) != 1 ||\n        header[0] != 0x41474331u || header[1] != key.size() || !header[2] ||\n        uint64_t(header[1]) + header[2] + 24 != file.GetSize())\n        return false;\n    std::string stored(key.size(), '\\0');\n    std::vector<char> binary(header[2]);\n    if (file.ReadArray(&stored[0], stored.size()) != stored.size() || stored != key ||\n        file.ReadArray(binary.data(), binary.size()) != binary.size() ||\n        AmbientHash(binary.data(), binary.size()) != checksum)\n        return false;\n    GLint formats_count = 0;\n    glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, &formats_count);\n    if (formats_count <= 0 || formats_count > 256)\n        return false;\n    std::vector<GLint> formats(static_cast<size_t>(formats_count));\n    glGetIntegerv(GL_PROGRAM_BINARY_FORMATS, formats.data());\n    bool supported = false;\n    for (GLint format : formats)\n        supported |= static_cast<uint32_t>(format) == header[3];\n    if (!supported)\n        return false;\n    glProgramBinary(program, header[3], binary.data(), static_cast<GLsizei>(binary.size()));\n    GLint linked = GL_FALSE;\n    glGetProgramiv(program, GL_LINK_STATUS, &linked);\n    return linked == GL_TRUE;\n}\n\nvoid AmbientStoreProgram(GLuint program, const std::string& path, const std::string& key) {\n    GLint length = 0;\n    glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, &length);\n    if (length <= 0 || uint64_t(length) + key.size() + 24 > AmbientCacheLimit)\n        return;\n    std::vector<char> binary(static_cast<size_t>(length));\n    GLsizei written = 0;\n    GLenum format = 0;\n    glGetProgramBinary(program, length, &written, &format, binary.data());\n    if (written <= 0 || written > length)\n        return;\n    const uint32_t header[] = {0x41474331u, static_cast<uint32_t>(key.size()),\n                               static_cast<uint32_t>(written), static_cast<uint32_t>(format)};\n    const uint64_t checksum = AmbientHash(binary.data(), static_cast<size_t>(written));\n    // Unique temporary file + atomic rename: interrupted writes are never cache hits.\n    std::string temp = path + \".tmp.XXXXXX\";\n    int fd = mkstemp(&temp[0]);\n    if (fd < 0)\n        return;\n    FILE* file = fdopen(fd, \"wb\");\n    if (!file) {\n        close(fd);\n        unlink(temp.c_str());\n        return;\n    }\n    const bool ok = fwrite(header, sizeof(header), 1, file) == 1 &&\n                    fwrite(&checksum, sizeof(checksum), 1, file) == 1 &&\n                    fwrite(key.data(), 1, key.size(), file) == key.size() &&\n                    fwrite(binary.data(), 1, static_cast<size_t>(written), file) == static_cast<size_t>(written);\n    const bool closed = fclose(file) == 0;\n    if (ok && closed)\n        rename(temp.c_str(), path.c_str());\n    unlink(temp.c_str());\n}\n} // namespace\n#endif\n\nstatic GLuint LoadProgramInternal(bool separable_program, const std::vector<GLuint>& shaders,\n                                  bool compile_sources) {\n    // Link the program\n    LOG_DEBUG(Render_OpenGL, \"Linking program...\");\n\n    GLuint program_id = glCreateProgram();\n#ifdef __ANDROID__\n    std::string key, cache_path;\n    if (AmbientProgramCacheKey(separable_program, shaders, key))\n        cache_path = AmbientProgramCachePath(key);\n    if (!cache_path.empty()) {\n        if (separable_program)\n            glProgramParameteri(program_id, GL_PROGRAM_SEPARABLE, GL_TRUE);\n        if (AmbientLoadProgram(program_id, cache_path, key))\n            return program_id;\n        // A rejected driver binary may leave partial state. Recreate before linking.\n        glDeleteProgram(program_id);\n        program_id = glCreateProgram();\n        glProgramParameteri(program_id, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);\n    }\n#endif\n\n    for (GLuint shader : shaders) {\n        if (shader != 0) {\n            if (compile_sources) {\n                GLint type = 0, length = 0;\n                glGetShaderiv(shader, GL_SHADER_TYPE, &type);\n                glGetShaderiv(shader, GL_SHADER_SOURCE_LENGTH, &length);\n                std::vector<char> source(length > 0 ? length : 1, '\\0');\n                glGetShaderSource(shader, static_cast<GLsizei>(source.size()), nullptr, source.data());\n                CompileShader(shader, source.data(), static_cast<GLenum>(type));\n            }\n            glAttachShader(program_id, shader);\n        }\n    }\n\n    if (separable_program) {\n        glProgramParameteri(program_id, GL_PROGRAM_SEPARABLE, GL_TRUE);\n    }\n\n    glLinkProgram(program_id);\n\n    // Check the program\n    GLint result = GL_FALSE;\n    GLint info_log_length;\n    glGetProgramiv(program_id, GL_LINK_STATUS, &result);\n    glGetProgramiv(program_id, GL_INFO_LOG_LENGTH, &info_log_length);\n\n    if (info_log_length > 1) {\n        std::vector<char> program_error(info_log_length);\n        glGetProgramInfoLog(program_id, info_log_length, nullptr, &program_error[0]);\n        if (result == GL_TRUE) {\n            LOG_DEBUG(Render_OpenGL, \"{}\", &program_error[0]);\n        } else {\n            LOG_ERROR(Render_OpenGL, \"Error linking shader:\\n{}\", &program_error[0]);\n        }\n    }\n\n    ASSERT_MSG(result == GL_TRUE, \"Shader not linked\");\n#ifdef __ANDROID__\n    if (result == GL_TRUE && !cache_path.empty())\n        AmbientStoreProgram(program_id, cache_path, key);\n#endif\n\n    for (GLuint shader : shaders) {\n        if (shader != 0) {\n            glDetachShader(program_id, shader);\n        }\n    }\n\n    return program_id;\n}\n\nGLuint LoadProgram(bool separable_program, const std::vector<GLuint>& shaders) {\n    return LoadProgramInternal(separable_program, shaders, false);\n}\n\nGLuint LoadProgramFromSources(bool separable_program,\n                              const std::vector<std::pair<GLenum, const char*>>& sources) {\n    std::vector<GLuint> shaders;\n    shaders.reserve(sources.size());\n    for (const auto& source : sources)\n        if (source.second)\n            shaders.push_back(CreateShaderSource(source.second, source.first));\n    // Shader objects carry exact source and type but have not been compiled.\n    // On a validated binary cache hit, neither shader compile nor link runs.\n    GLuint program = LoadProgramInternal(separable_program, shaders, true);\n    for (GLuint shader : shaders)\n        glDeleteShader(shader);\n    return program;\n}\n\n} // namespace GLShader\n"
    ],
    [
      "src/video_core/renderer_opengl/gl_shader_util.h",
      "#include <vector>",
      "#include <vector>\n#include <utility>"
    ],
    [
      "src/video_core/renderer_opengl/gl_shader_util.h",
      "GLuint LoadProgram(bool separable_program, const std::vector<GLuint>& shaders);",
      "GLuint LoadProgram(bool separable_program, const std::vector<GLuint>& shaders);\nGLuint LoadProgramFromSources(bool separable_program,\n                              const std::vector<std::pair<GLenum, const char*>>& sources);"
    ],
    [
      "src/video_core/renderer_opengl/gl_resource_manager.h",
      "        OGLShader vert, frag;\n        vert.Create(vert_shader, GL_VERTEX_SHADER);\n        frag.Create(frag_shader, GL_FRAGMENT_SHADER);\n        Create(false, {vert.handle, frag.handle});",
      "        if (handle != 0)\n            return;\n        handle = GLShader::LoadProgramFromSources(false, {{GL_VERTEX_SHADER, vert_shader},\n                                                         {GL_FRAGMENT_SHADER, frag_shader}});"
    ],
    [
      "src/video_core/renderer_opengl/gl_shader_manager.cpp",
      "            OGLShader shader;\n            shader.Create(source, type);\n            OGLProgram& program = boost::get<OGLProgram>(shader_or_program);\n            program.Create(true, {shader.handle});",
      "            OGLProgram& program = boost::get<OGLProgram>(shader_or_program);\n            if (program.handle == 0)\n                program.handle = GLShader::LoadProgramFromSources(true, {{type, source}});"
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
    ],
    [
      "Source/Core/VideoCommon/VideoConfig.cpp",
      "#include <optional>",
      "#include <optional>\n#ifdef __ANDROID__\n#include <sched.h>\n#endif"
    ],
    [
      "Source/Core/VideoCommon/VideoConfig.cpp",
      "static u32 GetNumAutoShaderCompilerThreads()\n{",
      "static int AmbientEffectiveShaderCores()\n{\n  int cores = std::max(1, static_cast<int>(cpu_info.num_cores));\n#ifdef __ANDROID__\n  cpu_set_t available;\n  CPU_ZERO(&available);\n  if (sched_getaffinity(0, sizeof(available), &available) == 0)\n  {\n    const int count = CPU_COUNT(&available);\n    if (count > 0)\n      cores = std::min(cores, count);\n  }\n#endif\n  return cores;\n}\n\nstatic u32 GetNumAutoShaderCompilerThreads()\n{"
    ],
    [
      "Source/Core/VideoCommon/VideoConfig.cpp",
      "std::clamp(cpu_info.num_cores - 3, 1, 4)",
      "std::clamp(AmbientEffectiveShaderCores() - 3, 1, 4)"
    ],
    [
      "Source/Core/VideoCommon/VideoConfig.cpp",
      "std::max(cpu_info.num_cores - 2, 1)",
      "std::max(AmbientEffectiveShaderCores() - 2, 1)"
    ],
    [
      "Source/Core/VideoCommon/AsyncShaderCompiler.cpp",
      "    m_worker_thread_wake.wait(pending_lock);",
      "    // Work may have been queued before a newly started worker reached wait.\n    m_worker_thread_wake.wait(pending_lock, [this] {\n      return m_exit_flag.IsSet() || !m_pending_work.empty();\n    });"
    ]
  ],
  "flycast": [
    [
      "shell/libretro/audiostream.cpp",
      "#include <vector>\r\n#include <mutex>",
      "#include <array>\r\n#include <atomic>\r\n#include <limits>"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "static std::mutex audio_buffer_mutex;\r\nstatic std::vector<int16_t> audio_buffer;\r\nstatic size_t audio_buffer_idx;\r\nstatic size_t audio_batch_frames_max;\r\nstatic bool drop_samples = true;\r\n\r\nstatic int16_t *audio_out_buffer = nullptr;\r\n",
      "// AICA is the sole producer and retro_run is the sole consumer. Use\r\n// monotonic frame counters and a power-of-two ring so wrap is well-defined.\r\nstatic constexpr uint32_t audio_ring_capacity = 32768;\r\nstatic constexpr uint32_t audio_frame_limit = (44100 / 25) * 10;\r\nstatic std::array<int16_t, audio_ring_capacity * 2> audio_ring;\r\nstatic std::atomic<uint32_t> audio_write_pos{0};\r\nstatic std::atomic<uint32_t> audio_read_pos{0};\r\nstatic std::atomic<bool> audio_enabled{false};\r\nstatic std::atomic<bool> audio_overflow{false};\r\nstatic size_t audio_batch_frames_max;"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "void retro_audio_init(void)\r\n{\r\n\tconst std::lock_guard<std::mutex> lock(audio_buffer_mutex);\r\n\r\n\t/* Worst case is 25 fps content with an audio sample rate\r\n\t * of 44.1 kHz -> 1764 stereo samples\r\n\t * But flycast can stop rendering for arbitrary lengths of\r\n\t * time, leading to multiple 'frames' worth of audio being\r\n\t * uploaded in retro_run(). We therefore require some leniency,\r\n\t * but must limit the total number of samples that can be\r\n\t * uploaded since the libretro frontend can 'hang' if too\r\n\t * many samples are sent during a single call of retro_run().\r\n\t * We therefore (arbitrarily) choose to allow up to 10 frames\r\n\t * worth of 'worst case' stereo samples... */\r\n\tsize_t audio_buffer_size = (44100 / 25) * 2 * 10;\r\n\r\n\taudio_buffer.resize(audio_buffer_size);\r\n\taudio_buffer_idx = 0;\r\n\taudio_batch_frames_max = std::numeric_limits<size_t>::max();\r\n\r\n\taudio_out_buffer = (int16_t*)malloc(audio_buffer_size * sizeof(int16_t));\r\n\r\n\tdrop_samples = false;\r\n\r\n\taudio_samples_per_frame_avg = 0.0f;\r\n\tvsync_swap_interval_last = 1;\r\n\tvsync_swap_interval_conter = 0;\r\n}\r\n\r\nvoid retro_audio_deinit(void)\r\n{\r\n\tconst std::lock_guard<std::mutex> lock(audio_buffer_mutex);\r\n\r\n\taudio_buffer.clear();\r\n\taudio_buffer_idx = 0;\r\n\r\n\tif (audio_out_buffer != nullptr)\r\n\t\tfree(audio_out_buffer);\r\n\r\n\taudio_out_buffer = nullptr;\r\n\r\n\tdrop_samples = true;\r\n\r\n\taudio_samples_per_frame_avg = 0.0f;\r\n\tvsync_swap_interval_last = 1;\r\n\tvsync_swap_interval_conter = 0;\r\n}\r\n\r\nvoid retro_audio_flush_buffer(void)\r\n{\r\n\tconst std::lock_guard<std::mutex> lock(audio_buffer_mutex);\r\n\taudio_buffer_idx = 0;\r\n\r\n\t/* We are manually 'resetting' the audio buffer\r\n\t * -> any 'drop samples' lock can be released */\r\n\tdrop_samples = false;\r\n}\r\n\r\n",
      "void retro_audio_init(void)\r\n{\r\n\t// Libretro init/deinit occur with the emulation producer stopped.\r\n\taudio_enabled.store(false, std::memory_order_release);\r\n\taudio_read_pos.store(0, std::memory_order_relaxed);\r\n\taudio_write_pos.store(0, std::memory_order_relaxed);\r\n\taudio_overflow.store(false, std::memory_order_relaxed);\r\n\taudio_batch_frames_max = std::numeric_limits<size_t>::max();\r\n\taudio_samples_per_frame_avg = 0.0f;\r\n\tvsync_swap_interval_last = 1;\r\n\tvsync_swap_interval_conter = 0;\r\n\taudio_enabled.store(true, std::memory_order_release);\r\n}\r\n\r\nvoid retro_audio_deinit(void)\r\n{\r\n\taudio_enabled.store(false, std::memory_order_release);\r\n\taudio_samples_per_frame_avg = 0.0f;\r\n\tvsync_swap_interval_last = 1;\r\n\tvsync_swap_interval_conter = 0;\r\n}\r\n\r\nvoid retro_audio_flush_buffer(void)\r\n{\r\n\t// Only the consumer changes read_pos; the producer may continue appending.\r\n\taudio_read_pos.store(audio_write_pos.load(std::memory_order_acquire), std::memory_order_release);\r\n\taudio_overflow.store(false, std::memory_order_release);\r\n}\r\n\r\n"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "void retro_audio_upload(void)\r\n{\r\n\taudio_buffer_mutex.lock();\r\n\r\n\tfor (size_t i = 0; i < audio_buffer_idx; i++)\r\n\t\taudio_out_buffer[i] = audio_buffer[i];\r\n\r\n\tsize_t num_frames = audio_buffer_idx >> 1;\r\n\taudio_buffer_idx = 0;\r\n\r\n\t/* Uploading audio 'resets' the audio buffer\r\n\t * -> any 'drop samples' lock can be released */\r\n\tdrop_samples = false;\r\n\r\n\taudio_buffer_mutex.unlock();\r\n\r\n",
      "void retro_audio_upload(void)\r\n{\r\n\tuint32_t read = audio_read_pos.load(std::memory_order_relaxed);\r\n\tconst uint32_t end = audio_write_pos.load(std::memory_order_acquire);\r\n\tif (audio_overflow.load(std::memory_order_acquire))\r\n\t{\r\n\t\t// Preserve the upstream policy: discard the entire overflowed block.\r\n\t\taudio_read_pos.store(end, std::memory_order_release);\r\n\t\taudio_overflow.store(false, std::memory_order_release);\r\n\t\treturn;\r\n\t}\r\n\tsize_t num_frames = end - read;\r\n\r\n"
    ],
    [
      "shell/libretro/audiostream.cpp",
      "\tint16_t *audio_out_buffer_ptr = audio_out_buffer;\r\n\twhile (num_frames > 0)\r\n\t{\r\n\t\tsize_t frames_to_write = (num_frames > audio_batch_frames_max) ?\r\n\t\t\t\taudio_batch_frames_max : num_frames;\r\n\t\tsize_t frames_written = audio_batch_cb(audio_out_buffer_ptr,\r\n\t\t\t\tframes_to_write);\r\n\r\n\t\tif ((frames_written < frames_to_write) &&\r\n\t\t\t (frames_written > 0))\r\n\t\t\taudio_batch_frames_max = frames_written;\r\n\r\n\t\tnum_frames -= frames_to_write;\r\n\t\taudio_out_buffer_ptr += frames_to_write << 1;\r\n\t}\r\n}\r\n\r\nvoid WriteSample(s16 r, s16 l)\r\n{\r\n\tconst std::lock_guard<std::mutex> lock(audio_buffer_mutex);\r\n\r\n\tif (drop_samples)\r\n\t\treturn;\r\n\r\n\tif (audio_buffer.size() < audio_buffer_idx + 2)\r\n\t{\r\n\t\t/* Audio buffer overflow...\r\n\t\t * > Drop any existing samples\r\n\t\t * > Drop any future samples until the next\r\n\t\t *   call of retro_audio_upload() */\r\n\t\taudio_buffer_idx = 0;\r\n\t\tdrop_samples = true;\r\n\t\treturn;\r\n\t}\r\n\r\n\taudio_buffer[audio_buffer_idx++] = l;\r\n\taudio_buffer[audio_buffer_idx++] = r;\r\n}\r\n",
      "\twhile (num_frames > 0)\r\n\t{\r\n\t\tconst uint32_t index = read & (audio_ring_capacity - 1);\r\n\t\tsize_t frames_to_write = num_frames;\r\n\t\tif (frames_to_write > audio_batch_frames_max)\r\n\t\t\tframes_to_write = audio_batch_frames_max;\r\n\t\tif (frames_to_write > audio_ring_capacity - index)\r\n\t\t\tframes_to_write = audio_ring_capacity - index;\r\n\t\tconst size_t frames_written = audio_batch_cb(audio_ring.data() + index * 2, frames_to_write);\r\n\r\n\t\tif ((frames_written < frames_to_write) && (frames_written > 0))\r\n\t\t\taudio_batch_frames_max = frames_written;\r\n\r\n\t\tnum_frames -= frames_to_write;\r\n\t\tread += static_cast<uint32_t>(frames_to_write);\r\n\t\t// The callback has returned, so the producer may reuse this segment.\r\n\t\taudio_read_pos.store(read, std::memory_order_release);\r\n\t}\r\n}\r\n\r\nvoid WriteSample(s16 r, s16 l)\r\n{\r\n\tif (!audio_enabled.load(std::memory_order_acquire) ||\r\n\t    audio_overflow.load(std::memory_order_acquire))\r\n\t\treturn;\r\n\r\n\tconst uint32_t write = audio_write_pos.load(std::memory_order_relaxed);\r\n\tconst uint32_t read = audio_read_pos.load(std::memory_order_acquire);\r\n\tif (write - read >= audio_frame_limit)\r\n\t{\r\n\t\taudio_overflow.store(true, std::memory_order_release);\r\n\t\treturn;\r\n\t}\r\n\r\n\tconst uint32_t index = (write & (audio_ring_capacity - 1)) * 2;\r\n\taudio_ring[index] = l;\r\n\taudio_ring[index + 1] = r;\r\n\taudio_write_pos.store(write + 1, std::memory_order_release);\r\n}\r\n"
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
      "    ScreenLayout layout = screenLayout.Layout();\n\n    // A tightly packed full-screen copy overwrites every pixel. Avoid clearing\n    // the same memory first, but preserve blank regions for all other layouts.\n    if ((layout == ScreenLayout::TopOnly || layout == ScreenLayout::BottomOnly) &&\n        LayoutSupportsDirectCopy(layout) &&\n        buffer.Width() == NDS_SCREEN_WIDTH && buffer.Height() == NDS_SCREEN_HEIGHT) {\n        const auto translation = layout == ScreenLayout::TopOnly\n            ? screenLayout.GetTopScreenTranslation() : screenLayout.GetBottomScreenTranslation();\n        if (translation.x == 0 && translation.y == 0) {\n            buffer.CopyDirect(layout == ScreenLayout::TopOnly ? topBuffer.data() : bottomBuffer.data(), translation);\n            return;\n        }\n    }\n\n    // The usual unscaled two-screen layouts can also cover the full output.\n    // Test the actual geometry, not only the layout enum: gaps, scaling and\n    // hybrid layouts must still receive a cleared background.\n    if (layout != ScreenLayout::TopOnly && layout != ScreenLayout::BottomOnly &&\n        !IsHybridLayout(layout) && !IsLargeScreenLayout(layout) &&\n        (!LayoutSupportsSecondaryScreenScale(layout) || screenLayout.SecondaryScreenScale() >= 100)) {\n        const auto top = screenLayout.GetTopScreenTranslation();\n        const auto bottom = screenLayout.GetBottomScreenTranslation();\n        const bool stacked = buffer.Width() == NDS_SCREEN_WIDTH &&\n            buffer.Height() == 2 * NDS_SCREEN_HEIGHT && top.x == 0 && bottom.x == 0 &&\n            ((top.y == 0 && bottom.y == NDS_SCREEN_HEIGHT) ||\n             (bottom.y == 0 && top.y == NDS_SCREEN_HEIGHT));\n        const bool sideBySide = !LayoutSupportsDirectCopy(layout) &&\n            buffer.Width() == 2 * NDS_SCREEN_WIDTH && buffer.Height() == NDS_SCREEN_HEIGHT &&\n            top.y == 0 && bottom.y == 0 &&\n            ((top.x == 0 && bottom.x == NDS_SCREEN_WIDTH) ||\n             (bottom.x == 0 && top.x == NDS_SCREEN_WIDTH));\n        if (stacked || sideBySide) {\n            CopyScreen(topBuffer.data(), top, layout);\n            CopyScreen(bottomBuffer.data(), bottom, layout);\n            return;\n        }\n    }\n\n    buffer.Clear();"
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
    ],
    [
      "Common/Thread/ThreadManager.cpp",
      "#include <atomic>",
      "#include <atomic>\n#ifdef __ANDROID__\n#include <sched.h>\n#endif"
    ],
    [
      "Common/Thread/ThreadManager.cpp",
      "\tnumComputeThreads_ = std::min(32, numRealCores * numLogicalCoresPerCpu);\n\t// Double it for the IO blocking threads.\n\tconst int numIOThreads = std::min(32, std::max(MIN_IO_BLOCKING_THREADS, numComputeThreads_) * 2);",
      "\tconst int configured = std::max(1, std::min(32, std::min(32, std::max(1, numRealCores)) *\n\t    std::min(32, std::max(1, numLogicalCoresPerCpu))));\n\tnumComputeThreads_ = configured;\n#ifdef __ANDROID__\n\t// Respect the current Android cpuset, not merely the installed CPUs.\n\t// Reserve capacity for the emulation/render threads and keep independent\n\t// IO workers so blocking storage cannot starve the compute pool.\n\tcpu_set_t available;\n\tCPU_ZERO(&available);\n\tint effective = configured;\n\tif (sched_getaffinity(0, sizeof(available), &available) == 0) {\n\t\tconst int count = CPU_COUNT(&available);\n\t\tif (count > 0) effective = std::min(configured, count);\n\t}\n\tnumComputeThreads_ = std::max(1, effective - (effective >= 4 ? 2 : 1));\n\tconst int numIOThreads = std::min(16, std::max(MIN_IO_BLOCKING_THREADS, effective));\n#else\n\tconst int numIOThreads = std::min(32, std::max(MIN_IO_BLOCKING_THREADS, numComputeThreads_) * 2);\n#endif"
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
