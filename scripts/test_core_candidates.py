import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from core_build_policy import plan
import json

class CandidateTests(unittest.TestCase):
    def test_jit_candidates_are_narrow_and_artifact_only(self):
        catalog = json.loads((Path(__file__).parent.parent / "cores/cores.json").read_text())
        for profile, core in (("citra-arm64-jit", "citra2018"), ("yabause-arm64-jit", "yabause")):
            jobs = plan(catalog, "workflow_dispatch", "refs/heads/main", False, profile=profile)["include"]
            self.assertEqual([(j["core"], j["abi"]) for j in jobs], [(core, "arm64-v8a")])
            with self.assertRaises(ValueError):
                plan(catalog, "workflow_dispatch", "refs/heads/main", True, profile=profile)
            with self.assertRaises(ValueError):
                plan(catalog, "workflow_dispatch", "refs/heads/main", False, core, "x86_64", profile)

    def test_sh2_emitter_instruction_words_and_fallback(self):
        cc = shutil.which("cc")
        self.assertIsNotNone(cc)
        header = Path(__file__).parent / "native_core_candidates"
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "test.c"
            path.write_text(r"""
#include <assert.h>
#include "sh2_arm64.h"
int main(void) {
    uint32_t out[16];
    assert(ambient_sh2_emit(0x0009, out) == 1);
    assert(ambient_sh2_emit(0xe180, out) == 3 && out[0] == 0x12800fe1u && out[1] == 0xb9000401u);
    assert(ambient_sh2_emit(0x312c, out) == 5 && out[0] == 0xb9400401u &&
           out[1] == 0xb9400802u && out[2] == 0x0b020021u && out[3] == 0xb9000401u);
    assert(ambient_sh2_emit(0xafff, out) == 0); /* BRA */
    assert(ambient_sh2_emit(0x2122, out) == 0); /* MOV.L to memory */
    assert(ambient_sh2_emit(0x000b, out) == 0); /* RTS */
    assert(ambient_sh2_emit(0x3120, out) == 9 && out[2] == 0x6b02003fu && out[3] == 0x1a9f17e3u);
    assert(ambient_sh2_emit(0xc9ff, out) == 5 && out[1] == 0x52801fe2u);
    for (unsigned op=0; op<65536; ++op) assert(ambient_sh2_emit(op, out) <= 16);
}
""")
            binary = path.with_suffix("")
            subprocess.run([cc, "-std=c99", "-O2", "-fsanitize=undefined",
                            "-I", str(header), str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_citra_fastmem_shared_aliases_and_revocation(self):
        compiler = shutil.which("c++")
        self.assertIsNotNone(compiler)
        header = Path(__file__).parent / "native_core_candidates"
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "test.cpp"
            path.write_text(r"""
#include <cassert>
#include <sys/wait.h>
#include <signal.h>
#include "citra_fastmem.h"
static void inaccessible(const uint8_t* address) {
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) { volatile uint8_t value = *address; (void)value; _exit(0); }
    int status=0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFSIGNALED(status) && (WTERMSIG(status) == SIGSEGV || WTERMSIG(status) == SIGBUS));
}
int main() {
    using namespace AmbientFastmem;
    const size_t page = host_page();
    assert(page);
    std::vector<uint8_t*> pointers(AddressSpace / GuestPage, nullptr);
    Arena arena;
    auto first = arena.ensure(pointers);
    assert(first);
    auto* guest = reinterpret_cast<uint8_t*>(*first);
    {
        MemoryBlock memory(page * 3, 0);
        // File-backed allocation is required for this host regression test.
        { auto& r = registry(); std::lock_guard<std::mutex> lock(r.mutex);
          assert(r.allocations.count(reinterpret_cast<uintptr_t>(memory.data()))); }
        for (size_t i=0; i<page/GuestPage; ++i) {
            pointers[i] = memory.data()+i*GuestPage;
            pointers[page/GuestPage+i] = memory.data()+i*GuestPage;
            pointers[2*page/GuestPage+i] = memory.data()+page+i*GuestPage;
            pointers[3*page/GuestPage+i] = memory.data()+2*page+i*GuestPage;
        }
        arena.sync(pointers, 0, 4*page/GuestPage);
        guest[3*page+9] = 42;
        assert(memory[2*page+9] == 42);
        guest[17] = 123;
        assert(memory[17] == 123 && guest[page+17] == 123);
        memory[17] = 77;
        assert(guest[17] == 77 && guest[page+17] == 77);
        pointers[0] = nullptr;
        arena.sync(pointers, 0, 1);
        inaccessible(guest+17);
        assert(guest[page+17] == 77);
        assert(guest[3*page+9] == 42);
        pointers[0] = memory.data();
        arena.sync(pointers, 0, 1);
        assert(guest[17] == 77);
    }
    // Backing destruction revokes both aliases, even before a page table refresh.
    inaccessible(guest+17);
    inaccessible(guest+page+17);
    inaccessible(guest+3*page+9);
    auto& r = registry();
    std::lock_guard<std::mutex> lock(r.mutex);
    assert(r.allocations.empty());
}
""")
            binary = path.with_suffix("")
            subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                            "-pthread", "-I", str(header), str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_sh2_memory_addressing_and_bus_semantics(self):
        compiler=shutil.which("cc")
        self.assertIsNotNone(compiler)
        header=Path(__file__).parent/"native_core_candidates"
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"memory.c"
            path.write_text(r"""
#include <assert.h>
#include <stdint.h>
typedef uint32_t u32; typedef int32_t s32; typedef int16_t s16; typedef int8_t s8;
typedef struct SH2_struct SH2_struct;
struct SH2_struct {
    struct {u32 R[16],PC,GBR;} regs;
    u32 cycles, addr, value, calls, width, stalled;
    u32 (*MappedMemoryReadByte)(SH2_struct*,u32);
    u32 (*MappedMemoryReadWord)(SH2_struct*,u32);
    u32 (*MappedMemoryReadLong)(SH2_struct*,u32);
    void (*MappedMemoryWriteByte)(SH2_struct*,u32,u32);
    void (*MappedMemoryWriteWord)(SH2_struct*,u32,u32);
    void (*MappedMemoryWriteLong)(SH2_struct*,u32,u32);
};
static int sh2_check_wait(SH2_struct* s,u32 a,int w){(void)a;(void)w;return s->stalled;}
#define READ(N) static u32 read##N(SH2_struct* s,u32 a){s->addr=a;s->width=N;++s->calls;return s->value;}
#define WRITE(N) static void write##N(SH2_struct* s,u32 a,u32 v){s->addr=a;s->width=N;s->value=v;++s->calls;}
READ(1) READ(2) READ(4) WRITE(1) WRITE(2) WRITE(4)
#define AMBIENT_SH2_ARM64_JIT 1
#include "sh2_memory.h"
int main(void) {
    SH2_struct s={0};
    s.MappedMemoryReadByte=read1;s.MappedMemoryReadWord=read2;s.MappedMemoryReadLong=read4;
    s.MappedMemoryWriteByte=write1;s.MappedMemoryWriteWord=write2;s.MappedMemoryWriteLong=write4;
    unsigned supported=0;
    for(unsigned op=0;op<65536;++op) {
        AmbientMemOp a=ambient_mem_decode(op);
        if(!a.mode)continue;
        ++supported;
        assert(a.size==1||a.size==2||a.size==4);
        for(unsigned i=0;i<16;++i)s.regs.R[i]=0x1000+i*16;
        s.regs.PC=0x1002;s.regs.GBR=0x5000;s.calls=0;s.cycles=0;s.value=0xdead8080u;s.stalled=0;
        u32 addr=ambient_mem_address(a,s.regs.R,s.regs.PC,s.regs.GBR);
        u32 oldbase=s.regs.R[a.base],oldvalue=s.regs.R[a.reg];
        ambient_mem_execute(&s,a);
        assert(s.calls==1&&s.addr==addr&&s.width==a.size&&s.cycles==1&&s.regs.PC==0x1004);
        if(a.load) {
            assert(s.regs.R[a.reg]==(a.size==1?0xffffff80u:a.size==2?0xffff8080u:0xdead8080u));
            if(a.mode==AM_POST && a.reg!=a.base)assert(s.regs.R[a.base]==oldbase+a.size);
        } else {
            assert(s.value==oldvalue);
            if(a.mode==AM_PRE)assert(s.regs.R[a.base]==oldbase-a.size);
        }
        if(a.wait) {
            s.stalled=1;s.calls=0;s.cycles=0;s.regs.PC=0x1002;
            ambient_mem_execute(&s,a);
            assert(!s.calls&&s.cycles==1&&s.regs.PC==0x1002);
        }
    }
    assert(supported==23552);
    assert(ambient_mem_decode(0x6125).mode==AM_POST);
    assert(ambient_mem_decode(0x2116).mode==AM_PRE);
}
""")
            binary=path.with_suffix("")
            subprocess.run([compiler,"-std=c99","-O2","-Wall","-Wextra","-Werror",
                            "-fsanitize=undefined","-I",str(header),str(path),"-o",str(binary)],check=True)
            subprocess.run([str(binary)],check=True)
