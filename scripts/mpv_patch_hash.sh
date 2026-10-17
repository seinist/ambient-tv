#!/usr/bin/env bash
# Invalidate packaged runtimes when a project MPV patch changes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cat "$root"/native/ambientmpv/patches/mpv-*.patch | sha256sum | cut -d ' ' -f1
