#!/usr/bin/env python3
"""Verify a complete paired Full/Modular Android release before uploading it."""
import argparse
import json
from pathlib import Path
import zipfile

import generate_update_manifest as updates
import check_modular_preview_apk as preview
import package_system_modules as packages


def verify(full, modular, aapt2, apksigner):
    manifests = []
    identities = set()
    all_names = set()
    by_variant = {}
    for directory, variant, name in ((full, "full", "update.json"), (modular, "modular", "update-modular.json")):
        paths = sorted(directory.glob("*.apk"))
        if len(paths) != 4:
            raise ValueError(f"{variant}: expected four APKs")
        result = updates.generate(paths, aapt2, apksigner, updates.ABIS, variant)
        if result != json.loads((directory / name).read_text()):
            raise ValueError(f"{variant}: manifest differs from signed APKs")
        mapping = {}
        for path in paths:
            updates.verify_packaging(path, aapt2, variant, require_marker=True)
            identity, entry = updates.inspect_apk(path, aapt2, apksigner)
            identities.add(identity)
            if path.name in all_names:
                raise ValueError("Duplicate release asset filename")
            all_names.add(path.name)
            mapping[entry["abi"]] = path
            if variant == "modular" and preview.verify(path, expect_vlc_external=True) != {entry["abi"]}:
                raise ValueError("Modular payload ABI mismatch")
        by_variant[variant] = mapping
        manifests.append(result)
    if len(identities) != 1:
        raise ValueError("Full and Modular APK package/version/SDK/signing identities differ")

    # Native packages derived from Full may depend on exact host-owned bytes.
    # Require the Modular base to provide identical shared dependencies.
    shared = set().union(*packages.HOST_LIBRARIES.values())
    for abi in updates.ABIS:
        with zipfile.ZipFile(by_variant["full"][abi]) as f, zipfile.ZipFile(by_variant["modular"][abi]) as m:
            prefix = f"lib/{abi}/"
            full_names, modular_names = set(f.namelist()), set(m.namelist())
            for lib in shared:
                path = prefix + lib
                if path in full_names or path in modular_names:
                    if path not in full_names or path not in modular_names or f.read(path) != m.read(path):
                        raise ValueError(f"Different shared library bytes: {abi}/{lib}")
    return manifests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", type=Path, required=True)
    parser.add_argument("--modular", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    args = parser.parse_args()
    verify(args.full, args.modular, args.aapt2, args.apksigner)
    print("Verified paired release: eight APKs, two manifests, one signing identity")


if __name__ == "__main__":
    main()
