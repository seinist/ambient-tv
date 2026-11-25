"""Compile the current prepared LibretroDroid output wrapper for shader checks."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def build_output_wrapper(directory: Path) -> Path:
    header = ROOT / "third_party/libretrodroid/src/main/cpp/sdrtohdr.h"
    if not header.is_file():
        raise SystemExit("Run scripts/fetch_libretrodroid.sh before checking the production shader wrappers")
    compiler = shutil.which("g++") or shutil.which("c++")
    if compiler is None:
        raise SystemExit("C++ compiler required for shader output-wrapper verification")
    source = directory / "wrap.cpp"
    source.write_text(
        '#include "sdrtohdr.h"\n#include <iostream>\n#include <iterator>\n'
        'int main(int argc,char** argv) { '
        'std::string s((std::istreambuf_iterator<char>(std::cin)),std::istreambuf_iterator<char>());'
        'std::cout << ((argc>1 && std::string(argv[1])=="opaque") ? '
        'libretrodroid::wrapFragmentForOpaqueOutput(s) : libretrodroid::wrapFragmentForPq(s,203)); }\n'
    )
    target = directory / "wrap"
    subprocess.run([compiler, "-std=c++17", "-I", str(header.parent), str(source), "-o", str(target)], check=True)
    return target
