import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

import j2v8_source_audit as audit
from package_system_modules import ABIS


def fixture(values):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        for abi, value in values.items():
            archive.writestr(f"jni/{abi}/libj2v8.so", value)
    return data.getvalue()


class J2V8SourceAuditTest(unittest.TestCase):
    def test_comparison_does_not_promote_version_to_provenance(self):
        values = {abi: abi.encode() for abi in ABIS}
        historical = fixture(values)
        official = fixture({**values, "x86": b"different"})
        with tempfile.TemporaryDirectory() as temp:
            shared = Path(temp)
            for abi, data in values.items():
                target = shared / f"j2v8/src/main/jniLibs/{abi}/libj2v8.so"
                target.parent.mkdir(parents=True)
                target.write_bytes(data)
            with mock.patch.object(audit, "historical_aar", return_value=historical), mock.patch(
                    "module_corresponding_source.elf_text_digest", side_effect=lambda data: hashlib.sha256(data).hexdigest()):
                result = audit.compare(shared, official)
        self.assertEqual("unverified", result["nativeSourceCorrespondence"])
        self.assertTrue(all(row["matchesHistorical"] for row in result["libraries"]))
        self.assertFalse(next(row for row in result["libraries"] if row["abi"] == "x86")["matchesOfficial"])

    def test_missing_abi_and_wrong_historical_blob_rejected(self):
        with self.assertRaises(ValueError):
            audit.aar_libraries(fixture({"x86": b"data"}))
        with mock.patch.object(audit.subprocess, "check_output", return_value=b"not the fixed AAR"):
            with self.assertRaisesRegex(ValueError, "pinned Git blob"):
                audit.historical_aar(Path("unused"))


if __name__ == "__main__":
    unittest.main()
