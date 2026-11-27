import importlib.util
import unittest
from pathlib import Path

PATH = Path(__file__).resolve().parents[1] / "measure_monogram_device.py"
SPEC = importlib.util.spec_from_file_location("monogram_device", PATH)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class DeviceMetricsTest(unittest.TestCase):
    def test_grouped_percentiles_and_bounded_sizes(self):
        lines = [
            "I MonogramPerf: media-read path=ram bytes=524288 us=10",
            "I MonogramPerf: media-read path=ram bytes=524288 us=20",
            "I MonogramPerf: media-read path=native bytes=524305 us=10000",
            "I MonogramPerf: media-read path=ram bytes=1048577 us=1",
            "I MonogramPerf: media-read path=ram bytes=0 us=1",
            "unrelated log line",
        ]
        self.assertEqual({
            "ram": {"windows": 2, "bytes": 1048576, "p50_ms": 0.01, "p95_ms": 0.02, "max_ms": 0.02},
            "native": {"windows": 1, "bytes": 524305, "p50_ms": 10.0, "p95_ms": 10.0, "max_ms": 10.0},
        }, module.summarize(lines))

    def test_empty_or_invalid_diagnostics_are_ignored(self):
        self.assertEqual({}, module.summarize([
            "media-read path=unknown bytes=4 us=1",
            "media-read path=ram bytes=4 us=-1",
            "media-read path=ram bytes=4 us=1 appended",
        ]))


if __name__ == "__main__":
    unittest.main()
