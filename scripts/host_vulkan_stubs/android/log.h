// SPDX-License-Identifier: GPL-3.0-or-later
// Host-only Android logging shim; production VulkanSlangPipeline is unchanged.
#pragma once

#include <cstdarg>
#include <cstdio>

#define ANDROID_LOG_WARN 5

inline int __android_log_print(int, const char* tag, const char* format, ...) {
    std::fprintf(stderr, "%s: ", tag);
    va_list args;
    va_start(args, format);
    const int result = std::vfprintf(stderr, format, args);
    va_end(args);
    std::fputc('\n', stderr);
    return result;
}
