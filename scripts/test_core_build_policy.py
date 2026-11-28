import json
from pathlib import Path
import tempfile
import struct
import unittest
from core_acceleration_audit import AUDITED, audit_acceleration, compiler_commands, defines, requirements
from unittest.mock import patch
from core_link_evidence import target_commands

from core_build_policy import (REQUIRED_EXPORTS, audit_commands, ndk_flags, plan,
                               select_library, verify_elf_abi, dynamic_dependencies, prepare_cmake, prepare_ndk)


class CoreBuildPolicyTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((Path(__file__).resolve().parents[1] / "cores/cores.json").read_text())

    def test_citra_ci_version_template_accepts_empty_repository(self):
        import tempfile
        from pathlib import Path
        from core_build_policy import prepare_cmake
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "src/common/CMakeLists.txt"
            path.parent.mkdir(parents=True)
            path.write_text('string(REGEX MATCH "citra-emu/citra-?(.*)" OUTVAR ${BUILD_REPOSITORY})')
            tests = Path(temp) / "src/tests/CMakeLists.txt"
            tests.parent.mkdir(parents=True)
            tests.write_text("target_link_libraries(tests PRIVATE common core video_core)\n")
            prepare_cmake(Path(temp), "citra2018", "baseline")
            self.assertIn('"${BUILD_REPOSITORY}"', path.read_text())
            self.assertIn("if(ANDROID)\n    target_link_libraries(tests PRIVATE log)\nendif()", tests.read_text())
            self.assertEqual(tests.read_text().count("target_link_libraries(tests PRIVATE log)"), 1)

    def test_ndk_dotdot_source_identity(self):
        from core_acceleration_audit import evidence
        self.assertTrue(evidence([["-c", "/src/yabause/src/libretro/jni/../../sh2int.c",
                                   "-DAMBIENT_SH2_ARM64_JIT=1"]],
                                 "yabause/src/sh2int.c", (("AMBIENT_SH2_ARM64_JIT", "1"),)))

    def test_patched_source_contract(self):
        from core_hotpath_patches import patched_sources
        from core_link_evidence import source_dependencies
        for core in self.catalog["cores"]:
            self.assertTrue(patched_sources(core["core"], "arm64-v8a", "optimized"))
        self.assertIn("Common/Thread/ThreadManager.cpp", patched_sources("ppsspp", "arm64-v8a", "optimized"))
        self.assertIn("src/memfile.h", patched_sources("noods", "arm64-v8a", "baseline"))
        self.assertFalse(patched_sources("picodrive", "x86", "optimized"))
        self.assertNotIn("libco/arm.c", patched_sources("bsnes", "arm64-v8a", "optimized"))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/"a.d").write_text("a.o: src/a.cpp \\\n src/b.h\nsrc/b.h:\n")
            self.assertEqual(source_dependencies(["-c", "src/a.cpp", "-MF", "a.d"], root),
                             {root/"src/a.cpp", root/"src/b.h"})

    def test_pgo_acceleration_matches_optimized(self):
        for profile in ("pgo-generate", "pgo-use"):
            for core in ("picodrive", "snes9x2010", "mednafen_saturn"):
                job = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False,
                           cores=core, abis="arm64-v8a", profile=profile)["include"][0]
                optimized = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False,
                                 cores=core, abis="arm64-v8a", profile="optimized")["include"][0]
                self.assertEqual(job["makevars"], optimized["makevars"])
                self.assertEqual(requirements(core, "arm64-v8a", profile),
                                 requirements(core, "arm64-v8a", "optimized"))

    def test_pgo_is_artifact_only_and_requires_one_explicit_target(self):
        for profile in ("pgo-generate", "pgo-use"):
            jobs = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False,
                        cores="mgba", abis="arm64-v8a", profile=profile)["include"]
            self.assertEqual(len(jobs), 1)
            self.assertTrue(jobs[0]["artifact"].startswith("candidate-pgo-"))
            with self.assertRaises(ValueError):
                plan(self.catalog, "workflow_dispatch", "refs/heads/main", True,
                     cores="mgba", abis="arm64-v8a", profile=profile)
            with self.assertRaises(ValueError):
                plan(self.catalog, "workflow_dispatch", "refs/heads/main", False, profile=profile)
            flag = jobs[0]["cflags"].split()[1]
            commands = [["clang", "-O3", flag, "-flto=thin", "-c", "a.c"], ["clang", flag, "-flto=thin", "-shared", "a.o"]]
            self.assertEqual(audit_commands("", profile, scoped_commands=commands)["pgo_compiles"], 1)
            with self.assertRaises(ValueError):
                audit_commands("", profile, scoped_commands=[commands[0], ["clang", "-shared", "a.o"]])

    def test_release_matrix(self):
        matrix = plan(self.catalog, "push", "refs/heads/main", False)["include"]
        self.assertEqual(len(matrix), sum(len(c.get("abis", self.catalog["abis"])) for c in self.catalog["cores"]))
        self.assertTrue(all(item["profile"] == "baseline" for item in matrix))
        self.assertEqual(len({item["artifact"] for item in matrix}), len(matrix))

    def test_catalog_matches_app_and_abi_restrictions(self):
        import re
        app = (Path(__file__).resolve().parents[1] / "app/src/main/java/com/ambient/tv/games/GameSystems.kt").read_text()
        ids = set(re.findall(r'GameCore\(\s*"([^"]+)"', app))
        self.assertEqual({c["core"] for c in self.catalog["cores"]}, ids)
        for profile in ("baseline", "optimized", "optimized-a57"):
            jobs = plan(self.catalog, "push", "refs/heads/main", False, profile=profile)["include"]
            self.assertEqual({j["core"] for j in jobs}, ids)
            self.assertFalse(any(j["core"] == "melondsds" and j["abi"] == "x86" for j in jobs))
            self.assertTrue(all(j["abi"] == "arm64-v8a" for j in jobs if j["core"] in {"armsx2", "panda3ds"}))
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False, cores="melondsds", abis="x86")
        job = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                   cores="melondsds", abis="armeabi-v7a")["include"][0]
        self.assertIn("-DENABLE_JIT=OFF", job["args"])

    def test_experiment(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                      "mgba,snes9x", "arm64-v8a", "o3-thinlto")["include"]
        self.assertEqual(len(matrix), 2)
        self.assertTrue(all(item["cflags"] == "-O3 -flto=thin" for item in matrix))
        self.assertTrue(all(item["ldflags"] == "-flto=thin" for item in matrix))
        self.assertTrue(all(item["artifact"].startswith("candidate-") for item in matrix))

    def test_pr_comparison(self):
        matrix = plan(self.catalog, "pull_request", "refs/pull/1/merge", False)["include"]
        self.assertLessEqual(len(matrix), 256)
        self.assertEqual({item["core"] for item in matrix if item["profile"] == "core-hotpaths"},
                         {core["core"] for core in self.catalog["cores"] if not core.get("extended")} - {"picodrive", "snes9x2010"})
        self.assertIn(("picodrive", "picodrive-sh2"), {(j["core"], j["profile"]) for j in matrix})
        self.assertIn(("snes9x2010", "snes9x2010-threaded"), {(j["core"], j["profile"]) for j in matrix})
        for core in self.catalog["cores"]:
            if core.get("extended"):
                actual = {(item["abi"], item["profile"]) for item in matrix if item["core"] == core["core"]}
                expected = {(abi, profile) for abi in core.get("abis", self.catalog["abis"])
                            for profile in ("baseline", "optimized")}
                expected.add(("arm64-v8a", "optimized-a57"))
                self.assertEqual(actual, expected)
        self.assertEqual({item["profile"] for item in matrix},
                         {"baseline", "thinlto", "picodrive-sh2", "tune-a57", "o3", "o3-thinlto", "snes9x2010-threaded", "core-hotpaths", "optimized", "optimized-a57"})
        self.assertEqual(len({item["artifact"] for item in matrix}), len(matrix))
        self.assertEqual([item["makevars"] for item in matrix if item["profile"] == "picodrive-sh2"], ["use_sh2drc=1"])

    def test_combined_public_profiles(self):
        for profile in ("optimized", "optimized-a57"):
            matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False, profile=profile)["include"]
            abis = self.catalog["abis"] if profile == "optimized" else ["arm64-v8a"]
            self.assertEqual(len(matrix), sum(len(set(c.get("abis", self.catalog["abis"])) & set(abis)) for c in self.catalog["cores"]))
            self.assertEqual({j["abi"] for j in matrix}, set(abis))
            self.assertTrue(all(j["cflags"].startswith("-O3 -flto=thin") and j["ldflags"] == "-flto=thin" for j in matrix))
            self.assertTrue(all(("-mtune=cortex-a57" in j["cflags"]) == (profile == "optimized-a57") for j in matrix))
            self.assertEqual([(j["core"], j["abi"]) for j in matrix if j["makevars"]], [("picodrive", "arm64-v8a")])
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/main", False, abis="x86", profile="optimized-a57")
        log = "clang -O3 -flto=thin -mtune=cortex-a57 -c a.c -o a.o\nclang -shared -flto=thin a.o -o a.so\n"
        self.assertEqual(audit_commands(log, "optimized-a57")["thinlto_compiles"], 1)
        for flag in ("-O3", "-flto=thin", "-mtune=cortex-a57"):
            with self.assertRaises(ValueError):
                audit_commands(log.replace(flag, ""), "optimized-a57")

    def test_mupen_native_offset_helper(self):
        import shutil
        import subprocess
        marker = "ASM_DEFINES_OBJ := $(LOCAL_OBJS_DIR)/$(call get-object-name,$(CORE_DIR)/src/asm_defines/asm_defines.c)"
        generator = "\t\t-f $(CORE_DIR)/tools/gen_asm_defines.awk"
        original = marker + "\n$(ASM_DEFINES_NASM): $(ASM_DEFINES_OBJ)\n" + generator + "\n"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "libretro"
            (root / "jni").mkdir(parents=True)
            amd64 = root.parent / "libretro-common/libco/amd64.c"
            amd64.parent.mkdir(parents=True)
            coroutine = "#include <stdlib.h>\n#include <assert.h>\ntypedef void *cothread_t;\n#define ASM_PREFIX \"\"\nstatic long long co_active_buffer[64];\nstatic cothread_t co_active_handle = 0;\ncothread_t co_active(void) { if (!co_active_handle) co_active_handle = &co_active_buffer; return co_active_handle; }\nstatic void crash(void) { abort(); }\ncothread_t co_create(unsigned int size, void (*entrypoint)(void)) {\n    co_active(); size += 512; size &= ~15;\n    void *h = malloc(size);\n    long long *p = (long long*)((char*)h + size);\n    *--p = (long long)crash; *--p = (long long)entrypoint; *(long long*)h = (long long)p;\n    return h;\n}\nvoid co_delete(cothread_t h) { free(h); }\n__asm__(\n\".intel_syntax noprefix         \\n\"\n\".globl \" ASM_PREFIX \"co_switch              \\n\"\nASM_PREFIX \"co_switch:                     \\n\"\n\"mov rsi, [rip+\" ASM_PREFIX \"co_active_handle]\\n\"\n\"mov [rsi],rsp                  \\n\"\n\"mov [rsi+0x08],rbp             \\n\"\n\"mov [rsi+0x10],rbx             \\n\"\n\"mov [rsi+0x18],r12             \\n\"\n\"mov [rsi+0x20],r13             \\n\"\n\"mov [rsi+0x28],r14             \\n\"\n\"mov [rsi+0x30],r15             \\n\"\n\"mov [rip+\" ASM_PREFIX \"co_active_handle], rdi\\n\"\n\"mov rsp,[rdi]                  \\n\"\n\"mov rbp,[rdi+0x08]             \\n\"\n\"mov rbx,[rdi+0x10]             \\n\"\n\"mov r12,[rdi+0x18]             \\n\"\n\"mov r13,[rdi+0x20]             \\n\"\n\"mov r14,[rdi+0x28]             \\n\"\n\"mov r15,[rdi+0x30]             \\n\"\n\"ret                            \\n\"\n\".att_syntax                    \\n\"\n);\n"
            recipe = root / "jni/Android.mk"
            launcher = root / "jni/ambient-mupen-cc"
            for profile in ("baseline", "optimized", "optimized-a57", "thinlto"):
                recipe.write_text(original)
                amd64.write_text(coroutine)
                launcher.unlink(missing_ok=True)
                prepare_ndk(root, "mupen64plus_next_gles3", profile)
                if profile == "baseline":
                    self.assertEqual(recipe.read_text(), original)
                    self.assertFalse(launcher.exists())
                    self.assertEqual(amd64.read_text(), coroutine)
                else:
                    self.assertTrue(launcher.is_file())
                    self.assertNotIn("mov rsi, [rip+", amd64.read_text())
                    self.assertNotIn("mov [rip+", amd64.read_text())
                    self.assertIn("ambient_mupen_co_swap(handle, previous)", amd64.read_text())
                    self.assertIn("test -s $(ASM_DEFINES_NASM) && test -s $(ASM_DEFINES_GAS)", recipe.read_text())
            # Real shared-library ThinLTO link and repeated stack switches on x86_64.
            import platform
            if platform.machine() == "x86_64" and shutil.which("clang") and shutil.which("ld.lld"):
                driver = shutil.which("clang")
                library = root / "libco-test.so"
                harness = root / "coroutine-test.c"
                harness.write_text("#include <assert.h>\ntypedef void *cothread_t;\nextern cothread_t co_active(void), co_create(unsigned, void (*)(void));\nextern void co_switch(cothread_t), co_delete(cothread_t);\nstatic cothread_t main_co, child_co;\nstatic int phase;\nstatic void child(void) {\n    for (int i = 0; i < 10000; ++i) {\n        assert(co_active() == child_co); ++phase; co_switch(main_co);\n    }\n    __builtin_trap();\n}\nint main(void) {\n    main_co = co_active(); child_co = co_create(65536, child);\n    for (int i = 0; i < 10000; ++i) {\n        co_switch(child_co); assert(co_active() == main_co); assert(phase == i + 1);\n    }\n    co_delete(child_co);\n}\n")
                subprocess.run([driver, "-O3", "-flto=thin", "-fPIC", "-shared", "-fuse-ld=lld",
                                "-Wl,--no-undefined", str(amd64), "-o", str(library)], check=True)
                executable = root / "coroutine-test"
                subprocess.run([driver, "-O3", "-flto=thin", "-fuse-ld=lld", str(harness),
                                str(library), "-Wl,-rpath," + str(root), "-o", str(executable)], check=True)
                subprocess.run([str(executable)], check=True, timeout=10)
            recipe.write_text("changed")
            with self.assertRaises(ValueError):
                prepare_ndk(root, "mupen64plus_next_gles3", "optimized")
            # Exercise the actual generated launcher with an argument recorder
            # even on a host without Clang, including response-file detection.
            driver = root / "clang"
            driver.write_text("#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            driver.chmod(0o755)
            offset = root / "mupen64plus-core/src/asm_defines/asm_defines.c"
            offset.parent.mkdir(parents=True)
            offset.write_text('const char offset[] = "@ASM_DEFINE offsetof_struct_test 0x12345678";\n')
            other = root / "other/asm_defines.c"
            other.parent.mkdir()
            other.write_text("int f(void) { return 42; }\n")
            flags = ["--target=aarch64-linux-android21", "-O3", "-flto=thin", "-mtune=cortex-a57"]
            response = root / "flags.rsp"
            response.write_text(" ".join(flags + ["-c", str(offset), "-o", str(root / "response.o")]))
            with patch.dict("os.environ", {"CORE_CCACHE": ""}):
                for args, expected in ((flags + ["-c", str(offset), "-o", "offset.o"], True),
                                       (flags + ["-c", str(other), "-o", "other.o"], False),
                                       (["@" + str(response)], True),
                                       (["-shared", "-flto=thin", "offset.o", "other.o"], False)):
                    output = subprocess.check_output([str(launcher), str(driver), *args], text=True)
                    executed = json.loads(output.splitlines()[-1])
                    self.assertEqual(executed, args + (["-fno-lto"] if expected else []))
                clang = shutil.which("clang")
                if clang:
                    for src, name, magic in ((offset, "offset.o", b"\x7fELF"), (other, "other.o", b"BC\xc0\xde")):
                        obj = root / name
                        subprocess.run([str(launcher), clang, *flags, "-c", str(src), "-o", str(obj)], check=True)
                        self.assertTrue(obj.read_bytes().startswith(magic))
                    subprocess.run([str(launcher), clang, "@" + str(response)], check=True)
                    for name in ("offset.o", "response.o"):
                        self.assertIn(b"@ASM_DEFINE offsetof_struct_test 0x12345678", (root / name).read_bytes())
        for profile in ("optimized", "optimized-a57"):
            flags = "-O3 -flto=thin" + (" -mtune=cortex-a57" if profile == "optimized-a57" else "")
            helper = f"clang {flags} -fno-lto -c src/mupen64plus-core/src/asm_defines/asm_defines.c -o offsets.o\n"
            dyn = f"clang {flags} -DNEW_DYNAREC=4 -DDYNAREC -DHAVE_PARALLEL_RDP -c new_dynarec/new_dynarec.c -o dyn.o\n"
            rsp = f"clang++ {flags} -DHAVE_PARALLEL_RSP -c parallel.cpp -o rsp.o\n"
            link = "clang++ -shared -flto=thin offsets.o dyn.o rsp.o -o libretro.so\n"
            log = helper + dyn + rsp + link
            result = audit_commands(log, profile, core="mupen64plus_next_gles3", abi="arm64-v8a")
            self.assertEqual(result["native_offset_helpers"], 1)
            self.assertEqual(result["thinlto_compiles"], 2)
            for bad in (log.replace("-fno-lto ", ""), log.replace(" -DNEW_DYNAREC", " -fno-lto -DNEW_DYNAREC"),
                        log.replace("src/mupen64plus-core/src/asm_defines/asm_defines.c", "src/other/asm_defines.c")):
                with self.assertRaises(ValueError):
                    audit_commands(bad, profile, core="mupen64plus_next_gles3", abi="arm64-v8a")

    def test_auto_experimental_full_catalogs(self):
        for profile in ("optimized", "optimized-a57"):
            automatic = plan(self.catalog, "push", "refs/heads/main", False, profile=profile)
            manual = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False, profile=profile)
            self.assertEqual(automatic, manual)
            for ref, cores in (("refs/heads/feature", ""), ("refs/heads/main", "mgba")):
                with self.assertRaises(ValueError):
                    plan(self.catalog, "push", ref, False, cores=cores, profile=profile)
        with self.assertRaises(ValueError):
            plan(self.catalog, "push", "refs/heads/main", False, profile="o3")

    def test_mupen_yasm_linked_objects(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for abi, variant, stub, fmt in (("x86", "1", "x86/linkage_x86", "elf32"),
                                             ("x86_64", "2", "x64/linkage_x64", "elf64")):
                with self.subTest(abi=abi):
                    source = "new_dynarec/" + stub + ".asm"
                    asm = f"/usr/bin/ccache /ndk/bin/yasm -I/root/include -f {fmt} {source} -o stub.o\n"
                    compile = f"clang -O3 -flto=thin -DNEW_DYNAREC={variant} -DDYNAREC -c new_dynarec/new_dynarec.c -o dyn.o\n"
                    (root / "linker.list").write_text("dyn.o stub.o")
                    link = "clang++ -flto=thin -shared @linker.list -o libretro.so\n"
                    log = asm + compile + link
                    commands, graph = target_commands(log, root, root, root / "libretro.so")
                    self.assertEqual(graph["unresolved_local_inputs"], [])
                    self.assertEqual(graph["linked_compile_commands"], 2)
                    result = audit_commands(log, "optimized", core="mupen64plus_next_gles3", abi=abi, scoped_commands=commands)
                    self.assertEqual(result["compile_commands"], 1)
                    self.assertTrue(result["acceleration"]["native_stub"])
                    for bad in (compile + link, "warning: " + asm + compile + link, log.replace(fmt, "bin")):
                        _, missing = target_commands(bad, root, root, root / "libretro.so")
                        self.assertEqual(missing["unresolved_local_inputs"], ["stub.o"])
                    (root / "linker.list").write_text("dyn.o")
                    commands, _ = target_commands(log, root, root, root / "libretro.so")
                    with self.assertRaises(ValueError):
                        audit_commands(log, "optimized", core="mupen64plus_next_gles3", abi=abi, scoped_commands=commands)

    def test_full_paired_comparisons(self):
        for comparison, profiles in (("baseline-thinlto", ("baseline", "thinlto")),
                                     ("o3-o3-thinlto", ("o3", "o3-thinlto"))):
            # The expanded catalog exceeds the Actions limit for a paired
            # all-ABI build. Pair per ABI without changing the coverage.
            matrix = [job for abi in self.catalog["abis"]
                      for job in plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                                      abis=abi, comparison=comparison)["include"]]
            expected = {(core["core"], abi, profile) for core in self.catalog["cores"]
                        for abi in core.get("abis", self.catalog["abis"]) for profile in profiles}
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
        pico = [["-c", "src/"+source] for source, _ in
                requirements("picodrive", "arm64-v8a", "baseline").values()]
        self.assertFalse(audit_acceleration(pico, "picodrive", "arm64-v8a", "baseline")["sh2_dynarec"])
        with self.assertRaises(ValueError):
            audit_acceleration(pico, "picodrive", "arm64-v8a", "picodrive-sh2")
        self.assertTrue(audit_acceleration(pico+[["-DDRC_SH2", "-c", "src/cpu/sh2/compiler.c"]],
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

    def test_nested_ccache_link_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / "build"
            nested.mkdir()
            log = ("cd build && ccache /usr/bin/ccache /ndk/bin/clang++ -O3 -c ../src/a.cpp -o a.o\n"
                   "cd build && /usr/bin/ccache /usr/bin/ccache /ndk/bin/clang -O3 -c ../src/b.c -o b.o\n"
                   "cd build && /ndk/bin/clang++ -shared a.o b.o -o libretro.so\n")
            commands, graph = target_commands(log, root, root, nested / "libretro.so",
                                               ("a.cpp", "b.c"))
            self.assertEqual(graph["unresolved_local_inputs"], [])
            self.assertEqual(graph["linked_compile_commands"], 2)
            self.assertEqual(len(commands), 3)

    def test_external_archive_contract(self):
        from core_build_policy import external_archive_evidence
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relative = "src/.deps/lib/libzstd.a"
            path = root / relative
            path.parent.mkdir(parents=True)
            obj = bytearray(64)
            obj[:7] = b"\x7fELF" + bytes((2, 1, 1))
            struct.pack_into("<HHI", obj, 16, 1, 183, 1)
            header = b"member.o/       " + b"0           " + b"0     " + b"0     " + b"644     " + b"64        " + bytes((96, 10))
            self.assertEqual(len(header), 60)
            path.write_bytes(b"!<arch>\n" + header + obj)
            missing, proof = external_archive_evidence(root, "armsx2", "arm64-v8a", [relative, "unknown.a"])
            self.assertEqual(missing, ["unknown.a"])
            self.assertEqual(proof[relative]["elf_members"], 1)
            self.assertFalse(proof[relative]["compiler_flags_verified"])
            self.assertEqual(len(proof[relative]["sha256"]), 64)
            self.assertEqual(external_archive_evidence(root, "cemu", "arm64-v8a", [relative]), ([relative], {}))
            struct.pack_into("<H", obj, 18, 62)
            path.write_bytes(b"!<arch>\n" + header + obj)
            with self.assertRaises(ValueError):
                external_archive_evidence(root, "armsx2", "arm64-v8a", [relative])

    def test_dolphin_and_ppsspp_explicit_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mbed = root / "Externals/mbedtls/CMakeLists.txt"
            mbed.parent.mkdir(parents=True)
            mbed.write_text('set(CMAKE_C_FLAGS_RELEASE     "-O2")\n' * 2)
            prepare_cmake(root, "dolphin", "optimized")
            self.assertEqual(mbed.read_text().count("-O3 -DNDEBUG"), 2)
            source = root / "Common/Input/InputState.cpp"
            source.parent.mkdir(parents=True)
            source.write_text("#include <cstdio>\n")
            cmake = root / "CMakeLists.txt"
            cmake.write_text("\n".join(f'target_compile_options({target} PRIVATE "-O2")'
                                       for target in ("udis86", "cityhash", "zlib")))
            prepare_cmake(root, "ppsspp", "optimized-a57")
            self.assertIn("#include <cstdlib>", source.read_text())
            self.assertEqual(cmake.read_text().count('"-O3"'), 3)

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

    def test_dolphin_zlib_native_simd_thinlto_barrier(self):
        from core_build_policy import _DOLPHIN_ZLIB_NATIVE_SIMD
        prefix = "src/Externals/zlib-ng/zlib-ng/arch/"
        for abi, jit, profile in (("x86_64", "Jit64", "optimized"),
                                  ("arm64-v8a", "JitArm64", "optimized-a57")):
            flags = ["-O3", "-flto=thin"]
            if abi == "arm64-v8a":
                flags.append("-mtune=cortex-a57")
            required = ["Source/Core/Core/PowerPC/" + jit + "/Jit.cpp",
                        "Source/Core/DolphinLibretro/Input.cpp",
                        "Source/Core/DolphinLibretro/Audio.cpp",
                        "Source/Core/DolphinLibretro/Vulkan.cpp"]
            normal = [["clang++", *flags, "-c", src] for src in required]
            simd = [["clang", *flags, *isa, "-fno-lto", "-c", prefix + src]
                    for src, isa in _DOLPHIN_ZLIB_NATIVE_SIMD[abi].items()]
            link = ["clang++", "-shared", "-flto=thin", "a.o"]
            def audit(commands, core="dolphin", selected_abi=abi):
                return audit_commands("", profile, core=core, abi=selected_abi,
                                      scoped_commands=commands)
            result = audit(normal + simd + [link])
            self.assertEqual(result["thinlto_compiles"], len(normal))
            self.assertEqual(result["native_simd_sources"], sorted(_DOLPHIN_ZLIB_NATIVE_SIMD[abi]))
            self.assertEqual(result["compile_lto_modes"]["-fno-lto"], len(simd))
            invalid = [simd[0][:-1] + [prefix + "generic.c"],
                       simd[0][:-1] + ["other/arch/" + simd[0][-1].split("/")[-1]],
                       [arg for arg in simd[0] if arg not in _DOLPHIN_ZLIB_NATIVE_SIMD[abi][next(iter(_DOLPHIN_ZLIB_NATIVE_SIMD[abi]))]],
                       [arg for arg in simd[0] if arg != "-flto=thin"]]
            for bad in invalid:
                with self.assertRaisesRegex(ValueError, "ThinLTO not effective"):
                    audit(normal + [bad, link])
            with self.assertRaisesRegex(ValueError, "ThinLTO not effective"):
                audit(normal + simd + [link], core=None)
            with self.assertRaisesRegex(ValueError, "ThinLTO not effective"):
                audit(normal + simd + [link], selected_abi="x86")
            with self.assertRaisesRegex(ValueError, "ThinLTO not effective"):
                audit(normal + simd + [["clang++", "-shared", "a.o"]])
            with self.assertRaisesRegex(ValueError, "overrides requested -O3"):
                audit(normal + [[arg if arg != "-O3" else "-O2" for arg in simd[0]], link])
            with self.assertRaisesRegex(ValueError, "Duplicate Dolphin"):
                audit(normal + simd + [simd[0], link])

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
            prepare_cmake(root, "swanstation", "core-hotpaths")
            self.assertEqual(path.read_text(), original)
            prepare_cmake(root, "mgba", "o3")
            self.assertEqual(path.read_text(), original)
            prepare_cmake(root, "swanstation", "o3")
            self.assertIn("if(FALSE)", path.read_text())
            with self.assertRaises(ValueError):
                prepare_cmake(root, "swanstation", "thinlto")

    def test_panda3ds_ipo_is_not_allowed_to_override_explicit_profile(self):
        original = ("if(ENABLE_LTO OR ENABLE_USER_BUILD)\n"
                    "  set_target_properties(panda3ds_libretro PROPERTIES INTERPROCEDURAL_OPTIMIZATION TRUE)\n"
                    "endif()\n")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "CMakeLists.txt"
            path.write_text(original)
            prepare_cmake(root, "panda3ds", "baseline")
            self.assertEqual(path.read_text(), original)
            prepare_cmake(root, "panda3ds", "optimized-a57")
            self.assertIn("if(FALSE)", path.read_text())
            self.assertIn("INTERPROCEDURAL_OPTIMIZATION TRUE", path.read_text())
            with self.assertRaises(ValueError):
                prepare_cmake(root, "panda3ds", "optimized")

    def test_extended_acceleration_contracts_are_architecture_specific(self):
        self.assertIn("jit_fastmem_build", requirements("melondsds", "arm64-v8a", "optimized"))
        self.assertNotIn("jit_fastmem_build", requirements("melondsds", "armeabi-v7a", "optimized"))
        self.assertNotIn("dynarmic", requirements("citra2018", "arm64-v8a", "optimized"))
        self.assertIn("dynarmic", requirements("citra2018", "x86_64", "optimized"))
        self.assertIn("vif_neon", requirements("armsx2", "arm64-v8a", "optimized-a57"))
        self.assertIn("spu2_neon", requirements("armsx2", "arm64-v8a", "optimized-a57"))
        for profile in ("core-hotpaths", "optimized", "optimized-a57"):
            saturn = requirements("mednafen_saturn", "arm64-v8a", profile)
            for dsp in ("scsp_dsp_jit", "scu_dsp_jit"):
                self.assertIn(("WANT_JIT", "1"), saturn[dsp][1])
            commands = [["-D" + key + "=" + value for key, value in macros] + ["-c", source]
                        for source, macros in saturn.values()]
            self.assertTrue(all(audit_acceleration(commands, "mednafen_saturn", "arm64-v8a", profile).values()))
            with self.assertRaises(ValueError):
                audit_acceleration([[flag for flag in cmd if flag != "-DWANT_JIT=1"] for cmd in commands],
                                   "mednafen_saturn", "arm64-v8a", profile)
        self.assertEqual(requirements("mednafen_saturn", "armeabi-v7a", "optimized")["scsp_dsp_jit"][1], ())
        rules = requirements("vice_x64sc", "arm64-v8a", "optimized")
        commands = [["-D" + key + "=" + value for key, value in macros] + ["-c", source]
                    for source, macros in rules.values()]
        self.assertTrue(all(audit_acceleration(commands, "vice_x64sc", "arm64-v8a", "optimized").values()))
        with self.assertRaises(ValueError):
            audit_acceleration(commands, "vice_x64", "arm64-v8a", "optimized")

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
            source = "include $(CORE_DIR)/build/Makefile.common\nLOCAL_CFLAGS    := $(COREFLAGS)\n"
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
            for abi in entry.get("abis", self.catalog["abis"]):
                with self.subTest(core=core, abi=abi):
                    rules = requirements(core, abi, "baseline")
                    # Even interpreter-only targets need linked hotpath evidence.
                    self.assertTrue(rules)
                    commands = [
                        ["-D" + key + "=" + value for key, value in macros]
                        + ["-c", "upstream/" + source]
                        for source, macros in rules.values()
                    ]
                    actual = audit_acceleration(commands, core, abi, "baseline")
                    self.assertTrue(all(actual[k] for k in rules))
                    with self.assertRaises(ValueError):
                        audit_acceleration([], core, abi, "baseline")

    def test_source_hotpath_candidates(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                      profile="core-hotpaths")["include"]
        self.assertEqual({item["core"] for item in matrix},
                         {core["core"] for core in self.catalog["cores"]})
        self.assertEqual(len(matrix), sum(len(c.get("abis", self.catalog["abis"])) for c in self.catalog["cores"]))
        self.assertTrue(all(item["artifact"].startswith("candidate-core-hotpaths-") for item in matrix))
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/main", True, profile="core-hotpaths")
        with self.assertRaises(ValueError):
            plan(self.catalog, "workflow_dispatch", "refs/heads/feature", False,
                 cores="not-a-registered-core", profile="core-hotpaths")

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
