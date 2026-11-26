"""Plan incremental signed core releases against verified publication history."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HEX = re.compile(r"[0-9a-f]{64}")
SHARED = ("core_build_policy.py", "core_acceleration_audit.py",
          "core_link_evidence.py", "check_elf_pages.py")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def key(entry):
    return entry["core"], entry["abi"]


def fingerprint(job, ndk, root=ROOT):
    # Only the actual build step and recipe/audit inputs matter; packaging,
    # titles, scheduling and unrelated catalog entries do not invalidate cores.
    workflow = (root / ".github/workflows/cores.yml").read_text()
    start = workflow.index("      - name: Install NDK")
    end = workflow.index("      - uses: actions/upload-artifact", start)
    files = list(SHARED)
    if job["profile"] == "core-hotpaths":
        files += ["core_hotpath_patches.py", "core_hotpath_remaining.py"]
    payload = {"schema": 1, "ndk": ndk, "job": job,
               "build": digest(workflow[start:end].encode()),
               "files": {p: digest((root / "scripts" / p).read_bytes()) for p in files}}
    return digest(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode())


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def signed_history(repo, profile, desired, root=ROOT):
    pages = json.loads(gh("api", "--paginate", "--slurp",
                          f"repos/{repo}/releases?per_page=100"))
    releases = [r for page in pages for r in page if not r["draft"] and
                ((profile == "baseline" and not r["prerelease"] and r["tag_name"].startswith("cores-"))
                 or (profile != "baseline" and r["prerelease"] and
                     r["tag_name"].startswith(f"core-experiments-{profile}--")))]
    releases.sort(key=lambda r: (r.get("published_at") or "", r["id"]), reverse=True)
    records = {}
    latest_keys = None
    for release in releases:
        tag = release["tag_name"]
        assets = {a["name"]: a for a in release["assets"]}
        if latest_keys is not None and profile != "baseline" and not any(
                f"{abi}--{core}_libretro_android.so.zip" in assets for core, abi in desired - records.keys()):
            continue
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            gh("release", "download", tag, "--repo", repo, "--dir", tmp,
               "--pattern", "manifest.json", "--pattern", "manifest.json.sig")
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(root / "cores/signing-public.pem"),
                            "-signature", str(directory / "manifest.json.sig"),
                            str(directory / "manifest.json")], check=True, stdout=subprocess.DEVNULL)
            manifest = json.loads((directory / "manifest.json").read_text())
        if manifest.get("version") != 1 or manifest.get("tag") != tag or manifest.get("profile", "baseline") != profile:
            raise ValueError(f"{tag}: signed manifest channel mismatch")
        entries = manifest["cores"]
        keys = {key(e) for e in entries}
        if len(keys) != len(entries):
            raise ValueError(f"{tag}: duplicate signed core entries")
        if latest_keys is None:
            latest_keys = keys
        for e in entries:
            k = key(e)
            if k not in desired or k in records:
                continue
            # Remember the newest signed entry even if its binary was removed;
            # never quietly reuse an older version of that core.
            expected = f"{k[1]}--{k[0]}_libretro_android.so.zip"
            asset = assets.get(expected)
            if e.get("asset") != expected or not HEX.fullmatch(e.get("sha256", "")):
                raise ValueError(f"{tag}: invalid signed asset")
            records[k] = ({"tag": tag, "entry": e} if asset and asset["size"] == e.get("size") else None)
        if profile == "baseline" or desired <= records.keys():
            break
    return records, latest_keys


def select(matrix, records, latest_keys, profile, ndk, force=False, root=ROOT):
    desired = {key(j) for j in matrix["include"]}
    build, reuse = [], []
    for job in matrix["include"]:
        job = dict(job)
        fp = fingerprint(job, ndk, root)
        job["fingerprint"] = fp
        previous = records.get(key(job))
        if not force and previous and previous["entry"].get("fingerprint") == fp:
            if profile == "baseline":
                reuse.append(previous)
        else:
            build.append(job)
    # Stable snapshots retain the entire current catalog for older clients;
    # removal-only catalog changes need publication too.
    changed = bool(build) or (profile == "baseline" and latest_keys != desired)
    return {"matrix": {"include": build}, "reuse": reuse, "publish": changed,
            "total": len(desired), "rebuilt": len(build)}


def stage_reuse(repo, reuse, destination):
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    groups = {}
    for record in reuse:
        entry = record["entry"]
        name = entry["asset"]
        if name != f"{entry['abi']}--{entry['core']}_libretro_android.so.zip" or Path(name).name != name:
            raise ValueError("Invalid reuse asset")
        groups.setdefault(record["tag"], []).append(entry)
    for tag, group in groups.items():
        patterns = [arg for entry in group for arg in ("--pattern", entry["asset"])]
        gh("release", "download", tag, "--repo", repo, "--dir", str(destination), *patterns)
        for entry in group:
            name = entry["asset"]
            target = destination / name
            with target.open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual != entry["sha256"] or target.stat().st_size != entry["size"]:
                raise ValueError(f"Reused asset failed signature-bound hash/size: {name}")
            entries.append(entry)
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("plan")
    for flag in ("repo", "profile", "ndk", "matrix"):
        p.add_argument("--" + flag, required=True)
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("reuse")
    p.add_argument("--repo", required=True)
    p.add_argument("--records", required=True)
    p.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "plan":
        matrix = json.loads(args.matrix)
        desired = {key(j) for j in matrix["include"]}
        records, latest = ({}, None) if args.force else signed_history(args.repo, args.profile, desired)
        result = select(matrix, records, latest, args.profile, args.ndk, args.force)
    else:
        result = stage_reuse(args.repo, json.loads(args.records), args.destination)
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
