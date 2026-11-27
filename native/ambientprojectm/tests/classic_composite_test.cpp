// Host-side regression test for the actual patched MilkDrop classic composite.
// The same test is linked against the reference and direct-composite builds.
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include "MilkdropPreset/PresetState.hpp"
#include "MilkdropPreset/PerFrameContext.hpp"
#include "MilkdropPreset/VideoEcho.hpp"
#include "MilkdropPreset/Filters.hpp"
#include "Renderer/CopyTexture.hpp"
#include "Renderer/Texture.hpp"
#include <array>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

using namespace libprojectM;
static void check(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

struct Target {
    std::shared_ptr<Renderer::Texture> texture;
    GLuint fbo{};
    Target(int w, int h) : texture(std::make_shared<Renderer::Texture>("", w, h, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, false)) {
        glGenFramebuffers(1, &fbo);
        bind();
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture->TextureID(), 0);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "incomplete RGBA8 framebuffer");
    }
    void bind() const {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, texture->Width(), texture->Height());
    }
    ~Target() { glDeleteFramebuffers(1, &fbo); }
};

static void renderCases(std::ofstream& out) {
    for (auto size : {std::array<int, 2>{128, 96}, std::array<int, 2>{96, 128}, std::array<int, 2>{127, 91}}) {
        const auto w = size[0], h = size[1];
        Target source(w, h), flipped(w, h), composite(w, h), result(w, h);
        std::vector<unsigned char> data(w * h * 4);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            const auto i = (y * w + x) * 4;
            data[i] = (x * 3 + y * 7) % 256;
            data[i + 1] = (x * 11 + y * 2 + 17) % 256;
            data[i + 2] = ((x / 9 + y / 7) % 2) ? 231 : 29;
            data[i + 3] = 255;
        }
        source.texture->Bind(0);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
        source.texture->Unbind(0);
        Renderer::CopyTexture copy;
        MilkdropPreset::PresetState state;
        state.renderContext.viewportSizeX = w;
        state.renderContext.viewportSizeY = h;
        state.renderContext.aspectX = w < h ? float(w) / h : 1.f;
        state.renderContext.aspectY = h < w ? float(h) / w : 1.f;
        state.renderContext.invAspectX = 1.f / state.renderContext.aspectX;
        state.renderContext.invAspectY = 1.f / state.renderContext.aspectY;
        state.renderContext.time = 3.125f;
        state.hueRandomOffsets = {1.f, 2.f, 3.f, 4.f};
        MilkdropPreset::VideoEcho echo(state);
        MilkdropPreset::Filters filters(state);
        for (float alpha : {0.f, .35f, 1.f}) for (float gamma : {.75f, 1.f, 2.4f})
            for (float zoom : {.8f, 1.5f}) for (int orientation = 0; orientation < 4; ++orientation)
                for (int mode : {0, 1, 2, 4, 8, 15}) {
                    state.videoEchoAlpha = alpha;
                    state.videoEchoZoom = zoom;
                    state.gammaAdj = gamma;
                    state.videoEchoOrientation = orientation;
                    state.brighten = mode & 1;
                    state.darken = mode & 2;
                    state.solarize = mode & 4;
                    state.invert = mode & 8;
                    glDisable(GL_BLEND);
#if defined(AMBIENT_PROJECTM_DIRECT_CLASSIC_COMPOSITE)
                    state.mainTexture = source.texture;
                    result.bind();
#else
                    flipped.bind();
                    copy.Draw(source.texture, true, false);
                    state.mainTexture = flipped.texture;
                    composite.bind();
#endif
                    glClearColor(.1f, .2f, .3f, 1.f);
                    glClear(GL_COLOR_BUFFER_BIT);
                    echo.Draw();
                    filters.Draw();
#if !defined(AMBIENT_PROJECTM_DIRECT_CLASSIC_COMPOSITE)
                    result.bind();
                    copy.Draw(composite.texture, true, false);
#endif
                    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
                    check(glGetError() == GL_NO_ERROR, "GL error in classic composite");
                    out.write(reinterpret_cast<const char*>(data.data()), data.size());
                }
    }
}

static void renderBlurCases(std::ofstream& out) {
    // One instance grows from blur1 to blur3, then resizes, just as a shader can.
    MilkdropPreset::PresetState state;
    MilkdropPreset::PerFrameContext frame(state.globalMemory, &state.globalRegisters);
    frame.RegisterBuiltinVariables();
    GLuint readFbo{};
    glGenFramebuffers(1, &readFbo);
    for (auto size : {std::array<int, 2>{128, 96}, std::array<int, 2>{193, 111}}) {
        const int w = size[0], h = size[1];
        Target source(w, h);
        std::vector<unsigned char> data(w * h * 4);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            const auto i = (y * w + x) * 4;
            data[i] = (x * 3 + y * 7) % 256;
            data[i + 1] = (x * 11 + y * 2 + 17) % 256;
            data[i + 2] = ((x / 9 + y / 7) % 2) ? 231 : 29;
            data[i + 3] = 255;
        }
        source.texture->Bind(0);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
        source.texture->Unbind(0);
        for (int level = 1; level <= 3; ++level) {
            state.blurTexture.SetRequiredBlurLevel(static_cast<MilkdropPreset::BlurTexture::BlurLevel>(level));
            for (int mode = 0; mode < 2; ++mode) {
                *frame.blur1_min = mode ? .5 : .05;
                *frame.blur1_max = mode ? .5 : .95;
                *frame.blur2_min = mode ? .5 : .15;
                *frame.blur2_max = mode ? .5 : .85;
                *frame.blur3_min = mode ? .5 : .25;
                *frame.blur3_max = mode ? .5 : .75;
                *frame.blur1_edge_darken = .2;
                MilkdropPreset::BlurTexture::Values min{}, max{};
                MilkdropPreset::BlurTexture::GetSafeBlurMinMaxValues(frame, min, max);
                for (int i = 0; i < 3; ++i)
                    check(std::isfinite(min[i]) && std::isfinite(max[i]) && max[i] - min[i] > .09f, "invalid blur range");
                source.bind();
                glDisable(GL_BLEND);
                state.blurTexture.Update(*source.texture, frame);
                GLint read{}, draw{};
                glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
                glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
                check(read == GLint(source.fbo) && draw == GLint(source.fbo), "blur changed caller FBO bindings");
                state.texturedShader.Bind();
                GLint units = 0;
                state.blurTexture.Bind(units, state.texturedShader);
                const int expected = size[0] == 128 ? level : 3;
                check(units == expected, "incorrect blur pass allocation");
                int tw = w, th = h;
                for (int pass = 0; pass < expected * 2; ++pass) {
                    if (!(pass & 1) || pass < 2) {
                        tw = std::max(16, tw / 2);
                        th = std::max(16, th / 2);
                    }
                    if (!(pass & 1)) continue;
                    const int bw = ((tw + 3) / 16) * 16;
                    const int bh = ((th + 3) / 4) * 4;
                    glActiveTexture(GL_TEXTURE0 + pass / 2);
                    GLint tex{};
                    glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex);
                    check(tex != 0, "missing blur texture");
                    glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, GLuint(tex), 0);
                    check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "blur read FBO incomplete");
                    std::vector<unsigned char> pixels(bw * bh * 4);
                    glReadPixels(0, 0, bw, bh, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
                    check(glGetError() == GL_NO_ERROR, "GL error reading blur");
                    out.write(reinterpret_cast<const char*>(pixels.data()), pixels.size());
                }
                glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
                for (int i = 0; i < units; ++i) {
                    glActiveTexture(GL_TEXTURE0 + i);
                    glBindTexture(GL_TEXTURE_2D, 0);
                    glBindSampler(i, 0);
                }
                glActiveTexture(GL_TEXTURE0);
                Renderer::Shader::Unbind();
            }
        }
    }
    glDeleteFramebuffers(1, &readFbo);
}

int main(int argc, char** argv) {
    try {
        check(argc == 3, "expected classic and blur output filenames");
        EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        check(display != EGL_NO_DISPLAY && eglInitialize(display, nullptr, nullptr), "EGL initialize");
        check(eglBindAPI(EGL_OPENGL_ES_API), "EGL bind ES");
        const EGLint attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLConfig config{};
        EGLint count = 0;
        check(eglChooseConfig(display, attrs, &config, 1, &count) && count == 1, "EGL config");
        const EGLint pb[] = {EGL_WIDTH, 128, EGL_HEIGHT, 128, EGL_NONE};
        const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        EGLSurface surface = eglCreatePbufferSurface(display, config, pb);
        EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx);
        check(surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT &&
              eglMakeCurrent(display, surface, surface, context), "EGL context");
        std::ofstream out(argv[1], std::ios::binary);
        check(bool(out), "open output");
        renderCases(out);
        check(bool(out), "write output");
        out.close();
        std::ofstream blur(argv[2], std::ios::binary);
        check(bool(blur), "open blur output");
        renderBlurCases(blur);
        check(bool(blur), "write blur output");
        blur.close();
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(display, context);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
