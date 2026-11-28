import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from run_core_benchmark import fixture_identity
from compare_core_profiles import compare

class BenchmarkTests(unittest.TestCase):
    def test_strict_correctness_proofs(self):
        from benchmark_core_matrix import validate_output_proofs
        base=dict(audio_hash="a"*16,video_hash="b"*16,state_loaded=False)
        validate_output_proofs({"baseline":base,"optimized":dict(base)},require_video=True)
        for changed in (dict(audio_hash="c"*16),dict(video_hash=None),dict(state_loaded=True)):
            with self.assertRaises(ValueError):
                validate_output_proofs({"baseline":base,"optimized":dict(base,**changed)},require_video=True)
        hw=dict(base,video_hash=None,state_loaded=True)
        validate_output_proofs({"baseline":hw,"optimized":dict(hw)},state_loaded=True)
        with self.assertRaises(ValueError):
            validate_output_proofs({"baseline":hw},require_video=True,state_loaded=True)

    def test_dynamic_av_configuration(self):
        from benchmark_core_matrix import validate_workload
        base=dict(round=1,profile="baseline",video_frames=60,fresh_frames=60,
                  duplicate_frames=0,audio_frames=44100,width=320,height=240,hardware=False,
                  av_hash="a"*16,sample_rate=44100)
        validate_workload([base,dict(base,profile="optimized")])
        for change in (dict(av_hash="b"*16),dict(sample_rate=48000)):
            with self.assertRaisesRegex(ValueError,"dynamic A/V"):
                validate_workload([base,dict(base,profile="optimized",**change)])

    def test_workload_rejects_missing_output(self):
        from benchmark_core_matrix import validate_workload
        base = dict(round=1, profile="baseline", video_frames=1000, fresh_frames=900,
                    duplicate_frames=100, audio_frames=441000, width=1280, height=720, hardware=True)
        other = dict(base, profile="optimized")
        validate_workload([base, other])
        validate_workload([base, dict(other, fresh_frames=895, duplicate_frames=105)])
        for changed in (dict(fresh_frames=450, duplicate_frames=550),
                        dict(audio_frames=0), dict(width=640),
                        dict(video_frames=1000, fresh_frames=0, duplicate_frames=1000),
                        dict(video_frames=999)):
            with self.assertRaises(ValueError):
                validate_workload([base, dict(other, **changed)])


    def test_workload_window_cannot_include_warmup(self):
        from benchmark_core_matrix import validate_workload
        base = dict(round=1, profile="baseline", frames=1000, warmup_frames=900,
                    measured_frames=100, video_frames=100, fresh_frames=90,
                    duplicate_frames=10, audio_frames=73500, width=1280, height=720, hardware=True)
        other = dict(base, profile="optimized")
        validate_workload([base, other], 900, 100)
        for changed in (dict(warmup_frames=0, measured_frames=1000),
                        dict(fresh_frames=80, duplicate_frames=20), dict(audio_frames=65000)):
            with self.assertRaises(ValueError):
                validate_workload([base, dict(other, **changed)], 900, 100)
        old = {k:v for k,v in other.items() if k not in ("warmup_frames", "measured_frames")}
        with self.assertRaises(ValueError):
            validate_workload([base, old], 900, 100)

    def test_fixture_identity_tracks_runtime_and_all_content(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            content, system = path/"content", path/"bios"
            content.mkdir()
            system.mkdir()
            rom = content/"game.rom"
            rom.write_bytes(b"game")
            (content/"track.bin").write_bytes(b"track")
            (system/"bios.bin").write_bytes(b"bios")
            args = SimpleNamespace(**{key:path/key for key in ("runner","input","options","runtime")})
            for file in vars(args).values():
                file.write_bytes(b"original")
            before = fixture_identity(args, content, system, rom)
            self.assertEqual(len(before["fixture_files"]), 2)
            args.runtime.write_bytes(b"new runtime")
            self.assertNotEqual(before, fixture_identity(args, content, system, rom))
            args.runtime.write_bytes(b"original")
            self.assertEqual(before, fixture_identity(args, content, system, rom))
            (content/"track.bin").write_bytes(b"changed")
            self.assertNotEqual(before, fixture_identity(args, content, system, rom))


    def test_paired_runs_and_rejection(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            identity = dict(core="mgba", abi="arm64-v8a", commit="a"*40, ndk="30",
                device_fingerprint="shield", app_commit="b"*40, content_sha256="1"*64,
                input_sha256="2"*64, settings_sha256="3"*64, renderer="gles", fps=60)
            runs = []
            for n in range(3):
                for profile, dur, digest in (("baseline",10000000,"4"*64),("optimized",8000000,"5"*64)):
                    name = f"{profile}-{n}"
                    (path/(name+".csv")).write_text("name,ts,dur\n"+"".join(
                        f"AmbientGame core run,{i*16000000},{dur}\n" for i in range(65)))
                    (path/(name+"-thermal.csv")).write_text("ts,status\n0,0\n")
                    (path/(name+".json")).write_text(json.dumps({**identity, "profile":profile,
                                                                "binary_sha256":digest}))
                    runs.append({**identity, "profile":profile, "round":n, "binary_sha256":digest,
                                 "slices":name+".csv", "thermal":name+"-thermal.csv",
                                 "build_report":name+".json"})
            manifest = {**identity, "profiles":["baseline","optimized"], "runs":runs,
                        "warmup_frames":5, "frames":60}
            result = compare(manifest, path)
            self.assertAlmostEqual(result["profiles"]["optimized"]["median"]["p95_ms_delta_pct"], -20)
            with self.assertRaises(ValueError):
                compare({**manifest,"runs":runs[:-1]}, path)
            thermal = path/"optimized-2-thermal.csv"
            thermal.write_text("ts,status\n0,0\n90000000,3\n")
            with self.assertRaises(ValueError):
                compare(manifest, path)
            thermal.write_text("ts,status\n0,0\n")
            trace = path/"optimized-2.csv"
            trace.write_text("name,ts,dur\n"+"".join(
                f"AmbientGame core run,{i*16000000},{8000000 if i < 64 else 6000000000}\n"
                for i in range(65)))
            with self.assertRaisesRegex(ValueError, "thermal evidence"):
                compare(manifest, path)
            runs[-1]["renderer"]="vulkan"
            with self.assertRaises(ValueError):
                compare(manifest, path)
