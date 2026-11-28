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
    source = source.replace(marker, """
static const void* ambient_sh2_source_view(SH2_struct* context,fetchfunc fetch,u32 addr,unsigned count)
{
    (void)context;
    const u8* base=fetch==FetchBios?BiosRom:fetch==FetchLWram?LowWram:
                   fetch==FetchHWram?HighWram:NULL;
    const u32 size=fetch==FetchBios?0x80000u:0x100000u;
    const u32 offset=addr&(size-1);
    return base && count<=(size-offset)/2 ? base+offset : NULL;
}
#define AMBIENT_SH2_SOURCE_VIEW ambient_sh2_source_view
#include "ambient_sh2_arm64.h"

""" + marker +
                            "\n   ambient_sh2_clear();")
    source = source.replace(loop, loop.replace("         const u32",
                            "         if (ambient_sh2_try(context, cycles)) continue;\n         const u32"))
    dispatch = "         ((opcodefunc *)context->opcodes)[context->instruction](context);"
    if source.count(dispatch) < 1:
        raise ValueError("Yabause SH2 dispatch changed")
    start = source.index("         if (ambient_sh2_try(context, cycles)) continue;")
    end = source.index(dispatch, start)
    source = source[:end] + (
        "         if (!ambient_sh2_eligible(context) ||\n"
        "             !ambient_sh2_native_one(context, context->instruction))\n"
        "   " + dispatch
    ) + source[end+len(dispatch):]
    reset = "void SH2InterpreterReset(UNUSED SH2_struct *context)\n{"
    if source.count(reset) != 1:
        raise ValueError("Yabause reset layout changed")
    source = source.replace(reset, reset + "\n   ambient_sh2_note_bulk_write();")
    path.write_text(source)
    shutil.copyfile(Path(__file__).parent / "native_core_candidates/sh2_arm64.h",
                    path.with_name("ambient_sh2_arm64.h"))
    shutil.copyfile(Path(__file__).parent / "native_core_candidates/sh2_memory.h",
                    path.with_name("ambient_sh2_memory.h"))
    shutil.copyfile(Path(__file__).parent / "native_core_candidates/sh2_control.h",
                    path.with_name("ambient_sh2_control.h"))
    shutil.copyfile(Path(__file__).parent / "native_core_candidates/sh2_regcache.h",
                    path.with_name("sh2_regcache.h"))
    # Every T2 write to physical executable memory, including the direct
    # mapped-RAM specialization below, marks its physical source page.
    # Bulk external writes/savestate restores still get exact entry validation.
    memory_header = root / "yabause/src/memory.h"
    source = memory_header.read_text()
    anchor = "#define T2MemoryDeInit(x) (T1MemoryDeInit(x))"
    if source.count(anchor) != 1:
        raise ValueError("Yabause T2 memory layout changed")
    source = source.replace(anchor, anchor + """

#if defined(AMBIENT_SH2_ARM64_JIT)
extern u8 *BiosRom, *LowWram;
#ifdef __cplusplus
extern "C" {
#endif
extern u8 *HighWram;
void ambient_sh2_note_t2_write(unsigned bank,u32 offset,unsigned size);
void ambient_sh2_note_bulk_write(void);
#ifdef __cplusplus
}
#endif
static INLINE void ambient_sh2_t2_written(u8* mem,u32 addr,unsigned size)
{
   if(mem==HighWram)ambient_sh2_note_t2_write(2,addr,size);
   else if(mem==LowWram)ambient_sh2_note_t2_write(1,addr,size);
   else if(mem==BiosRom)ambient_sh2_note_t2_write(0,addr,size);
}
#endif
""")
    for width, ctype, size in (("Byte","u8",1),("Word","u16",2),("Long","u32",4)):
        anchor = f"static INLINE void T2Write{width}(u8 * mem, u32 addr, {ctype} val)\n{{"
        if source.count(anchor) != 1:
            raise ValueError(f"Yabause T2 writer changed: {width}")
        end = source.index("\n}", source.index(anchor)) 
        source = source[:end] + (
            "\n#if defined(AMBIENT_SH2_ARM64_JIT)\n"
            f"   ambient_sh2_t2_written(mem,addr,{size});\n"
            "#endif"
        ) + source[end:]
    memory_header.write_text(source)
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
    bulk = "   MemStateRead((void *)LowWram, 0x100000, 1, stream);"
    if code.count(bulk) != 1:
        raise ValueError("Yabause savestate memory layout changed")
    memory.write_text(code.replace(bulk, bulk + "\n   ambient_sh2_note_bulk_write();"))
    # The upstream DMA notifier's second argument is not consistently a
    # length. Conservatively invalidate all validation epochs here.
    sh2core = root / "yabause/src/sh2core.c"
    source = sh2core.read_text()
    anchor = "void SH2WriteNotify(u32 start, u32 length) {"
    if source.count(anchor) != 1:
        raise ValueError("Yabause DMA notification changed")
    sh2core.write_text(source.replace(anchor, anchor + "\n   ambient_sh2_note_bulk_write();"))
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
