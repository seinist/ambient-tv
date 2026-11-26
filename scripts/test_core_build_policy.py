import json
from pathlib import Path
import tempfile
import struct
import unittest
from core_acceleration_audit import AUDITED, audit_acceleration, compiler_commands, defines, requirements
from unittest.mock import patch

from core_build_policy import (REQUIRED_EXPORTS, audit_commands, ndk_flags, plan,
                               select_library, verify_elf_abi, dynamic_dependencies, prepare_cmake, prepare_ndk)


class CoreBuildPolicyTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((Path(__file__).resolve().parents[1] / "cores/cores.json").read_text())

    def test_release_matrix(self):
        matrix = plan(self.catalog, "push", "refs/heads/main", False)["include"]
        self.assertEqual(len(matrix), len(self.catalog["cores"]) * len(self.catalog["abis"]))
        self.assertTrue(all(item["profile"] == "baseline" for item in matrix))
        self.assertEqual(len({item["artifact"] for item in matrix}), len(matrix))

    def test_experiment(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                      "mgba,snes9x", "arm64-v8a", "o3-thinlto")["include"]
        self.assertEqual(len(matrix), 2)
        self.assertTrue(all(item["cflags"] == "-O3 -flto=thin" for item in matrix))
        self.assertTrue(all(item["ldflags"] == "-flto=thin" for item in matrix))
        self.assertTrue(all(item["artifact"].startswith("candidate-") for item in matrix))

    def test_pr_comparison(self):
        matrix = plan(self.catalog, "pull_request", "refs/pull/1/merge", False)["include"]
        self.assertEqual(len(matrix), 29)
        self.assertEqual({item["profile"] for item in matrix},
                         {"baseline", "thinlto", "picodrive-sh2", "tune-a57", "o3", "o3-thinlto", "snes9x2010-threaded", "core-hotpaths"})
        self.assertEqual(len({item["artifact"] for item in matrix}), len(matrix))
        self.assertEqual([item["makevars"] for item in matrix if item["profile"] == "picodrive-sh2"], ["use_sh2drc=1"])

    def test_full_paired_comparisons(self):
        for comparison, profiles in (("baseline-thinlto", ("baseline", "thinlto")),
                                     ("o3-o3-thinlto", ("o3", "o3-thinlto"))):
            matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                          comparison=comparison)["include"]
            expected = {(core["core"], abi, profile) for core in self.catalog["cores"]
                        for abi in self.catalog["abis"] for profile in profiles}
            actual = {(item["core"], item["abi"], item["profile"]) for item in matrix}
            self.assertEqual(actual, expected)
            self.assertEqual(len(matrix), len(expected))
            self.assertEqual(len({item["artifact"] for item in matrix}), len(matrix))
            with self.assertRaises(ValueError):
                plan(self.catalog, "workflow_dispatch", "refs/heads/main", True, comparison=comparison)
            with self.assertRaises(ValueError):
                plan(self.catalog, "pull_request", "refs/pull/1/merge", False, comparison=comparison)

    def test_narrow_candidates(self):
        candidate = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                         profile="picodrive-sh2")["include"]
        self.assertEqual(len(candidate), 1)
        self.assertEqual((candidate[0]["core"], candidate[0]["abi"], candidate[0]["makevars"]),
                         ("picodrive", "arm64-v8a", "use_sh2drc=1"))
        for core, abi, profile in [("mgba", "arm64-v8a", "picodrive-sh2"),
                                    ("picodrive", "armeabi-v7a", "picodrive-sh2"),
                                    ("snes9x", "x86_64", "tune-a57")]:
            with self.assertRaises(ValueError):
                plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False, core, abi, profile)
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/main", True, profile="picodrive-sh2")
        log = "clang -O2 -mtune=cortex-a57 -c a.c -o a.o\nclang -shared a.o -o a.so\n"
        self.assertEqual(audit_commands(log, "tune-a57")["cortex_a57_compiles"], 1)
        with self.assertRaises(ValueError):
            audit_commands(log.replace("-mtune=cortex-a57", "-mtune=generic"), "tune-a57")

    def test_acceleration_evidence_and_effective_defines(self):
        commands = list(compiler_commands("clang -DHAVE_DYNAREC -DMMAP_JIT_CACHE -c src/cpu_threaded.c -o a.o\n"
                                         "clang -c src/arm/arm64_stub.S -o b.o\n"))
        self.assertEqual(audit_acceleration(commands, "gpsp", "arm64-v8a", "baseline"),
                         {"dynarec": True, "native_stub": True})
        with self.assertRaises(ValueError):
            audit_acceleration(commands[:1], "gpsp", "arm64-v8a", "baseline")
        with self.assertRaises(ValueError):
            audit_acceleration([["-DHAVE_DYNAREC", "-DMMAP_JIT_CACHE", "-UHAVE_DYNAREC", "-c", "cpu_threaded.c"],
                                commands[1]], "gpsp", "arm64-v8a", "baseline")
        self.assertEqual(defines(["-D", "A=4", "-UB", "-D", "B=0", "-UA"]), {"B": "0"})
        self.assertFalse(audit_acceleration([], "picodrive", "arm64-v8a", "baseline")["sh2_dynarec"])
        with self.assertRaises(ValueError):
            audit_acceleration([], "picodrive", "arm64-v8a", "picodrive-sh2")
        self.assertTrue(audit_acceleration([["-DDRC_SH2", "-c", "src/cpu/sh2/compiler.c"]],
                                          "picodrive", "arm64-v8a", "picodrive-sh2")["sh2_dynarec"])

    def test_response_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "compile.rsp").write_text("-DHAVE_DYNAREC -DMMAP_JIT_CACHE -c src/cpu_threaded.c -o a.o")
            commands = list(compiler_commands("clang @compile.rsp\nclang -c src/arm/arm64_stub.S",
                                              root, root, strict=True))
            self.assertTrue(audit_acceleration(commands, "gpsp", "arm64-v8a", "baseline")["dynarec"])
            with self.assertRaises(ValueError):
                list(compiler_commands("clang @../outside.rsp", root, root, strict=True))

    def test_compiler_log_diagnostics_do_not_trip_strict_parser(self):
        # Clang diagnostics can quote source text containing a tool path. That is
        # not a compiler invocation and must not fail an otherwise valid audit.
        diagnostic = ('2026-11-26T16:49:47.2705010Z   115 | '
                      '{ "AXP/usr/local/ndk/bin/clang --target=armv7-none-linux-android29')
        self.assertEqual(list(compiler_commands(diagnostic, strict=True)), [])
        malformed = '2026-11-26T16:49:47.2705010Z /ndk/bin/clang "unterminated'
        with self.assertRaisesRegex(ValueError, "Malformed compiler command"):
            list(compiler_commands(malformed, strict=True))

    def test_command_boundaries_and_working_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / "nested"
            nested.mkdir()
            (nested / "flags.rsp").write_text("-O3 -c test.c -o test.o")
            log = ("warning: clang -c diagnostic.c -o diagnostic.o\n"
                   "cd nested && /ndk/bin/clang @flags.rsp && echo @not-a-response-file\n"
                   "[12/20] : && clang -shared test.o -o test.so && :\n")
            commands = list(compiler_commands(log, root, root, strict=True))
            self.assertEqual(commands, [
                ["-O3", "-c", "test.c", "-o", "test.o"],
                ["-shared", "test.o", "-o", "test.so"],
            ])

    def test_fail_closed_publish(self):
        for ref, cores, abis, profile in [
            ("refs/heads/feature", "", "", "baseline"),
            ("refs/heads/main", "mgba", "", "baseline"),
            ("refs/heads/main", "", "arm64-v8a", "baseline"),
            ("refs/heads/main", "", "", "thinlto"),
        ]:
            with self.subTest(ref=ref, cores=cores, abis=abis, profile=profile):
                with self.assertRaises(ValueError):
                    plan(self.catalog, "workflow_dispatch", ref, True, cores, abis, profile)

    def test_unknown_selection(self):
        for cores in ("nope", "mgba,invalid", "mgba,mgba", "mgba,"):
            with self.assertRaises(ValueError):
                plan(self.catalog, "workflow_dispatch", "refs/heads/main", False, cores)

    def test_actual_flags(self):
        log = ("clang -O2 -O3 -flto=thin -c a.c -o a.o\n"
               "/ndk/bin/aarch64-linux-android29-clang++ -O3 -flto=thin -c b.cpp -o b.o\n"
               "clang -c a.S -o asm.o\n"
               "clang++ -flto=thin -shared a.o b.o -o lib.so\n")
        result = audit_commands(log, "o3-thinlto")
        self.assertEqual(result["compile_commands"], 2)
        self.assertEqual(result["thinlto_compiles"], 2)
        self.assertEqual(result["thinlto_links"], 1)
        with self.assertRaises(ValueError):
            audit_commands(log.replace("-O2 -O3", "-O3 -O2"), "o3-thinlto")
        with self.assertRaises(ValueError):
            audit_commands(log.replace("-flto=thin -shared", "-shared"), "thinlto")
        with self.assertRaises(ValueError):
            audit_commands(log.replace("-flto=thin -c a.c", "-flto=thin -fno-lto -c a.c"), "thinlto")
        with self.assertRaises(ValueError):
            audit_commands("", "thinlto")

    def test_preserve_ndk_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Application.mk"
            path.write_text("APP_LDFLAGS := -Wl,--gc-sections\nAPP_CPPFLAGS := -fexceptions\n")
            ndk_flags(path, "thinlto", "-Wno-invalid-specialization")
            result = path.read_text()
            self.assertIn("APP_LDFLAGS := -Wl,--gc-sections", result)
            self.assertIn("APP_CPPFLAGS := -fexceptions", result)
            self.assertIn("APP_CPPFLAGS += -Wno-invalid-specialization", result)
            self.assertIn("APP_CFLAGS += -flto=thin", result)
            self.assertIn("APP_LDFLAGS += -Wl,-z,max-page-size=16384 -flto=thin", result)

    def test_elf_abi_identity(self):
        for abi, machine, elfclass in (("arm64-v8a", 183, 2), ("armeabi-v7a", 40, 1),
                                       ("x86_64", 62, 2), ("x86", 3, 1)):
            with self.subTest(abi=abi), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "core.so"
                header = bytearray(64 if elfclass == 2 else 52)
                header[:7] = b"\x7fELF" + bytes((elfclass, 1, 1))
                struct.pack_into("<HHI", header, 16, 3, machine, 1)
                path.write_bytes(header)
                self.assertEqual(verify_elf_abi(path, abi),
                                 {"elf_class": elfclass * 32, "elf_machine": machine})
                for offset, value in ((4, 3 - elfclass), (5, 2), (16, 2), (18, 0), (20, 0)):
                    bad = bytearray(header)
                    bad[offset] = value
                    path.write_bytes(bad)
                    with self.assertRaises(ValueError):
                        verify_elf_abi(path, abi)
                path.write_bytes(header[:20])
                with self.assertRaises(ValueError):
                    verify_elf_abi(path, abi)

    def test_dynamic_dependencies(self):
        valid = " 0x00000001 (NEEDED) Shared library: [libc.so]\n"
        with patch("core_build_policy.subprocess.check_output", return_value=valid):
            self.assertEqual(dynamic_dependencies("readelf", Path("test.so")), ["libc.so"])
        for invalid in (
            " (NEEDED) Shared library: [/tmp/libc.so]",
            " (NEEDED) Shared library: [libunbundled.so]",
            " (NEEDED) unknown format",
            " (RUNPATH) Library runpath: [/tmp]",
            " (TEXTREL) 0x0",
        ):
            with self.subTest(invalid=invalid), patch("core_build_policy.subprocess.check_output", return_value=valid + invalid):
                with self.assertRaises(ValueError):
                    dynamic_dependencies("readelf", Path("test.so"))

    def test_swanstation_explicit_ipo_candidates(self):
        original = ('# Enable LTO/LTCG on Release builds.\n'
                    'if(CMAKE_BUILD_TYPE MATCHES "Release")\n'
                    '  set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)\nendif()\n')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "CMakeLists.txt"
            path.write_text(original)
            prepare_cmake(root, "swanstation", "baseline")
            self.assertEqual(path.read_text(), original)
            prepare_cmake(root, "mgba", "o3")
            self.assertEqual(path.read_text(), original)
            prepare_cmake(root, "swanstation", "o3")
            self.assertIn("if(FALSE)", path.read_text())
            with self.assertRaises(ValueError):
                prepare_cmake(root, "swanstation", "thinlto")

    def test_threaded_snes_candidate(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                      profile="snes9x2010-threaded")["include"]
        self.assertEqual({item["core"] for item in matrix}, {"snes9x2010"})
        self.assertEqual({item["abi"] for item in matrix}, set(self.catalog["abis"]))
        self.assertTrue(all(item["artifact"].startswith("candidate-") for item in matrix))
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/main", True, profile="snes9x2010-threaded")
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False, cores="mgba",
                 profile="snes9x2010-threaded")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "jni").mkdir()
            (root / "build").mkdir()
            recipe = root / "jni/Android.mk"
            source = "include $(CORE_DIR)/build/Makefile.common\nLOCAL_CFLAGS   := $(COREFLAGS)\n"
            recipe.write_text(source)
            (root / "build/Makefile.common").write_text(
                "ifneq ($(HAVE_THREADS),0)\n"
                "SOURCES_C += rthreads/rthreads.c rthreads/retro_eventcount.c\nendif\n")
            prepare_ndk(root, "snes9x2010", "baseline")
            self.assertEqual(recipe.read_text(), source)
            prepare_ndk(root, "snes9x2010", "snes9x2010-threaded")
            self.assertIn("HAVE_THREADS := 1\ninclude", recipe.read_text())
            self.assertIn("$(COREFLAGS) -DHAVE_THREADS", recipe.read_text())
            recipe.write_text("changed")
            with self.assertRaises(ValueError):
                prepare_ndk(root, "snes9x2010", "snes9x2010-threaded")
        log = ("clang -DHAVE_THREADS -c src/ppu.c -o ppu.o\n"
               "clang -DHAVE_THREADS -DFRONTEND_SUPPORTS_RGB565 -c libretro/libretro.c -o core.o\n"
               "clang -c rthreads/rthreads.c -o threads.o\n"
               "clang -c rthreads/retro_eventcount.c -o event.o\n")
        commands = list(compiler_commands(log))
        self.assertTrue(all(audit_acceleration(commands, "snes9x2010", "arm64-v8a",
                                               "snes9x2010-threaded").values()))
        with self.assertRaises(ValueError):
            audit_acceleration(commands[:-1], "snes9x2010", "arm64-v8a", "snes9x2010-threaded")

    def test_every_catalog_core_has_build_contracts(self):
        self.assertEqual(AUDITED, {core["core"] for core in self.catalog["cores"]})
        for entry in self.catalog["cores"]:
            core = entry["core"]
            for abi in self.catalog["abis"]:
                with self.subTest(core=core, abi=abi):
                    rules = requirements(core, abi, "baseline")
                    # These two recipes intentionally have no acceleration
                    # requirement for their non-dynarec baseline variants.
                    if core == "picodrive" and abi != "armeabi-v7a":
                        continue
                    if core == "swanstation" and abi == "x86":
                        continue
                    self.assertTrue(rules)
                    commands = [
                        ["-D" + key + "=" + value for key, value in macros]
                        + ["-c", "upstream/" + source]
                        for source, macros in rules.values()
                    ]
                    self.assertTrue(all(audit_acceleration(commands, core, abi, "baseline").values()))
                    with self.assertRaises(ValueError):
                        audit_acceleration([], core, abi, "baseline")

    def test_source_hotpath_candidates(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                      profile="core-hotpaths")["include"]
        self.assertEqual({item["core"] for item in matrix}, {"bsnes"})
        self.assertEqual(len(matrix), len(self.catalog["abis"]))
        self.assertTrue(all(item["artifact"].startswith("candidate-core-hotpaths-") for item in matrix))
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/main", True, profile="core-hotpaths")
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                 cores="mgba", profile="core-hotpaths")

    def test_select_by_exports_not_filename_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "a.so").write_bytes(b"not a core")
            core = root / "z.so"
            core.write_bytes(b"core")
            (root / "copy.so").write_bytes(b"core")
            with patch("core_build_policy.exports", side_effect=lambda _, p: REQUIRED_EXPORTS if p.name != "a.so" else set()):
                self.assertEqual(select_library("nm", root, "*.so").read_bytes(), b"core")
                (root / "different.so").write_bytes(b"another core")
                with self.assertRaises(ValueError):
                    select_library("nm", root, "*.so")


if __name__ == "__main__":
    unittest.main()
