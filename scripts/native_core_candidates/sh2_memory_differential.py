"""Differential test for every candidate SH2 memory operation against pinned handlers."""
import argparse
import re
import subprocess
import tempfile
from pathlib import Path

PRELUDE = r"""
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef uint8_t u8;typedef uint16_t u16;typedef uint32_t u32;
typedef int8_t s8;typedef int16_t s16;typedef int32_t s32;
#define FASTCALL
#define INSTRUCTION_A(op) (((op)>>12)&15)
#define INSTRUCTION_B(op) (((op)>>8)&15)
#define INSTRUCTION_C(op) (((op)>>4)&15)
#define INSTRUCTION_D(op) ((op)&15)
#define INSTRUCTION_CD(op) ((op)&255)
enum SHMODELTYPE {SHMT_SH1,SHMT_SH2};
typedef struct SH2_struct SH2_struct;
typedef void(*opcodefunc)(SH2_struct*);
typedef struct {u32 kind,addr,value;} Event;
struct SH2_struct {
    struct {u32 R[16],PC,GBR,VBR,MACH,MACL,PR;union {u32 all;struct {u32 T:1,S:1;} part;} SR;} regs;
    u32 cycles,seed,stall,count;
    u16 instruction;
    Event events[8];
    u8 (*MappedMemoryReadByte)(SH2_struct*,u32);
    u16 (*MappedMemoryReadWord)(SH2_struct*,u32);
    u32 (*MappedMemoryReadLong)(SH2_struct*,u32);
    void (*MappedMemoryWriteByte)(SH2_struct*,u32,u8);
    void (*MappedMemoryWriteWord)(SH2_struct*,u32,u16);
    void (*MappedMemoryWriteLong)(SH2_struct*,u32,u32);
};
static u32 next(u32* seed){*seed^=*seed<<13;*seed^=*seed>>17;*seed^=*seed<<5;return *seed;}
static void event(SH2_struct* s,u32 kind,u32 addr,u32 value) {
    if(s->count>=8)abort();
    s->events[s->count++]=(Event){kind,addr,value};
}
static int sh2_check_wait(SH2_struct* s,u32 addr,int width) {
    event(s,0,addr,width);return s->stall;
}
#define READ(N,T) static T read##N(SH2_struct* s,u32 a){T v=(T)next(&s->seed);event(s,N,a,v);return v;}
#define WRITE(N,T) static void write##N(SH2_struct* s,u32 a,T v){event(s,N+8,a,v);}
READ(1,u8) READ(2,u16) READ(4,u32) WRITE(1,u8) WRITE(2,u16) WRITE(4,u32)
static void SH2delay(SH2_struct* s,u32 addr){event(s,16,addr,0);s->regs.PC-=2;++s->cycles;}
#define AMBIENT_SH2_ARM64_JIT 1
#include "sh2_memory.h"
"""
HARNESS = r"""
int main(void) {
    unsigned supported=0;
    u32 seed=0x5ca0ffeeu;
    for(unsigned raw=0;raw<65536;++raw) {
        AmbientMemOp simple=ambient_mem_decode(raw);
        AmbientComplexMem complex=ambient_complex_decode(raw);
        opcodefunc handler=decode(SHMT_SH2,raw);
        if(!!handler != !!(simple.mode||complex.mode) || (simple.mode&&complex.mode)) {
            fprintf(stderr,"memory decode mismatch %04x\n",raw);return 1;
        }
        if(!handler)continue;
        ++supported;
        for(unsigned sample=0;sample<32;++sample) {
            SH2_struct want={0},got={0};
            want.MappedMemoryReadByte=read1;want.MappedMemoryReadWord=read2;want.MappedMemoryReadLong=read4;
            want.MappedMemoryWriteByte=write1;want.MappedMemoryWriteWord=write2;want.MappedMemoryWriteLong=write4;
            for(unsigned i=0;i<16;++i)want.regs.R[i]=next(&seed);
            if(sample<4)want.regs.R[(raw>>8)&15]=want.regs.R[(raw>>4)&15];
            want.regs.PC=next(&seed)&~1u;want.regs.GBR=next(&seed);want.regs.VBR=next(&seed);
            want.regs.MACH=next(&seed);want.regs.MACL=next(&seed);want.regs.PR=next(&seed);
            want.regs.SR.all=next(&seed)&0x3f3;want.regs.SR.part.S=sample&1;
            want.instruction=raw;want.cycles=next(&seed)&0xffff;
            want.seed=next(&seed);want.stall=(sample&2)!=0;
            got=want;
            handler(&want);
            if(simple.mode)ambient_mem_execute(&got,simple);else ambient_complex_execute(&got,complex);
            if(memcmp(&want.regs,&got.regs,sizeof(want.regs)) || want.cycles!=got.cycles ||
               want.seed!=got.seed || want.count!=got.count ||
               memcmp(want.events,got.events,sizeof(want.events))) {
                fprintf(stderr,"memory differential mismatch %04x sample %u: cycles %u/%u, calls %u/%u, MAC %08x:%08x/%08x:%08x\n",
                    raw,sample,want.cycles,got.cycles,want.count,got.count,
                    want.regs.MACH,want.regs.MACL,got.regs.MACH,got.regs.MACL);
                return 1;
            }
        }
    }
    printf("SH2 memory: %u encodings, %u differential executions against pinned upstream handlers passed\n",supported,supported*32);
    return supported==25553?0:1;
}
"""
def run(upstream: Path):
    source=upstream.read_text()
    functions={}
    for match in re.finditer(r"static void FASTCALL (SH2\w+)\(SH2_struct \* sh\)\n\{[\s\S]*?\n\}",source):
        name=match.group(1)
        body=match.group()
        if name!="SH2undecoded" and re.search(r"MappedMemory(?:Read|Write)",body):
            functions[name]=body
    if len(functions)!=53:
        raise ValueError(f"Unexpected pinned memory handler count: {len(functions)}")
    decode=re.search(r"static opcodefunc decode\(enum SHMODELTYPE model, u16 instruction\)\n\{[\s\S]*?\n\}",source)
    if not decode:
        raise ValueError("Pinned decoder not found")
    decoder=re.sub(r"&((?:SH2)\w+)",lambda m:m.group() if m.group(1) in functions else "NULL",decode.group())
    header=Path(__file__).parent
    with tempfile.TemporaryDirectory() as temp:
        path=Path(temp)/"diff.c"
        path.write_text(PRELUDE+"\n"+"\n".join(functions.values())+"\n"+decoder+"\n"+HARNESS)
        binary=path.with_suffix("")
        subprocess.run(["cc","-std=c99","-O2","-fwrapv","-I",str(header),str(path),"-o",str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--upstream",type=Path,required=True)
    run(p.parse_args().upstream)
