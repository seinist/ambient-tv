// Host-side regression test for the actual patched MilkDrop classic composite.
// The same test is linked against the reference and direct-composite builds.
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include "MilkdropPreset/PresetState.hpp"
#include "MilkdropPreset/VideoEcho.hpp"
#include "MilkdropPreset/Filters.hpp"
#include "Renderer/CopyTexture.hpp"
#include "Renderer/Texture.hpp"
#include <array>
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

int main(int argc, char** argv) {
    try {
        check(argc == 2, "expected output filename");
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
