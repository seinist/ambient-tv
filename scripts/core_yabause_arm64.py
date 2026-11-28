"""Install the bounded ARM64 SH2 ALU block candidate into pinned Yabause."""
import argparse
from pathlib import Path
import shutil

def prepare(root):
    path = root / "yabause/src/sh2int.c"
    source = path.read_text()
    marker = "void SH2InterpreterDeInit()\n{"
    loop = "      while (context->cycles < cycles)\n      {\n         const u32 pc = context->regs.PC;"
    if source.count(marker) != 1 or source.count(loop) != 1:
        raise ValueError("Yabause interpreter layout changed")
    source = source.replace(marker, '#include "ambient_sh2_arm64.h"\n\n' + marker +
                            "\n   ambient_sh2_clear();")
    source = source.replace(loop, loop.replace("         const u32", 
                            "         if (ambient_sh2_try(context, cycles)) continue;\n         const u32"))
    path.write_text(source)
    shutil.copyfile(Path(__file__).parent / "native_core_candidates/sh2_arm64.h",
                    path.with_name("ambient_sh2_arm64.h"))
    shutil.copyfile(Path(__file__).parent / "native_core_candidates/sh2_memory.h",
                    path.with_name("ambient_sh2_memory.h"))
    makefile = root / "yabause/src/libretro/jni/Android.mk"
    source = makefile.read_text()
    marker = "COREFLAGS := $(FLAGS)"
    if source.count(marker) != 1:
        raise ValueError("Yabause Android flags changed")
    makefile.write_text(source.replace(marker, marker + " -DAMBIENT_SH2_ARM64_JIT=1"))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    prepare(parser.parse_args().root)
