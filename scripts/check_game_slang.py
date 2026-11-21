#!/usr/bin/env python3
"""Compile each bundled Vulkan Slang stage; constrain preset size and pass dependencies.

This only tests original, pinned app assets. It never downloads or executes
unreviewed user shaders. Real Vulkan device/render validation is still required.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "app/src/main/assets/games/slang"
KOTLIN = ROOT / "app/src/main/java/com/ambient/tv/ui/games/GameVulkanSlangPresets.kt"
MAX_FILE_BYTES = 16 * 1024
MAX_TOTAL_ASSETS = 24 * 1024
PRESETS = {
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

def compile_stage(glslang, source, label, stage, directory):
    path = directory / (label + (".vert" if stage == "vert" else ".frag"))
    path.write_text(source, encoding="utf-8")
    result = subprocess.run(
        [glslang, "-V", "--target-env", "vulkan1.0", "-S", stage, str(path)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        raise SystemExit(f"{label} {stage} shader invalid:\n{result.stdout}\n{result.stderr}")

def main():
    expected_sources = {source for passes in PRESETS.values() for source in passes}
    actual_sources = {p.stem for p in SHADERS.glob("*.slang")}
    actual_presets = {p.stem for p in SHADERS.glob("*.slangp")}
    assert actual_sources == expected_sources, f"Unexpected shader files: {actual_sources ^ expected_sources}"
    assert actual_presets == set(PRESETS), f"Unexpected preset files: {actual_presets ^ set(PRESETS)}"
    mapped = set(re.findall(r'GameShader\.\w+\s+to\s+"([a-z0-9-]+)"', KOTLIN.read_text()))
    assert mapped == set(PRESETS), f"Shader UI names differ: {mapped ^ set(PRESETS)}"
    total = sum(p.stat().st_size for p in SHADERS.iterdir() if p.is_file())
    assert total <= MAX_TOTAL_ASSETS, f"Vulkan Slang source budget exceeded: {total}"
    glslang = shutil.which("glslangValidator")
    if glslang is None:
        raise SystemExit("glslangValidator required for Vulkan shader verification")
    with tempfile.TemporaryDirectory(prefix="ambient-native-slang-") as folder:
        location = Path(folder)
        for name in sorted(expected_sources):
            file = SHADERS / (name + ".slang")
            assert 0 < file.stat().st_size <= MAX_FILE_BYTES
            text = file.read_text(encoding="utf-8")
            assert text.count("#pragma stage vertex") == 1
            assert text.count("#pragma stage fragment") == 1
            header, after_vertex = text.split("#pragma stage vertex", 1)
            vertex, fragment = after_vertex.split("#pragma stage fragment", 1)
            assert header.startswith("#version 450\n")
            assert "layout(std140, set = 0, binding = 0) uniform UBO" in header
            assert "layout(set=0,binding=2) uniform sampler2D Source;" in fragment
            compile_stage(glslang, header + vertex, name, "vert", location)
            compile_stage(glslang, header + fragment, name, "frag", location)
        for name, sources in sorted(PRESETS.items()):
            file = SHADERS / (name + ".slangp")
            assert 0 < file.stat().st_size <= 1024
            directives = [x.strip() for x in file.read_text(encoding="utf-8").splitlines() if x.strip()]
            assert directives[0] == f"shaders = {len(sources)}"
            assert len(sources) <= 2, "Unexpected framebuffer pressure"
            for i, source in enumerate(sources):
                assert f"shader{i} = {source}.slang" in directives
                assert f"filter_linear{i} = true" in directives or f"filter_linear{i} = false" in directives
                scale_mode = "viewport" if i == len(sources)-1 else "source"
                assert f"scale_type{i} = {scale_mode}" in directives
                if i < len(sources) - 1:
                    # Multi-pass CRT bloom allocates only a quarter-size source
                    # frame for its intermediate, not another full-resolution 4K FBO.
                    assert f"scale{i} = 0.5" in directives
            assert len(directives) <= 1 + len(sources) * 4
    print(f"OK: {len(PRESETS)} original Slang presets / {len(expected_sources)} stages, {total} bytes")

if __name__ == "__main__":
    main()
