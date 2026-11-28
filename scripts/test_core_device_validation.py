import json
from pathlib import Path
import unittest
from validate_core_device_matrix import selection, artifact_name

class DeviceValidationTests(unittest.TestCase):
    def setUp(self):
        self.catalog=json.loads((Path(__file__).resolve().parents[1]/"cores/cores.json").read_text())
    def test_all_abis_and_jit_selection(self):
        for abi in self.catalog["abis"]:
            targets=selection(self.catalog,abi)
            self.assertEqual(len(targets),sum(abi in c.get("abis",self.catalog["abis"]) for c in self.catalog["cores"]))
            for profiles in targets.values():
                self.assertEqual(profiles,["baseline","optimized","optimized-a57"] if abi=="arm64-v8a" else ["baseline","optimized"])
        targets=selection(self.catalog,"arm64-v8a","citra2018,yabause",True)
        self.assertIn("citra-arm64-jit",targets["citra2018"])
        self.assertIn("yabause-arm64-jit",targets["yabause"])
        self.assertEqual(artifact_name("mgba","arm64-v8a","baseline"),"core-mgba-arm64-v8a")
        self.assertEqual(artifact_name("mgba","arm64-v8a","optimized"),"candidate-optimized-mgba-arm64-v8a")
        for value in ("mgba,mgba","unknown","mgba,"):
            with self.assertRaises(ValueError):
                selection(self.catalog,"arm64-v8a",value)
