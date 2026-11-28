import json
import shutil
import hashlib
import struct
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core_build_policy import plan
from core_release_incremental import fingerprint, key, select, stage_reuse, signed_history, assert_current_recipe, verify_build_artifacts


class IncrementalCoreReleaseTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((Path(__file__).resolve().parents[1] / "cores/cores.json").read_text())
        self.ndk = "30.0.16248370"
        self.matrix = plan(self.catalog, "push", "refs/heads/main", False)
        self.records = {key(j): {"tag": "cores-1", "entry": {
            "core": j["core"], "abi": j["abi"], "fingerprint": fingerprint(j, self.ndk),
        }} for j in self.matrix["include"]}
        self.keys = set(self.records)

    def test_fingerprint_is_stable_when_planner_attaches_it(self):
        job = self.matrix["include"][0]
        fp = fingerprint(job, self.ndk)
        self.assertEqual(fp, fingerprint(dict(job, fingerprint=fp), self.ndk))

    def test_update_branch_is_not_a_binary_build_input(self):
        from copy import deepcopy
        catalog = deepcopy(self.catalog)
        catalog["cores"][0]["update_ref"] = "refs/heads/another-branch"
        changed = plan(catalog, "push", "refs/heads/main", False)
        self.assertEqual(changed, self.matrix)
        job = self.matrix["include"][0]
        self.assertEqual(fingerprint(job, self.ndk),
                         fingerprint(dict(job, update_ref="refs/heads/another-branch"), self.ndk))

    def test_no_changes_skips_entire_release(self):
        result = select(self.matrix, self.records, self.keys, "baseline", self.ndk)
        self.assertFalse(result["publish"])
        self.assertEqual(result["rebuilt"], 0)
        self.assertEqual(len(result["reuse"]), len(self.keys))

    def test_single_pin_change_builds_only_affected_core(self):
        changed = {"include": [dict(j, commit="0" * 40) if j["core"] == "mgba" else j
                               for j in self.matrix["include"]]}
        result = select(changed, self.records, self.keys, "baseline", self.ndk)
        self.assertTrue(result["publish"])
        self.assertEqual({j["core"] for j in result["matrix"]["include"]}, {"mgba"})
        self.assertEqual(result["rebuilt"], len(self.catalog["abis"]))
        self.assertEqual(len(result["reuse"]) + result["rebuilt"], len(self.keys))

    def test_failed_or_missing_build_is_retried(self):
        missing = dict(self.records)
        k = ("mgba", "arm64-v8a")
        missing[k] = None
        result = select(self.matrix, missing, self.keys, "baseline", self.ndk)
        self.assertEqual([key(j) for j in result["matrix"]["include"]], [k])

    def test_force_ndk_change_and_legacy_manifest(self):
        for records, ndk, force in ((self.records, self.ndk, True),
                                    (self.records, "new-ndk", False),
                                    ({k: {"entry": {}} for k in self.keys}, self.ndk, False)):
            with self.subTest(ndk=ndk, force=force):
                result = select(self.matrix, records, self.keys, "baseline", ndk, force)
                self.assertEqual(result["rebuilt"], len(self.keys))
                self.assertEqual(result["reuse"], [])

    def test_removal_only_publishes_complete_remaining_snapshot(self):
        matrix = {"include": [j for j in self.matrix["include"] if j["core"] != "mgba"]}
        result = select(matrix, self.records, self.keys, "baseline", self.ndk)
        self.assertTrue(result["publish"])
        self.assertEqual(result["rebuilt"], 0)
        self.assertEqual(len(result["reuse"]), len(matrix["include"]))

    def test_experimental_only_publishes_changed_assets(self):
        matrix = plan(self.catalog, "workflow_dispatch", "refs/heads/main", False,
                      "mgba,snes9x", "arm64-v8a", "core-hotpaths")
        records = {key(j): {"tag": "experiment", "entry": {"fingerprint": fingerprint(j, self.ndk)}}
                   for j in matrix["include"] if j["core"] == "mgba"}
        result = select(matrix, records, set(records), "core-hotpaths", self.ndk)
        self.assertEqual([j["core"] for j in result["matrix"]["include"]], ["snes9x"])
        self.assertEqual(result["reuse"], [])

    def test_signed_history_does_not_skip_missing_latest_experimental_asset(self):
        profile = "core-hotpaths"
        tag = f"core-experiments-{profile}--20261126-2-1"
        older = f"core-experiments-{profile}--20261126-1-1"
        entry = {"core": "mgba", "abi": "arm64-v8a",
                 "asset": "arm64-v8a--mgba_libretro_android.so.zip",
                 "sha256": "a" * 64, "size": 20}
        releases = [
            {"tag_name": tag, "id": 2, "draft": False, "prerelease": True,
             "published_at": "2026-11-26T20:00:00Z", "assets": []},
            {"tag_name": older, "id": 1, "draft": False, "prerelease": True,
             "published_at": "2026-11-26T19:00:00Z",
             "assets": [{"name": entry["asset"], "size": 20}]},
        ]
        def fake_gh(*args):
            if args[0] == "api":
                return json.dumps([releases])
            self.assertEqual(args[2], tag)
            directory = Path(args[args.index("--dir") + 1])
            (directory / "manifest.json").write_text(json.dumps({
                "version": 1, "tag": tag, "profile": profile, "cores": [entry]}))
            return ""
        with patch("core_release_incremental.gh", side_effect=fake_gh), \
                patch("core_release_incremental.subprocess.run") as verify:
            records, latest = signed_history("owner/repo", profile, {key(entry)})
        self.assertEqual(records, {key(entry): None})
        self.assertEqual(latest, {key(entry)})
        verify.assert_called_once()

    def test_fingerprint_ignores_catalog_order_and_release_metadata(self):
        matrix = plan(self.catalog, "push", "refs/heads/main", False)
        job = matrix["include"][0]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "scripts").mkdir()
            source = Path(__file__).resolve().parents[1]
            from core_release_incremental import SHARED
            for name in SHARED:
                (root / "scripts" / name).write_bytes((source / "scripts" / name).read_bytes())
            workflow = root / ".github/workflows/cores.yml"
            workflow.write_text((source / ".github/workflows/cores.yml").read_text())
            before = fingerprint(job, self.ndk, root)
            workflow.write_text(workflow.read_text() + "\\n# release title only\\n")
            self.assertEqual(before, fingerprint(job, self.ndk, root))
            workflow.write_text(workflow.read_text().replace("android-29", "android-30"))
            self.assertNotEqual(before, fingerprint(job, self.ndk, root))

    def test_all_profiles_reuse_unchanged_and_rebuild_only_changed_core(self):
        for profile in ("baseline", "optimized", "optimized-a57"):
            matrix = plan(self.catalog, "push", "refs/heads/main", False, profile=profile)
            records = {key(j): {"entry": {"fingerprint": fingerprint(j, self.ndk)}} for j in matrix["include"]}
            self.assertFalse(select(matrix, records, set(records), profile, self.ndk)["publish"])
            changed = {"include": [dict(j, commit="0" * 40) if j["core"] == "mgba" else j for j in matrix["include"]]}
            result = select(changed, records, set(records), profile, self.ndk)
            self.assertEqual({j["core"] for j in result["matrix"]["include"]}, {"mgba"})
            self.assertEqual(result["rebuilt"], 1 if profile == "optimized-a57" else 4)
            self.assertEqual(len(result["reuse"]) + result["rebuilt"], len(records))
            if profile != "baseline":
                partial = set(records) - {next(iter(records))}
                snapshot = select(matrix, records, partial, profile, self.ndk)
                self.assertTrue(snapshot["publish"])
                self.assertEqual(snapshot["rebuilt"], 0)
                self.assertEqual(len(snapshot["reuse"]), len(records))

    def test_core_specific_recipe_and_patch_fingerprints(self):
        source = Path(__file__).resolve().parents[1]
        from core_release_incremental import SHARED
        jobs = {j["core"]: j for j in plan(self.catalog, "push", "refs/heads/main", False,
                profile="optimized-a57")["include"]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "scripts").mkdir()
            (root / ".github/workflows/cores.yml").write_bytes((source / ".github/workflows/cores.yml").read_bytes())
            for name in (*SHARED, "core_hotpath_patches.py", "core_hotpath_remaining.py"):
                (root / "scripts" / name).write_bytes((source / "scripts" / name).read_bytes())
            before = {core: fingerprint(j, self.ndk, root) for core, j in jobs.items()}
            policy = root / "scripts/core_build_policy.py"
            original = policy.read_text()
            policy.write_text(original.replace("test -s $(ASM_DEFINES_NASM)", "test -f $(ASM_DEFINES_NASM)"))
            self.assertEqual({core for core, j in jobs.items() if fingerprint(j, self.ndk, root) != before[core]},
                             {"mupen64plus_next_gles3"})
            policy.write_text(original)
            patches = root / "scripts/core_hotpath_remaining.py"
            patches.write_text(patches.read_text().replace("idle stick needs neither sqrt nor atan2", "idle stick skips sqrt and atan2"))
            changed = {core for core, j in jobs.items() if fingerprint(j, self.ndk, root) != before[core]}
            # Non-patched cores use a conservative fallback, but unrelated
            # published patched recipes are unaffected.
            self.assertEqual(changed, {"mupen64plus_next_gles3"})

    def test_publish_freshness_ignores_workflow_cleanup_but_rejects_build_changes(self):
        source = Path(__file__).resolve().parents[1]
        from core_release_incremental import SHARED
        paths = ("cores/cores.json", "cores/signing-public.pem",
                 ".github/workflows/cores.yml", ".github/workflows/cores-all.yml",
                 *("scripts/" + name for name in (*SHARED, "core_hotpath_patches.py",
                   "core_hotpath_remaining.py", "core_release_incremental.py")))
        with tempfile.TemporaryDirectory() as tmp:
            current = Path(tmp)
            for path in paths:
                (current / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / path, current / path)
            workflow = current / ".github/workflows/cores.yml"
            original = workflow.read_text()
            workflow.write_text(original.replace("required: true", "required: false", 1)
                                .replace("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", "cancel-in-progress: false"))
            caller = current / ".github/workflows/cores-all.yml"
            caller.write_text(caller.read_text() + "\n# temporary validation removed\n")
            policy = current / "scripts/core_build_policy.py"
            previous = policy.read_text()
            policy.write_text(previous.replace('    if event == "pull_request":',
                                               '    if event == "pull_request" and cores and profile in PUBLIC_EXPERIMENTS:\n'
                                               '        jobs = [(core, abi, profile) for core in chosen_cores for abi in chosen_abis]\n'
                                               '    elif event == "pull_request":'))
            for profile in ("baseline", "optimized", "optimized-a57"):
                assert_current_recipe(source, current, profile, self.ndk)
            # A change in one core recipe invalidates only the profiles affected.
            policy.write_text(previous.replace("test -s $(ASM_DEFINES_NASM)", "test -f $(ASM_DEFINES_NASM)"))
            assert_current_recipe(source, current, "baseline", self.ndk)
            with self.assertRaisesRegex(ValueError, "mupen64plus_next_gles3/arm64-v8a"):
                assert_current_recipe(source, current, "optimized-a57", self.ndk)
            policy.write_text(previous)
            catalog = current / "cores/cores.json"
            changed = json.loads(catalog.read_text())
            next(row for row in changed["cores"] if row["core"] == "mgba")["commit"] = "0" * 40
            catalog.write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "mgba/"):
                assert_current_recipe(source, current, "baseline", self.ndk)
            shutil.copyfile(source / "cores/cores.json", catalog)
            workflow.write_text(original.replace(self.ndk, "99.0.0"))
            with self.assertRaisesRegex(ValueError, "NDK version"):
                assert_current_recipe(source, current, "baseline", self.ndk)
            workflow.write_text(original.replace("gh release edit", "gh release delete"))
            with self.assertRaisesRegex(ValueError, "signing/publication"):
                assert_current_recipe(source, current, "baseline", self.ndk)
            workflow.write_text(original)
            (current / "cores/signing-public.pem").write_text("different key")
            with self.assertRaisesRegex(ValueError, "trust/planning"):
                assert_current_recipe(source, current, "baseline", self.ndk)

    def test_only_all_profiles_has_automatic_core_trigger(self):
        root = Path(__file__).resolve().parents[1] / ".github/workflows"
        single = (root / "cores.yml").read_text()
        all_profiles = (root / "cores-all.yml").read_text()
        self.assertNotIn("\n  push:", single)
        # Catalog changes must build on PRs, but automatic publication stays on main.
        self.assertIn("\n  pull_request:\n    paths:\n      - cores/cores.json\n", single)
        publication = single.split("\n  publish:\n", 1)[1].split("\n    steps:", 1)[0]
        self.assertIn("github.ref == 'refs/heads/main'", publication)
        self.assertIn("needs.plan.outputs.publish == 'true'", publication)
        self.assertNotIn("pull_request", publication)
        self.assertIn("\n  push:", all_profiles)
        self.assertNotIn("\n  pull_request:", all_profiles)
        self.assertNotIn("mupen-validation", all_profiles)
        self.assertNotIn("cores: mupen64plus_next_gles3", all_profiles)
        self.assertIn("profile: [baseline, optimized, optimized-a57]", all_profiles)
        self.assertIn("pattern: ${{ needs.plan.outputs.artifact_pattern }}", single)
        self.assertNotIn("format('candidate-{0}-*'", single)
        self.assertIn("force_rebuild: ${{ inputs.force_rebuild || false }}", all_profiles)
        self.assertFalse((root / "cores-experimental.yml").exists())

    def test_elf_program_header_bounds_and_congruence(self):
        from check_elf_pages import load_alignments
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "core.so"
            for elfclass, header_size, ph_size, code in ((2, 64, 56, "IIQQQQQQ"), (1, 52, 32, "IIIIIIII")):
                for endian, tag in (("<", 1), (">", 2)):
                    header = bytearray(header_size)
                    header[:7] = b"\x7fELF" + bytes((elfclass, tag, 1))
                    if elfclass == 2:
                        struct.pack_into(endian+"Q", header, 32, header_size)
                        struct.pack_into(endian+"HH", header, 54, ph_size, 1)
                        def entry(offset=0, address=0, filesz=32, memsz=32, align=16384):
                            return struct.pack(endian+code, 1, 5, offset, address, 0, filesz, memsz, align)
                    else:
                        struct.pack_into(endian+"I", header, 28, header_size)
                        struct.pack_into(endian+"HH", header, 42, ph_size, 1)
                        def entry(offset=0, address=0, filesz=32, memsz=32, align=16384):
                            return struct.pack(endian+code, 1, offset, address, 0, filesz, memsz, 5, align)
                    path.write_bytes(header + entry())
                    self.assertEqual(load_alignments(path), [16384])
                    for bad in (entry(address=4096), entry(align=24576), entry(filesz=4096, memsz=4096),
                                entry(filesz=32, memsz=16), entry()[:-1]):
                        path.write_bytes(header + bad)
                        with self.assertRaises(ValueError):
                            load_alignments(path)

    def test_publication_rechecks_downloaded_binary_and_report(self):
        from core_build_policy import REQUIRED_EXPORTS
        job = next(j for j in self.matrix["include"] if j["abi"] == "arm64-v8a")
        job = dict(job, fingerprint=fingerprint(job, self.ndk))
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            folder = directory / job["artifact"]
            folder.mkdir()
            binary = folder / (job["core"] + "_libretro_android.so")
            # Minimal synthetic ELF header with a 16 KiB PT_LOAD. Exports
            # and link evidence are normally supplied by the actual build.
            elf = bytearray(120)
            elf[:7] = b"\x7fELF\x02\x01\x01"
            struct.pack_into("<HHI", elf, 16, 3, 183, 1)
            struct.pack_into("<Q", elf, 32, 64)
            struct.pack_into("<HH", elf, 54, 56, 1)
            struct.pack_into("<I", elf, 64, 1)
            struct.pack_into("<Q", elf, 112, 16384)
            binary.write_bytes(elf)
            report = {k: job[k] for k in ("core", "abi", "commit", "profile", "fingerprint")}
            report.update(ndk=self.ndk, elf_class=64, elf_machine=183,
                          binary_sha256=hashlib.sha256(elf).hexdigest(),
                          binary_bytes=len(elf), export_count=len(REQUIRED_EXPORTS),
                          linked_compile_commands=1, unresolved_local_inputs=[], needed_libraries=["libc.so"])
            path = folder / "build.json"
            path.write_text(json.dumps(report))
            matrix = {"include": [job]}
            result = verify_build_artifacts(matrix, directory, self.ndk)
            self.assertEqual(result[job["artifact"]]["binary_sha256"], report["binary_sha256"])
            self.assertEqual(result[job["artifact"]]["report_json"].encode(), path.read_bytes())
            self.assertEqual(result[job["artifact"]]["build_report_sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            for changed in (dict(profile="optimized"), dict(fingerprint="0"*64),
                            dict(unresolved_local_inputs=["bad.o"]), dict(needed_libraries=["unknown.so"])):
                path.write_text(json.dumps({**report, **changed}))
                with self.assertRaises(ValueError):
                    verify_build_artifacts(matrix, directory, self.ndk)
            path.write_text(json.dumps(report))
            binary.write_bytes(elf + b"bad")
            with self.assertRaisesRegex(ValueError, "hash/size"):
                verify_build_artifacts(matrix, directory, self.ndk)
            binary.write_bytes(elf)
            path.unlink()
            with self.assertRaisesRegex(ValueError, "missing binary or build report"):
                verify_build_artifacts(matrix, directory, self.ndk)

    def test_reuse_rejects_tampered_asset(self):
        record = {"tag": "cores-1", "entry": {"core": "mgba", "abi": "arm64-v8a",
                  "asset": "arm64-v8a--mgba_libretro_android.so.zip", "sha256": "0"*64, "size": 3}}
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / record["entry"]["asset"]).write_bytes(b"bad")
            with patch("core_release_incremental.gh"):
                with self.assertRaisesRegex(ValueError, "hash/size"):
                    stage_reuse("owner/repo", [record], directory)


if __name__ == "__main__":
    unittest.main()
