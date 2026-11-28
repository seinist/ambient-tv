#!/usr/bin/env python3
"""Route the pinned LibVLC Java native load to the host-selected absolute path."""
import argparse
from pathlib import Path
import re

MARKER = "org/videolan/libvlc/AmbientNativeLibraryLoader.java"
CALL = re.compile(r'System\s*\.\s*loadLibrary\s*\(\s*"(vlc|vlcjni)"\s*\)')
PATCHED = re.compile(r'org\.videolan\.libvlc\.AmbientNativeLibraryLoader\.load\("(vlc|vlcjni)"\)')
LEGACY_HELPER = """package org.videolan.libvlc;

/** Installed by Ambient TV's pinned LibVLC build. */
public final class AmbientNativeLibraryLoader {
    private AmbientNativeLibraryLoader() {}
    public static void load(String name) {
        if (!"vlc".equals(name) && !"vlcjni".equals(name))
            throw new IllegalArgumentException("Unexpected VLC library");
        String directory = System.getProperty("ambient.vlc.native.directory");
        if (directory == null) {
            System.loadLibrary(name);
            return;
        }
        java.io.File file = new java.io.File(directory, System.mapLibraryName(name));
        if (!file.isAbsolute() || !file.isFile())
            throw new UnsatisfiedLinkError("Selected VLC library missing: " + name);
        System.load(file.getAbsolutePath());
    }
}
"""

HELPER = LEGACY_HELPER.replace("    private AmbientNativeLibraryLoader() {}",
    "    private AmbientNativeLibraryLoader() {}\\n"
    "    public static native boolean vulkanModules();")

PROBE_MARKER = "Java_org_videolan_libvlc_AmbientNativeLibraryLoader_vulkanModules"
PROBE = r"""
/* Ambient TV: query the module bank from the VLC JNI linker namespace. */
#include <dlfcn.h>
#include <string.h>
JNIEXPORT jboolean JNICALL
Java_org_videolan_libvlc_AmbientNativeLibraryLoader_vulkanModules(JNIEnv *env, jclass type)
{
    (void)env; (void)type;
    void *lib = dlopen("libvlc.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return JNI_FALSE;
    void **(*list)(size_t *) = (void **(*)(size_t *))dlsym(lib, "module_list_get");
    const char *(*name)(const void *) = (const char *(*)(const void *))dlsym(lib, "module_get_object");
    void (*release)(void **) = (void (*)(void **))dlsym(lib, "module_list_free");
    int display = 0, vk = 0, android = 0;
    if (list && name && release) {
        size_t count = 0;
        void **modules = list(&count);
        if (modules) {
            for (size_t i = 0; i < count; ++i) {
                const char *n = name(modules[i]);
                if (!n) continue;
                display |= !strcmp(n, "placebo");
                vk |= !strcmp(n, "placebo_vk");
                android |= !strcmp(n, "vk_android");
            }
            release(modules);
        }
    }
    dlclose(lib);
    return display && vk && android ? JNI_TRUE : JNI_FALSE;
}
"""


def patch_probe(jni):
    path = Path(jni) / "libvlcjni-mediaplayer.c"
    source = path.read_text()
    if "Java_org_videolan_libvlc_MediaPlayer_nativeSetTime" not in source:
        raise ValueError("pinned VLC JNI source not found")
    if PROBE_MARKER in source:
        if not source.endswith(PROBE):
            raise ValueError("unexpected VLC namespace probe")
        return
    path.write_text(source + PROBE)


def patch(root):
    root = Path(root)
    marker = root / MARKER
    if marker.exists() and marker.read_text() not in (HELPER, LEGACY_HELPER):
        raise ValueError("unexpected VLC loader helper")
    changes = {}
    seen = set()
    for path in sorted(root.rglob("*.java")):
        if path == marker:
            continue
        source = path.read_text()
        seen.update(CALL.findall(source))
        seen.update(PATCHED.findall(source))
        updated = CALL.sub(lambda m: 'org.videolan.libvlc.AmbientNativeLibraryLoader.load("' + m[1] + '")', source)
        if updated != source:
            changes[path] = updated
    if "vlcjni" not in seen:
        raise ValueError("pinned LibVLC JNI load site not found")
    for path, data in changes.items():
        path.write_text(data)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(HELPER)
    print("VLC verified load sites:", ", ".join(sorted(seen)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("java_root", type=Path)
    parser.add_argument("--jni-root", type=Path)
    args = parser.parse_args()
    patch(args.java_root)
    if args.jni_root:
        patch_probe(args.jni_root)
