#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <dlfcn.h>
#include <cstring>
#include <string>
#include <sstream>
#include <vector>

// No mandatory Vulkan dependency: devices without a loader retain normal playback.
namespace {
template<class T> T symbol(void *lib, const char *name) { return reinterpret_cast<T>(dlsym(lib, name)); }
std::string quote(const char *s) {
    std::string out = "\"";
    for (; *s; ++s) {
        if (*s == '"' || *s == '\\') out += '\\';
        if (static_cast<unsigned char>(*s) >= 32) out += *s;
    }
    return out + '"';
}
bool has(const std::vector<VkExtensionProperties> &ext, const char *name) {
    for (const auto &e : ext) if (!strcmp(e.extensionName, name)) return true;
    return false;
}
struct Probe {
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    PFN_vkGetInstanceProcAddr get = lib ? symbol<PFN_vkGetInstanceProcAddr>(lib, "vkGetInstanceProcAddr") : nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    ANativeWindow *window = nullptr;
    template<class T> T fn(const char *name) { return reinterpret_cast<T>(get(instance, name)); }
    ~Probe() {
        if (surface) fn<PFN_vkDestroySurfaceKHR>("vkDestroySurfaceKHR")(instance, surface, nullptr);
        if (instance) fn<PFN_vkDestroyInstance>("vkDestroyInstance")(instance, nullptr);
        if (window) ANativeWindow_release(window);
        if (lib) dlclose(lib);
    }
    std::string run(JNIEnv *env, jobject javaSurface) {
        if (!get) return "{\"reason\":\"loader\"}";
        auto enumerate = fn<PFN_vkEnumerateInstanceExtensionProperties>("vkEnumerateInstanceExtensionProperties");
        uint32_t count = 0;
        if (enumerate(nullptr, &count, nullptr) != VK_SUCCESS) return "{}";
        std::vector<VkExtensionProperties> ext(count);
        if (enumerate(nullptr, &count, ext.data()) != VK_SUCCESS) return "{}";
        if (!has(ext, VK_KHR_SURFACE_EXTENSION_NAME) || !has(ext, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME))
            return "{\"reason\":\"android_surface\"}";
        uint32_t version = VK_API_VERSION_1_0;
        auto getVersion = fn<PFN_vkEnumerateInstanceVersion>("vkEnumerateInstanceVersion");
        if (getVersion) getVersion(&version);
        // VLC's bundled libplacebo 5 supports 1.1 + timeline semaphores;
        // MPV's newer libplacebo requires 1.2 (checked per backend in Kotlin).
        if (version < VK_API_VERSION_1_1) return "{\"reason\":\"vulkan_1_1\",\"version\":" + std::to_string(version) + "}";
        std::vector<const char *> extensions{VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        if (has(ext, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME))
            extensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Ambient TV capability probe";
        app.apiVersion = version;
        VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        info.pApplicationInfo = &app; info.enabledExtensionCount = extensions.size(); info.ppEnabledExtensionNames = extensions.data();
        if (fn<PFN_vkCreateInstance>("vkCreateInstance")(&info, nullptr, &instance) != VK_SUCCESS) return "{}";
        if (javaSurface) {
            window = ANativeWindow_fromSurface(env, javaSurface);
            if (!window) return "{\"reason\":\"native_window\"}";
            VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
            si.window = window;
            if (fn<PFN_vkCreateAndroidSurfaceKHR>("vkCreateAndroidSurfaceKHR")(instance, &si, nullptr, &surface) != VK_SUCCESS) return "{\"reason\":\"surface_create\"}";
        }
        auto devices = fn<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
        if (devices(instance, &count, nullptr) != VK_SUCCESS) return "{}";
        std::vector<VkPhysicalDevice> physical(count);
        if (devices(instance, &count, physical.data()) != VK_SUCCESS) return "{}";
        std::string unsupported = "{\"reason\":\"device\"}";
        for (auto device : physical) {
            VkPhysicalDeviceProperties props;
            fn<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties")(device, &props);
            auto failure = [&](const char *why) { return "{\"device\":" + quote(props.deviceName) + ",\"version\":" + std::to_string(props.apiVersion) + ",\"reason\":" + quote(why) + "}"; };
            unsupported = failure("device_features");
            if (props.apiVersion < VK_API_VERSION_1_1 || props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) continue;
            auto devExt = fn<PFN_vkEnumerateDeviceExtensionProperties>("vkEnumerateDeviceExtensionProperties");
            if (devExt(device, nullptr, &count, nullptr) != VK_SUCCESS) continue;
            ext.resize(count);
            if (devExt(device, nullptr, &count, ext.data()) != VK_SUCCESS || !has(ext, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
            if (props.apiVersion < VK_API_VERSION_1_2 && !has(ext, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
                unsupported = failure("timeline_semaphore"); continue;
            }
            auto queues = fn<PFN_vkGetPhysicalDeviceQueueFamilyProperties2>("vkGetPhysicalDeviceQueueFamilyProperties2");
            queues(device, &count, nullptr);
            std::vector<VkQueueFamilyProperties2> families(count);
            std::vector<VkQueueFamilyVideoPropertiesKHR> video(count);
            bool videoQueue = has(ext, VK_KHR_VIDEO_QUEUE_EXTENSION_NAME) && has(ext, VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME);
            for (uint32_t i = 0; i < count; ++i) {
                families[i].sType = VK_STRUCTURE_TYPE_QUEUE_FAMILY_PROPERTIES_2;
                video[i].sType = VK_STRUCTURE_TYPE_QUEUE_FAMILY_VIDEO_PROPERTIES_KHR;
                if (videoQueue) families[i].pNext = &video[i];
            }
            queues(device, &count, families.data());
            int graphics = -1; VkVideoCodecOperationFlagsKHR operations = 0;
            for (uint32_t i = 0; i < count; ++i) {
                const auto &q = families[i].queueFamilyProperties;
                if (!q.queueCount) continue;
                VkBool32 present = VK_TRUE;
                if (surface && fn<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>("vkGetPhysicalDeviceSurfaceSupportKHR")(device, i, surface, &present) != VK_SUCCESS) present = VK_FALSE;
                if ((q.queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) graphics = i;
                if (q.queueFlags & VK_QUEUE_VIDEO_DECODE_BIT_KHR) operations |= video[i].videoCodecOperations;
            }
            if (graphics < 0) { unsupported = failure("present_queue"); continue; }
            VkPhysicalDeviceTimelineSemaphoreFeatures timeline{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
            VkPhysicalDeviceHostQueryResetFeatures hostReset{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES};
            if (props.apiVersion >= VK_API_VERSION_1_2) timeline.pNext = &hostReset;
            VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            features.pNext = &timeline;
            fn<PFN_vkGetPhysicalDeviceFeatures2>("vkGetPhysicalDeviceFeatures2")(device, &features);
            if (!timeline.timelineSemaphore) { unsupported = failure("timeline_semaphore"); continue; }
            const float priority = 1.0f;
            VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            qi.queueFamilyIndex = graphics; qi.queueCount = 1; qi.pQueuePriorities = &priority;
            const char *deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME};
            VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
            di.pNext = &timeline; di.queueCreateInfoCount = 1; di.pQueueCreateInfos = &qi;
            di.enabledExtensionCount = props.apiVersion < VK_API_VERSION_1_2 ? 2 : 1;
            di.ppEnabledExtensionNames = deviceExtensions;
            VkDevice logical;
            if (fn<PFN_vkCreateDevice>("vkCreateDevice")(device, &di, nullptr, &logical) != VK_SUCCESS) { unsupported = failure("logical_device"); continue; }
            fn<PFN_vkDestroyDevice>("vkDestroyDevice")(logical, nullptr);
            std::vector<VkSurfaceFormatKHR> surfaceFormats;
            if (surface) {
                uint32_t formats = 0;
                auto queryFormats = fn<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>("vkGetPhysicalDeviceSurfaceFormatsKHR");
                if (queryFormats(device, surface, &formats, nullptr) != VK_SUCCESS || !formats) { unsupported = failure("surface_formats"); continue; }
                surfaceFormats.resize(formats);
                if (queryFormats(device, surface, &formats, surfaceFormats.data()) != VK_SUCCESS) { unsupported = failure("surface_formats"); continue; }
                surfaceFormats.resize(formats);
            }
            std::ostringstream out;
            out << "{\"render\":true,\"reason\":\"\",\"hostQueryReset\":" << (hostReset.hostQueryReset ? "true" : "false") << ",\"device\":" << quote(props.deviceName) << ",\"version\":" << props.apiVersion << ",\"profiles\":[";
            bool first = true;
            auto capsFn = fn<PFN_vkGetPhysicalDeviceVideoCapabilitiesKHR>("vkGetPhysicalDeviceVideoCapabilitiesKHR");
            auto formatsFn = fn<PFN_vkGetPhysicalDeviceVideoFormatPropertiesKHR>("vkGetPhysicalDeviceVideoFormatPropertiesKHR");
            auto profile = [&](const char *codec, const char *extension, VkVideoCodecOperationFlagBitsKHR op, int bits, void *codecProfile, void *codecCaps) {
                if (!videoQueue || !capsFn || !formatsFn || !(operations & op) || !has(ext, extension)) return;
                VkVideoProfileInfoKHR p{VK_STRUCTURE_TYPE_VIDEO_PROFILE_INFO_KHR};
                p.pNext = codecProfile; p.videoCodecOperation = op; p.chromaSubsampling = VK_VIDEO_CHROMA_SUBSAMPLING_420_BIT_KHR;
                p.lumaBitDepth = p.chromaBitDepth = bits == 10 ? VK_VIDEO_COMPONENT_BIT_DEPTH_10_BIT_KHR : VK_VIDEO_COMPONENT_BIT_DEPTH_8_BIT_KHR;
                VkVideoDecodeCapabilitiesKHR dc{VK_STRUCTURE_TYPE_VIDEO_DECODE_CAPABILITIES_KHR}; dc.pNext = codecCaps;
                VkVideoCapabilitiesKHR caps{VK_STRUCTURE_TYPE_VIDEO_CAPABILITIES_KHR}; caps.pNext = &dc;
                if (capsFn(device, &p, &caps) != VK_SUCCESS) return;
                VkVideoProfileListInfoKHR list{VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR}; list.profileCount = 1; list.pProfiles = &p;
                VkPhysicalDeviceVideoFormatInfoKHR fi{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VIDEO_FORMAT_INFO_KHR}; fi.pNext = &list;
                fi.imageUsage = VK_IMAGE_USAGE_VIDEO_DECODE_DST_BIT_KHR;
                uint32_t n = 0;
                if (formatsFn(device, &fi, &n, nullptr) != VK_SUCCESS || !n) return;
                if (!first) out << ','; first = false;
                out << "{\"codec\":\"" << codec << "\",\"bits\":" << bits << ",\"width\":" << caps.maxCodedExtent.width << ",\"height\":" << caps.maxCodedExtent.height << '}';
            };
            VkVideoDecodeH264ProfileInfoKHR h264{VK_STRUCTURE_TYPE_VIDEO_DECODE_H264_PROFILE_INFO_KHR};
            h264.stdProfileIdc = STD_VIDEO_H264_PROFILE_IDC_HIGH; h264.pictureLayout = VK_VIDEO_DECODE_H264_PICTURE_LAYOUT_PROGRESSIVE_KHR;
            VkVideoDecodeH264CapabilitiesKHR h264Caps{VK_STRUCTURE_TYPE_VIDEO_DECODE_H264_CAPABILITIES_KHR};
            profile("h264", VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME, VK_VIDEO_CODEC_OPERATION_DECODE_H264_BIT_KHR, 8, &h264, &h264Caps);
            VkVideoDecodeH265ProfileInfoKHR h265{VK_STRUCTURE_TYPE_VIDEO_DECODE_H265_PROFILE_INFO_KHR};
            VkVideoDecodeH265CapabilitiesKHR h265Caps{VK_STRUCTURE_TYPE_VIDEO_DECODE_H265_CAPABILITIES_KHR};
            for (int bits : {8, 10}) {
                h265.stdProfileIdc = bits == 10 ? STD_VIDEO_H265_PROFILE_IDC_MAIN_10 : STD_VIDEO_H265_PROFILE_IDC_MAIN;
                profile("hevc", VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME, VK_VIDEO_CODEC_OPERATION_DECODE_H265_BIT_KHR, bits, &h265, &h265Caps);
            }
            VkVideoDecodeAV1ProfileInfoKHR av1{VK_STRUCTURE_TYPE_VIDEO_DECODE_AV1_PROFILE_INFO_KHR}; av1.stdProfile = STD_VIDEO_AV1_PROFILE_MAIN;
            VkVideoDecodeAV1CapabilitiesKHR av1Caps{VK_STRUCTURE_TYPE_VIDEO_DECODE_AV1_CAPABILITIES_KHR};
            for (int bits : {8, 10}) profile("av1", VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME, VK_VIDEO_CODEC_OPERATION_DECODE_AV1_BIT_KHR, bits, &av1, &av1Caps);
#ifdef VK_KHR_video_decode_vp9
            VkVideoDecodeVP9ProfileInfoKHR vp9{VK_STRUCTURE_TYPE_VIDEO_DECODE_VP9_PROFILE_INFO_KHR};
            VkVideoDecodeVP9CapabilitiesKHR vp9Caps{VK_STRUCTURE_TYPE_VIDEO_DECODE_VP9_CAPABILITIES_KHR};
            for (int bits : {8, 10}) {
                vp9.stdProfile = bits == 10 ? STD_VIDEO_VP9_PROFILE_2 : STD_VIDEO_VP9_PROFILE_0;
                profile("vp9", VK_KHR_VIDEO_DECODE_VP9_EXTENSION_NAME, VK_VIDEO_CODEC_OPERATION_DECODE_VP9_BIT_KHR, bits, &vp9, &vp9Caps);
            }
#endif
            out << "],\"surfaceColorSpaces\":[";
            for (size_t i = 0; i < surfaceFormats.size(); ++i) {
                if (i) out << ",";
                out << static_cast<int>(surfaceFormats[i].colorSpace);
            }
            out << "]}"; return out.str(); // Keep renderer and decoder capabilities on the SAME device.
        }
        return unsupported;
    }
};
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_ambient_tv_ui_player_backend_VulkanNativeProbe_probe(JNIEnv *env, jobject, jobject surface) {
    Probe probe;
    auto result = probe.run(env, surface);
    return env->NewStringUTF(result.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ambient_tv_ui_player_backend_VulkanNativeProbe_restoreDataSpace(JNIEnv *env, jobject, jobject surface, jint dataSpace) {
    if (!surface) return false;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return false;
    const int result = ANativeWindow_setBuffersDataSpace(window, dataSpace);
    ANativeWindow_release(window);
    return result == 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_VulkanNativeProbe_dataSpace(JNIEnv *env, jobject, jobject surface) {
    if (!surface) return 0;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    const int result = ANativeWindow_getBuffersDataSpace(window);
    ANativeWindow_release(window);
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ambient_tv_ui_player_backend_VulkanNativeProbe_vlcModules(JNIEnv *, jobject) {
    // Called while a LibVLC instance holds the registered module bank alive.
    void *lib = dlopen("libvlc.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return false;
    auto list = symbol<void **(*)(size_t *)>(lib, "module_list_get");
    auto name = symbol<const char *(*)(const void *)>(lib, "module_get_object");
    auto release = symbol<void (*)(void **)>(lib, "module_list_free");
    bool display = false, vk = false, android = false;
    if (list && name && release) {
        size_t count = 0; void **modules = list(&count);
        for (size_t i = 0; i < count; ++i) {
            const char *n = name(modules[i]);
            if (!n) continue;
            display |= !strcmp(n, "placebo"); vk |= !strcmp(n, "placebo_vk"); android |= !strcmp(n, "vk_android");
        }
        release(modules);
    }
    dlclose(lib);
    return display && vk && android;
}
