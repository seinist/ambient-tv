#!/usr/bin/env python3
"""Static validation for bundled original Vulkan Slang presets, not full RetroArch packs.

The two #pragma stage sections compile independently as Vulkan 1.0 GLSL 450. Source
and preset size limits keep additional APK overhead small. Runtime compilation on
an Android Vulkan GPU is still part of device acceptance, not implied by this test.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "app/src/main/assets/games/slang"
KOTLIN = ROOT / "app/src/main/java/com/ambient/tv/ui/games/GameVulkanSlangPresets.kt"
MAX_FILE = 16 * 1024
EXPECTED = {"crt-light", "crt-geometry", "crt-aperture", "gb-dmg", "gbc-grid",
            "ntsc", "color-boost", "sharp-bilinear"}

def validate(glslang: str, source: str, label: str, stage: str, work: Path) -> None:
    target = work / (label + (".vert" if stage == "vert" else ".frag"))
    target.write_text(source, encoding="utf-8")
    result = subprocess.run(
        [glslang, "-V", "--target-env", "vulkan1.0", "-S", stage, str(target)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        raise SystemExit(f"{label} {stage} invalid:\n{result.stdout}\n{result.stderr}")

def main() -> None:
    actual = {p.stem for p in SHADERS.glob("*.slang")}
    assert actual == EXPECTED, f"Slang source catalog differs: {actual ^ EXPECTED}"
    selected = set(re.findall(r'GameShader\.\w+\s+to\s+"([a-z0-9-]+)"', KOTLIN.read_text()))
    assert selected == EXPECTED, f"Native UI catalog not identical: {selected ^ EXPECTED}"
    glslang = shutil.which("glslangValidator")
    if not glslang:
        raise SystemExit("glslangValidator is required")
    with tempfile.TemporaryDirectory(prefix="ambient-slang-") as location:
        work = Path(location)
        for name in sorted(EXPECTED):
            file = SHADERS / (name + ".slang")
            preset = SHADERS / (name + ".slangp")
            assert 0 < file.stat().st_size <= MAX_FILE
            assert 0 < preset.stat().st_size <= 1024
            raw = file.read_text(encoding="utf-8")
            assert raw.count("#pragma stage vertex") == 1
            assert raw.count("#pragma stage fragment") == 1
            common, after_vertex = raw.split("#pragma stage vertex", 1)
            vertex, fragment = after_vertex.split("#pragma stage fragment", 1)
            assert common.startswith("#version 450\n")
            assert "layout(std140, set = 0, binding = 0) uniform UBO" in common
            assert "layout(set=0,binding=2) uniform sampler2D Source;" in fragment
            validate(glslang, common + vertex, name, "vert", work)
            validate(glslang, common + fragment, name, "frag", work)
            settings = preset.read_text(encoding="utf-8").splitlines()
            assert settings[0] == "shaders = 1" and settings[1] == f"shader0 = {name}.slang"
            assert len(settings) == 4
            assert settings[2] in {"filter_linear0 = false", "filter_linear0 = true"}
            assert settings[3] == "scale_type0 = viewport"
        print(f"OK: {len(EXPECTED)} compiled original Vulkan Slang presets with small assets")

if __name__ == "__main__":
    main()
