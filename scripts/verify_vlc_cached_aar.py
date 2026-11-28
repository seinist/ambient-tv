#!/usr/bin/env python3
"""Verify an exact native source/toolchain cache hit before issuing current-run provenance."""
import hashlib
import pathlib
import subprocess
import sys
import zipfile

abi, source_hash, toolchain_hash = sys.argv[1:]
assert abi in ("arm64-v8a", "x86_64")
root = pathlib.Path("native/vlc-aar/build/experimental")
receipt = root / "native-link-receipt.txt"
lines = receipt.read_text().splitlines()
assert "source_hash=" + source_hash in lines
assert "toolchain_hash=" + toolchain_hash in lines
assert [x for x in lines if x.startswith("abi=")] == ["abi=" + abi]
expected = dict((line.split("  ", 1)[1], line.split("  ", 1)[0])
                for line in lines if "  " in line)
aar_path = root / "libvlc-vulkan-ahb-experimental.aar"
with aar_path.open("rb") as stream:
    assert "aar_sha256=" + hashlib.file_digest(stream, "sha256").hexdigest() in lines
with zipfile.ZipFile(aar_path) as aar:
    assert aar.testzip() is None
    assert aar.read("assets/ambient-vlc-extended-v1") == b"AHB DOVI FEL HDR10PLUS\n"
    for name in ("libvlc.so", "libvlcjni.so"):
        assert hashlib.sha256(aar.read(f"jni/{abi}/{name}")).hexdigest() == expected[name]
head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
origin = next(x.split("=", 1)[1] for x in lines if x.startswith("ambient_head="))
lines = [x for x in lines if not x.startswith(("ambient_head=", "cached_from_head="))]
lines.extend(("ambient_head=" + head, "cached_from_head=" + origin))
tmp = receipt.with_suffix(".tmp")
tmp.write_text("\n".join(lines) + "\n")
tmp.replace(receipt)
