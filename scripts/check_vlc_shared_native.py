#!/usr/bin/env python3
"""Verify namespace-local JNI and exported C registration symbols in VLC."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

CORE = {"ambient_vlc_bind_svp_v1", "ambient_vlc_bind_pcm_v1"}
JNI = {"Java_org_videolan_libvlc_AmbientNativeLibraryLoader_vulkanModules",
       "Java_org_videolan_libvlc_AmbientNativeLibraryLoader_bindSharedNative"}

def symbols(data):
    with tempfile.NamedTemporaryFile() as binary:
        binary.write(data)
        binary.flush()
        out = subprocess.check_output(["readelf", "--dyn-syms", "--wide", binary.name], text=True)
    return {match[1].split("@")[0] for line in out.splitlines()
            if (match := re.search(r"\b(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+(\S+)", line))}

LIBRETRO = {"Java_com_swordfish_libretrodroid_LibretroDroid_ambientBindSharedShader"}

def require_exports(data, required, label):
    missing = required - symbols(data)
    if missing:
        raise ValueError(f"{label}: missing exports {sorted(missing)}")

def verify(aar, abis):
    with zipfile.ZipFile(aar) as archive:
        for abi in abis:
            for library, required in (("libvlc.so", CORE), ("libvlcjni.so", JNI)):
                require_exports(archive.read(f"jni/{abi}/{library}"), required, f"{abi}/{library}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--abis", required=True)
    args = parser.parse_args()
    verify(args.aar, args.abis.split())
