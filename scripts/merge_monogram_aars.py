#!/usr/bin/env python3
"""Merge only native/mtproto AAR outputs, never the rest of Monogram."""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path

def merge(output: Path, parts: list[str]) -> None:
    if not parts:
        raise ValueError("No ABI AARs supplied")
    common: dict[str, bytes] = {}
    native: dict[str, bytes] = {}
    for entry in parts:
        abi, source = entry.split("=", 1)
        with zipfile.ZipFile(source) as z:
            names = set(z.namelist())
            so = f"jni/{abi}/libmonogram_mtproto.so"
            if so not in names:
                raise ValueError(f"{source} lacks {so}; upstream ABI build incomplete")
            for item in z.infolist():
                if item.is_dir():
                    continue
                name = item.filename
                payload = z.read(item)
                if name.startswith("jni/"):
                    if name.startswith(f"jni/{abi}/"):
                        if name in native and native[name] != payload:
                            raise ValueError(f"Conflicting ABI library {name}")
                        native[name] = payload
                    continue
                if name in common and common[name] != payload:
                    raise ValueError(f"Non-native AAR content differs across ABIs: {name}")
                common[name] = payload
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix(".tmp")
    with zipfile.ZipFile(temp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, data in sorted((common | native).items()):
            z.writestr(name, data)
    temp.replace(output)
    print(f"Monogram native AAR: {output} ({output.stat().st_size / 1024 / 1024:.1f} MiB, {len(native)} ABI libraries)")

if __name__ == "__main__":
    merge(Path(sys.argv[1]), sys.argv[2:])
