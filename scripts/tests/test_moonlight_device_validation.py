from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import moonlight_device_validation as validation


def sample(time, count, presented=0):
    return validation.parse_snapshot(
        "Moonlight diagnostics v1\n"
        f"session_generation=2 sample_ns={time}\n"
        "display_mode=1920x1080 60 Hz display_hdr10_capable=true\n"
        "decoder=hardware mime=video/hevc size=1920x1080 fps=60\n"
        "hdr_signaled=true decoder_hdr_evidence=confirmed color_transfer=6\n"
        f"received={count} missing=0 released={count} dropped=0 presented={presented}\n"
        "underruns=0 dropped_samples=0 queue_ms=null\n"
    )


class DeviceValidationTest(unittest.TestCase):
    def test_compare_real_counter_intervals(self):
        before, after = sample(10**9, 60), sample(61 * 10**9, 3660)
        report = validation.compare(before, after, "hdr10")
        self.assertEqual(report["released_fps"], 60)
        self.assertIsNone(report["presented_fps"])
        self.assertIsNone(report["input_to_photon"])
        self.assertTrue(report["decoder_and_display_capability_evidence"])
        self.assertEqual(report["profile"]["display_mode"], "1920x1080 60 Hz")

    def test_reject_reset_or_different_session(self):
        before, after = sample(10**9, 60), sample(61 * 10**9, 1)
        with self.assertRaises(ValueError):
            validation.compare(before, after)
        after = sample(61 * 10**9, 3660)
        after["session_generation"] = "3"
        with self.assertRaises(ValueError):
            validation.compare(before, after)

    def test_camera_latency_is_external_and_requires_valid_events(self):
        data = "input_frame,presented_frame\n0,2\n10,14\n"
        result = validation.camera_latency(data, 200)
        self.assertEqual(result["median_ms"], 15)
        self.assertEqual(result["p95_ms"], 20)
        self.assertFalse(result["minimum_30_samples"])
        with self.assertRaises(ValueError):
            validation.camera_latency("input_frame,presented_frame\n3,2\n", 200)


if __name__ == "__main__":
    unittest.main()
