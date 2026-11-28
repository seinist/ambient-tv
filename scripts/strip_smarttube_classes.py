#!/usr/bin/env python3
"""Strip only SmartTube-owned executable classes from AGP scoped CLASSES.

Resources, manifest entries, assets and dependency resolution remain owned
by the normal Android build. The host retains all generated R classes.
"""
import argparse
import hashlib
from pathlib import Path
import tempfile
import zipfile

from export_monogram_adapter import ownership, read_classes, validate_private_boundary, validate_vlc_boundary


def strip(inputs, output, modules=('smarttube',)):
    owners = [ownership(module) for module in modules]
    required = set().union(*(set(required) for _, required in owners))
    seen = {}
    removed = set()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temp:
        candidate = Path(temp) / "classes.jar"
        with zipfile.ZipFile(candidate, "w", compression=zipfile.ZIP_DEFLATED) as jar:
            for path in inputs:
                for name, data in read_classes(path):
                    digest = hashlib.sha256(data).digest()
                    if name in seen:
                        if seen[name] != digest:
                            raise ValueError(f"conflicting duplicate class: {name}")
                        continue
                    seen[name] = digest
                    if any(owner(name) for owner, _ in owners):
                        removed.add(name)
                    else:
                        if 'smarttube' in modules:
                            validate_private_boundary(name, data)
                        if 'vlc' in modules:
                            validate_vlc_boundary(name, data)
                        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                        info.compress_type = zipfile.ZIP_DEFLATED
                        jar.writestr(info, data)
        missing = required - removed
        if missing:
            raise ValueError(f"incomplete SmartTube build inputs: {sorted(missing)}")
        candidate.replace(output)
    print(f"Removed {len(removed)} SmartTube-owned classes; retained {len(seen) - len(removed)} host classes")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--module", choices=("smarttube", "vlc"), action="append")
    args = parser.parse_args()
    strip(args.input, args.output, args.module or ('smarttube',))


if __name__ == "__main__":
    main()
