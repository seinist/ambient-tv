#!/usr/bin/env python3
"""Generate update.json from final signed APK bytes; never infer versions from filenames."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def inspect_apk(path, aapt2, apksigner):
    if not path.is_file() or not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", path.name):
        raise ValueError(f"Missing or invalid APK: {path}")
    signature = subprocess.run([str(apksigner), "verify", "--print-certs", str(path)],
                               check=True, capture_output=True, text=True).stdout
    certificates = tuple(sorted(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)))
    if not certificates:
        raise ValueError(f"No signing certificate found: {path}")
    result = subprocess.run([str(aapt2), "dump", "badging", str(path)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", result, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", result, re.M)
    native = re.search(r"^native-code:(.*)$", result, re.M)
    if not package or not sdk or not native:
        raise ValueError(f"Incomplete APK metadata: {path}")
    abis = set(re.findall(r"'([^']+)'", native[1]))
    if not abis or not abis <= ABIS or (len(abis) > 1 and abis != ABIS):
        raise ValueError(f"Unexpected ABI set in {path}: {abis}")
    if package[1] != "com.ambient.tv" or not re.fullmatch(r"[0-9A-Za-z._+-]{1,64}", package[3]):
        raise ValueError(f"Invalid package/version in {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    identity = (package[1], int(package[2]), package[3], int(sdk[1]), certificates)
    return identity, {"name": path.name, "abi": next(iter(abis)) if len(abis) == 1 else "universal",
                      "size": path.stat().st_size, "sha256": digest.hexdigest()}


def generate(paths, aapt2, apksigner, expected_abis):
    if not paths:
        raise ValueError("No APKs supplied")
    inspected = [inspect_apk(path, aapt2, apksigner) for path in paths]
    identities = {identity for identity, _ in inspected}
    if len(identities) != 1:
        raise ValueError("APKs have different package names, versions, minimum SDKs or signing identities")
    apks = [apk for _, apk in inspected]
    actual = {apk["abi"] for apk in apks}
    if len(actual) != len(apks) or len({apk["name"] for apk in apks}) != len(apks):
        raise ValueError("Duplicate ABI or filename")
    if actual != set(expected_abis):
        raise ValueError(f"Expected {sorted(expected_abis)}, got {sorted(actual)}")
    package, code, version, sdk, _ = identities.pop()
    return {"schemaVersion": 1, "packageName": package, "versionCode": code,
            "versionName": version, "minSdk": sdk, "apks": sorted(apks, key=lambda apk: apk["abi"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--expected-abis", required=True)
    parser.add_argument("--gradle-metadata", type=Path)
    parser.add_argument("--skip-unsigned", action="store_true", help="Raw Gradle APKs may await build.sh signing")
    parser.add_argument("apks", type=Path, nargs="*")
    args = parser.parse_args()
    # Never leave a previously valid manifest beside a failed or unsigned build.
    args.output.unlink(missing_ok=True)
    paths = args.apks
    if args.gradle_metadata:
        metadata = json.loads(args.gradle_metadata.read_text())
        paths = [args.gradle_metadata.parent / item["outputFile"] for item in metadata["elements"]]
    if args.skip_unsigned:
        if not paths or any(not path.is_file() for path in paths):
            raise ValueError("Expected APK is missing")
        if any(subprocess.run([str(args.apksigner), "verify", str(path)], capture_output=True).returncode for path in paths):
            print("Update manifest deferred: APKs are unsigned. build.sh generates it after signing.")
            return
    manifest = generate(paths, args.aapt2, args.apksigner, args.expected_abis.split(","))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=args.output.parent, delete=False) as stream:
        json.dump(manifest, stream, indent=2)
        stream.write("\n")
        temporary = stream.name
    os.replace(temporary, args.output)
    print(f"Update manifest: {args.output} ({manifest['versionName']}, code {manifest['versionCode']})")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Update manifest failed: {error}", file=sys.stderr)
        sys.exit(1)
