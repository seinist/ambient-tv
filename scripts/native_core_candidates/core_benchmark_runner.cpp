/* SPDX-License-Identifier: GPL-2.0-or-later
 * Android diagnostic runner: deterministic input, real core calls, optional
 * GLES pbuffer context, and normal process exit for LLVM profile writers.
 * It does not claim to model Ambient TV display/input latency.
 */
#include <libretro.h>
#include "core_benchmark_vulkan.h"
#include "core_benchmark_input.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <fstream>
#include <limits>
#include <map>
#include <sstream>
#include <mutex>
#include <string>
#include <vector>

static std::string system_dir, save_dir, content_dir;
static std::map<std::string,std::string> options, defaults;
static std::map<std::string,std::string> overrides;
static std::atomic<bool> unsupported{false}, quit{false};
static std::atomic<const BenchmarkInput*> current_input{nullptr};
static unsigned input_ports=1;
static std::map<unsigned,unsigned> controller_devices;
static retro_keyboard_event_t keyboard_callback=nullptr;
static retro_hw_render_callback hw{};
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
static bool hardware = false, reset_done = false, vulkan = false;
static int gles_major=0;
static std::vector<uint8_t> hardware_pixels;
static BenchmarkVulkan vk;
static unsigned preferred_renderer=RETRO_HW_CONTEXT_OPENGLES3;
static bool require_vulkan=false, require_gles=false;
static uint64_t video_frames = 0, fresh_frames = 0, duplicate_frames = 0;
static std::atomic<uint64_t> audio_frames{0};
static unsigned width = 0, height = 0;
static retro_pixel_format pixel_format=RETRO_PIXEL_FORMAT_0RGB1555;
static bool verify_output=false;
static retro_system_av_info current_av{};
static unsigned rotation=0;
static bool valid_timing(const retro_system_timing& timing) {
    return std::isfinite(timing.fps) && timing.fps>=1 && timing.fps<=240 &&
           std::isfinite(timing.sample_rate) && timing.sample_rate>0 && timing.sample_rate<=384000;
}
static bool valid_geometry(const retro_game_geometry& geometry) {
    return geometry.base_width && geometry.base_height &&
           geometry.base_width<=16384 && geometry.base_height<=16384 &&
           std::isfinite(geometry.aspect_ratio) && geometry.aspect_ratio>=0;
}
static constexpr uint64_t hash_offset=14695981039346656037ull;
static uint64_t video_hash=hash_offset, audio_hash=hash_offset;
static std::mutex audio_hash_mutex;
static void hash_value(uint64_t& hash,uint32_t value,unsigned bytes) {
    for(unsigned i=0;i<bytes;++i) {
        hash^=static_cast<uint8_t>(value>>(i*8));
        hash*=1099511628211ull;
    }
}
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
    hardware=true;gles_major=major;
    return true;
}
static void log_message(enum retro_log_level, const char* fmt, ...) {
    va_list ap; va_start(ap,fmt); std::vfprintf(stderr,fmt,ap); va_end(ap);
}
template<class Definition> static void register_options(const Definition* def) {
    if(!def)die("Missing core option definitions");
    for(;def->key;++def) {
        if(!def->values[0].value)die("Core option has no values");
        const std::string fallback=def->default_value?def->default_value:def->values[0].value;
        auto requested=overrides.find(def->key);
        const std::string selected=requested==overrides.end()?fallback:requested->second;
        bool valid=false;
        for(const auto& item:def->values) {
            if(!item.value)break;
            if(selected==item.value)valid=true;
        }
        if(!valid)die("Unsupported core option value in fixture");
        defaults[def->key]=fallback;
        options[def->key]=selected;
    }
}
static bool env(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *static_cast<const char**>(data)=system_dir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *static_cast<const char**>(data)=save_dir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: *static_cast<const char**>(data)=content_dir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *static_cast<bool*>(data)=true; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *static_cast<unsigned*>(data)=2; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS: *static_cast<unsigned*>(data)=input_ports; return true;
    case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
        keyboard_callback=data?static_cast<const retro_keyboard_callback*>(data)->callback:nullptr;
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        register_options(static_cast<const retro_core_option_definition*>(data));return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
        register_options(static_cast<const retro_core_options_intl*>(data)->us);return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        register_options(static_cast<const retro_core_options_v2*>(data)->definitions);return false;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
        register_options(static_cast<const retro_core_options_v2_intl*>(data)->us->definitions);return false;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        return true; // no UI; values are still available
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
        if(format!=RETRO_PIXEL_FORMAT_0RGB1555 && format!=RETRO_PIXEL_FORMAT_XRGB8888 &&
           format!=RETRO_PIXEL_FORMAT_RGB565)return false;
        pixel_format=format;
        return true;
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
    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
        const auto* geometry=static_cast<const retro_game_geometry*>(data);
        if(!geometry || !valid_geometry(*geometry))return false;
        current_av.geometry.base_width=geometry->base_width;
        current_av.geometry.base_height=geometry->base_height;
        current_av.geometry.aspect_ratio=geometry->aspect_ratio;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
        const auto* av=static_cast<const retro_system_av_info*>(data);
        if(!av || !valid_geometry(av->geometry) || !valid_timing(av->timing))return false;
        current_av=*av;return true;
    }
    case RETRO_ENVIRONMENT_SET_ROTATION:
        if(!data || *static_cast<const unsigned*>(data)>3)return false;
        rotation=*static_cast<const unsigned*>(data);return true;
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        return true;
    case RETRO_ENVIRONMENT_SHUTDOWN: quit=true; return true;
    default: return false;
    }
}
static void read_gles_pixels(unsigned w,unsigned h) {
    EGLint surface_width=0,surface_height=0;
    if(!w || !h || !eglQuerySurface(display,surface,EGL_WIDTH,&surface_width) ||
       !eglQuerySurface(display,surface,EGL_HEIGHT,&surface_height) ||
       surface_width<=0 || surface_height<=0 || w>unsigned(surface_width) || h>unsigned(surface_height))
        die("Invalid GLES readback dimensions");
    GLint fb=0,alignment=0,pack_buffer=0,row_length=0,skip_rows=0,skip_pixels=0,read_buffer=0;
    const GLenum target=gles_major>=3?GL_READ_FRAMEBUFFER:GL_FRAMEBUFFER;
    glGetIntegerv(gles_major>=3?GL_READ_FRAMEBUFFER_BINDING:GL_FRAMEBUFFER_BINDING,&fb);
    glGetIntegerv(GL_PACK_ALIGNMENT,&alignment);
    glBindFramebuffer(target,0);glPixelStorei(GL_PACK_ALIGNMENT,1);
    if(gles_major>=3) {
        glGetIntegerv(GL_READ_BUFFER,&read_buffer);
        glReadBuffer(GL_BACK);
        glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING,&pack_buffer);
        glGetIntegerv(GL_PACK_ROW_LENGTH,&row_length);
        glGetIntegerv(GL_PACK_SKIP_ROWS,&skip_rows);glGetIntegerv(GL_PACK_SKIP_PIXELS,&skip_pixels);
        glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
        glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
    }
    hardware_pixels.resize(size_t(w)*h*4);
    glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,hardware_pixels.data());
    GLenum error=glGetError();
    if(gles_major>=3) {
        glReadBuffer(static_cast<GLenum>(read_buffer));
        glBindBuffer(GL_PIXEL_PACK_BUFFER,static_cast<GLuint>(pack_buffer));
        glPixelStorei(GL_PACK_ROW_LENGTH,row_length);glPixelStorei(GL_PACK_SKIP_ROWS,skip_rows);glPixelStorei(GL_PACK_SKIP_PIXELS,skip_pixels);
    }
    glPixelStorei(GL_PACK_ALIGNMENT,alignment);glBindFramebuffer(target,static_cast<GLuint>(fb));
    if(error!=GL_NO_ERROR)die("GLES readback failed");
}
static void video(const void* data,unsigned w,unsigned h,size_t pitch) {
    VkFormat vk_format=VK_FORMAT_UNDEFINED;
    VkComponentMapping components{};
    const bool hw_frame=data==RETRO_HW_FRAME_BUFFER_VALID;
    if(vulkan && !vk.frame(hw_frame,w,h,verify_output?&hardware_pixels:nullptr,&vk_format,&components))
        die("Vulkan frame submission/readback failed");
    if(verify_output && hardware) {
        hash_value(video_hash,w,4);hash_value(video_hash,h,4);
        hash_value(video_hash,hw_frame?1u:0u,1);
        if(data && !hw_frame)die("Unexpected software callback in hardware mode");
        if(hw_frame) {
            if(!vulkan)read_gles_pixels(w,h);
            hash_value(video_hash,static_cast<uint32_t>(vk_format),4);
            const auto layout=vulkan?benchmark_pixel_layout(vk_format):BenchmarkPixelLayout{4,3,0};
            if(!layout.bytes || hardware_pixels.size()%layout.bytes)die("Invalid GPU pixel layout");
            if(vulkan) {
                for(auto component:{components.r,components.g,components.b,components.a})
                    hash_value(video_hash,static_cast<uint32_t>(component),4);
            }
            for(size_t i=0;i<hardware_pixels.size();i+=layout.bytes) {
                if(layout.color_mask) {
                    uint32_t packed=0;
                    std::memcpy(&packed,hardware_pixels.data()+i,layout.bytes);
                    hash_value(video_hash,packed&layout.color_mask,layout.bytes);
                } else {
                    for(uint32_t byte=0;byte<layout.color_bytes;++byte)
                        hash_value(video_hash,hardware_pixels[i+byte],1);
                }
            }
        }
    }
    if(verify_output && !hardware) {
        // Hash visible pixels only, excluding row padding and unused bits.
        if(data==RETRO_HW_FRAME_BUFFER_VALID)die("Unexpected hardware frame");
        hash_value(video_hash,w,4);hash_value(video_hash,h,4);
        hash_value(video_hash,static_cast<uint32_t>(pixel_format),4);
        hash_value(video_hash,data?1u:0u,1);
        if(data) {
            const unsigned bytes=pixel_format==RETRO_PIXEL_FORMAT_XRGB8888?4:2;
            if(!w || !h || w>16384 || h>16384 || pitch<size_t(w)*bytes ||
               (h>1 && pitch>(std::numeric_limits<size_t>::max()-size_t(w)*bytes)/(h-1)))
                die("Invalid software frame geometry");
            const auto* row=static_cast<const uint8_t*>(data);
            for(unsigned y=0;y<h;++y) {
                for(unsigned x=0;x<w;++x) {
                    uint32_t value=0;
                    std::memcpy(&value,row+size_t(x)*bytes,bytes);
                    if(bytes==4)value&=0xffffffu;
                    else if(pixel_format==RETRO_PIXEL_FORMAT_0RGB1555)value&=0x7fffu;
                    hash_value(video_hash,value,bytes);
                }
                if(y+1<h)row+=pitch;
            }
        }
    }
    if(data || video_frames) {
        ++video_frames;
        if(data)++fresh_frames;else ++duplicate_frames;
        width=w;height=h;
    }
}
static void audio(int16_t left,int16_t right) {
    if(verify_output) {
        std::lock_guard<std::mutex> lock(audio_hash_mutex);
        hash_value(audio_hash,static_cast<uint16_t>(left),2);
        hash_value(audio_hash,static_cast<uint16_t>(right),2);
    }
    audio_frames.fetch_add(1,std::memory_order_relaxed);
}
static size_t audio_batch(const int16_t* samples,size_t count) {
    if(verify_output) {
        if(count>std::numeric_limits<size_t>::max()/2 || (count && !samples))
            die("Invalid audio batch");
        std::lock_guard<std::mutex> lock(audio_hash_mutex);
        for(size_t i=0;i<count*2;++i)hash_value(audio_hash,static_cast<uint16_t>(samples[i]),2);
    }
    audio_frames.fetch_add(count,std::memory_order_relaxed);return count;
}
static void poll() {}
static int16_t input(unsigned port,unsigned device,unsigned index,unsigned id) {
    const auto* frame=current_input.load(std::memory_order_acquire);
    return frame?frame->state(port,device,index,id):0;
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
    if(argc!=9 && argc!=10)die("usage: runner CORE CONTENT SYSTEM_DIR SAVE_DIR INPUT OPTIONS FRAMES OUTPUT_PREFIX [WARMUP_FRAMES]");
    system_dir=argv[3];save_dir=argv[4];content_dir=argv[2];
    auto slash=content_dir.find_last_of('/');
    content_dir=slash==std::string::npos?".":content_dir.substr(0,slash);
    unsigned frames=static_cast<unsigned>(number(argv[7],1000000));
    if(!frames)die("No frames");
    const unsigned warmup=argc==10?static_cast<unsigned>(number(argv[9],frames)):0u;
    if(warmup>=frames)die("No measurement frames");
    std::vector<BenchmarkInput> inputs;
    std::ifstream in(argv[5]);std::string line;
    while(std::getline(in,line)) {
        if(inputs.size()>=frames)die("Too many input frames");
        try {
            inputs.push_back(BenchmarkInput::parse(line));
            input_ports=std::max(input_ports,inputs.back().ports);
        } catch(const std::invalid_argument& error){die(error.what());}
    }
    if(inputs.size()!=frames)die("Input fixture must contain exactly one record per frame");
    std::ifstream opt(argv[6]);
    if(!opt)die("Cannot read options fixture");
    while(std::getline(opt,line)) {
        auto p=line.find('=');
        if(p==std::string::npos || !p)die("Invalid option");
        const auto key=line.substr(0,p);
        if(key.rfind("@controller.",0)==0) {
            const unsigned port=static_cast<unsigned>(number(key.c_str()+12,7));
            const unsigned device=static_cast<unsigned>(number(line.c_str()+p+1,65535));
            if(!controller_devices.emplace(port,device).second)die("Duplicate controller option");
            input_ports=std::max(input_ports,port+1);
        } else if(!overrides.emplace(key,line.substr(p+1)).second)die("Duplicate option");
    }
    verify_output=std::getenv("AMBIENT_BENCHMARK_VERIFY_OUTPUT")!=nullptr;
    const char* state_input=std::getenv("AMBIENT_BENCHMARK_STATE_INPUT");
    const bool export_state=std::getenv("AMBIENT_BENCHMARK_EXPORT_STATE")!=nullptr;
    if((state_input || export_state) && !verify_output)die("State interchange requires correctness mode");
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
    LOAD(serialize_size);LOAD(serialize);LOAD(unserialize);
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
    for(unsigned port=0;port<input_ports;++port) {
        const auto configured=controller_devices.find(port);
        set_controller_port_device(port,configured==controller_devices.end()?RETRO_DEVICE_JOYPAD:configured->second);
    }
    if(state_input) {
        std::ifstream saved(state_input,std::ios::binary|std::ios::ate);
        const auto size=saved.tellg();
        if(size<=0 || size>std::streamoff(256u*1024*1024))die("Invalid interchange state size");
        std::vector<uint8_t> data(static_cast<size_t>(size));
        saved.seekg(0);
        if(!saved.read(reinterpret_cast<char*>(data.data()),size) || !unserialize(data.data(),data.size()))
            die("Core rejected interchange save state");
    }
    retro_system_av_info av{};get_system_av_info(&av);
    if(!valid_timing(av.timing) || !valid_geometry(av.geometry))die("Invalid core A/V info");
    current_av=av;
    // Discard any output produced during load/reset/unserialize, before the
    // deterministic input replay starts. The actual timing path stays unchanged.
    if(verify_output) {
        video_hash=hash_offset;
        std::lock_guard<std::mutex> lock(audio_hash_mutex);
        audio_hash=hash_offset;
    }
    std::string prefix=argv[8];
    FILE* csv=std::fopen((prefix+".csv").c_str(),"w");
    FILE* thermal=std::fopen((prefix+"-thermal.csv").c_str(),"w");
    if(!csv || !thermal)die("Cannot write benchmark files");
    std::fprintf(csv,"name,ts,dur,fps,sample_rate\n");std::fprintf(thermal,"ts,status\n");
    // Android API 30+ thermal service, optional on older systems.
    void* android=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);
    using acquire_t=void*(*)();using status_t=int(*)(void*);using release_t=void(*)(void*);
    auto acquire=android?reinterpret_cast<acquire_t>(dlsym(android,"AThermal_acquireManager")):nullptr;
    auto status=android?reinterpret_cast<status_t>(dlsym(android,"AThermal_getCurrentThermalStatus")):nullptr;
    auto release=android?reinterpret_cast<release_t>(dlsym(android,"AThermal_releaseManager")):nullptr;
    void* manager=acquire && status && release?acquire():nullptr;
    // Sample per second rather than frame, including slow cores.
    uint64_t last_thermal=0;
    uint64_t initial_video=0,initial_fresh=0,initial_duplicate=0,initial_audio=0;
    uint64_t av_hash=hash_offset;
    auto hash_double=[&](double value) {
        uint64_t bits=0;std::memcpy(&bits,&value,sizeof(bits));
        hash_value(av_hash,static_cast<uint32_t>(bits),4);
        hash_value(av_hash,static_cast<uint32_t>(bits>>32),4);
    };
    const BenchmarkInput neutral{};
    for(unsigned frame=0;frame<frames;++frame) {
        // Snapshot immediately before the first timed measurement frame.
        // Leave the cumulative counters intact: a duplicate of the last
        // warm-up frame is still a valid duplicate in the measured window.
        if(frame==warmup) {
            initial_video=video_frames;initial_fresh=fresh_frames;
            initial_duplicate=duplicate_frames;
            initial_audio=audio_frames.load(std::memory_order_relaxed);
        }
        current_input.store(&inputs[frame],std::memory_order_release);
        if(keyboard_callback)inputs[frame].keyboard_events(frame?inputs[frame-1]:neutral,keyboard_callback);
        const auto timing=current_av.timing;
        const auto before_video=video_frames;
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
        // A core can submit work without calling video_refresh on a frame.
        // Include that work in the same frame's timing, not the next sample.
        if(vulkan && before_video==video_frames)vk.idle();
        uint64_t end=now();
        std::fprintf(csv,"AmbientTV:Libretro/retro_run,%llu,%llu,%.17g,%.17g\n",
                     (unsigned long long)start,(unsigned long long)(end-start),
                     timing.fps,timing.sample_rate);
        if(frame>=warmup) {
            hash_double(timing.fps);hash_double(timing.sample_rate);
            hash_value(av_hash,current_av.geometry.base_width,4);
            hash_value(av_hash,current_av.geometry.base_height,4);
            hash_double(current_av.geometry.aspect_ratio);
            hash_value(av_hash,rotation,4);
        }
        if(quit || unsupported || (vulkan && !vk.good()))die("Core requested early termination or GPU failed");
    }
    if(vulkan)vk.idle();
    else if(hardware)glFinish();
    if(manager) {
        const auto ts=now();
        const int value=status(manager);
        if(value>=0 && value<=6)std::fprintf(thermal,"%llu,%d\n",(unsigned long long)ts,value);
    }
    if(vulkan && !vk.good())die("GPU failed during final synchronization");
    // Snapshot workload before serialization or shutdown can emit callbacks.
    const uint64_t captured_video=video_frames-initial_video, captured_fresh=fresh_frames-initial_fresh,
                   captured_duplicate=duplicate_frames-initial_duplicate,
                   captured_audio=audio_frames.load(std::memory_order_relaxed)-initial_audio;
    const unsigned captured_width=width,captured_height=height;
    uint64_t captured_audio_hash=0;
    {std::lock_guard<std::mutex> lock(audio_hash_mutex);captured_audio_hash=audio_hash;}
    const uint64_t captured_video_hash=video_hash;
    uint64_t state_hash=hash_offset;
    const size_t size=serialize_size();
    bool state_ok=false;
    if(size && size<=256u*1024*1024) {
        std::vector<uint8_t> state(size);
        state_ok=serialize(state.data(),size);
        if(state_ok)for(auto byte:state){state_hash^=byte;state_hash*=1099511628211ull;}
        if(export_state && state_ok) {
            std::ofstream saved(prefix+"-state.bin",std::ios::binary);
            if(!saved.write(reinterpret_cast<const char*>(state.data()),state.size()) || !saved.flush())
                die("Cannot export interchange save state");
        }
    }
    if(export_state && !state_ok)die("Core does not support requested state interchange");
    if(verify_output) {
        FILE* proof=std::fopen((prefix+"-output.json").c_str(),"w");
        if(!proof)die("Cannot write correctness proof");
        std::fprintf(proof,"{\"audio_hash\":\"%016llx\",\"video_hash\":",
                     static_cast<unsigned long long>(captured_audio_hash));
        std::fprintf(proof,"\"%016llx\"",static_cast<unsigned long long>(captured_video_hash));
        std::fprintf(proof,",\"state_loaded\":%s}\n",state_input?"true":"false");
        if(std::fclose(proof))die("Cannot write correctness proof");
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
    std::fprintf(summary,"{\"frames\":%u,\"warmup_frames\":%u,\"measured_frames\":%u,\"fps\":%.9g,\"video_frames\":%llu,\"fresh_frames\":%llu,\"duplicate_frames\":%llu,\"audio_frames\":%llu,"
                         "\"width\":%u,\"height\":%u,\"hardware\":%s,\"renderer\":\"%s\",\"state_hash\":\"%016llx\",\"state_valid\":%s,\"av_hash\":\"%016llx\",\"sample_rate\":%.17g}\n",
                 frames,warmup,frames-warmup,av.timing.fps,(unsigned long long)captured_video,(unsigned long long)captured_fresh,
                 (unsigned long long)captured_duplicate,(unsigned long long)captured_audio,
                 captured_width,captured_height,hardware?"true":"false",vulkan?"vulkan-sync-headless":hardware?"gles-sync-pbuffer":"software",(unsigned long long)state_hash,state_ok?"true":"false",
                  (unsigned long long)av_hash,av.timing.sample_rate);
    if(std::fclose(summary))die("Summary output failed");
    // Deliberately keep the DSO loaded until normal process exit so the
    // compiler instrumentation runtime can emit LLVM_PROFILE_FILE.
    return 0;
}
