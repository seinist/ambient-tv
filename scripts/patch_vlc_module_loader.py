#!/usr/bin/env python3
"""Route the pinned LibVLC Java native load to the host-selected absolute path."""
import argparse
from pathlib import Path
import re

MARKER = "org/videolan/libvlc/AmbientNativeLibraryLoader.java"
CALL = re.compile(r'System\s*\.\s*loadLibrary\s*\(\s*"(vlc|vlcjni)"\s*\)')
PATCHED = re.compile(r'org\.videolan\.libvlc\.AmbientNativeLibraryLoader\.load\("(vlc|vlcjni)"\)')
HELPER = """package org.videolan.libvlc;

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


def patch(root):
    root = Path(root)
    marker = root / MARKER
    if marker.exists() and marker.read_text() != HELPER:
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
    patch(parser.parse_args().java_root)
