"""Unit tests for the ELF 16-KiB release gate."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from check_elf_page_alignment import alignments, validate


class ElfAlignmentTest(unittest.TestCase):
    def test_valid_16k_segments(self):
        dump = """
Program Headers:
  Type           Offset             VirtAddr           PhysAddr
                 FileSiz            MemSiz              Flags  Align
  LOAD           0x0000000000000000 0x0000000000000000 0x0000000000000000 0x000800 0x000800 R   0x4000
  LOAD           0x0000000000004000 0x0000000000004000 0x0000000000004000 0x001000 0x002000 R E 0x4000
"""
        self.assertEqual([0x4000, 0x4000], alignments(dump))
        self.assertTrue(validate(dump))

    def test_bad_4k_page_alignment(self):
        self.assertFalse(validate("LOAD 0x0000 0x0000 0x0000 0x0200 0x0300 R E 0x1000"))
        self.assertFalse(validate("LOAD 0x0000 0x0000 0x0000 0x0200 0x0300 R E 0x4000\nLOAD 0 0 0 0 0 RW 0x1000"))

    def test_reject_missing_or_malformed_segments(self):
        self.assertFalse(validate(""))
        self.assertFalse(validate("No program headers."))
        self.assertFalse(validate("LOAD 0x0 0x0 X 0xnothex"))
        self.assertFalse(validate("  NOTE 0x0000 0x0000 R 0x1000"))


if __name__ == "__main__":
    unittest.main()
