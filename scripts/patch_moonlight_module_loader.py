#!/usr/bin/env python3
"""Independent native module seam for the pinned Moonlight JNI bridge.

Run on the staged protocol-only sources after Moonlight's own patches.
No networking, decoder, audio, input, or native optimization code is changed.
"""
import argparse
import hashlib
from pathlib import Path

LOAD = '        System.loadLibrary("moonlight-core");'
PROPERTY = "ambient.moonlight.native.directory"
REPLACEMENT = """        String directory = System.getProperty("ambient.moonlight.native.directory");
        if (directory == null) {
            System.loadLibrary("moonlight-core");
        } else {
            java.io.File library = new java.io.File(directory, "libmoonlight-core.so");
            if (!library.isAbsolute() || !library.isFile())
                throw new UnsatisfiedLinkError("Selected Moonlight native library is missing");
            System.load(library.getAbsolutePath());
        }"""
BRIDGE = Path("app/protocol-only-src/com/limelight/nvstream/jni/MoonBridge.java")
MARKER = Path(".ambient-moonlight-pins")


def patch(path):
    source = path.read_text(encoding="utf-8")
    if REPLACEMENT in source:
        if source.count(REPLACEMENT) != 1 or source.count(LOAD.strip()) != 1:
            raise ValueError("ambiguous Moonlight native loader")
        return
    if source.count(LOAD) != 1 or PROPERTY in source:
        raise ValueError("pinned MoonBridge JNI load site changed")
    path.write_text(source.replace(LOAD, REPLACEMENT, 1), encoding="utf-8")


def binding(source):
    """Fingerprint exact staged protocol Java and reviewed source/patch provenance."""
    root = Path(source)
    bridge = root / BRIDGE
    if REPLACEMENT not in bridge.read_text(encoding="utf-8"):
        raise ValueError("unpatched Moonlight JNI binding")
    marker = (root / MARKER).read_bytes()
    if not marker or not all(key + b"=" in marker for key in
                             (b"android", b"common-c", b"enet", b"patch")):
        raise ValueError("missing Moonlight source provenance")
    digest = hashlib.sha256()
    digest.update(b"ambient-moonlight-module-v1\0")
    digest.update(marker)
    digest.update(b"\0")
    base = root / "app/protocol-only-src"
    for path in sorted(base.rglob("*.java")):
        if path.is_symlink() or not path.is_file():
            raise ValueError("unexpected staged Moonlight Java source")
        data = path.read_bytes()
        digest.update(path.relative_to(base).as_posix().encode("utf-8") + b"\0")
        digest.update(hashlib.sha256(data).digest())
    return digest.hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--fingerprint", action="store_true")
    args = parser.parse_args()
    if args.fingerprint:
        print(binding(args.source))
    else:
        patch(args.source / BRIDGE)
