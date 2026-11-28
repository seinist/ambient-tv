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
require(abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), "unsupported ABI")
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
    require("assets/ambient-vlc-svp-hdr-v1" in aar.namelist() and
            aar.read("assets/ambient-vlc-svp-hdr-v1") == b"SVP HDR10PLUS FEL SCENE 1\n", "SVP HDR bridge capability mismatch")
    # The receipt is single-ABI. Check actual ELF class and machine, not
    # only ZIP paths and hashes (a mislabeled binary could match its receipt).
    elf_class, elf_machine = {
        "armeabi-v7a": (1, 40), "arm64-v8a": (2, 183),
        "x86": (1, 3), "x86_64": (2, 62),
    }[abi]
    for name in ("libvlc.so", "libvlcjni.so"):
        data = aar.read(f"jni/{abi}/{name}")
        require(hashlib.sha256(data).hexdigest() == expected[name], name + " digest mismatch")
        require(len(data) >= (52 if elf_class == 1 else 64) and data[:4] == b"\x7fELF"
                and data[4] == elf_class and data[5:7] == b"\x01\x01"
                and int.from_bytes(data[16:18], "little") == 3
                and int.from_bytes(data[18:20], "little") == elf_machine,
                name + " ELF ABI mismatch")
head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
origin = next(x.split("=", 1)[1] for x in lines if x.startswith("ambient_head="))
lines = [x for x in lines if not x.startswith(("ambient_head=", "cached_from_head="))]
lines.extend(("ambient_head=" + head, "cached_from_head=" + origin))
tmp = receipt.with_suffix(".tmp")
tmp.write_text("\n".join(lines) + "\n")
tmp.replace(receipt)
