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


def patch(path):
    source = path.read_text()
    if REPLACEMENT in source:
        if source.count(REPLACEMENT) != 1:
            raise ValueError("duplicate LibretroDroid module loader")
        return
    if source.count(LOAD) != 1:
        raise ValueError("pinned LibretroDroid static JNI load site changed")
    path.write_text(source.replace(LOAD, REPLACEMENT, 1))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("java_file", type=Path)
    patch(parser.parse_args().java_file)
