"""Verify the flags on the configured native LibretroDroid Ninja target."""
import argparse
import json
from pathlib import Path
import subprocess

from core_build_policy import audit_commands
from core_acceleration_audit import compiler_commands


def verify(commands, profile, cwd, root):
    scoped = list(compiler_commands(commands, cwd, root, strict=True))
    # Ninja -t commands <target> includes all commands for that target and its
    # dependencies, not commands for the rest of the Gradle application.
    if not any("-shared" in args and any(
            Path(arg).name == "liblibretrodroid.so" for arg in args)
            for args in scoped):
        raise ValueError("Missing LibretroDroid shared-link command")
    return audit_commands(commands, profile, cwd, root, scoped_commands=scoped)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ninja", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--profile", choices=("o3", "thinlto", "o3-thinlto"), required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    directory = args.build_dir.resolve()
    commands = subprocess.check_output(
        [str(args.ninja.resolve()), "-C", str(directory), "-t", "commands", "libretrodroid"],
        text=True,
    )
    print(json.dumps(verify(commands, args.profile, directory, args.root), indent=2))


if __name__ == "__main__":
    main()
