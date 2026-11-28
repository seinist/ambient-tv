#!/usr/bin/env python3
"""Reserve a local APK version without changing tracked files until packaging succeeds."""
import fcntl
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile


def property_value(data, key):
    values = re.findall(r"(?m)^[ \t]*" + key + r"[ \t]*=[ \t]*(.*)$", data)
    if len(values) != 1:
        raise ValueError(f"Expected exactly one {key} in version.properties")
    return values[0].strip()


def atomic_write(path, data):
    fd, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), stat.S_IMODE(path.stat().st_mode))
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def persist_version(root, original, name, code):
    version = root / "version.properties"
    if version.read_bytes() != original:
        raise RuntimeError("version.properties changed during the build; refusing to overwrite it")
    badge = ("https://img.shields.io/badge/Version-" +
             name.replace("-", "--").replace("_", "__").replace(" ", "%20") +
             f"%20%28{code}%29-E5A00D")
    changes = []
    for filename in ("README.md", "README.de.md"):
        path = root / filename
        if not path.is_file():
            raise RuntimeError(f"Missing {filename}")
        before = path.read_bytes()
        after, count = re.subn(r"<!-- version:start -->.*?<!-- version:end -->",
                               lambda _: f"<!-- version:start -->"+chr(96)+f"{name}"+chr(96)+f" ({code})<!-- version:end -->",
                               before.decode("utf-8"))
        after, badges = re.subn(r"<!-- version-badge:start -->.*?<!-- version-badge:end -->",
                                lambda _: f"<!-- version-badge:start -->\n\n[![Version]({badge})](#)\n\n<!-- version-badge:end -->",
                                after, flags=re.S)
        if count != 1 or badges != 1:
            raise RuntimeError(f"Invalid version markers in {filename}")
        changes.append((path, before, after.encode("utf-8")))
    after = re.sub(r"(?m)^[ \t]*VERSION_CODE[ \t]*=.*$", f"VERSION_CODE={code}", original.decode("utf-8"))
    changes.append((version, original, after.encode("utf-8")))
    completed = []
    try:
        for path, before, after in changes:
            if path.read_bytes() != before:
                raise RuntimeError(f"{path.name} changed during version finalization")
            atomic_write(path, after)
            completed.append((path, before))
    except BaseException:
        for path, before in reversed(completed):
            atomic_write(path, before)
        raise


def run(root, args):
    lock_dir = root / ".native"
    lock_dir.mkdir(exist_ok=True)
    with (lock_dir / "local-build.lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        original = (root / "version.properties").read_bytes()
        text = original.decode("utf-8")
        stored = int(property_value(text, "VERSION_CODE"))
        if not 0 < stored < 2100000000:
            raise ValueError("VERSION_CODE is outside the incrementable Android range")
        name = os.environ.get("ORG_GRADLE_PROJECT_versionName", "").strip() or property_value(text, "VERSION_NAME")
        if not re.fullmatch(r"[0-9A-Za-z._+-]{1,64}", name):
            raise ValueError("Invalid version name")
        code = stored + 1
        env = dict(os.environ, AMBIENT_TV_LOCAL_BUILD_CODE=str(code), AMBIENT_TV_LOCAL_BUILD_NAME=name)
        print(f"Reserved build number {code} (not persisted yet)", flush=True)
        child = subprocess.Popen(["bash", str(root / "build.sh"), *args], cwd=root,
                                 env=env, pass_fds=(lock.fileno(),))
        def terminate(signum, frame):
            if child.poll() is None:
                child.send_signal(signum)
            raise SystemExit(128 + signum)
        previous = signal.signal(signal.SIGTERM, terminate)
        try:
            status = child.wait()
        except KeyboardInterrupt:
            if child.poll() is None:
                child.send_signal(signal.SIGINT)
                child.wait()
            return 130
        finally:
            signal.signal(signal.SIGTERM, previous)
        if status:
            return status if status > 0 else 128 - status
        persist_version(root, original, name, code)
        print(f"Build completed: versionCode {stored} -> {code}", flush=True)
        return 0


if __name__ == "__main__":
    try:
        sys.exit(run(Path(sys.argv[1]).resolve(), sys.argv[2:]))
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
