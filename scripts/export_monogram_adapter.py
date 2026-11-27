#!/usr/bin/env python3
"""Export a standalone pre-R8 Monogram DEX using only explicitly owned classes."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

PREFIXES = ("org/monogram/mtproto/", "uniffi/monogram_mtproto/", "com/sun/jna/", "org/rustls/")
CLASSES = tuple("com/ambient/tv/data/telegram/" + name for name in (
    "MonogramModuleAdapter", "MonogramNativeBackend", "MonogramNativeBackendKt",
    "AmbientMonogramJni", "AmbientMonogramJniFailure",
))
ENTRY = CLASSES[0] + ".class"
REQUIRED = (ENTRY, CLASSES[1] + ".class", "com/sun/jna/Native.class",
            "org/monogram/mtproto/MtprotoNativeLoader.class")


def owns(path):
    if not path.endswith(".class"):
        return False
    name = path[:-6]
    return name.startswith(PREFIXES) or any(name == c or name.startswith(c + "$") for c in CLASSES)


def read_classes(path):
    if path.is_dir():
        for item in sorted(path.rglob("*.class")):
            yield item.relative_to(path).as_posix(), item.read_bytes()
    else:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                name = info.filename
                if name.endswith(".class") and not name.startswith("META-INF/") and name != "module-info.class":
                    yield name, archive.read(info)


def split(inputs, program, classpath):
    seen = {}
    owned = set()
    with zipfile.ZipFile(program, "w", compression=zipfile.ZIP_DEFLATED) as p, \
         zipfile.ZipFile(classpath, "w", compression=zipfile.ZIP_DEFLATED) as h:
        for path in inputs:
            for name, data in read_classes(path):
                if not re.fullmatch(r"[A-Za-z0-9_/$-]+[.]class", name):
                    raise ValueError(f"unsafe class entry: {name}")
                sha = hashlib.sha256(data).digest()
                if name in seen:
                    if seen[name] != sha:
                        raise ValueError(f"conflicting duplicate class: {name}")
                    continue
                seen[name] = sha
                if owns(name):
                    p.writestr(name, data)
                    owned.add(name)
                else:
                    h.writestr(name, data)
    if not set(REQUIRED) <= owned:
        raise ValueError(f"missing adapter classes: {set(REQUIRED) - owned}")
    for prefix in ("uniffi/monogram_mtproto/", "org/rustls/"):
        if not any(name.startswith(prefix) for name in owned):
            raise ValueError(f"missing adapter dependency {prefix}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--android-lib", type=Path, action="append", required=True)
    parser.add_argument("--d8", type=Path, required=True)
    parser.add_argument("--binding", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.binding):
        parser.error("invalid host adapter fingerprint")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        parser.error("output must be empty")
    with tempfile.TemporaryDirectory() as temp:
        program = Path(temp) / "program.jar"
        host = Path(temp) / "host.jar"
        split(args.input, program, host)
        cmd = [str(args.d8), "--release", "--min-api", "29", "--classpath", str(host),
               "--output", str(args.output)]
        for lib in args.android_lib:
            cmd += ["--lib", str(lib)]
        subprocess.run(cmd + [str(program)], check=True)
    if not (args.output / "classes.dex").is_file():
        raise ValueError("D8 did not generate adapter DEX")
    (args.output / "host-binding.sha256").write_text(args.binding + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
