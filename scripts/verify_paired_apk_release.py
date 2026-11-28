#!/usr/bin/env python3
"""Verify a complete paired Full/Modular Android release before uploading it."""
import argparse
import json
from pathlib import Path
import zipfile

import generate_update_manifest as updates
import check_modular_preview_apk as preview
import package_system_modules as packages


def verify_native_graph(full, modular, abi):
    """Check actual ELF edges as well as byte parity for shared host libraries.

    The base must never link to an omitted engine via DT_NEEDED, even if a
    module with that name could later be downloaded. Optional dlsym/JNI paths
    are separately validated by the host and are not DT_NEEDED edges.
    """
    prefix = f"lib/{abi}/"
    def libraries(archive):
        names = [name.removeprefix(prefix) for name in archive.namelist()
                 if name.startswith(prefix) and name.endswith(".so") and "/" not in name.removeprefix(prefix)]
        if len(names) != len(set(names)):
            raise ValueError(f"{abi}: duplicate native library")
        return set(names)

    full_names, modular_names = libraries(full), libraries(modular)
    shared = set().union(*packages.HOST_LIBRARIES.values())
    checked = {}
    def full_deps(name):
        if name not in checked:
            checked[name] = packages.native_dependencies(full.read(prefix + name), name)
        return checked[name]

    # Fixed shared allowlist remains an explicit host ABI contract. Also
    # discover the complete transitive closure from each present engine root.
    for module, roots in packages.NATIVE_ROOTS.items():
        present = set(roots) & full_names
        if not present:
            continue
        if present != set(roots):
            raise ValueError(f"{abi}/{module}: incomplete Full native roots")
        seen = set()
        def visit(name):
            if name in packages.PLATFORM or name in seen:
                return
            seen.add(name)
            if name not in roots:
                if name not in packages.HOST_LIBRARIES[module]:
                    raise ValueError(f"{abi}/{module}: unapproved shared dependency {name}")
                shared.add(name)
            if name not in full_names:
                raise ValueError(f"{abi}/{module}: missing Full dependency {name}")
            for dep in full_deps(name):
                if name not in roots and dep in roots:
                    raise ValueError(f"{abi}/{module}: host dependency links into optional engine {dep}")
                visit(dep)
        for root in roots:
            visit(root)

    for name in shared & (full_names | modular_names):
        if name not in full_names or name not in modular_names or full.read(prefix + name) != modular.read(prefix + name):
            raise ValueError(f"Different shared library bytes: {abi}/{name}")

    # Inspect the actual Modular ELF, including libraries not in the module
    # allowlist (e.g. media decoders). Compare against Full to avoid assuming
    # every Android platform library is enumerated by the module policy.
    for name in sorted(modular_names):
        data = modular.read(prefix + name)
        if name in full_names and data == full.read(prefix + name):
            dependencies = full_deps(name)
        else:
            dependencies = packages.native_dependencies(data, name)
        missing = (dependencies & full_names) - modular_names
        if missing:
            raise ValueError(f"{abi}/{name}: retained library links to omitted payload {sorted(missing)}")


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
    for abi in updates.ABIS:
        with zipfile.ZipFile(by_variant["full"][abi]) as f, zipfile.ZipFile(by_variant["modular"][abi]) as m:
            verify_native_graph(f, m, abi)
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
