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
