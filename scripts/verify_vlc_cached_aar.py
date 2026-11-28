#!/usr/bin/env python3
"""Verify an exact native source/toolchain cache hit before issuing current-run provenance."""
import hashlib
import pathlib
import subprocess
import sys
import zipfile

def require(condition, message):
    if not condition:
        raise ValueError(message)


abi, source_hash, toolchain_hash = sys.argv[1:]
require(abi in ("arm64-v8a", "x86_64"), "unsupported ABI")
root = pathlib.Path("native/vlc-aar/build/experimental")
receipt = root / "native-link-receipt.txt"
lines = receipt.read_text().splitlines()
require("source_hash=" + source_hash in lines, "source identity mismatch")
require("toolchain_hash=" + toolchain_hash in lines, "toolchain identity mismatch")
require([x for x in lines if x.startswith("abi=")] == ["abi=" + abi], "ABI mismatch")
expected = dict((line.split("  ", 1)[1], line.split("  ", 1)[0])
                for line in lines if "  " in line)
aar_path = root / "libvlc-vulkan-ahb-experimental.aar"
with aar_path.open("rb") as stream:
    require("aar_sha256=" + hashlib.file_digest(stream, "sha256").hexdigest() in lines, "AAR digest mismatch")
with zipfile.ZipFile(aar_path) as aar:
    require(aar.testzip() is None, "corrupt ZIP member")
    require(len(aar.namelist()) == len(set(aar.namelist())), "duplicate ZIP members")
    require(aar.read("assets/ambient-vlc-extended-v1") == b"AHB DOVI FEL HDR10PLUS\n", "capability mismatch")
    for name in ("libvlc.so", "libvlcjni.so"):
        require(hashlib.sha256(aar.read(f"jni/{abi}/{name}")).hexdigest() == expected[name], name + " digest mismatch")
head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
origin = next(x.split("=", 1)[1] for x in lines if x.startswith("ambient_head="))
lines = [x for x in lines if not x.startswith(("ambient_head=", "cached_from_head="))]
lines.extend(("ambient_head=" + head, "cached_from_head=" + origin))
tmp = receipt.with_suffix(".tmp")
tmp.write_text("\n".join(lines) + "\n")
tmp.replace(receipt)
