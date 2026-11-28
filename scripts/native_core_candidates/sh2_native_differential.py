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
    sh->instruction=yabsys.sh2_cache_enabled && (addr&0xc0000000u)==0xc0000000u ?
        DataArrayReadWord(sh,addr) : FetchBios(sh,addr);
    opcodefunc handler=decode(SHMT_SH2,sh->instruction);
    if(!handler)abort();
    handler(sh);
    sh->regs.PC-=2;
}
static void reference_step(SH2_struct* sh) {
    sh->instruction=FetchBios(sh,sh->regs.PC);
    opcodefunc handler=decode(SHMT_SH2,sh->instruction);
    if(!handler)abort();
    handler(sh);
}
static void setup_program(SH2_struct* sh,u16* program,unsigned length,u32 base) {
    memset(sh,0,sizeof(*sh));
    sh->model=SHMT_SH2;
    sh->program=program;sh->program_base=base;sh->program_length=length;
    sh->regs.PC=base;
    sh->MappedMemoryReadByte=read1;sh->MappedMemoryReadWord=read2;sh->MappedMemoryReadLong=read4;
    sh->MappedMemoryWriteByte=write1;sh->MappedMemoryWriteWord=write2;sh->MappedMemoryWriteLong=write4;
    static void* opcode_table[65536];
    for(unsigned i=0;i<65536;++i)opcode_table[i]=(void*)decode(SHMT_SH2,i);
    sh->opcodes=opcode_table;
    for(unsigned i=0;i<256;++i)sh->fetchlist[i]=(void*)FetchBios;
}
static void test_linked_traces(void) {
    u16 program[128];
    for(unsigned i=0;i<128;++i)program[i]=0x0009;
    program[0]=0xe101;program[1]=0x7101;
    program[2]=0xb004;program[3]=0x7103; /* BSR to 8, add in slot */
    program[8]=0x022a;program[9]=0x7102; /* STS PR,R2 */
    program[10]=0xa004;program[11]=0x7104; /* BRA to 16 */
    program[16]=0x7105;program[17]=0xafee;program[18]=0x7106; /* BRA to 1 */
    SH2_struct got,want;
    const u32 base=0x06000100u;
    setup_program(&got,program,128,base);
    AmbientSh2Block* executed=NULL;
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&got,100,NULL,&executed))abort();
    AmbientSh2Block* trace=&ambient_sh2_cache[0][ambient_sh2_slot(base)];
    if(!trace->code || trace->count!=AMBIENT_SH2_MAX_OPS || trace->pcs[4]!=base+16)abort();
    want=got;
    while(want.cycles<trace->cycles)reference_step(&want);
    if(!ambient_sh2_try_block(&got,100,NULL,&executed)||executed!=trace ||
       got.cycles!=want.cycles || got.instruction!=want.instruction ||
       memcmp(&got.regs,&want.regs,sizeof(got.regs)))abort();
    /* Mutate a non-contiguous delay slot: the old trace must be invalidated
       before any register or cycle changes, not merely at its entry PC. */
    setup_program(&got,program,128,base);
    program[11]=0x7107;
    if(ambient_sh2_try_block(&got,100,NULL,&executed)||got.cycles||trace->code)abort();
    ambient_sh2_clear();

    /* Two decoded memory-only blocks: execute a linked edge, then replace
       the successor instruction and ensure the old link is rejected. */
    program[0]=0x2112;program[1]=0x6212;program[2]=0x0009;
    setup_program(&got,program,128,base);
    for(unsigned j=0;j<2;++j) {
        got.regs.PC=base+2*j;
        for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
            if(ambient_sh2_try_block(&got,20,NULL,&executed))abort();
    }
    got.regs.PC=base;got.regs.R[1]=0x1234;got.seed=1234;
    want=got;reference_step(&want);reference_step(&want);
    if(!ambient_sh2_try(&got,20)||got.cycles!=want.cycles ||
       got.count!=want.count || memcmp(&got.regs,&want.regs,sizeof(got.regs)) ||
       memcmp(got.events,want.events,sizeof(got.events)))abort();
    trace=&ambient_sh2_cache[0][ambient_sh2_slot(base)];
    if(!trace->edges[0].target)abort();
    setup_program(&got,program,128,base);
    program[1]=0xe180;
    if(!ambient_sh2_try(&got,20)||got.cycles!=1||got.regs.PC!=base+2)abort();
    ambient_sh2_clear();
    puts("SH2 fused BRA/BSR traces, register state, chained exits and stale-source invalidation passed");
}
static void test_scoped_page_invalidation(void) {
    u16 program[64];
    for(unsigned i=0;i<64;++i)program[i]=0x7101;
    const u32 base=0x06000fe0u;
    SH2_struct got;
    setup_program(&got,program,64,base);
    AmbientSh2Block* executed=NULL;
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&got,100,NULL,&executed))abort();
    AmbientSh2Block* trace=ambient_sh2_lookup(0,base,0);
    if(!trace||!trace->code||trace->span_count!=2)abort();
    uint64_t scope=ambient_sh2_new_scope(0);
    if(!ambient_sh2_validate(trace,&got,FetchBios,scope))abort();
    /* A write on a different physical page does not invalidate this trace. */
    ambient_sh2_note_t2_write(0,0x4000,4);
    if(!ambient_sh2_validate(trace,&got,FetchBios,scope))abort();
    /* A write straddling a page boundary marks both aliases. */
    program[16]=0xe180;
    ambient_sh2_note_t2_write(0,0xffe,4);
    if(ambient_sh2_validate(trace,&got,FetchBios,scope))abort();
    program[16]=0x7101;
    if(!ambient_sh2_validate(trace,&got,FetchBios,scope))abort();
    /* Raw savestate/cheat-style write without a notification is caught at
       the next scope boundary, even if all page generations are unchanged. */
    program[3]=0xe180;
    if(ambient_sh2_validate(trace,&got,FetchBios,ambient_sh2_new_scope(0)))abort();
    ambient_sh2_clear();
    puts("SH2 physical page invalidation: unrelated writes, cross-page write and bulk-write boundary passed");
}
static void test_associative_cache(void) {
    u16 program[8]={0x7101,0x7102,0x7103,0x7104,0x7105,0x7106,0x7107,0x7108};
    SH2_struct got;
    const u32 base=0x06000100u;
    u32 collide=base+2;
    while(ambient_sh2_slot(collide)!=ambient_sh2_slot(base))collide+=2;
    u32 third=collide+2;
    while(ambient_sh2_slot(third)!=ambient_sh2_slot(base))third+=2;
    AmbientSh2Block* executed=NULL;
    setup_program(&got,program,8,base);
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&got,100,NULL,&executed))abort();
    got.program_base=got.regs.PC=collide;
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&got,100,NULL,&executed))abort();
    AmbientSh2Block* first=ambient_sh2_lookup(0,base,0);
    AmbientSh2Block* second=ambient_sh2_lookup(0,collide,0);
    if(!first||!second||first==second||!first->code||!second->code)abort();
    /* Touch first, replace the other way, and reject the stale linked slot. */
    if(ambient_sh2_lookup(0,base,0)!=first)abort();
    if(ambient_sh2_lookup(0,third,1)!=second || second->pc!=third || second->code)abort();
    got.regs.PC=collide;
    if(ambient_sh2_try_block(&got,100,second,&executed))abort();
    if(ambient_sh2_lookup(0,base,0)!=first||!first->code)abort();
    ambient_sh2_clear();
    puts("SH2 two-way cache collision and stale dynamic-link replacement passed");
}
static void test_indirect_native_exits(void) {
    const u16 branches[]={0x0123,0x0103,0x412b,0x410b,0x000b};
    const u16 slots[]={0x7105,0xe101,0x412a,0x002a,0x0018};
    const u32 base=0x06000100u;
    for(unsigned b=0;b<5;++b)for(unsigned s=0;s<5;++s) {
        u16 program[64];
        for(unsigned i=0;i<64;++i)program[i]=0x0009;
        program[0]=0x7101;program[1]=0x7102;program[2]=branches[b];program[3]=slots[s];
        SH2_struct got,want;
        setup_program(&got,program,64,base);
        got.regs.R[1]=base+40;got.regs.PR=base+50;
        AmbientSh2Block* executed=NULL;
        for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
            if(ambient_sh2_try_block(&got,100,NULL,&executed))abort();
        AmbientSh2Block* trace=&ambient_sh2_cache[0][ambient_sh2_slot(base)];
        if(!trace->code||!trace->dynamic_exit||trace->count!=4)abort();
        want=got;reference_step(&want);reference_step(&want);reference_step(&want);
        if(!ambient_sh2_try_block(&got,100,NULL,&executed) ||
           got.cycles!=want.cycles || got.instruction!=want.instruction ||
           memcmp(&got.regs,&want.regs,sizeof(got.regs))) {
            fprintf(stderr,"native indirect exit mismatch %04x/%04x PC %08x/%08x\n",
                    branches[b],slots[s],got.regs.PC,want.regs.PC);abort();
        }
        ambient_sh2_clear();
    }
    puts("SH2 native BRAF/BSRF/JMP/JSR/RTS exits: 25 target/slot alias cases passed");
}
static void test_resident_cache_traces(void) {
    u16 program[64];
    for(unsigned i=0;i<64;++i)program[i]=0x7101;
    program[2]=0xa004;program[3]=0x7103;
    SH2_struct got,want;
    const u32 base=0x06000100u;
    setup_program(&got,program,64,base);
    yabsys.sh2_cache_enabled=1;got.onchip.cache.enable=1;
    for(unsigned i=0;i<64;++i) {
        const u32 addr=base+2*i;
        const unsigned entry=(addr&0x3f0u)>>4,off=addr&15u,way=entry&3;
        cache_line* line=&got.onchip.cache.way[way][entry];
        line->v=1;line->tag=addr&0x1ffffc00u;
        line->data[off]=program[i]>>8;line->data[off+1]=program[i]&255;
    }
    /* Deliberately different backing RAM: translated code must come from
       the resident cache, not the current contents of physical RAM. */
    program[0]=0xe180;
    AmbientSh2Block* executed=NULL;
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&got,100,NULL,&executed))abort();
    if(got.fetch_count||got.refills||got.cycles)abort();
    AmbientSh2Block* trace=&ambient_sh2_cache[0][ambient_sh2_slot(base)];
    if(!trace->code||trace->count!=AMBIENT_SH2_MAX_OPS)abort();
    want=got;
    while(want.cycles<trace->cycles)reference_step(&want);
    if(!ambient_sh2_try_block(&got,100,NULL,&executed) ||
       got.cycles!=want.cycles || got.fetch_count!=want.fetch_count || got.refills ||
       memcmp(&got.regs,&want.regs,sizeof(got.regs)) ||
       memcmp(&got.onchip.cache,&want.onchip.cache,sizeof(cache_enty)))abort();
    got.regs.PC=base;got.cycles=got.fetch_count=0;
    const unsigned entry=((base+16)&0x3f0u)>>4;
    got.onchip.cache.way[entry&3][entry].v=0;
    if(ambient_sh2_try_block(&got,100,NULL,&executed)||got.cycles||got.fetch_count||got.refills)abort();
    ambient_sh2_clear();yabsys.sh2_cache_enabled=0;
    puts("SH2 cache-resident fused traces: exact fetch order, LRU, stale RAM and miss guards passed");
}
/* Diagnostic microbenchmark, not a device acceptance gate. Both paths use
 * the identical fixture and exact guest cycle budget. The baseline uses
 * predecoded upstream opcode pointers, not the slower decoder itself. */
static uint64_t monotonic_ns(void) {
    struct timespec ts;
    if(clock_gettime(CLOCK_MONOTONIC,&ts))abort();
    return (uint64_t)ts.tv_sec*UINT64_C(1000000000)+(uint64_t)ts.tv_nsec;
}
static void test_direct_native_pair(void) {
    u16 program[96];
    for(unsigned i=0;i<96;++i)program[i]=(uint16_t)(0x7101+(i%3)*0x100);
    const u32 base=0x06000100u;
    SH2_struct prepared,got,want;
    setup_program(&prepared,program,96,base);
    AmbientSh2Block* executed=NULL;
    for(unsigned block=0;block<2;++block) {
        prepared.regs.PC=base+64*block;
        for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
            if(ambient_sh2_try_block(&prepared,100,NULL,&executed))abort();
    }
    AmbientSh2Block* first=ambient_sh2_lookup(0,base,0);
    AmbientSh2Block* second=ambient_sh2_lookup(0,base+64,0);
    if(!first||!second||!first->native_link||!second->native_link)abort();
    prepared.regs.PC=base;
    got=want=prepared;
    for(unsigned i=0;i<64;++i)reference_step(&want);
    if(!ambient_sh2_try_block_scoped(&got,64,NULL,&executed,ambient_sh2_new_scope(0)) ||
       executed!=second||got.cycles!=64||got.instruction!=want.instruction||
       memcmp(&got.regs,&want.regs,sizeof(got.regs)))abort();
    got=prepared;
    if(!ambient_sh2_try_block_scoped(&got,63,NULL,&executed,ambient_sh2_new_scope(0))||
       executed!=first||got.cycles!=32)abort();
    /* A stale successor must never be reached via the machine-code branch. */
    program[35]=0xe180;
    got=prepared;
    if(!ambient_sh2_try_block_scoped(&got,64,NULL,&executed,ambient_sh2_new_scope(0))||
       executed!=first||got.cycles!=32)abort();
    ambient_sh2_clear();
    puts("SH2 direct ARM64 tail-linked block pair: target state, budget and stale-successor guards passed");
}
static void test_native_loop_budget(void) {
    u16 program[8]={0x7101,0x312c,0x312e,0x7103,0x312a,0x0009,0xaff8,0x0018};
    /* Branch at index six to index zero; slot writes T. */
    const u32 base=0x06000100u;
    SH2_struct prepared,got,want;
    setup_program(&prepared,program,8,base);
    prepared.regs.R[1]=0xfffffff0;prepared.regs.R[2]=0x12345678;
    AmbientSh2Block* executed=NULL;
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&prepared,1000,NULL,&executed))abort();
    AmbientSh2Block* trace=ambient_sh2_lookup(0,base,0);
    if(!trace||!trace->native_loop||trace->cycles!=36||trace->next_pc!=base)abort();
    const unsigned counts[]={1,2,7,63,64,65};
    for(unsigned i=0;i<sizeof(counts)/sizeof(counts[0]);++i) {
        got=want=prepared;
        const u32 budget=trace->cycles*counts[i];
        while(want.cycles<budget)reference_step(&want);
        while(got.cycles<budget)
            if(!ambient_sh2_try(&got,budget))abort();
        if(got.cycles!=want.cycles||got.instruction!=want.instruction||
           memcmp(&got.regs,&want.regs,sizeof(got.regs)))abort();
    }
    got=prepared;
    if(!ambient_sh2_try(&got,trace->cycles+3)||got.cycles!=trace->cycles)abort();
    program[7]=0x0008;
    got=prepared;
    if(ambient_sh2_try(&got,1000)||got.cycles)abort();
    ambient_sh2_clear();
    puts("SH2 native register-resident loop: 1..65 repetitions, cycle budgets and source mutation passed");
}
static void benchmark_native_trace(void) {
    u16 program[32];
    for(unsigned i=0;i<30;++i)program[i]=(uint16_t)(0x7101+(i%3)*0x100);
    program[30]=0xafe0;program[31]=0x0009; /* loop to index zero */
    const u32 base=0x06000100u,budget=33u*100000u;
    SH2_struct prepared;
    setup_program(&prepared,program,32,base);
    AmbientSh2Block* executed=NULL;
    for(unsigned i=0;i<AMBIENT_SH2_HOT;++i)
        if(ambient_sh2_try_block(&prepared,budget,NULL,&executed))abort();
    AmbientSh2Block* trace=ambient_sh2_lookup(0,base,0);
    if(!trace||!trace->code||trace->cycles!=33||trace->next_pc!=base)abort();
    for(unsigned round=0;round<3;++round) {
        SH2_struct states[2]={prepared,prepared};
        uint64_t elapsed[2]={0};
        for(unsigned order=0;order<2;++order) {
            unsigned native=(order+round)&1;
            SH2_struct* sh=&states[native];
            const uint64_t start=monotonic_ns();
            if(native) {
                while(sh->cycles<budget) {
                    if(!ambient_sh2_try(sh,budget))abort();
                }
            } else {
                while(sh->cycles<budget) {
                    sh->instruction=FetchBios(sh,sh->regs.PC);
                    ((opcodefunc*)sh->opcodes)[sh->instruction](sh);
                }
            }
            elapsed[native]=monotonic_ns()-start;
        }
        if(states[0].cycles!=budget || states[1].cycles!=budget ||
           states[0].instruction!=states[1].instruction ||
           memcmp(&states[0].regs,&states[1].regs,sizeof(states[0].regs)))abort();
        printf("SH2 synthetic ALU-loop benchmark round %u: guest_cycles=%u upstream_ns=%llu native_ns=%llu\n",
               round+1,budget,(unsigned long long)elapsed[0],(unsigned long long)elapsed[1]);
    }
    ambient_sh2_clear();
}
int main(void) {
    test_linked_traces();
    test_indirect_native_exits();
    test_associative_cache();
    test_scoped_page_invalidation();
    test_resident_cache_traces();
    test_direct_native_pair();
    test_native_loop_budget();
    benchmark_native_trace();
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
            yabsys.sh2_cache_enabled=(sample>>2)&1;
            if(yabsys.sh2_cache_enabled)want.regs.PC|=0xc0000000u;
            want.model=SHMT_SH2;
            for(unsigned i=0;i<256;++i)want.fetchlist[i]=(void*)FetchBios;
            got=want;
            handler(&want);
            if(!ambient_sh2_eligible(&got)||!ambient_sh2_native_one(&got,raw)){
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
            if(want.count && (want.events[0].kind==16 || want.events[0].kind==17))++delayed;
        }
    }
    yabsys.sh2_cache_enabled=0;
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
    prelude=prelude.replace("struct SH2_struct {", """
typedef struct {u32 tag;int v;u8 data[16];} cache_line;
typedef struct {u32 enable,lru[64];cache_line way[4][64];} cache_enty;
struct SH2_struct {""")
    prelude=prelude.replace("    u32 cycles,seed,stall,count;", """    u32 cycles,seed,stall,count,slot;
    unsigned isslave, isIdle, isSleeping, delay, breakpointEnabled, backtraceEnabled;
    struct { cache_enty cache; } onchip;
    u32 fetch_count,refills;
    enum SHMODELTYPE model;
    struct { unsigned enabled; } stepOverOut,trackInfLoop;
    void* fetchlist[256];
    void** opcodes;
    u16* program;u32 program_base,program_length;""")
    prelude+=r"""
#include <time.h>
typedef u32(*fetchfunc)(SH2_struct*,u32);
static struct { int sh2_cache_enabled; } yabsys;
static u32 FetchBios(SH2_struct* sh,u32 addr){
    if(sh->program) {
        const u32 index=(addr-sh->program_base)>>1;
        if(yabsys.sh2_cache_enabled && sh->onchip.cache.enable) {
            ++sh->fetch_count;
            const unsigned entry=(addr&0x3f0u)>>4,off=addr&15u;
            const u32 tag=addr&0x1ffffc00u;
            for(unsigned way=0;way<4;++way) {
                cache_line* line=&sh->onchip.cache.way[way][entry];
                if(line->v && line->tag==tag) {
                    sh->onchip.cache.lru[entry]=sh->onchip.cache.lru[entry]*5+way+1;
                    return (line->data[off]<<8)|line->data[off+1];
                }
            }
            ++sh->refills;sh->cycles+=4;
        }
        return index<sh->program_length?sh->program[index]:0x0009;
    }
    event(sh,16,addr,sh->slot);return sh->slot;
}
static u16 DataArrayReadWord(SH2_struct* sh,u32 addr) {
    event(sh,17,addr,sh->slot);return sh->slot;
}
static u32 FetchLWram(SH2_struct* sh,u32 addr){return FetchBios(sh,addr);}
static u32 FetchHWram(SH2_struct* sh,u32 addr){return FetchBios(sh,addr);}
static const void* test_source_view(SH2_struct* sh,fetchfunc fetch,u32 addr,unsigned count)
{
    (void)fetch;
    if(!sh->program || addr<sh->program_base)return NULL;
    const unsigned index=(addr-sh->program_base)>>1;
    if(index>=sh->program_length || count>sh->program_length-index)return NULL;
    return sh->program+index;
}
static void SH2delay(SH2_struct*,u32);
#define AMBIENT_SH2_SOURCE_VIEW test_source_view
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
