#!/usr/bin/env python3
"""Validate all compact game shaders as GLSL ES 100 before testing on devices."""
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/ambient/tv/ui/games/GameRetroShaders.kt"
SDR_TO_HDR_PATCH = ROOT / "scripts/patches/libretrodroid/0033-sdr-to-hdr-output.patch"
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
        wrapped = check_sdr_to_hdr(compiler, out, programs)
    print(f"OK: {len(EXPECTED)} built-ins + {len(optionalFiles)} optional GLES 100 shaders; {len(raw)} Kotlin source bytes; "
          f"{wrapped} PQ-wrapped")

def check_sdr_to_hdr(compiler, out, programs):
    """The SDR-to-HDR patch rewrites the last picture pass; every wrapped built-in must still compile."""
    lines, inside = [], False
    for line in SDR_TO_HDR_PATCH.read_text("utf-8").splitlines():
        if line.startswith("diff --git"):
            inside = line.endswith("b/src/main/cpp/sdrtohdr.h")
        elif inside and line.startswith("+") and not line.startswith("+++"):
            lines.append(line[1:])
    assert lines, "sdrtohdr.h missing from the SDR-to-HDR patch"
    cxx = shutil.which("g++") or shutil.which("c++")
    if not cxx:
        raise SystemExit("g++ required for the SDR-to-HDR shader check")
    (out / "sdrtohdr.h").write_text("\n".join(lines) + "\n")
    (out / "wrap.cpp").write_text(
        '#include "sdrtohdr.h"\n#include <iostream>\n#include <iterator>\n'
        'int main(){std::string s((std::istreambuf_iterator<char>(std::cin)),std::istreambuf_iterator<char>());'
        'std::cout<<libretrodroid::wrapFragmentForPq(s,203);}\n')
    subprocess.run([cxx, "-std=c++17", "-o", str(out / "wrap"), str(out / "wrap.cpp")], check=True)
    count = 0
    for name in sorted(EXPECTED):
        for version in ("#version 100\n", ""):
            src = version + programs["HEADER"] + "\nconst float INTENSITY = 1.0;\n" + programs[name]
            result = subprocess.run([str(out / "wrap")], input=src, capture_output=True, text=True, check=True)
            stage_source = result.stdout if version else "#version 100\n" + result.stdout
            validate(compiler, out / (name.lower() + "-pq" + str(len(version)) + ".frag"), "frag", stage_source)
            count += 1
    return count

def validate(compiler, path, stage, source):
    path.write_text(source)
    result = subprocess.run([compiler,"-S",stage,str(path)],capture_output=True,text=True)
    if result.returncode:
        raise SystemExit(f"{path.name} invalid:\n{result.stdout}\n{result.stderr}")

if __name__ == "__main__":
    main()
