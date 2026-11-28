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
    uint32_t out[4];
    assert(ambient_sh2_emit(0x0009, out) == 1);
    assert(ambient_sh2_emit(0xe180, out) == 3 && out[0] == 0x12800fe1u && out[1] == 0xb9000401u);
    assert(ambient_sh2_emit(0x312c, out) == 5 && out[0] == 0xb9400401u &&
           out[1] == 0xb9400802u && out[2] == 0x0b020021u && out[3] == 0xb9000401u);
    assert(ambient_sh2_emit(0xafff, out) == 0); /* BRA */
    assert(ambient_sh2_emit(0x2122, out) == 0); /* MOV.L to memory */
    assert(ambient_sh2_emit(0x000b, out) == 0); /* RTS */
    for (unsigned op=0; op<65536; ++op) assert(ambient_sh2_emit(op, out) <= 5);
}
""")
            binary = path.with_suffix("")
            subprocess.run([cc, "-std=c99", "-O2", "-fsanitize=undefined",
                            "-I", str(header), str(path), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)
