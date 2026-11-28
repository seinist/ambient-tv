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
    # Specialize the mapped no-cache RAM handlers inside their own TU, where
    # the exact upstream table targets are visible. All other targets retain
    # the original indirect call; do not guess mappings from the address.
    memory = root / "yabause/src/memory.c"
    code = memory.read_text()
    for width, ctype in (("Byte", "u8"), ("Word", "u16"), ("Long", "u32")):
        size = {"Byte": 1, "Word": 2, "Long": 4}[width]
        index = "(addr >> 16) & 0xFFF"
        read = f"return sh->Read{width}List[{index}](sh, addr);"
        write = f"sh->Write{width}List[{index}](sh, addr, val);"
        for direction, anchor in (("Read", read), ("Write", write)):
            if code.count(anchor) != 1:
                raise ValueError(f"Yabause mapped RAM dispatch changed: {width}/{direction}")
            decl = f"{direction.lower()}{width.lower()}func target = sh->{direction}{width}List[{index}];"
            body = decl + "\n         #if !defined(SH2_TRACE)\n"
            for ram in ("High", "Low"):
                # T2 functions use the core's byte-swapped backing format.
                # Alignment and mapping checks preserve unusual accesses.
                call = f"T2{direction}{width}({ram}Wram, addr & 0xFFFFF"
                if direction == "Read":
                    stmt = f"return {call});"
                else:
                    stmt = f"{{ {call}, val); return; }}"
                body += (f"         if (target == Sh2{ram}WramMemory{direction}{width} && {ram}Wram && "
                         f"!(addr & {size-1}u)) {stmt}\n")
            body += "         #endif\n         "
            body += "return target(sh, addr);" if direction == "Read" else "target(sh, addr, val);"
            code = code.replace(anchor, body)
    memory.write_text(code)
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
