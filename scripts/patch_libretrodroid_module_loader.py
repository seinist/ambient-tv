#!/usr/bin/env python3
"""Patch the pinned frontend's single static JNI initializer, fail on drift."""
import argparse
from pathlib import Path

LOAD = '        System.loadLibrary("libretrodroid");'
REPLACEMENT = """        String directory = System.getProperty("ambient.libretrodroid.native.directory");
        if (directory == null) {
            System.loadLibrary("libretrodroid");
        } else {
            java.io.File library = new java.io.File(directory, "liblibretrodroid.so");
            if (!library.isAbsolute() || !library.isFile())
                throw new UnsatisfiedLinkError("Selected LibretroDroid library is missing");
            System.load(library.getAbsolutePath());
        }"""


SHARED_SHADER = "    public static native void ambientBindSharedShader(long resolver);"


def patch(path):
    source = path.read_text()
    if REPLACEMENT in source:
        if source.count(REPLACEMENT) != 1:
            raise ValueError("duplicate LibretroDroid module loader")
    else:
        if source.count(LOAD) != 1:
            raise ValueError("pinned LibretroDroid static JNI load site changed")
        source = source.replace(LOAD, REPLACEMENT, 1)
    if SHARED_SHADER not in source:
        # Insert a declaration immediately before the final class brace.
        end = source.rstrip().rfind("}")
        if end < 0:
            raise ValueError("LibretroDroid class declaration changed")
        source = source[:end] + SHARED_SHADER + "\n" + source[end:]
    if source.count(SHARED_SHADER) != 1:
        raise ValueError("duplicate shader resolver declaration")
    if path.read_text() != source:
        path.write_text(source)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("java_file", type=Path)
    patch(parser.parse_args().java_file)
