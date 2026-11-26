import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core_build_policy import (REQUIRED_EXPORTS, audit_commands, ndk_flags, plan,
                               select_library)


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
        self.assertEqual(len(matrix), 8)
        self.assertEqual({item["profile"] for item in matrix}, {"baseline", "thinlto"})
        self.assertEqual(len({item["artifact"] for item in matrix}), 8)

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
