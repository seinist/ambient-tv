import tempfile
import unittest
from pathlib import Path

from analyze_libretro_trace import summarize
from core_acceleration_audit import compiler_commands
from core_build_policy import audit_commands
from core_link_evidence import target_commands
from verify_libretro_native_profile import verify


class CorePerformanceToolsTest(unittest.TestCase):
    def test_selected_link_graph_excludes_unrelated_objects(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = (
                "/usr/bin/ccache /ndk/clang -O3 -flto=thin -c core.c -o core.o\n"
                "clang -O3 -flto=thin -c helper.c -o helper.o\n"
                "llvm-ar qc libhelper.a helper.o\n"
                "clang -O0 -c unrelated.c -o unrelated.o\n"
                "clang -flto=thin -shared core.o libhelper.a -o libretro.so\n"
                "clang -shared unrelated.o -o unrelated.so\n"
            )
            scoped, evidence = target_commands(log, root, root, root / "libretro.so")
            self.assertEqual(evidence["linked_compile_commands"], 2)
            self.assertEqual(evidence["linked_archives"], 1)
            self.assertEqual(evidence["unrelated_compile_commands"], 1)
            self.assertEqual(evidence["unresolved_local_inputs"], [])
            self.assertEqual(audit_commands(log, "o3-thinlto", scoped_commands=scoped)["compile_commands"], 2)
            _, evidence = target_commands(log.replace("libhelper.a helper.o", "libhelper.a missing.o"),
                                          root, root, root / "libretro.so")
            self.assertEqual(evidence["unresolved_local_inputs"], ["missing.o"])

    def test_native_target_flag_gate(self):
        log = ("clang++ -O3 -flto=thin -c libretrodroid.cpp -o frontend.o\n"
               ": && clang++ -flto=thin -shared frontend.o -o liblibretrodroid.so && :\n")
        root = Path.cwd()
        result = verify(log, "o3-thinlto", root, root)
        self.assertEqual(result["thinlto_compiles"], 1)
        for changed in (log.replace("-O3", "-O2"),
                        log.replace("-flto=thin -shared", "-shared"),
                        log.replace("liblibretrodroid.so", "other.so")):
            with self.assertRaises(ValueError):
                verify(changed, "o3-thinlto", root, root)

    def test_trace_summary(self):
        rows = [{"name": "AmbientTV:Libretro/retro_run", "ts": str(i * 1_000_000_000),
                 "dur": str(ms * 1_000_000)} for i, ms in enumerate((5, 10, 20, 30))]
        rows += [{"name": "AmbientTV:Libretro/rewind", "ts": "0", "dur": "50000000"},
                 {"name": "AmbientTV:Libretro/netplay", "ts": "0", "dur": "-1"},
                 {"name": "unrelated", "ts": "0", "dur": "1"}]
        report = summarize(rows, 60, [{"ts": "0", "status": "2"}], 1_000_000_000)
        forward = report["paths"]["retro_run"]
        self.assertEqual(forward["samples"], 4)
        self.assertEqual(forward["p50_ms"], 10)
        self.assertEqual(forward["p95_ms"], 30)
        self.assertEqual(forward["core_budget_overruns"], 2)
        self.assertEqual(forward["by_thermal_status"]["2"]["samples"], 2)
        self.assertEqual(forward["by_thermal_status"]["unknown"]["samples"], 2)
        self.assertNotIn("core_budget_overruns", report["paths"]["rewind"])
        self.assertEqual(report["ignored_incomplete_slices"], 1)
        with self.assertRaises(ValueError):
            summarize(rows, 0)
        with self.assertRaises(ValueError):
            summarize(rows, 60, [{"ts": "0", "status": "9"}])


if __name__ == "__main__":
    unittest.main()
