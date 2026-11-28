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

from export_monogram_adapter import owns_smarttube, read_classes, SMARTTUBE_REQUIRED, validate_private_boundary


def strip(inputs, output):
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
                    if owns_smarttube(name):
                        removed.add(name)
                    else:
                        validate_private_boundary(name, data)
                        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                        info.compress_type = zipfile.ZIP_DEFLATED
                        jar.writestr(info, data)
        missing = set(SMARTTUBE_REQUIRED) - removed
        if missing:
            raise ValueError(f"incomplete SmartTube build inputs: {sorted(missing)}")
        candidate.replace(output)
    print(f"Removed {len(removed)} SmartTube-owned classes; retained {len(seen) - len(removed)} host classes")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    strip(args.input, args.output)


if __name__ == "__main__":
    main()
