#!/usr/bin/env python3
"""Compute independent native cache keys from tracked source and dependency pins."""
import argparse
from fnmatch import fnmatchcase
import hashlib
from pathlib import Path
import subprocess

GROUPS = {
  "mpv": {
    "pins": [
      "MPV_",
      "FFMPEG_",
      "SVPFLOW_",
      "PROJECTM_"
    ],
    "inputs": [
      "native/ambientmpv/**",
      "native/ambientsvp/**",
      "native/ambientprojectm/native_audio_tap.h",
      "native/ambientaudio/**",
      "scripts/*mpv*",
      "scripts/*ffmpeg*",
      "scripts/*local_audio*",
      "scripts/*svp*",
      "scripts/patches/mpv/**",
      "scripts/patches/svp/**"
    ]
  },
  "vlc": {
    "pins": [
      "SVPFLOW_",
      "PROJECTM_",
      "FFMPEG_"
    ],
    "inputs": [
      "native/vlc*/**",
      "native/ambientsvp/**",
      "native/ambientprojectm/native_audio_tap.h",
      "scripts/*vlc*",
      "scripts/*svp*",
      "scripts/patches/vlc*/**",
      "scripts/patches/svp/**"
    ]
  },
  "projectm": {
    "pins": [
      "PROJECTM_"
    ],
    "inputs": [
      "native/ambientprojectm/**",
      "scripts/*projectm*",
      "scripts/patches/projectm/**",
      "app/src/main/assets/projectm/**"
    ]
  },
  "svp": {
    "pins": [
      "SVPFLOW_"
    ],
    "inputs": [
      "native/ambientsvp/**",
      "scripts/*svp*",
      "scripts/patches/svp/**"
    ]
  },
  "shaders": {
    "pins": [
      "LIBRASHADER_"
    ],
    "inputs": [
      "scripts/*librashader*",
      "scripts/native_libretro_vulkan/**",
      "scripts/patches/librashader/**"
    ]
  },
  "monogram": {
    "pins": [
      "MONOGRAM_"
    ],
    "inputs": [
      "scripts/*monogram*",
      "scripts/patches/monogram/**",
      "native/monogram*/**"
    ]
  },
  "spotify": {
    "pins": [
      "SPOTIFY_"
    ],
    "inputs": [
      "scripts/*spotify*",
      "scripts/patches/spotify/**",
      "native/spotify*/**"
    ]
  },
  "libretrodroid": {
    "pins": [
      "LIBRETRODROID_",
      "LIBRASHADER_"
    ],
    "inputs": [
      "scripts/*libretro*",
      "scripts/patches/libretrodroid/**",
      "scripts/native_rewind/**",
      "scripts/native_libretro_vulkan/**",
      "libretrodroid_wrapper/**"
    ]
  },
  "smarttube": {
    "pins": [
      "SMARTTUBE_"
    ],
    "inputs": [
      "scripts/*smarttube*",
      "scripts/patches/smarttube/**",
      "smarttube_wrappers/**"
    ]
  }
}
COMMON = ("build.sh", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
          "gradle/**", "gradlew", "scripts/native_cache_keys.py", ".github/workflows/release.yml")
PINS = "gradle/external-versions.properties"


def selected(path, group):
    if path == PINS:
        return False
    return any(fnmatchcase(path, pattern) for pattern in (*COMMON, *GROUPS[group]["inputs"]))


def compute(group, tracked, read, toolchain, pins):
    digest = hashlib.sha256()
    def add(name, value):
        digest.update(name.encode() + b"\0" + str(len(value)).encode() + b"\0" + value)
    add("toolchain", toolchain.encode())
    for line in sorted(pins.splitlines()):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name = line.split("=", 1)[0]
        if name.startswith(tuple(GROUPS[group]["pins"])) or not any(name.startswith(tuple(g["pins"])) for g in GROUPS.values()):
            add("pin:" + name, line.encode())
    for path in sorted(tracked):
        if selected(path, group):
            add(path, read(path))
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--toolchain", required=True)
    args = parser.parse_args()
    # Read tracked blobs from HEAD rather than generated/restored working files.
    tracked = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "-z", "HEAD"]).decode().strip("\0").split("\0")
    def read(path):
        return subprocess.check_output(["git", "show", "HEAD:" + path])
    pins = read(PINS).decode()
    for group in GROUPS:
        print(f"{group}={compute(group, tracked, read, args.toolchain, pins)}")


if __name__ == "__main__":
    main()
