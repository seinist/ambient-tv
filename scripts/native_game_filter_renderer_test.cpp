// SPDX-License-Identifier: GPL-3.0-or-later
// Real production Video/Renderer integration on surfaceless EGL. Only the explicit
// allocation-failure regression withholds texture storage; all rendering stays real.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>
#include "video.h"
#include "renderers/es3/es3utils.h"
#include <limits>
#include "libretro/libretro-common/include/libretro.h"
#include "game_filter_test_presets.h"

int ambientShaderFallbacks = 0;
namespace {
bool rejectShaderTargetStorage = false;
unsigned rejectedShaderTargets = 0;
unsigned validIntegerUniformWrites = 0;
unsigned validPairUniformWrites = 0;
unsigned validFloatUniformWrites = 0;
unsigned linkedPrograms = 0;
}
extern "C" void __real_glLinkProgram(GLuint);
extern "C" void __wrap_glLinkProgram(GLuint program) {
    ++linkedPrograms;
    __real_glLinkProgram(program);
}
extern "C" void __real_glTexStorage2D(GLenum, GLsizei, GLenum, GLsizei, GLsizei);
extern "C" void __wrap_glTexStorage2D(GLenum target, GLsizei levels, GLenum format,
                                      GLsizei width, GLsizei height) {
    if (rejectShaderTargetStorage) {
        // Simulate a failed target allocation without allocating a huge image. A
        // real framebuffer attached to this storage-less texture is incomplete,
        // so the production GL error/fallback path must handle it.
        ++rejectedShaderTargets;
        return;
    }
    __real_glTexStorage2D(target, levels, format, width, height);
}
extern "C" void __real_glUniform1i(GLint, GLint);
extern "C" void __wrap_glUniform1i(GLint location, GLint value) {
    if (location >= 0) ++validIntegerUniformWrites;
    __real_glUniform1i(location, value);
}
extern "C" void __real_glUniform2f(GLint, GLfloat, GLfloat);
extern "C" void __wrap_glUniform2f(GLint location, GLfloat x, GLfloat y) {
    if (location >= 0) ++validPairUniformWrites;
    __real_glUniform2f(location, x, y);
}
extern "C" void __real_glUniform1f(GLint, GLfloat);
extern "C" void __wrap_glUniform1f(GLint location, GLfloat value) {
    if (location >= 0) ++validFloatUniformWrites;
    __real_glUniform1f(location, value);
}
using namespace libretrodroid;
namespace {
constexpr int WIDTH = 96, HEIGHT = 64, SOURCE = 32;
const std::string VERTEX = R"(attribute vec4 VertexCoord; attribute vec2 TexCoord;
varying highp vec2 uv; void main() { gl_Position=VertexCoord; uv=TexCoord; })";
const std::string COPY = R"(precision highp float; uniform highp sampler2D Texture;
varying highp vec2 uv; void main() { gl_FragColor=vec4(texture2D(Texture,uv).rgb,1.0); })";
const std::string INVERT = R"(precision highp float; uniform highp sampler2D Texture;
varying highp vec2 uv; void main() { gl_FragColor=vec4(vec3(1.0)-texture2D(Texture,uv).rgb,1.0); })";
ShaderManager::Pass copyPass() { return {VERTEX,COPY,false,1.0f}; }
ShaderManager::Config custom(std::vector<ShaderManager::Pass> passes) {
    return {ShaderManager::Type::SHADER_CUSTOM,{},std::move(passes)};
}
ShaderManager::Config pixel() { return {ShaderManager::Type::SHADER_DEFAULT,{{"AMBIENT_NEAREST","1"}}}; }
void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}
struct Context {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    explicit Context(int version) {
        auto platform = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
        require(platform != nullptr,"EGL_EXT_platform_base required by host test");
        display = platform(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,nullptr);
        require(eglInitialize(display,nullptr,nullptr),"Cannot initialize surfaceless EGL");
        const EGLint attributes[] = {EGL_RENDERABLE_TYPE,version == 2 ? EGL_OPENGL_ES2_BIT : EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig config; EGLint count = 0;
        require(eglChooseConfig(display,attributes,&config,1,&count) && count == 1,"Cannot choose EGL config");
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION,version,EGL_NONE};
        context = eglCreateContext(display,config,EGL_NO_CONTEXT,contextAttributes);
        const EGLint surfaceAttributes[] = {EGL_WIDTH,WIDTH,EGL_HEIGHT,HEIGHT,EGL_NONE};
        surface = eglCreatePbufferSurface(display,config,surfaceAttributes);
        require(context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE &&
            eglMakeCurrent(display,surface,surface,context),"Cannot create GLES context");
        std::printf("Renderer: %s / %s\n",glGetString(GL_RENDERER),glGetString(GL_VERSION));
    }
    ~Context() {
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display,surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display,context);
        eglTerminate(display);
    }
};
using Rgba = std::array<unsigned char,4>;
Rgba at(int x,int y) {
    Rgba result{};glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,result.data());return result;
}
void expect(Rgba actual,Rgba wanted,const std::string& point) {
    if(actual != wanted) throw std::runtime_error(point+" got="+std::to_string(actual[0])+","+
        std::to_string(actual[1])+","+std::to_string(actual[2])+","+std::to_string(actual[3]));
}
void expectBorders(const std::string& checkpoint) {
    std::vector<unsigned char> image(WIDTH*HEIGHT*4);
    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,image.data());
    for(int y=0;y<HEIGHT;++y) for(int x=0;x<WIDTH;++x) if(x<16||x>=80) {
        auto p=&image[(y*WIDTH+x)*4];
        if(p[0]||p[1]||p[2]||p[3]!=255)
            throw std::runtime_error(checkpoint+": colored/transparent letterbox pixel at "+std::to_string(x)+","+std::to_string(y));
    }
    require(glGetError()==GL_NO_ERROR,checkpoint+": GL error");
}
void upload(Video& video,int width=SOURCE,int height=SOURCE) {
    std::vector<uint32_t> pixels(width*height,0xffff0000);
    video.onNewFrame(pixels.data(),width,height,width*sizeof(uint32_t));
}
Video::RenderingOptions options(int version,bool hardware=false) {
    return {hardware,hardware ? 64u : SOURCE,hardware ? 64u : SOURCE,false,false,version,RETRO_PIXEL_FORMAT_XRGB8888};
}
void setLayout(Video& video) { video.updateScreenSize(WIDTH,HEIGHT);video.updateAspectRatio(1.0f); }
void switchPaused(Video& video,const ShaderManager::Config& shader,const std::string& label,Rgba center) {
    const int fallbacks=ambientShaderFallbacks;
    video.updateShaderType(shader);video.renderFrame(true);
    require(ambientShaderFallbacks==fallbacks,label+": unexpectedly used fallback");
    expectBorders(label);expect(at(48,32),center,label+" center");
}
void verifySwitches(int version) {
    Video video(options(version),pixel(),false,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);video.renderFrame(true);
    auto inverted=custom({{VERTEX,INVERT,false,1.0f},copyPass()});
    switchPaused(video,inverted,"single to multipass without new frame",{0,255,255,255});
    switchPaused(video,pixel(),"multipass to single without new frame",{255,0,0,255});
    auto three=custom({copyPass(),{VERTEX,INVERT,false,2.0f},copyPass()});
    switchPaused(video,three,"changed intermediate dimensions",{0,255,255,255});
    for(int i=0;i<30;++i) switchPaused(video,i%2 ? pixel() : inverted,"repeated paused change",i%2 ? Rgba{255,0,0,255}:Rgba{0,255,255,255});
    // The entire window clear must be independent of shared GL state.
    glEnable(GL_SCISSOR_TEST);glScissor(0,0,1,1);glColorMask(GL_FALSE,GL_TRUE,GL_FALSE,GL_FALSE);
    switchPaused(video,inverted,"scissor and channel-mask isolation",{0,255,255,255});
    glEnable(GL_SCISSOR_TEST);glScissor(0,0,1,1);glColorMask(GL_FALSE,GL_TRUE,GL_FALSE,GL_FALSE);
    video.renderBlackFrame();expect(at(48,32),{0,0,0,255},"black-frame insertion clear");expectBorders("black frame");
    upload(video,16,24);switchPaused(video,three,"source resolution change",{0,255,255,255});
    std::puts("PASS: paused filter switches, source preservation, border clear, resizing");
}
void verifyFallbacks(int version) {
    Video video(options(version),pixel(),false,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);
    int before=ambientShaderFallbacks;
    video.updateShaderType(custom({copyPass(),{VERTEX,"invalid fragment",false,1.0f}}));video.renderFrame(true);
    require(ambientShaderFallbacks==before+1,"bad fragment did not use safe fallback");
    expectBorders("compile fallback");expect(at(48,32),{255,0,0,255},"fallback retains source");
    before=ambientShaderFallbacks;
    auto impossible=custom({{VERTEX,COPY,false,100000.0f},copyPass()});
    video.updateShaderType(impossible);video.renderFrame(true);
    require(ambientShaderFallbacks==before+1,"invalid target dimensions not rejected");
    expectBorders("allocation fallback");expect(at(48,32),{255,0,0,255},"allocation fallback source");
    before=ambientShaderFallbacks;
    auto prefixed=custom({{VERTEX,INVERT,false,1.0f},{VERTEX,"invalid fragment",false,1.0f}});
    prefixed.params["AMBIENT_PREPEND"]="1";
    video.updateShaderType(prefixed);video.renderFrame(true);
    require(ambientShaderFallbacks==before+1,"failed prefixed chain did not fall back");
    expectBorders("required prepass fallback");expect(at(48,32),{0,255,255,255},"color conversion prepass survives fallback");
    switchPaused(video,pixel(),"recovery after failures",{255,0,0,255});
    std::puts("PASS: shader/allocation fallback and required color-conversion retention");
}
void verifyFloatAndAliases(int version) {
    Video video(options(version),pixel(),false,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);
    const std::string encode=R"(precision highp float; varying highp vec2 uv; uniform highp sampler2D Texture;
        void main(){gl_FragColor=vec4(texture2D(Texture,uv).r-3.0,0.123456,4.0,1.0);})";
    const std::string check=R"(precision highp float; varying highp vec2 uv; uniform highp sampler2D Texture;
        void main(){vec3 p=texture2D(Texture,uv).rgb;gl_FragColor=(p.r < -1.0 && p.b > 3.0 && abs(p.g-0.123456)<0.000001)
        ? vec4(0,1,0,1) : vec4(1,0,0,1);})";
    const int before=ambientShaderFallbacks;
    video.updateShaderType(custom({{VERTEX,encode,false,1.0f,true},{VERTEX,check,false,1.0f}}));video.renderFrame(true);
    expectBorders("float target");
    if(version==2) {
        require(ambientShaderFallbacks==before+1,"GLES2 cannot silently quantize float targets");
        expect(at(48,32),{255,0,0,255},"GLES2 float fallback");
    } else {
        require(ambientShaderFallbacks==before,"GLES3 float chain fell back");
        expect(at(48,32),{0,255,0,255},"signed full-precision float target");
    }
    const std::string original=R"(precision highp float; varying highp vec2 uv; uniform highp sampler2D PassPrev5Texture;
        uniform highp vec2 PassPrev5TextureSize; uniform highp vec2 PassPrev5InputSize;
        void main(){gl_FragColor=all(equal(PassPrev5TextureSize,vec2(32))) && all(equal(PassPrev5InputSize,vec2(32)))
        ? vec4(texture2D(PassPrev5Texture,uv).rgb,1) : vec4(0,1,0,1);})";
    std::vector<ShaderManager::Pass> chain={copyPass(),{VERTEX,INVERT,false,1.0f},copyPass(),copyPass(),copyPass(),{VERTEX,original,false,1.0f}};
    switchPaused(video,custom(chain),"PassPrev5 normalized original",{255,0,0,255});
    chain.insert(chain.begin(),{VERTEX,INVERT,false,1.0f});auto prefix=custom(chain);prefix.params["AMBIENT_PREPEND"]="1";
    switchPaused(video,prefix,"PassPrev5 behind required prepass",{0,255,255,255});
    std::puts("PASS: float storage, previous-pass sampler and size bindings, prefix offsets");
}
void verifySamplerBindings(int version) {
    Video video(options(version),pixel(),false,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);
    // Distinct colors verify that the nonzero previous-pass unit still points
    // at pass zero, not the immediately preceding (inverted) input.
    const std::string aliases=R"(precision highp float; varying highp vec2 uv;
        uniform highp sampler2D Texture; uniform highp sampler2D PassPrev2Texture;
        void main(){gl_FragColor=vec4(texture2D(Texture,uv).g,
            texture2D(PassPrev2Texture,uv).r,0,1);})";
    const auto chain=custom({copyPass(),{VERTEX,INVERT,false,1.0f},{VERTEX,aliases,false,1.0f}});
    auto first=validIntegerUniformWrites;
    switchPaused(video,chain,"initial sampler assignments",{255,255,0,255});
    require(validIntegerUniformWrites-first>=4,"initial assignments must reach all active sampler uniforms");
    const auto cached=validIntegerUniformWrites;
    video.renderFrame(true);expectBorders("cached samplers");
    expect(at(48,32),{255,255,0,255},"cached previous-pass sampling");
    require(validIntegerUniformWrites==cached,"unchanged software draw rewrote sampler uniforms");
    std::vector<uint32_t> blue(16*24,0xff0000ff);
    video.onNewFrame(blue.data(),16,24,16*sizeof(uint32_t));video.renderFrame(true);
    expectBorders("cached samplers after source resize");
    expect(at(48,32),{255,0,0,255},"samplers must bind new source and resized targets");
    require(validIntegerUniformWrites==cached,"source resize invalidated immutable sampler units");
    switchPaused(video,pixel(),"switch away from cached chain",{0,0,255,255});
    first=validIntegerUniformWrites;
    switchPaused(video,chain,"relinked sampler assignments",{255,0,0,255});
    require(validIntegerUniformWrites-first>=4,"relinked programs must initialise sampler units");
    std::puts("PASS: software sampler assignment reuse, source resize, program relink");
}
void verifyIntensityUniforms(int version) {
    Video video(options(version),pixel(),false,0,true,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);
    const std::string strength=R"(precision highp float; varying highp vec2 uv;
        uniform highp sampler2D Texture;
        uniform highp float ambientFilterIntensity;
        void main(){gl_FragColor=vec4(texture2D(Texture,uv).r*ambientFilterIntensity,0,0,1);})";
    const auto pass = [&](float amount) {
        return ShaderManager::Pass{VERTEX,strength,false,1.0f,false,amount};
    };
    auto cfg=custom({pass(0.5f),pass(0.5f)});
    cfg.params["AMBIENT_PREPEND"]="1";
    auto changed=cfg;
    changed.customPasses[1].ambientIntensity=1.5f;
    require(!(cfg==changed) && cfg.sameShaderStructure(changed),"uniform-only structure comparison");
    auto different=changed;
    different.customPasses[1].linear=true;
    require(!cfg.sameShaderStructure(different),"sampling change must rebuild");
    auto disabled=changed;
    disabled.customPasses[1].ambientIntensity=0.0f;
    require(!cfg.sameShaderStructure(disabled),"uniform opt-in change must rebuild");
    require(ShaderManager::Pass::normalizedIntensity(100.f)==1.5f &&
            ShaderManager::Pass::normalizedIntensity(0.f)==0.f &&
            ShaderManager::Pass::normalizedIntensity(std::nanf(""))==0.f,"bounded intensity metadata");

    const auto fallbacks=ambientShaderFallbacks;
    video.updateShaderType(cfg);video.renderFrame(true);
    auto pixel=at(48,32);
    require(std::abs(int(pixel[0])-64)<=1 && pixel[1]==0 && pixel[2]==0 && pixel[3]==255,
            "both independent prefix/filter uniform values must be initialized");
    const auto links=linkedPrograms;
    video.updateShaderType(changed);
    video.prepareShaderAndGetSensorUsage();
    video.renderFrame(false); // duplicate-frame skipping must not hide live parameter changes
    pixel=at(48,32);
    require(std::abs(int(pixel[0])-192)<=1 && pixel[1]==0 && pixel[2]==0 && pixel[3]==255,
            "intensity-only update must change pixels while retaining prefix strength");
    require(linkedPrograms==links,"intensity-only update must not relink any program");
    const auto scalars=validFloatUniformWrites;
    video.renderFrame(true);
    require(validFloatUniformWrites==scalars,"unchanged software intensity must not re-upload");
    expectBorders("intensity-only change");
    video.updateShaderType(different);video.renderFrame(true);
    require(linkedPrograms>links,"sampling changes must still rebuild");
    require(ambientShaderFallbacks==fallbacks,"valid intensity must not select fallback");
    std::puts("PASS: per-pass live intensity, independent prefix, no relink, paused dirty redraw");
}

void verifyGeometryUniforms(int version) {
    Video video(options(version),pixel(),false,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);
    const std::string geometry=R"(precision highp float;
        uniform highp vec2 TextureSize, OrigTextureSize, OrigInputSize, InputSize, OutputSize;
        uniform highp vec2 PassPrev2TextureSize, PassPrev2InputSize;
        uniform highp float screenDensity;
        bool same(vec2 a,vec2 b){return all(lessThan(abs(a-b),vec2(0.01)));}
        void main(){
            bool valid = same(TextureSize,OrigTextureSize) && same(OrigTextureSize,OrigInputSize)
                && same(InputSize,OrigInputSize) && same(PassPrev2TextureSize,2.0*OrigInputSize)
                && same(PassPrev2InputSize,PassPrev2TextureSize) && same(OutputSize,vec2(OutputSize.x));
            gl_FragColor=vec4(valid?1.0:0.0,OutputSize.x/255.0,screenDensity*32.0/255.0,1.0);
        })";
    auto doubled=copyPass();doubled.scale=2.0f;
    const auto chain=custom({doubled,copyPass(),{VERTEX,geometry,false,1.0f}});
    auto pairs=validPairUniformWrites, scalars=validFloatUniformWrites;
    switchPaused(video,chain,"initial geometry uniforms",{255,64,64,255});
    require(validPairUniformWrites-pairs==7 && validFloatUniformWrites-scalars==1,
            "all active geometry uniforms must be initialized");
    pairs=validPairUniformWrites;scalars=validFloatUniformWrites;
    for(int redraw=0;redraw<4;++redraw) {
        video.renderFrame(true);expect(at(48,32),{255,64,64,255},"stable geometry draw");
    }
    require(validPairUniformWrites==pairs && validFloatUniformWrites==scalars,
            "unchanged software geometry rewrote uniforms");
    upload(video,16,16);video.renderFrame(true);
    expect(at(48,32),{255,64,128,255},"source and intermediate target resize");
    require(validPairUniformWrites-pairs==6 && validFloatUniformWrites-scalars==1,
            "source resize must update six changed pairs and density, not stable output size");
    pairs=validPairUniformWrites;scalars=validFloatUniformWrites;
    video.updateScreenSize(WIDTH,48);video.renderFrame(true);
    expect(at(48,24),{255,48,96,255},"paused screen resize");
    require(validPairUniformWrites-pairs==1 && validFloatUniformWrites-scalars==1,
            "screen resize must update output size and density only");
    pairs=validPairUniformWrites;scalars=validFloatUniformWrites;
    video.renderFrame(true);
    require(validPairUniformWrites==pairs && validFloatUniformWrites==scalars,
            "resized geometry should settle after one draw");
    setLayout(video);
    switchPaused(video,pixel(),"switch away from geometry chain",{255,0,0,255});
    pairs=validPairUniformWrites;scalars=validFloatUniformWrites;
    switchPaused(video,chain,"relinked geometry uniforms",{255,64,128,255});
    require(validPairUniformWrites-pairs==7 && validFloatUniformWrites-scalars==1,
            "a new linked program must initialize all active geometry uniforms");
    std::puts("PASS: software geometry uniform reuse, intermediate target/source/screen resize and relink");
}
void verifyPresets(int version) {
    Video video(options(version),pixel(),false,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);upload(video);
    for(auto preset:gameFilterTestPresets()) {
        if(version==2 && preset.name=="SCALEFX") continue; // Its unsupported-format fallback is tested above.
        switchPaused(video,custom(preset.passes),preset.name+" full upstream chain",{255,0,0,255});
    }
    std::puts("PASS: complete authentic shader presets compile, link, render and switch");
}
void verifyPaddedHardware() {
    Video video(options(3,true),pixel(),true,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);video.bindHWContext();
    glBindFramebuffer(GL_FRAMEBUFFER,video.getCurrentFramebuffer());
    glDisable(GL_SCISSOR_TEST);glClearColor(1,0,1,1);glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);glScissor(0,0,SOURCE,SOURCE/2);glClearColor(1,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
    glScissor(0,SOURCE/2,SOURCE,SOURCE/2);glClearColor(0,0,1,1);glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);glFinish();video.bindMainContext();
    video.onNewFrame(RETRO_HW_FRAME_BUFFER_VALID,SOURCE,SOURCE,0);video.renderFrame(true);
    const Rgba top=at(40,8),bottom=at(40,56);
    require(top!=bottom,"hardware fixture must distinguish vertical origin");
    for(auto preset:gameFilterTestPresets()) {
        const int before=ambientShaderFallbacks;
        video.updateShaderType(custom(preset.passes));video.renderFrame(true);
        require(ambientShaderFallbacks==before,preset.name+": padded hardware fallback");
        expectBorders(preset.name+" hardware");expect(at(40,8),top,preset.name+" upper source crop");
        expect(at(40,56),bottom,preset.name+" lower source crop");
        expect(at(18,8),top,preset.name+" left source edge");expect(at(77,56),bottom,preset.name+" right source edge");
    }
    video.updateShaderType(custom({copyPass()}));video.renderFrame(true);
    const auto beforeRedraw=validIntegerUniformWrites;
    video.renderFrame(true);
    require(validIntegerUniformWrites>beforeRedraw,"shared hardware path must restore sampler bindings");
    expectBorders("hardware sampler redraw");expect(at(40,8),top,"hardware sampler redraw source");
    const std::string dimensions=R"(precision highp float; varying highp vec2 uv;
        uniform highp sampler2D Texture; uniform highp vec2 TextureSize;
        uniform highp float screenDensity;
        void main(){gl_FragColor=TextureSize.x>0.0 && screenDensity>0.0
            ? vec4(texture2D(Texture,uv).rgb,1.0) : vec4(0,1,0,1);})";
    video.updateShaderType(custom({{VERTEX,dimensions,false,1.0f}}));video.renderFrame(true);
    const auto pairsBefore=validPairUniformWrites, floatsBefore=validFloatUniformWrites;
    video.renderFrame(true);
    require(validPairUniformWrites>pairsBefore && validFloatUniformWrites>floatsBefore,
            "shared hardware path must restore geometry uniforms");
    expectBorders("hardware geometry redraw");expect(at(40,8),top,"hardware geometry redraw source");
    std::puts("PASS: padded hardware source, edge clamping, vertical origin, paused changes");
}
void verifyHardwareAllocationFallback() {
    struct RejectStorage {
        RejectStorage() { rejectShaderTargetStorage = true; }
        ~RejectStorage() { rejectShaderTargetStorage = false; }
    };
    for (const int whiteNits : {0, 203}) {
        Video video(options(3, true), pixel(), true, 0, false, false, Rect(0, 0, 1, 1), {});
        setLayout(video);
        video.setSdrToHdr(whiteNits);
        video.bindHWContext();
        glBindFramebuffer(GL_FRAMEBUFFER, video.getCurrentFramebuffer());
        glDisable(GL_SCISSOR_TEST);
        glClearColor(1, 0, 1, 0); glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST); glScissor(0, 0, SOURCE, SOURCE);
        glClearColor(1, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST); glFinish(); video.bindMainContext();
        video.onNewFrame(RETRO_HW_FRAME_BUFFER_VALID, SOURCE, SOURCE, 0);
        video.renderFrame(true);
        const auto original = at(48, 32);
        require(original[3] == 255, "hardware output must be opaque before allocation failure");
        const int before = ambientShaderFallbacks;
        const unsigned rejectedBefore = rejectedShaderTargets;
        {
            RejectStorage reject;
            video.updateShaderType(custom({{VERTEX, INVERT, false, 1.0f}, copyPass()}));
            video.renderFrame(true);
            require(rejectedShaderTargets >= rejectedBefore + 2,
                "must reject both requested normalization and ordinary safe-default target");
            require(ambientShaderFallbacks == before + 1, "hardware target failure must choose safe fallback");
            expectBorders("hardware allocation-free fallback");
            expect(at(48, 32), original, "hardware fallback preserves SDR/PQ source presentation");
            expect(at(18, 8), original, "hardware fallback does not sample padded magenta texels");
        }
        video.setSdrToHdr(0);
        auto prefix = custom({{VERTEX, INVERT, false, 1.0f}, copyPass()});
        prefix.params["AMBIENT_PREPEND"] = "1";
        video.updateShaderType(prefix); video.renderFrame(true);
        expect(at(48, 32), {0, 255, 255, 255}, "required prefix baseline");
        bool refusedRawFallback = false;
        {
            RejectStorage reject;
            prefix.customPasses.back().fragment = "invalid optional fragment";
            video.updateShaderType(prefix);
            try { video.renderFrame(true); }
            catch (const std::runtime_error&) { refusedRawFallback = true; }
        }
        require(refusedRawFallback, "failed mandatory prefix must never be dropped for raw fallback");
        switchPaused(video, pixel(), "recovery after hardware target rejection", {255, 0, 0, 255});
    }
    std::puts("PASS: hardware target failure, allocation-free SDR/PQ fallback, mandatory prefix retention");
}
}
// Validate the production ingress and raw capture on both real GLES contexts.
void verifySoftwareFrameIngress(int version) {
    Video video(options(version),pixel(),true,0,false,false,Rect(0,0,1,1),{});
    setLayout(video);
    constexpr unsigned w = 4, h = 3;
    std::vector<uint8_t> data((w*4+3)*h, 0x7e);
    for(unsigned y=0;y<h;++y) for(unsigned x=0;x<w;++x) {
        auto* px=data.data()+y*(w*4+3)+x*4;
        px[0]=uint8_t(20+x);px[1]=uint8_t(40+y);px[2]=uint8_t(80+x+y);px[3]=255;
    }
    video.onNewFrame(data.data(),w,h,w*4+3);
    int cw=0,ch=0;
    auto raw=video.captureRawFrame(cw,ch);
    require(cw==int(w)&&ch==int(h)&&raw.size()==w*h*4,"valid raw capture");
    for(unsigned y=0;y<h;++y) for(unsigned x=0;x<w;++x) {
        auto i=(y*w+x)*4;
        require(raw[i]==80+x+y&&raw[i+1]==40+y&&raw[i+2]==20+x&&raw[i+3]==255,
                "software capture channel order, padding or orientation changed");
    }
    video.onNewFrame(data.data(),w,h,w*4-1);
    video.onNewFrame(data.data(),0,h,w*4+3);
    video.onNewFrame(data.data(),w,h,std::numeric_limits<size_t>::max());
    int aw=0,ah=0;
    auto after=video.captureRawFrame(aw,ah);
    require(aw==cw&&ah==ch&&after==raw,"invalid upload changed retained frame");
}

void verifyTargetTransitionReuse(int version) {
    ShaderManager::Chain chain;
    chain.passes={copyPass(),copyPass(),copyPass()};
    auto old=ES3Utils::buildShaderPasses(32,32,chain,version>=3);
    require(old->size()==2,"initial two targets");
    auto first=old->at(0)->texture,second=old->at(1)->texture;
    chain.passes[1].fragment=INVERT;
    auto next=ES3Utils::buildShaderPasses(32,32,chain,version>=3,old.get());
    require(next->at(0)->texture==first&&next->at(1)->texture==second,
            "compatible targets were reallocated");
    require(!old->at(0)&&!old->at(1),"target ownership not moved");
    chain.passes[1].scale=2.0f;
    auto third=ES3Utils::buildShaderPasses(32,32,chain,version>=3,next.get());
    require(third->at(0)->texture==first&&third->at(1)->texture!=second,
            "partial target reuse failed");
    const auto retained=third->at(1)->texture;
    chain.passes[1].scale=100.0f;
    bool rejected=false;
    try { auto bad=ES3Utils::buildShaderPasses(32,32,chain,version>=3,third.get()); }
    catch(const std::runtime_error&) { rejected=true; }
    require(rejected&&third->at(0)->texture==first&&third->at(1)->texture==retained,
            "failed preflight consumed old targets");
}

int main(int argc,char** argv) {
    const int version=argc>1 && std::string(argv[1])=="--gles2" ? 2:3;
    try {
        Context context(version);
        verifySoftwareFrameIngress(version);verifyTargetTransitionReuse(version);verifySwitches(version);verifyFallbacks(version);verifyFloatAndAliases(version);verifySamplerBindings(version);verifyIntensityUniforms(version);verifyGeometryUniforms(version);verifyPresets(version);
        if(version==3) { verifyPaddedHardware(); verifyHardwareAllocationFallback(); }
        std::printf("PASS: GLES%d production renderer regression\n",version);return 0;
    } catch(const std::exception& error) {
        std::fprintf(stderr,"FAIL GLES%d: %s\n",version,error.what());return 1;
    }
}
