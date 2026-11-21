#!/usr/bin/env python3
"""Validate all compact game shaders as GLSL ES 100 before testing on devices."""
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/ambient/tv/ui/games/GameRetroShaders.kt"
EXPECTED = {"SHARP_BILINEAR","PIXEL_AA","CRT_LIGHT","CRT_GEOMETRY","CRT_APERTURE",
            "CRT_GLOW","GAMEBOY_DMG","GBC_GRID","GBA_COLOR","DS_LCD","NTSC","VHS",
            "FILM_GRAIN","DEBAND","COLOR_BOOST"}

def main():
    raw = SRC.read_bytes()
    assert len(raw) <= 16*1024, f"Game shader source too large: {len(raw)}"
    programs = dict(re.findall(r'private const val (\w+)\s*=\s*"""(.*?)"""', raw.decode(), re.S))
    assert set(programs) == EXPECTED | {"HEADER","VERTEX"}, (
        f"Missing/unexpected shader source: {set(programs) ^ (EXPECTED | {'HEADER','VERTEX'})}")
    compiler = shutil.which("glslangValidator")
    if not compiler:
        raise SystemExit("glslangValidator required; install glslang-tools")
    with tempfile.TemporaryDirectory(prefix="game-glsl-") as directory:
        out = Path(directory)
        validate(compiler, out / "game.vert", "vert", "#version 100\n"+programs["VERTEX"])
        for name in sorted(EXPECTED):
            src = "#version 100\n"+programs["HEADER"]+"\nconst float INTENSITY = 1.0;\n"+programs[name]
            validate(compiler, out / (name.lower()+".frag"), "frag", src)
        optional = ROOT / "shader_packs/shaders"
        optionalFiles = sorted(optional.glob("*.frag"))
        for sourceFile in optionalFiles:
            code = sourceFile.read_text("utf-8")
            assert 0 < sourceFile.stat().st_size <= 16 * 1024, "Optional shader exceeds budget"
            validate(compiler, out / sourceFile.name, "frag", "#version 100\n" + code)
    print(f"OK: {len(EXPECTED)} built-ins + {len(optionalFiles)} optional GLES 100 shaders; {len(raw)} Kotlin source bytes")

def validate(compiler, path, stage, source):
    path.write_text(source)
    result = subprocess.run([compiler,"-S",stage,str(path)],capture_output=True,text=True)
    if result.returncode:
        raise SystemExit(f"{path.name} invalid:\n{result.stdout}\n{result.stderr}")

if __name__ == "__main__":
    main()
