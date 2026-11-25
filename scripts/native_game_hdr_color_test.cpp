// SPDX-License-Identifier: GPL-3.0-or-later
// Numerical color regression using the exact production GLES renderer.
#define main ambient_filter_fixture_main
#include "native_game_filter_renderer_test.cpp"
#undef main
#include <cmath>

namespace {
void uploadColor(Video& video, Rgba color) {
    uint32_t pixel = 0xff000000u | (uint32_t(color[0]) << 16) | (uint32_t(color[1]) << 8) | color[2];
    std::vector<uint32_t> pixels(SOURCE * SOURCE, pixel);
    video.onNewFrame(pixels.data(), SOURCE, SOURCE, SOURCE * sizeof(uint32_t));
}

Rgba expectedPq(Rgba sdr, double whiteNits) {
    std::array<double, 3> linear{};
    for (size_t channel = 0; channel < 3; ++channel) {
        const double value = sdr[channel] / 255.0;
        linear[channel] = value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
    }
    const std::array<double, 3> rec2020 = {
        0.6274 * linear[0] + 0.3293 * linear[1] + 0.0433 * linear[2],
        0.0691 * linear[0] + 0.9195 * linear[1] + 0.0114 * linear[2],
        0.0164 * linear[0] + 0.0880 * linear[1] + 0.8956 * linear[2],
    };
    Rgba encoded{0, 0, 0, 255};
    for (size_t channel = 0; channel < 3; ++channel) {
        const double p = std::pow(std::max(0.0, rec2020[channel] * whiteNits / 10000.0), 2610.0 / 16384.0);
        const double value = std::pow((3424.0 / 4096.0 + (2413.0 / 128.0) * p) /
            (1.0 + (2392.0 / 128.0) * p), 2523.0 / 32.0);
        encoded[channel] = static_cast<unsigned char>(std::lround(value * 255.0));
    }
    return encoded;
}

void closeColor(Rgba actual, Rgba wanted, int tolerance, const std::string& label) {
    for (size_t channel = 0; channel < 4; ++channel) {
        if (std::abs(int(actual[channel]) - int(wanted[channel])) > tolerance)
            throw std::runtime_error(label + " channel " + std::to_string(channel) +
                " got=" + std::to_string(actual[0]) + "," + std::to_string(actual[1]) + "," +
                std::to_string(actual[2]) + "," + std::to_string(actual[3]) +
                " expected=" + std::to_string(wanted[0]) + "," + std::to_string(wanted[1]) + "," +
                std::to_string(wanted[2]) + "," + std::to_string(wanted[3]));
    }
}

void verifyNativeHdrColors(int version) {
    using Type = ShaderManager::Type;
    const std::vector<std::pair<std::string, ShaderManager::Config>> filters = {
        {"Pixel", pixel()},
        {"Sharp", {Type::SHADER_SHARP}},
        {"CUT", {Type::SHADER_UPSCALE_CUT}},
        {"CUT2", {Type::SHADER_UPSCALE_CUT2}},
        {"CUT3", {Type::SHADER_UPSCALE_CUT3}},
        {"CRT", {Type::SHADER_CRT}},
        {"LCD", {Type::SHADER_LCD}},
    };
    const std::vector<Rgba> colors = {
        {128, 128, 128, 255}, {64, 64, 64, 255}, {238, 238, 238, 255},
        {0, 255, 0, 255}, {0, 0, 255, 255}, {255, 0, 0, 255}, {193, 71, 142, 255},
    };
    Video video(options(version), pixel(), false, 0, false, false, Rect(0,0,1,1), {});
    setLayout(video);
    for (const auto& filter : filters) {
        for (auto source : colors) {
            const int before = ambientShaderFallbacks;
            video.setSdrToHdr(0);
            video.updateShaderType(filter.second);
            uploadColor(video, source);
            video.renderFrame(true);
            const auto sdr = at(48,32);
            if (filter.first != "CRT" && filter.first != "LCD")
                closeColor(sdr, source, 2, filter.first + " must preserve a flat source color");
            for (int white : {100, 203, 400}) {
                video.setSdrToHdr(white);
                video.renderFrame(true);
                closeColor(at(48,32), expectedPq(sdr, white), 3, filter.first + " HDR output");
                expectBorders(filter.first + " HDR borders");
            }
            require(ambientShaderFallbacks == before, filter.first + " unexpectedly fell back during HDR output");
        }
        std::printf("PASS: %s SDR color and numeric HDR10 output\n", filter.first.c_str());
    }
}

void verifyUpscalerHdrColors(int version) {
    Video video(options(version), pixel(), false, 0, false, false, Rect(0,0,1,1), {});
    setLayout(video);
    for (auto preset : gameFilterTestPresets()) {
        if (version == 2 && preset.name == "SCALEFX") continue;
        const int before = ambientShaderFallbacks;
        for (Rgba source : {Rgba{128,128,128,255}, Rgba{0,255,0,255}, Rgba{193,71,142,255}}) {
            video.setSdrToHdr(0);
            video.updateShaderType(custom(preset.passes));
            uploadColor(video, source);
            video.renderFrame(true);
            const auto sdr = at(48,32);
            closeColor(sdr, source, 2, preset.name + " flat SDR source");
            video.setSdrToHdr(203);
            video.renderFrame(true);
            closeColor(at(48,32), expectedPq(sdr, 203), 3, preset.name + " HDR output");
            expectBorders(preset.name + " HDR borders");
        }
        require(ambientShaderFallbacks == before, preset.name + " unexpectedly fell back during HDR output");
    }
    std::puts("PASS: authentic upscalers preserve SDR colors and emit correct PQ/BT.2020");
}

void verifyConvertedOriginalColors(int version) {
    Video video(options(version), pixel(), false, 0, false, false, Rect(0,0,1,1), {});
    setLayout(video);
    const Rgba source{52, 181, 109, 255};
    const Rgba converted{203, 74, 146, 255};
    // A distinguishable conversion before CUT's analysis catches a subtle
    // error: re-binding the raw core as original after an HDR/effect prefix.
    for (auto type : {ShaderManager::Type::SHADER_UPSCALE_CUT2, ShaderManager::Type::SHADER_UPSCALE_CUT3}) {
        ShaderManager::Config filter{type, {{"AMBIENT_PREPEND", "1"}}, {{VERTEX, INVERT, false, 1.0f}}};
        const int before = ambientShaderFallbacks;
        video.setSdrToHdr(0);
        video.updateShaderType(filter);
        uploadColor(video, source);
        video.renderFrame(true);
        closeColor(at(48,32), converted, 2, "CUT must use the converted original frame");
        video.setSdrToHdr(203);
        video.renderFrame(true);
        closeColor(at(48,32), expectedPq(converted, 203), 3, "CUT converted source with HDR output");
        expectBorders("converted original with HDR output");
        require(ambientShaderFallbacks == before, "Converted original used an unexpected fallback");
    }
    std::puts("PASS: native multi-pass filters preserve the converted original behind HDR/effect prefixes");
}
}

int main(int argc, char** argv) {
    const int version = argc > 1 && std::string(argv[1]) == "--gles2" ? 2 : 3;
    try {
        Context context(version);
        verifyNativeHdrColors(version);
        verifyConvertedOriginalColors(version);
        verifyUpscalerHdrColors(version);
        std::printf("PASS: GLES%d real SDR/HDR color regression\n", version);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL GLES%d HDR color: %s\n", version, error.what());
        return 1;
    }
}
