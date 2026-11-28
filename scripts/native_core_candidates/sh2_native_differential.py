"""Execute the production SH2 native path against every pinned legal opcode.
Also compare taken delayed branches with real (non-stub) slot semantics.
The bus and fetch backing are deterministic test doubles, not a Saturn device.
"""
import argparse
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from sh2_memory_differential import PRELUDE

HARNESS = r"""
static void SH2delay(SH2_struct* sh,u32 addr) {
    sh->instruction=FetchBios(sh,addr);
    opcodefunc handler=decode(SHMT_SH2,sh->instruction);
    if(!handler)abort();
    handler(sh);
    sh->regs.PC-=2;
}
int main(void) {
    unsigned legal=0, illegal=0, delayed=0;
    u32 seed=0x6a09e667u;
    const uint16_t slots[]={0xe180,0x9101,0xd102,0x6122,0x2122,0x3124,0x400e,0x312d};
    for(unsigned raw=0;raw<65536;++raw) {
        opcodefunc handler=decode(SHMT_SH2,raw);
        uint32_t scratch[16];
        unsigned cost;
        const unsigned emitted=ambient_sh2_emit_extended(raw,scratch,ambient_sh2_layout,&cost);
        const unsigned memory=!!ambient_mem_decode(raw).mode+!!ambient_complex_decode(raw).mode;
        const unsigned control=!!ambient_sh2_control_supported(raw)+
            ((raw&0xff00u)==0x8900 || (raw&0xff00u)==0x8b00);
        if(!!handler != !!(emitted||memory||control) || (!!emitted+memory+control)>1) {
            fprintf(stderr,"complete native decode mismatch %04x: handler %d emit %u memory %u control %u\n",
                    raw,!!handler,emitted,memory,control);return 1;
        }
        if(!handler){++illegal;continue;}
        ++legal;
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
            want.slot=slots[sample%8];want.isslave=sample&1;
            for(unsigned i=0;i<256;++i)want.fetchlist[i]=(void*)FetchBios;
            got=want;
            handler(&want);
            if(!ambient_sh2_native_one(&got,raw)){
                fprintf(stderr,"legal opcode rejected %04x\n",raw);return 1;
            }
            if(memcmp(&want.regs,&got.regs,sizeof(want.regs)) || want.cycles!=got.cycles ||
               want.seed!=got.seed || want.count!=got.count || want.instruction!=got.instruction ||
               memcmp(want.events,got.events,sizeof(want.events))) {
                fprintf(stderr,"complete native mismatch %04x slot %04x sample %u: PC %08x/%08x cycles %u/%u SR %08x/%08x\n",
                    raw,want.slot,sample,want.regs.PC,got.regs.PC,want.cycles,got.cycles,
                    want.regs.SR.all,got.regs.SR.all);
                return 1;
            }
            if(want.count && want.events[0].kind==16)++delayed;
        }
    }
    ambient_sh2_clear();
    printf("SH2 complete native path: %u legal, %u illegal encodings, %u executions, %u delay-slot executions passed\n",
           legal,illegal,legal*32,delayed);
    return legal+illegal==65536 && delayed>0?0:1;
}
"""
def run(upstream: Path):
    source=upstream.read_text()
    functions={}
    for match in re.finditer(r"static void FASTCALL (SH2\w+)\(SH2_struct \* sh\)\n\{[\s\S]*?\n\}",source):
        if match.group(1)!="SH2undecoded":
            functions[match.group(1)]=match.group()
    if len(functions)!=142:
        raise ValueError(f"Unexpected pinned handler count: {len(functions)}")
    decode=re.search(r"static opcodefunc decode\(enum SHMODELTYPE model, u16 instruction\)\n\{[\s\S]*?\n\}",source)
    if not decode:
        raise ValueError("Pinned decoder not found")
    decoder=decode.group().replace("&SH2undecoded","NULL")
    prelude=PRELUDE.split("static void SH2delay(")[0]
    prelude=prelude.replace("    u32 cycles,seed,stall,count;", """    u32 cycles,seed,stall,count,slot;
    unsigned isslave, isIdle, isSleeping, delay, breakpointEnabled, backtraceEnabled;
    enum SHMODELTYPE model;
    struct { unsigned enabled; } stepOverOut,trackInfLoop;
    void* fetchlist[256];
    void* opcodes[1];""")
    prelude+=r"""
typedef u32(*fetchfunc)(SH2_struct*,u32);
static struct { int sh2_cache_enabled; } yabsys;
static u32 FetchBios(SH2_struct* sh,u32 addr){event(sh,16,addr,sh->slot);return sh->slot;}
static u32 FetchLWram(SH2_struct* sh,u32 addr){return FetchBios(sh,addr);}
static u32 FetchHWram(SH2_struct* sh,u32 addr){return FetchBios(sh,addr);}
static void SH2delay(SH2_struct*,u32);
#define AMBIENT_SH2_ARM64_JIT 1
#include "sh2_arm64.h"
"""
    header=Path(__file__).parent
    with tempfile.TemporaryDirectory() as temp:
        path=Path(temp)/"diff.c"
        for name in ("sh2_memory.h","sh2_control.h"):
            shutil.copyfile(header/name,Path(temp)/("ambient_"+name))
        path.write_text(prelude+"\n"+"\n".join(functions.values())+"\n"+decoder+"\n"+HARNESS)
        binary=path.with_suffix("")
        subprocess.run(["cc","-D_GNU_SOURCE","-std=c99","-O2","-fwrapv","-I",temp,"-I",str(header),
                        str(path),"-o",str(binary)],check=True)
        subprocess.run([str(binary)],check=True)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--upstream",type=Path,required=True)
    run(p.parse_args().upstream)
