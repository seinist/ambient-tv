"""Verify the flags on the configured native LibretroDroid Ninja target."""
import argparse
import json
from pathlib import Path
import subprocess
import shlex

from core_build_policy import audit_commands
from core_acceleration_audit import build_commands, DRIVER
from core_link_evidence import output_file, target_commands


def verify(commands, profile, cwd, root):
    libraries = set()
    for driver, args, directory in build_commands(commands, cwd, root, strict=True):
        if DRIVER.fullmatch(driver) and "-shared" in args:
            output = output_file(args, directory)
            if output is not None and output.name == "liblibretrodroid.so":
                libraries.add(output)
    if len(libraries) != 1:
        raise ValueError("Expected exactly one LibretroDroid shared-link command")
    scoped, evidence = target_commands(commands, cwd, root, next(iter(libraries)))
    if evidence["unresolved_local_inputs"]:
        raise ValueError(f"Native target has unaudited local inputs: {evidence['unresolved_local_inputs']}")
    return {**evidence, **audit_commands(commands, profile, cwd, root, scoped_commands=scoped)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ninja", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--profile", choices=("o3", "thinlto", "o3-thinlto"), required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    directory = args.build_dir.resolve()
    # Ninja expands response-file contents in its command database even after
    # a successful build removes the transient response files.
    database = json.loads(subprocess.check_output(
        [str(args.ninja.resolve()), "-C", str(directory), "-t", "compdb", "-x"],
        text=True,
    ))
    commands = "\n".join("cd " + shlex.quote(entry["directory"]) + " && " + entry["command"]
                         for entry in database if entry["command"])
    print(json.dumps(verify(commands, args.profile, directory, args.root), indent=2))


if __name__ == "__main__":
    main()
