#!/usr/bin/env python3
"""Vendor the additional NNEDI3 quality presets into Android res/raw.

nns16/nns32 are maintained in the source tree; this helper fetches the three
larger win8x4 presets plus the explicit nns256/win8x6 Expert preset from the
same pinned mpv-prescalers revision. Playback never needs a network fetch for
shaders.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "app/src/main/res/raw"
REVISION = "b3f0a59d68f33b7162051ea5970a5169558f0ea2"
REV_SHORT = "b3f0a59"
BASE = f"https://raw.githubusercontent.com/bjin/mpv-prescalers/{REVISION}"
MAX_BYTES = 2_000_000

PRESETS = {
    "nns64": (64, "win8x4", "nnedi3-nns64-win8x4.hook"),
    "nns128": (128, "win8x4", "nnedi3-nns128-win8x4.hook"),
    "nns256": (256, "win8x4", "nnedi3-nns256-win8x4.hook"),
    "nns256_win8x6": (256, "win8x6", "nnedi3-nns256-win8x6.hook"),
}


def destination(neurons: int, window: str) -> Path:
    return RAW / f"nnedi3_nns{neurons}_{window}_{REV_SHORT}.hook"


def valid(data: bytes, neurons: int, window: str) -> bool:
    if not (1_000 <= len(data) <= MAX_BYTES):
        return False
    try:
        source = data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    if "GNU Lesser General Public License" not in source or "//!HOOK LUMA" not in source:
        return False
    suffix = f"nns{neurons}, {window})"
    return all(
        f"//!DESC NNEDI3 ({stage}, {suffix}" in source
        for stage in ("double_y", "combine_y", "double_x", "combine_x")
    )


def fetch(neurons: int, window: str, file_name: str) -> bytes:
    request = urllib.request.Request(
        f"{BASE}/{file_name}",
        headers={"User-Agent": "Ambient TV-NNEDI3-vendor"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        data = response.read(MAX_BYTES + 1)
    if not valid(data, neurons, window):
        raise RuntimeError(f"upstream nns{neurons}/{window} shader failed validation")
    return data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify only; never download")
    args = parser.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)

    failures: list[str] = []
    for label, (neurons, window, source_name) in PRESETS.items():
        out = destination(neurons, window)
        if out.is_file() and valid(out.read_bytes(), neurons, window):
            print(f"NNEDI3 {label}: vendored ({out.relative_to(ROOT)})")
            continue
        if out.exists():
            out.unlink()
        if args.check:
            failures.append(f"{label}: missing or invalid")
            continue
        try:
            data = fetch(neurons, window, source_name)
            tmp = out.with_suffix(out.suffix + ".tmp")
            tmp.write_bytes(data)
            tmp.replace(out)
            print(f"NNEDI3 {label}: downloaded {len(data)} bytes")
        except Exception as exc:  # noqa: BLE001 - build helper must give one concise failure
            failures.append(f"{label}: {exc}")

    if failures:
        for failure in failures:
            print(f"WARNING: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
