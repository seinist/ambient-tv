import shutil
import struct
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import check_glsl_shaders as shaders


class HdrCasShaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = shaders.HDR_CAS.read_text()
        cls.lut = shaders.read_lut(cls.source)

    def test_lut_is_the_float32_pq_eotf_with_range_expansion(self) -> None:
        self.assertEqual([1024, 1024], [len(row) for row in self.lut])
        for row, limited in ((0, True), (1, False)):
            for code in range(1024):
                exact = shaders.pq_eotf(shaders.expand(code / 1023, limited))
                self.assertEqual(struct.unpack("<f", struct.pack("<f", exact))[0], self.lut[row][code])
        self.assertEqual(0.0, self.lut[0][64])
        self.assertEqual(1.0, self.lut[0][940])
        self.assertEqual(1.0, self.lut[1][1023])

    def test_optimised_cas_stays_within_a_quarter_code_of_the_reference(self) -> None:
        for trc in (2, 5):
            for limited in (True, False):
                for blend in (1.0, 0.5, 0.8):
                    with self.subTest(trc=trc, limited=limited, blend=blend):
                        self.assertLess(shaders.max_deviation_codes(self.lut, trc, limited, blend), 0.25)

    def test_flat_and_unsharpened_pixels_keep_their_source_code(self) -> None:
        for code in (0, 10, 63, 64, 500, 940, 1000, 1023):
            flat = [code / 1023] * 9
            self.assertEqual(code / 1023, shaders.optimised(flat, 2, True, 1.0, self.lut))
        # Pure black around a highlight: CAS amplitude 0, the centre keeps its code.
        isolated = [64 / 1023] * 4 + [80 / 1023] + [64 / 1023] * 4
        self.assertEqual(80 / 1023, shaders.optimised(isolated, 2, True, 1.0, self.lut))

    def test_light_medium_and_amd_cas_are_ordered(self) -> None:
        edge = [300 / 1023] * 4 + [400 / 1023] + [300 / 1023] * 4
        results = [shaders.optimised(edge, 2, True, blend, self.lut) for blend in (0.5, 0.8, 1.0)]
        self.assertTrue(400 / 1023 < results[0] < results[1] < results[2])

    def test_variants_carry_the_cache_defines(self) -> None:
        text = shaders.hdr_variant(self.source, 2, False, "0.5")
        for define in ("#define SOURCE_TRC 2", "#define TARGET_TRC 2", "#define LIMITED_RANGE 0", "#define CAS_BLEND 0.5"):
            self.assertIn(define, text)
        blocks = shaders.hook_blocks(text)
        self.assertEqual(1, len(blocks))
        self.assertEqual(["CAS_HDR_PQ_LUT"], blocks[0][0])
        self.assertEqual(1, shaders.upstream_variant(shaders.UPSTREAM_CAS.read_text(), "0.8").count("mix(e, pix.r"))

    @unittest.skipIf(shutil.which("glslangValidator") is None, "glslangValidator not installed")
    def test_every_variant_compiles_for_vulkan_and_gles(self) -> None:
        self.assertEqual([], shaders.compile_all(shutil.which("glslangValidator")))


if __name__ == "__main__":
    unittest.main()
