#!/usr/bin/env python3
"""Generate the selected Libretro GLES upscalers from pinned, unmodified sources.

The originals live outside Android assets, so the APK contains only the generated
Kotlin strings. --check verifies provenance and that generation is reproducible;
--validate compiles and links the actual GLES 100 programs with glslangValidator.
No network access, untrusted preset imports, LUTs, or native shader runtime needed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ORIGINALS = ROOT / "third_party_assets/game_shaders"
TARGET = ROOT / "app/src/main/java/com/ambient/tv/ui/games/GameUpscaleShaderSources.kt"
SOURCES = {
    **{f"SCALEFX_{i}": f"glsl/scalefx/shaders/scalefx-pass{i}.glsl" for i in range(5)},
    "SABR": "glsl/sabr/shaders/sabr-v3.0.glsl",
    "XBR_LV2": "glsl/xbr/shaders/xbr-lv2.glsl",
    "XBRZ_FREESCALE": "glsl/xbrz/shaders/xbrz-freescale.glsl",
    "XSAL_2X": "glsl/xsal/shaders/2xsal.glsl",
    "SUPER_EAGLE": "glsl/eagle/shaders/supereagle.glsl",
    "SCALEHQ2X": "glsl/scalehq/shaders/2xScaleHQ.glsl",
    "SUPER_2XSAI": "slang/edge-smoothing/eagle/shaders/super-2xsai.slang",
}

STOCK = """
#if defined(VERTEX)
attribute vec4 VertexCoord;
attribute vec4 TexCoord;
varying highp vec2 uv;
void main() { gl_Position = VertexCoord; uv = TexCoord.xy; }
#elif defined(FRAGMENT)
precision highp float;
uniform highp sampler2D Texture;
varying highp vec2 uv;
void main() { gl_FragColor = vec4(texture2D(Texture, uv).rgb, 1.0); }
#endif
""".lstrip()


def originals() -> dict[str, str]:
    manifest = json.loads((ORIGINALS / "gles-manifest.json").read_text())
    approved = {}
    for record in manifest["files"]:
        path = ORIGINALS / record["path"]
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != record["sha256"]:
            raise ValueError(f"Pinned upstream shader changed: {path}")
        approved[record["path"]] = raw.decode("utf-8")
    return {name: approved[path] for name, path in SOURCES.items()}


def adapt_glsl(source: str) -> str:
    # Use the upstream ES branches, with their default parameters. The native
    # renderer supplies the RetroArch attribute and uniform names verbatim.
    source = re.sub(r"^#version[^\n]*\n", "", source, flags=re.M)
    source = re.sub(r"^#pragma parameter[^\n]*\n", "", source, flags=re.M)
    # Pixel coordinates and the packed RGB comparison in SuperEagle require
    # full precision at 4K; mediump can overflow at 65536 or merge neighbours.
    source = source.replace("#define COMPAT_PRECISION mediump", "#define COMPAT_PRECISION highp")
    source = source.replace("#define PRECISION mediump", "#define PRECISION highp")
    # Sampler precision is independent from float precision in GLES. ScaleFX's
    # floating metric targets must not be quantized by an implicit lowp sampler.
    source = source.replace("uniform sampler2D ", "uniform highp sampler2D ")
    # The legacy xBR shader assigns RGB only. Android composes our surface using
    # alpha, so leaving it undefined can tint/hide the correctly filtered image.
    source = source.replace("FragColor.xyz = res;", "FragColor = vec4(res, 1.0);")
    # The legacy xBR program calls its input sampler 'decal'. Its fixed deltas
    # also need const qualifiers for an ES 100 global initializer dependency.
    source = re.sub(r"\bdecal\b", "Texture", source)
    source = re.sub(r"^vec4 (delta(?:_l|_u)?)\s*=", r"const vec4 \1 =", source, flags=re.M)
    # ES 100 has no non-square matrix types. Expand the three legacy xBR
    # vector-by-mat4x3 products into their identical four dot products.
    source = re.sub(
        r"mul\(\s*mat4x3\((\w+),\s*(\w+),\s*(\w+),\s*(\w+)\),\s*y_weight \* Y\s*\)",
        lambda m: "vec4(" + ", ".join(f"dot({value}, y_weight * Y)" for value in m.groups()) + ")",
        source,
    )
    return source


def adapt_super_2xsai(source: str) -> str:
    # This one has no upstream GLSL counterpart. Port only the Slang interface;
    # retain the complete DOSBox/guest(r) algorithm and its copyright notice.
    common, stages = source.split("#pragma stage vertex", 1)
    _, fragment = stages.split("#pragma stage fragment", 1)
    common = common[common.index("const vec3 dt"):]
    fragment = re.sub(r"^layout\([^\n]*\n", "", fragment, flags=re.M)
    fragment = fragment.replace("params.SourceSize", "vec4(TextureSize, 1.0 / TextureSize)")
    fragment = fragment.replace("texture(", "texture2D(").replace("0.0.xxx", "vec3(0.0)")
    fragment = fragment.replace("FragColor", "gl_FragColor")
    return """// Interface port of Libretro's pinned super-2xsai.slang; algorithm unchanged.
#if defined(VERTEX)
attribute vec4 VertexCoord;
attribute vec4 TexCoord;
varying highp vec2 vTexCoord;
void main() { gl_Position = VertexCoord; vTexCoord = TexCoord.xy; }
#elif defined(FRAGMENT)
precision highp float;
uniform highp sampler2D Texture;
#define Source Texture
uniform highp vec2 TextureSize;
varying highp vec2 vTexCoord;
""" + common + fragment + "\n#endif\n"


def adapted_sources() -> dict[str, str]:
    result = {}
    for name, source in originals().items():
        result[name] = adapt_super_2xsai(source) if name == "SUPER_2XSAI" else adapt_glsl(source)
    result["STOCK"] = STOCK
    return result


def stage_sources() -> dict[str, tuple[str, str]]:
    """Actual stage sources, also used by the native EGL rendering regression."""
    return {
        name: ("#version 100\n#define VERTEX\n" + source,
               "#version 100\n#define FRAGMENT\n" + source)
        for name, source in adapted_sources().items()
    }


def presets() -> dict[str, list[tuple[str, float, bool, bool]]]:
    """(source, scale relative to original, output linear sampling, float target).

    LibretroDroid's linear flag belongs to a pass OUTPUT, whereas RetroArch's
    filter_linearN belongs to that pass's INPUT. Only 2xSaL needs linear resolve.
    Fixed 2x/3x algorithms retain their native-sized output before final display.
    """
    chains = {
        "SCALEFX": [(f"SCALEFX_{i}", 3.0 if i == 4 else 1.0, False, i < 2) for i in range(5)] +
                   [("STOCK", 1.0, False, False)],
        "SABR": [("SABR", 1.0, False, False)],
        "XBR_LV2": [("XBR_LV2", 1.0, False, False)],
        "XBRZ_FREESCALE": [("XBRZ_FREESCALE", 1.0, False, False)],
        "XSAL_2X": [("XSAL_2X", 2.0, True, False), ("STOCK", 1.0, False, False)],
        "SUPER_2XSAI": [("SUPER_2XSAI", 2.0, False, False), ("STOCK", 1.0, False, False)],
        "SUPER_EAGLE": [("SUPER_EAGLE", 2.0, False, False), ("STOCK", 1.0, False, False)],
        "SCALEHQ2X": [("SCALEHQ2X", 2.0, False, False), ("STOCK", 1.0, False, False)],
    }
    # A hardware core may publish a cropped/flipped image inside a larger FBO.
    # Normalize it once using the native source UVs, before neighbour sampling
    # or a relative reference (ScaleFX PassPrev5) touches the source again.
    return {name: [("STOCK", 1.0, False, False)] + chain for name, chain in chains.items()}


def generated() -> str:
    lines = [
        "// Generated by scripts/import_game_upscalers.py; do not edit by hand.",
        "// Source revisions and SHA-256: third_party_assets/game_shaders/gles-manifest.json.",
        "// Each source retains its upstream copyright and license notices.",
        "package com.ambient.tv.ui.games", "", "internal object GameUpscaleShaderSources {",
    ]
    for name, source in adapted_sources().items():
        if '"""' in source or "$" in source:
            raise ValueError(f"Kotlin raw string delimiter in upstream {name}")
        lines.append(f'    const val {name} = """\n{source.rstrip()}\n"""')
    lines.append("}")
    return "\n".join(lines) + "\n"


def validate() -> None:
    compiler = shutil.which("glslangValidator")
    if compiler is None:
        raise SystemExit("glslangValidator required for --validate")
    with tempfile.TemporaryDirectory(prefix="ambient-upscalers-") as directory:
        wrapper = pq_wrapper(Path(directory))
        for name, (vertex, fragment) in stage_sources().items():
            paths = []
            for stage, source in (("vert", vertex), ("frag", fragment)):
                path = Path(directory) / f"{name}.{stage}"
                path.write_text(source)
                paths.append(str(path))
            result = subprocess.run([compiler, "-l", *paths], capture_output=True, text=True)
            if result.returncode:
                raise AssertionError(f"{name} GLES program did not compile/link:\n{result.stdout}\n{result.stderr}")
            # Exercise the exact production C++ sRGB -> HDR10 wrapper, including
            # the upstream stage #defines, on every possible final shader.
            final_sources = {chain[-1][0] for chain in presets().values()}
            if name in final_sources:
                wrapped = subprocess.run([str(wrapper)], input=fragment, capture_output=True, text=True, check=True)
                Path(paths[1]).write_text(wrapped.stdout)
                result = subprocess.run([compiler, "-l", *paths], capture_output=True, text=True)
                if result.returncode:
                    raise AssertionError(f"{name} PQ output did not compile/link:\n{result.stdout}\n{result.stderr}")
    print(f"OK: {len(stage_sources())} real GLES 100 programs compiled and linked")
    print(f"OK: all {len(final_sources)} final programs compile/link with the production HDR10 wrapper")


def pq_wrapper(directory: Path) -> Path:
    # Before fetch_libretrodroid.sh runs in CI, the pinned wrapper is available
    # as an added header in this patch. Compile that exact header, not a rewrite.
    patch = ROOT / "scripts/patches/libretrodroid/0033-sdr-to-hdr-output.patch"
    inside = False
    lines = []
    for line in patch.read_text().splitlines():
        if line.startswith("diff --git"):
            inside = line.endswith("b/src/main/cpp/sdrtohdr.h")
        elif inside and line.startswith("+") and not line.startswith("+++"):
            lines.append(line[1:])
    if not lines:
        raise AssertionError("The production SDR-to-HDR wrapper is missing")
    compiler = shutil.which("g++") or shutil.which("c++")
    if compiler is None:
        raise SystemExit("C++ compiler required for HDR10 wrapper verification")
    (directory / "sdrtohdr.h").write_text("\n".join(lines) + "\n")
    source = directory / "wrap.cpp"
    source.write_text(
        '#include "sdrtohdr.h"\n#include <iostream>\n#include <iterator>\n'
        'int main() { std::string s((std::istreambuf_iterator<char>(std::cin)), std::istreambuf_iterator<char>());'
        'std::cout << libretrodroid::wrapFragmentForPq(s, 203); }\n'
    )
    target = directory / "wrap"
    subprocess.run([compiler, "-std=c++17", str(source), "-o", str(target)], check=True)
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    output = generated()
    if args.check:
        if TARGET.read_text() != output:
            raise SystemExit("Generated GLES upscaler sources are stale; run scripts/import_game_upscalers.py")
    else:
        TARGET.write_text(output)
    print(f"OK: pinned originals verified; generated {len(output.encode())} bytes for {len(presets())} presets")
    if args.validate:
        validate()


if __name__ == "__main__":
    main()
