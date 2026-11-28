/* SPDX-License-Identifier: GPL-2.0-or-later
 * Android diagnostic runner: deterministic input, real core calls, optional
 * GLES pbuffer context, and normal process exit for LLVM profile writers.
 * It does not claim to model Ambient TV display/input latency.
 */
#include <libretro.h>
#include "core_benchmark_vulkan.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <map>
#include <string>
#include <vector>

static std::string system_dir, save_dir, content_dir;
static std::map<std::string,std::string> options, defaults;
static std::map<std::string,std::string> overrides;
static bool unsupported = false, quit = false;
static uint16_t buttons = 0;
static retro_hw_render_callback hw{};
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
static bool hardware = false, reset_done = false, vulkan = false;
static BenchmarkVulkan vk;
static unsigned preferred_renderer=RETRO_HW_CONTEXT_OPENGLES3;
static bool require_vulkan=false, require_gles=false;
static uint64_t video_frames = 0, audio_frames = 0;
static unsigned width = 0, height = 0;
static void die(const char* message) {
    std::fprintf(stderr, "%s\n", message);
    std::exit(2);
}
static uint64_t now() {
    timespec t{};
    if (clock_gettime(CLOCK_MONOTONIC, &t)) die("clock_gettime failed");
    return uint64_t(t.tv_sec)*1000000000ull+t.tv_nsec;
}
static uintptr_t framebuffer() { return 0; }
static retro_proc_address_t proc(const char* name) {
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));
}
static bool create_context(retro_hw_render_callback* request) {
    if (hardware) return false;
    if(request->context_type==RETRO_HW_CONTEXT_VULKAN) {
        request->get_current_framebuffer=framebuffer;
        request->get_proc_address=nullptr;
        hw=*request;hardware=true;vulkan=true;
        return true;
    }
    int major = 0, minor = 0;
    switch (request->context_type) {
    case RETRO_HW_CONTEXT_OPENGLES2: major=2; break;
    case RETRO_HW_CONTEXT_OPENGLES3: major=3; break;
    case RETRO_HW_CONTEXT_OPENGLES_VERSION: major=request->version_major; minor=request->version_minor; break;
    default: return false;
    }
    if (major != 2 && major != 3) return false;
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display,nullptr,nullptr) ||
        !eglBindAPI(EGL_OPENGL_ES_API)) return false;
    EGLConfig cfg{};
    EGLint count=0;
    const EGLint config[] = {EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,
        major==3 ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,
        EGL_DEPTH_SIZE,request->depth?24:0,EGL_STENCIL_SIZE,request->stencil?8:0,EGL_NONE};
    if (!eglChooseConfig(display,config,&cfg,1,&count) || !count) return false;
    // Large enough for common internal resolutions; the core may use its own FBOs.
    const EGLint pbuffer[] = {EGL_WIDTH,2048,EGL_HEIGHT,2048,EGL_NONE};
    surface = eglCreatePbufferSurface(display,cfg,pbuffer);
    if (surface == EGL_NO_SURFACE) return false;
    const EGLint version[] = {EGL_CONTEXT_MAJOR_VERSION_KHR,major,
                              EGL_CONTEXT_MINOR_VERSION_KHR,minor,EGL_NONE};
    context = eglCreateContext(display,cfg,EGL_NO_CONTEXT,version);
    if (context == EGL_NO_CONTEXT || !eglMakeCurrent(display,surface,surface,context)) return false;
    request->get_current_framebuffer=framebuffer;
    request->get_proc_address=proc;
    hw=*request;
    hardware=true;
    return true;
}
static void log_message(enum retro_log_level, const char* fmt, ...) {
    va_list ap; va_start(ap,fmt); std::vfprintf(stderr,fmt,ap); va_end(ap);
}
static bool env(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *static_cast<const char**>(data)=system_dir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *static_cast<const char**>(data)=save_dir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: *static_cast<const char**>(data)=content_dir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *static_cast<bool*>(data)=true; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *static_cast<unsigned*>(data)=0; return true;
    case RETRO_ENVIRONMENT_GET_JIT_CAPABLE: {
        const long page=sysconf(_SC_PAGESIZE);
        bool capable=false;
        if(page>0) {
            void* ptr=mmap(nullptr,static_cast<size_t>(page),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            if(ptr!=MAP_FAILED){capable=mprotect(ptr,static_cast<size_t>(page),PROT_READ|PROT_EXEC)==0;munmap(ptr,static_cast<size_t>(page));}
        }
        *static_cast<bool*>(data)=capable;return true;
    }
    case RETRO_ENVIRONMENT_GET_LANGUAGE: *static_cast<unsigned*>(data)=RETRO_LANGUAGE_ENGLISH; return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: *static_cast<int*>(data)=3; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *static_cast<bool*>(data)=false; return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING: *static_cast<bool*>(data)=false; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: static_cast<retro_log_callback*>(data)->log=log_message; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        auto format=*static_cast<retro_pixel_format*>(data);
        return format==RETRO_PIXEL_FORMAT_0RGB1555 || format==RETRO_PIXEL_FORMAT_XRGB8888 ||
               format==RETRO_PIXEL_FORMAT_RGB565;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        auto* v=static_cast<const retro_variable*>(data);
        for (;v && v->key;++v) {
            std::string value=v->value?v->value:"";
            auto pos=value.find("; ");
            if (pos==std::string::npos) continue;
            value=value.substr(pos+2);
            defaults[v->key]=value.substr(0,value.find('|'));
            auto override=overrides.find(v->key);
            if (override!=overrides.end()) {
                bool valid=false;
                size_t start=0;
                for (;;) {
                    size_t end=value.find('|',start);
                    if (value.substr(start,end==std::string::npos?end:end-start)==override->second) valid=true;
                    if(end==std::string::npos)break;
                    start=end+1;
                }
                if(!valid) die("Unsupported core option value in fixture");
                options[v->key]=override->second;
            } else options[v->key]=defaults[v->key];
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* v=static_cast<retro_variable*>(data);
        if (!v->key) return false;
        auto found=options.find(v->key);
        v->value=found==options.end()?nullptr:found->second.c_str();
        return found!=options.end();
    }
    case RETRO_ENVIRONMENT_SET_HW_RENDER:
        if (!create_context(static_cast<retro_hw_render_callback*>(data))) {
            unsupported=true;
            return false;
        }
        return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE:
        return vk.configure(static_cast<const retro_hw_render_context_negotiation_interface*>(data));
    case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
        auto* info=static_cast<retro_hw_render_context_negotiation_interface*>(data);
        if(info->interface_type!=RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN)return false;
        info->interface_version=2;return true;
    }
    case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
        if(!vulkan)return false;
        *static_cast<const retro_hw_render_interface**>(data)=vk.interface();
        return *static_cast<const retro_hw_render_interface**>(data)!=nullptr;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        *static_cast<unsigned*>(data)=preferred_renderer; return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_ROTATION:
        return true;
    case RETRO_ENVIRONMENT_SHUTDOWN: quit=true; return true;
    default: return false;
    }
}
static void video(const void* data,unsigned w,unsigned h,size_t) {
    if(vulkan && !vk.frame(data==RETRO_HW_FRAME_BUFFER_VALID))die("Vulkan frame submission failed");
    if(data || video_frames) {++video_frames;width=w;height=h;}
}
static void audio(int16_t,int16_t) {++audio_frames;}
static size_t audio_batch(const int16_t*,size_t count) {audio_frames+=count;return count;}
static void poll() {}
static int16_t input(unsigned port,unsigned device,unsigned index,unsigned id) {
    if(port || index || device!=RETRO_DEVICE_JOYPAD)return 0;
    if(id==RETRO_DEVICE_ID_JOYPAD_MASK)return static_cast<int16_t>(buttons);
    return id<16 ? ((buttons>>id)&1u) : 0;
}
template<class T> static T symbol(void* lib,const char* name) {
    void* p=dlsym(lib,name);
    if(!p)die(name);
    return reinterpret_cast<T>(p);
}
static unsigned long number(const char* value,unsigned long max) {
    char* end=nullptr;
    auto n=std::strtoul(value,&end,10);
    if(!*value || *end || n>max)die("Invalid numeric argument");
    return n;
}
int main(int argc,char** argv) {
    if(argc!=9)die("usage: runner CORE CONTENT SYSTEM_DIR SAVE_DIR INPUT OPTIONS FRAMES OUTPUT_PREFIX");
    system_dir=argv[3];save_dir=argv[4];content_dir=argv[2];
    auto slash=content_dir.find_last_of('/');
    content_dir=slash==std::string::npos?".":content_dir.substr(0,slash);
    unsigned frames=static_cast<unsigned>(number(argv[7],1000000));
    if(!frames)die("No frames");
    std::vector<uint16_t> inputs;
    std::ifstream in(argv[5]);std::string line;
    while(std::getline(in,line)) {
        char* end=nullptr; unsigned long n=std::strtoul(line.c_str(),&end,16);
        if(line.empty() || *end || n>65535 || inputs.size()>=frames)die("Invalid input fixture");
        inputs.push_back(static_cast<uint16_t>(n));
    }
    if(inputs.size()!=frames)die("Input fixture must contain exactly one hex mask per frame");
    std::ifstream opt(argv[6]);
    if(!opt)die("Cannot read options fixture");
    while(std::getline(opt,line)) {
        auto p=line.find('=');
        if(p==std::string::npos || !p || !overrides.emplace(line.substr(0,p),line.substr(p+1)).second)
            die("Invalid/duplicate option");
    }
    if(const char* renderer=std::getenv("AMBIENT_BENCHMARK_RENDERER")) {
        if(std::strcmp(renderer,"vulkan")==0){preferred_renderer=RETRO_HW_CONTEXT_VULKAN;require_vulkan=true;}
        else if(std::strcmp(renderer,"gles")==0)require_gles=true;
        else if(std::strcmp(renderer,"auto")!=0)
            die("Invalid benchmark renderer preference");
    }
    void* lib=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if(!lib)die(dlerror());
#define LOAD(name) auto name=symbol<decltype(&retro_##name)>(lib,"retro_" #name)
    LOAD(api_version);LOAD(set_environment);LOAD(set_video_refresh);LOAD(set_audio_sample);
    LOAD(set_audio_sample_batch);LOAD(set_input_poll);LOAD(set_input_state);
    LOAD(init);LOAD(deinit);LOAD(get_system_info);LOAD(get_system_av_info);
    LOAD(set_controller_port_device);LOAD(load_game);LOAD(unload_game);LOAD(run);
    LOAD(serialize_size);LOAD(serialize);
#undef LOAD
    if(api_version()!=RETRO_API_VERSION)die("Unsupported libretro ABI");
    set_environment(env);set_video_refresh(video);set_audio_sample(audio);
    set_audio_sample_batch(audio_batch);set_input_poll(poll);set_input_state(input);
    init();
    retro_system_info info{};get_system_info(&info);
    retro_game_info game{};game.path=argv[2];
    std::vector<char> rom;
    if(!info.need_fullpath) {
        std::ifstream file(game.path,std::ios::binary|std::ios::ate);
        auto size=file.tellg();
        if(size<=0 || size>std::streamoff(1024ull*1024*1024))die("Invalid in-memory content size");
        rom.resize(static_cast<size_t>(size));file.seekg(0);
        if(!file.read(rom.data(),rom.size()))die("Cannot read content");
        game.data=rom.data();game.size=rom.size();
    }
    if(!load_game(&game) || unsupported)die("Core load failed or requires unsupported hardware context");
    for(const auto& item:overrides)if(!options.count(item.first))die("Unknown option in fixture");
    if((require_vulkan && !vulkan) || (require_gles && (!hardware || vulkan)))
        die("Core did not select the explicitly requested renderer");
    if(vulkan && !vk.init())die("Vulkan context negotiation failed");
    if(hardware && hw.context_reset){hw.context_reset();reset_done=true;}
    set_controller_port_device(0,RETRO_DEVICE_JOYPAD);
    retro_system_av_info av{};get_system_av_info(&av);
    if(!(av.timing.fps>=1 && av.timing.fps<=240))die("Invalid core FPS");
    std::string prefix=argv[8];
    FILE* csv=std::fopen((prefix+".csv").c_str(),"w");
    FILE* thermal=std::fopen((prefix+"-thermal.csv").c_str(),"w");
    if(!csv || !thermal)die("Cannot write benchmark files");
    std::fprintf(csv,"name,ts,dur\n");std::fprintf(thermal,"ts,status\n");
    // Android API 30+ thermal service, optional on older systems.
    void* android=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);
    using acquire_t=void*(*)();using status_t=int(*)(void*);using release_t=void(*)(void*);
    auto acquire=android?reinterpret_cast<acquire_t>(dlsym(android,"AThermal_acquireManager")):nullptr;
    auto status=android?reinterpret_cast<status_t>(dlsym(android,"AThermal_getCurrentThermalStatus")):nullptr;
    auto release=android?reinterpret_cast<release_t>(dlsym(android,"AThermal_releaseManager")):nullptr;
    void* manager=acquire && status && release?acquire():nullptr;
    // Sample per second rather than frame, including slow cores.
    uint64_t last_thermal=0;
    for(unsigned frame=0;frame<frames;++frame) {
        buttons=inputs[frame];
        uint64_t start=now();
        if(manager && (!last_thermal || start-last_thermal>=1000000000ull)) {
            int value=status(manager);
            if(value>=0 && value<=6)std::fprintf(thermal,"%llu,%d\n",(unsigned long long)start,value);
            last_thermal=start;start=now();
        }
        run();
        // Both hardware backends are synchronous for each measured frame.
        // Do not attribute deferred GPU work to a later frame or teardown.
        if(hardware && !vulkan)glFinish();
        uint64_t end=now();
        std::fprintf(csv,"AmbientTV:Libretro/retro_run,%llu,%llu\n",
                     (unsigned long long)start,(unsigned long long)(end-start));
        if(quit || unsupported || (vulkan && !vk.good()))die("Core requested early termination or GPU failed");
    }
    if(vulkan)vk.idle();
    else if(hardware)glFinish();
    uint64_t state_hash=14695981039346656037ull;
    const size_t size=serialize_size();
    bool state_ok=false;
    if(size && size<=256u*1024*1024) {
        std::vector<uint8_t> state(size);
        state_ok=serialize(state.data(),size);
        if(state_ok)for(auto byte:state){state_hash^=byte;state_hash*=1099511628211ull;}
    }
    if(std::fclose(csv) || std::fclose(thermal))die("Benchmark output failed");
    if(manager)release(manager);
    unload_game();
    if(reset_done && hw.context_destroy)hw.context_destroy();
    deinit();
    if(vulkan)vk.shutdown();
    else if(hardware) {
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        eglDestroyContext(display,context);eglDestroySurface(display,surface);eglTerminate(display);
    }
    FILE* summary=std::fopen((prefix+"-summary.json").c_str(),"w");
    if(!summary)die("Cannot write benchmark summary");
    std::fprintf(summary,"{\"frames\":%u,\"fps\":%.9g,\"video_frames\":%llu,\"audio_frames\":%llu,"
                         "\"width\":%u,\"height\":%u,\"hardware\":%s,\"renderer\":\"%s\",\"state_hash\":\"%016llx\",\"state_valid\":%s}\n",
                 frames,av.timing.fps,(unsigned long long)video_frames,(unsigned long long)audio_frames,
                 width,height,hardware?"true":"false",vulkan?"vulkan-sync-headless":hardware?"gles-sync-pbuffer":"software",(unsigned long long)state_hash,state_ok?"true":"false");
    if(std::fclose(summary))die("Summary output failed");
    // Deliberately keep the DSO loaded until normal process exit so the
    // compiler instrumentation runtime can emit LLVM_PROFILE_FILE.
    return 0;
}
