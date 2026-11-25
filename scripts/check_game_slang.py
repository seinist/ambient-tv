#!/usr/bin/env python3
"""Validate every pinned Vulkan Slang dependency and compile both stages.

The original compact effects and the small vendored Libretro subset have fixed
dependency closures. No downloaded or user-provided preset is accepted here.
SPIR-V validation does not replace Android GPU/render acceptance testing.
"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "app/src/main/assets/games/slang"
KOTLIN = ROOT / "app/src/main/java/com/ambient/tv/ui/games/GameVulkanSlangPresets.kt"
MAX_FILE_BYTES = 16 * 1024
MAX_TOTAL_ASSETS = 192 * 1024  # Includes the complete GPL license texts and provenance.
MAX_PRESET_BYTES = 96 * 1024
UPSTREAM_COMMIT = "02bf134555c4595bd6b69be89feaff923da83754"
UPSTREAM_ROOT = "upstream/edge-smoothing/"
BICUBIC = (
    "upstream/interpolation/shaders/bicubic/bicubic-y",
    "upstream/interpolation/shaders/bicubic/bicubic-x",
)
ORIGINAL_PRESETS = {
    "crt-light": ("crt-light",),
    "crt-geometry": ("crt-geometry",),
    "crt-aperture": ("crt-aperture",),
    "crt-glow": ("crt-glow-horizontal", "crt-glow-final"),
    "gb-dmg": ("gb-dmg",),
    "gbc-grid": ("gbc-grid",),
    "ntsc": ("ntsc",),
    "color-boost": ("color-boost",),
    "sharp-bilinear": ("sharp-bilinear",),
}
UPSTREAM_PRESETS = {
    UPSTREAM_ROOT + "scalefx/scalefx": ("upstream/stock",) + tuple(
        UPSTREAM_ROOT + f"scalefx/shaders/scalefx-pass{i}" for i in range(5)
    ),
    UPSTREAM_ROOT + "sabr/sabr": (UPSTREAM_ROOT + "sabr/shaders/sabr-v3.0",),
    UPSTREAM_ROOT + "xbr/other presets/xbr-lv2-standalone": (
        UPSTREAM_ROOT + "xbr/shaders/xbr-lv2-standalone",
    ),
    UPSTREAM_ROOT + "xbrz/xbrz-freescale": (UPSTREAM_ROOT + "xbrz/shaders/xbrz-freescale",),
    UPSTREAM_ROOT + "xsal/2xsal": (UPSTREAM_ROOT + "xsal/shaders/2xsal",) + BICUBIC,
    UPSTREAM_ROOT + "eagle/super-2xsai": (UPSTREAM_ROOT + "eagle/shaders/super-2xsai",) + BICUBIC,
    UPSTREAM_ROOT + "eagle/supereagle": (UPSTREAM_ROOT + "eagle/shaders/supereagle",),
    UPSTREAM_ROOT + "scalehq/2xScaleHQ": (UPSTREAM_ROOT + "scalehq/shaders/2xScaleHQ",) + BICUBIC,
}
PRESETS = ORIGINAL_PRESETS | UPSTREAM_PRESETS


def parse_preset(text):
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            assert not line.startswith("#reference"), "Preset imports are not allowlisted"
            continue
        key, separator, value = line.partition("=")
        assert separator, f"Invalid preset line: {line}"
        key, value = key.strip(), value.strip().strip('"')
        assert key not in values, f"Duplicate directive: {key}"
        values[key] = value
    return values


def resolve_source(preset, relative, shaders=SHADERS):
    """Resolve an upstream relative reference inside the bundled asset root."""
    assert not Path(relative).is_absolute(), "Absolute shader dependency"
    resolved = (preset.parent / relative).resolve()
    assert resolved.is_relative_to(shaders.resolve()), "Shader dependency escapes asset root"
    assert resolved.suffix == ".slang" and resolved.is_file(), f"Missing shader: {relative}"
    return resolved.relative_to(shaders.resolve()).with_suffix("").as_posix()


def validate_assets():
    expected_sources = {source for passes in PRESETS.values() for source in passes}
    actual_sources = {p.relative_to(SHADERS).with_suffix("").as_posix() for p in SHADERS.rglob("*.slang")}
    actual_presets = {p.relative_to(SHADERS).with_suffix("").as_posix() for p in SHADERS.rglob("*.slangp")}
    assert actual_sources == expected_sources, f"Unexpected shader files: {actual_sources ^ expected_sources}"
    assert actual_presets == set(PRESETS), f"Unexpected presets: {actual_presets ^ set(PRESETS)}"

    # Check every copied dependency, not only the enum-to-preset mapping. A missing
    # shared bicubic or reference pass must fail CI instead of failing on the TV.
    registry = KOTLIN.read_text(encoding="utf-8")
    entries = re.findall(r'GameShader\.\w+\s+to\s+preset\((.*?)\),', registry, re.S)
    copied = {}
    for entry in entries:
        paths = re.findall(r'"([^"\n]+\.(?:slang|slangp))"', entry)
        assert paths and paths[0].endswith(".slangp"), "Invalid Kotlin Slang entry"
        copied[paths[0][:-7]] = tuple(path[:-6] for path in paths[1:])
    assert copied == PRESETS, "Kotlin Slang registry differs from the complete preset dependencies"

    total = sum(p.stat().st_size for p in SHADERS.rglob("*") if p.is_file())
    assert total <= MAX_TOTAL_ASSETS, f"Vulkan Slang source budget exceeded: {total}"
    for source in expected_sources:
        path = SHADERS / (source + ".slang")
        assert not path.is_symlink() and 0 < path.stat().st_size <= MAX_FILE_BYTES

    for name, sources in PRESETS.items():
        file = SHADERS / (name + ".slangp")
        assert not file.is_symlink() and 0 < file.stat().st_size <= 1024
        values = parse_preset(file.read_text(encoding="utf-8"))
        assert int(values["shaders"]) == len(sources)
        assert len(sources) <= 6, "Unexpected framebuffer pressure"
        assert "textures" not in values and "feedback_pass" not in values
        size = file.stat().st_size
        for i, source in enumerate(sources):
            assert resolve_source(file, values[f"shader{i}"]) == source
            assert values.get(f"filter_linear{i}") in ("true", "false")
            size += (SHADERS / (source + ".slang")).stat().st_size
        assert size <= MAX_PRESET_BYTES, f"Preset closure is too large: {name}"
        if name in ORIGINAL_PRESETS:
            assert len(sources) <= 2
            for i in range(len(sources)):
                assert values[f"scale_type{i}"] == ("viewport" if i == len(sources) - 1 else "source")
                if i < len(sources) - 1:
                    assert values[f"scale{i}"] == "0.5"
            assert len(values) <= 1 + len(sources) * 4

    manifest = json.loads((SHADERS / "upstream/PROVENANCE.json").read_text(encoding="utf-8"))
    assert manifest["repository"] == "https://github.com/libretro/slang-shaders"
    assert manifest["commit"] == UPSTREAM_COMMIT
    expected_upstream = {
        p.relative_to(SHADERS / "upstream").as_posix()
        for pattern in ("*.slang", "*.slangp") for p in (SHADERS / "upstream").rglob(pattern)
    }
    assert set(manifest["files"]) == expected_upstream, "Incomplete upstream provenance"
    for relative, identity in manifest["files"].items():
        data = (SHADERS / "upstream" / relative).read_bytes()
        assert len(data) == identity["bytes"]
        assert hashlib.sha256(data).hexdigest() == identity["sha256"], f"Changed upstream asset: {relative}"
        git_blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        assert git_blob == identity["git_blob_sha1"], f"Asset differs from the pinned Git blob: {relative}"
    for license_name in ("GPL-2.0.txt", "GPL-3.0.txt", "NOTICE.txt"):
        assert (SHADERS / "upstream" / license_name).is_file(), f"Missing license: {license_name}"
    return expected_sources, total


def compile_stage(glslang, source, label, stage, directory):
    stem = re.sub(r"[^a-zA-Z0-9_-]", "_", label)
    path = directory / (stem + (".vert" if stage == "vert" else ".frag"))
    path.write_text(source, encoding="utf-8")
    result = subprocess.run(
        [glslang, "-V", "--target-env", "vulkan1.0", "-S", stage,
         "-o", str(path) + ".spv", str(path)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        raise SystemExit(f"{label} {stage} shader invalid:\n{result.stdout}\n{result.stderr}")


def main():
    sources, total = validate_assets()
    glslang = shutil.which("glslangValidator")
    if glslang is None:
        raise SystemExit("glslangValidator required for Vulkan shader verification")
    with tempfile.TemporaryDirectory(prefix="ambient-native-slang-") as folder:
        for name in sorted(sources):
            text = (SHADERS / (name + ".slang")).read_text(encoding="utf-8")
            assert text.count("#pragma stage vertex") == 1
            assert text.count("#pragma stage fragment") == 1
            assert "#include" not in text, "Untracked shader include"
            assert not re.search(r"\b(?:OriginalHistory|PassFeedback)\d*\b", text), "Unbudgeted frame history"
            header, after_vertex = text.split("#pragma stage vertex", 1)
            vertex, fragment = after_vertex.split("#pragma stage fragment", 1)
            assert header.startswith("#version 450\n")
            assert re.search(r"uniform\s+sampler2D\s+Source\s*;", fragment)
            compile_stage(glslang, header + vertex, name, "vert", Path(folder))
            compile_stage(glslang, header + fragment, name, "frag", Path(folder))
    print(f"OK: {len(PRESETS)} Slang presets / {len(sources)} shader sources, {total} bytes including licenses")


if __name__ == "__main__":
    main()
