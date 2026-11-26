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

    def test_cmake_make_link_script_working_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = (
                "cd deps && ccache /ndk/clang -O3 -c dep.c -o dep.o\n"
                "cd deps && /usr/bin/cmake -E cmake_link_script CMakeFiles/dep.dir/link.txt --verbose=1\n"
                '"/ndk/llvm-ar" qc libdep.a dep.o\n'
                "gmake[2]: Leaving directory '/build'\n"
                "cd frontend && /ndk/clang -O3 -c main.c -o main.o\n"
                "cd frontend && /usr/bin/cmake -E cmake_link_script CMakeFiles/core.dir/link.txt --verbose=1\n"
                "/ndk/clang -shared main.o ../deps/libdep.a -o ../libretro.so\n"
                "gmake[2]: Leaving directory '/build'\n"
                "clang -O0 -c unrelated.c -o unrelated.o\n"
            )
            scoped, evidence = target_commands(log, root, root, root / "libretro.so")
            self.assertEqual(evidence["linked_compile_commands"], 2)
            self.assertEqual(evidence["linked_archives"], 1)
            self.assertEqual(evidence["unrelated_compile_commands"], 1)
            self.assertEqual(evidence["unresolved_local_inputs"], [])
            self.assertEqual(audit_commands(log, "o3", scoped_commands=scoped)["compile_commands"], 2)

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

    def test_main_trace_labels(self):
        rows = [{"name": name, "ts": "0", "dur": "1000000"} for name in (
            "AmbientGame core run", "AmbientGame netplay core",
            "AmbientGame rewind core", "AmbientGame rewind preview core",
            "AmbientGame pacing wait",
        )]
        report = summarize(rows, 60)
        self.assertTrue(all(path["samples"] == 1 for path in report["paths"].values()))

    def test_mixed_trace_generations_rejected(self):
        rows = [{"name": name, "ts": "0", "dur": "1000"} for name in
                ("AmbientGame core run", "AmbientTV:Libretro/retro_run")]
        with self.assertRaisesRegex(ValueError, "Mixed core trace formats"):
            summarize(rows, 60)

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
