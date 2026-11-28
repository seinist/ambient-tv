import json
import tempfile
import unittest
from pathlib import Path
from compare_core_profiles import compare

class BenchmarkTests(unittest.TestCase):
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
            runs[-1]["renderer"]="vulkan"
            with self.assertRaises(ValueError):
                compare(manifest, path)
