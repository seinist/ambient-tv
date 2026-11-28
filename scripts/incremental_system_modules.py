#!/usr/bin/env python3
"""Compare a verified candidate to the latest authenticated native catalog."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile

from prepare_native_module_release import MODULES
from package_system_modules import ABIS


def entries(manifest, module, tag):
    if manifest.get("schema") != 1 or manifest.get("tag") != tag:
        raise ValueError("Unexpected catalog identity")
    rows = manifest["modules"]
    if module == "shaders" and len(rows) == 1 and rows[0].get("abi") == "any":
        row = rows[0]
        if row.get("module") != module or row.get("asset") != "shaders--any--baseline.zip":
            raise ValueError("Invalid legacy shader catalog")
    elif len(rows) != len(ABIS) or {row["abi"] for row in rows} != set(ABIS):
        raise ValueError("Incomplete native catalog")
    versions = set()
    normalized = []
    for row in rows:
        if row["module"] != module or row["profile"] != "baseline" or row["asset"] != f"{module}--{row['abi']}--baseline.zip":
            raise ValueError("Unexpected native catalog entry")
        if type(row["version"]) is not int or row["version"] <= 0 or not re.fullmatch(r"[a-f0-9]{64}", row["sha256"]):
            raise ValueError("Invalid catalog version or hash")
        versions.add(row["version"])
        normalized.append({k: v for k, v in row.items() if k != "version"})
    if len(versions) != 1:
        raise ValueError("Mixed native catalog versions")
    return versions.pop(), sorted(normalized, key=lambda row: row["abi"])


def should_publish(candidate, previous):
    if previous is None:
        return True
    version, payload = candidate
    old_version, old_payload = previous
    if version < old_version or (version == old_version and payload != old_payload):
        raise ValueError("Stale or conflicting native module version")
    if any(row["abi"] == "any" for row in payload) and any(row["abi"] != "any" for row in old_payload):
        raise ValueError("Legacy shader publication cannot replace a native catalog")
    return payload != old_payload


def run(*args):
    return subprocess.check_output(args, text=True)


def previous_catalog(repo, module, public_key):
    # GitHub's release list is discovery only. Never trust a comparison until
    # the exact release catalog has been authenticated against the pinned key.
    prefix = f"modules-{module}-"
    newest = None
    seen_versions = {}
    seen_tags = set()
    for page in range(1, 11):
        releases = json.loads(run("gh", "api", f"repos/{repo}/releases?per_page=100&page={page}"))
        for release in releases:
            tag = release["tag_name"]
            if release["draft"] or release["prerelease"] or not tag.startswith(prefix):
                continue
            if tag in seen_tags:
                continue
            seen_tags.add(tag)
            if not re.fullmatch(r"modules-[A-Za-z0-9][A-Za-z0-9._-]{0,100}", tag):
                raise ValueError("Invalid published module tag")
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                assets = {asset["name"]: asset for asset in release["assets"] if asset["name"] in ("manifest.json", "manifest.json.sig")}
                if set(assets) != {"manifest.json", "manifest.json.sig"} or not 0 < assets["manifest.json"]["size"] <= 512*1024 or not 0 < assets["manifest.json.sig"]["size"] <= 256:
                    raise ValueError("Invalid published catalog assets")
                subprocess.run(["gh", "release", "download", tag, "--repo", repo, "--dir", temp,
                                "--pattern", "manifest.json", "--pattern", "manifest.json.sig"], check=True)
                manifest = root / "manifest.json"
                sig = root / "manifest.json.sig"
                if manifest.stat().st_size != assets["manifest.json"]["size"] or sig.stat().st_size != assets["manifest.json.sig"]["size"]:
                    raise ValueError("Published catalog size mismatch")
                subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public_key),
                                "-signature", str(sig), str(manifest)], check=True, stdout=subprocess.DEVNULL)
                parsed = entries(json.loads(manifest.read_text()), module, tag)
                version, payload = parsed
                if version in seen_versions and seen_versions[version] != payload:
                    raise ValueError("Conflicting signed catalogs at the same version")
                seen_versions[version] = payload
                if newest is None or version > newest[0]:
                    newest = parsed
        if len(releases) < 100:
            return newest
    raise ValueError("Module release history exceeds bounded search")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--module", choices=MODULES, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    manifest = json.loads(args.candidate.read_text())
    candidate = entries(manifest, args.module, manifest["tag"])
    if any(row["abi"] == "any" for row in candidate[1]) and args.module != "shaders":
        raise ValueError("Unexpected legacy candidate")
    previous = previous_catalog(args.repo, args.module, args.public_key)
    changed = should_publish(candidate, previous)
    # Force never overrides version conflicts or signature validation.
    publish = changed or (args.force and (previous is None or candidate[0] > previous[0]))
    print("publish" if publish else "unchanged")


if __name__ == "__main__":
    main()
