#!/usr/bin/env python3
"""Audit legacy SmartTube J2V8 binary lineage and collect pinned source candidates.

The historical fixed AAR is immutable Git evidence. A Maven artifact comparison
does not by itself prove the exact compiler, patch series or V8 dependency graph.
Never relabel the legacy binaries as rebuilt or source-verified.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

from package_system_modules import ABIS

HISTORICAL_COMMIT = "5191f173e966f982fe8cad980c819ef90deb705e"
HISTORICAL_PATH = "j2v8/j2v8-6.2.1-fixed.aar"
HISTORICAL_BLOB = "fad5a99ebd692aeec064399e1b0d835d3ecf06a1"
MAVEN_COORDINATE = "com.eclipsesource.j2v8:j2v8:6.2.1"
MAVEN_URL = "https://repo.maven.apache.org/maven2/com/eclipsesource/j2v8/j2v8/6.2.1/j2v8-6.2.1.aar"
SOURCE_PINS = {
    "j2v8": ("https://github.com/eclipsesource/J2V8.git", "00dddaa31a80782abbe93c4a01f325db3c4975d6",
             ("jni/com_eclipsesource_v8_V8Impl.cpp", "v8/Dockerfile", "CMakeLists.txt",
              "v8/android-arm/args.gn", "v8/android-arm64/args.gn", "v8/android-ia32/args.gn", "v8/android-x64/args.gn"),
             ("epl-v10.html",)),
    "v8": ("https://github.com/v8/v8.git", "17da96eb5a1f5db470de4488b780d6c07056d8cf",
           ("DEPS", "include/v8-version.h"), ("LICENSE", "LICENSE.v8", "LICENSE.fdlibm", "LICENSE.strongtalk")),
}
MAX_AAR = 128 * 1024**2
MAX_SO = 256 * 1024**2


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def historical_aar(shared):
    data = subprocess.check_output(["git", "-C", str(shared), "show", f"{HISTORICAL_COMMIT}:{HISTORICAL_PATH}"])
    blob = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
    if not 0 < len(data) <= MAX_AAR or blob != HISTORICAL_BLOB:
        raise ValueError("Historical J2V8 fixed AAR differs from pinned Git blob")
    return data


def aar_libraries(data):
    if not 0 < len(data) <= MAX_AAR:
        raise ValueError("Invalid J2V8 AAR size")
    result = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if len(entries) > 32768:
            raise ValueError("Too many J2V8 AAR entries")
        for abi in ABIS:
            matches = [item for item in entries if item.filename == f"jni/{abi}/libj2v8.so"]
            if len(matches) != 1 or not 0 < matches[0].file_size <= MAX_SO:
                raise ValueError(f"Missing, duplicate or oversized AAR library: {abi}")
            result[abi] = archive.read(matches[0])
    return result


def compare(shared, official=None):
    from module_corresponding_source import elf_text_digest
    fixed = historical_aar(shared)
    historical = aar_libraries(fixed)
    reference = aar_libraries(official) if official is not None else {}
    rows = []
    for abi in ABIS:
        path = shared / f"j2v8/src/main/jniLibs/{abi}/libj2v8.so"
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_SO:
            raise ValueError(f"Missing current J2V8 library: {abi}")
        data = path.read_bytes()
        digest = sha(data)
        text = elf_text_digest(data)
        row = {"abi": abi, "currentSha256": digest, "currentTextSha256": text,
               "historicalSha256": sha(historical[abi]),
               "historicalTextSha256": elf_text_digest(historical[abi]),
               "matchesHistorical": data == historical[abi]}
        if official is not None:
            row.update({"officialSha256": sha(reference[abi]),
                        "officialTextSha256": elf_text_digest(reference[abi]),
                        "matchesOfficial": data == reference[abi]})
        rows.append(row)
    result = {"schema": 1, "nativeSourceCorrespondence": "unverified",
              "historicalCommit": HISTORICAL_COMMIT, "historicalBlob": HISTORICAL_BLOB,
              "historicalAarSha256": sha(fixed), "libraries": rows}
    if official is not None:
        result.update({"officialCoordinate": MAVEN_COORDINATE, "officialAarSha256": sha(official)})
    return result


def collect_sources(shared, destination, official=None):
    destination.mkdir(parents=True, exist_ok=False)
    report = compare(shared, official)
    snapshots = []
    with tempfile.TemporaryDirectory() as temp:
        for name, (url, commit, required, licenses) in SOURCE_PINS.items():
            repo = Path(temp) / name
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "fetch", "--depth", "1", url, commit], check=True)
            actual = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "FETCH_HEAD"], text=True).strip()
            if actual != commit:
                raise ValueError(f"Unexpected {name} revision")
            path = destination / f"{name}-source.tar.gz"
            with path.open("xb") as output:
                subprocess.run(["git", "-C", str(repo), "archive", "--format=tar.gz", commit], stdout=output, check=True)
            with tarfile.open(path, "r:gz") as archive:
                if archive.pax_headers.get("comment") != commit:
                    # git stores its global PAX comment before the first member.
                    archive.next()
                if archive.pax_headers.get("comment") != commit:
                    raise ValueError(f"{name} source archive commit mismatch")
                for filename in required:
                    if not archive.getmember(filename).isreg():
                        raise ValueError(f"Missing {name} build source: {filename}")
                for filename in licenses:
                    info = archive.getmember(filename)
                    if not info.isreg() or not 0 < info.size <= 4 * 1024**2:
                        raise ValueError(f"Missing {name} license: {filename}")
                    (destination / f"LICENSE-{name}-{filename}").write_bytes(archive.extractfile(info).read())
            snapshots.append({"name": name, "commit": commit, "archive": path.name,
                              "size": path.stat().st_size, "sha256": file_sha(path)})
    report["sourceCandidates"] = snapshots
    (destination / "native-source-evidence.json").write_text(json.dumps(report, sort_keys=True) + "\n")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shared", type=Path, required=True)
    parser.add_argument("--official-aar", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    official = None
    if args.official_aar:
        if not 0 < args.official_aar.stat().st_size <= MAX_AAR:
            raise ValueError("Official AAR exceeds bounds")
        official = args.official_aar.read_bytes()
    report = compare(args.shared, official)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    for row in report["libraries"]:
        print(row["abi"], "historical exact:", row["matchesHistorical"],
              "official exact:", row.get("matchesOfficial", "not compared"))
    print("Native source correspondence remains unverified pending exact upstream build evidence.")


if __name__ == "__main__":
    main()
