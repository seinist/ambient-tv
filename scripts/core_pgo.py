"""Merge and validate Clang instrumentation profiles for one pinned Android core."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def recipe_digest(root, core):
    """Fingerprint source/build inputs shared by training and optimized use."""
    catalog = json.loads((root / "cores/cores.json").read_text())
    entries = [item for item in catalog["cores"] if item["core"] == core]
    if len(entries) != 1:
        raise ValueError("Expected one catalog entry")
    paths = sorted((root / "scripts").glob("core_*.py"))
    paths += [root / ".github/workflows/cores.yml"]
    payload = {"recipe": entries[0], "inputs": {
        str(path.relative_to(root)): digest(path) for path in paths if path.is_file()
    }}
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()

def verify(directory, core, abi, commit, ndk, sha256, source_recipe):
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("Expected explicit SHA-256 of trained profile")
    data = directory / "ambient.profdata"
    metadata = json.loads((directory / "metadata.json").read_text())
    if not re.fullmatch(r"[0-9a-f]{64}", source_recipe):
        raise ValueError("Expected source recipe digest")
    expected = {"core": core, "abi": abi, "commit": commit, "ndk": ndk,
                "source_recipe": source_recipe}
    if any(metadata.get(key) != value for key, value in expected.items()):
        raise ValueError("Profile belongs to a different core, ABI, pin or compiler")
    if metadata.get("sha256") != sha256 or digest(data) != sha256 or not data.stat().st_size:
        raise ValueError("Profile integrity mismatch")
    return metadata

def merge(llvm_profdata, directory, report, inputs):
    metadata = json.loads(report.read_text())
    if metadata.get("profile") != "pgo-generate":
        raise ValueError("Training requires an instrumented build report")
    if not inputs or any(not item.is_file() or not item.stat().st_size for item in inputs):
        raise ValueError("Missing or empty training profiles")
    directory.mkdir(parents=True, exist_ok=True)
    data = directory / "ambient.profdata"
    subprocess.run([llvm_profdata, "merge", "-failure-mode=any", "-o", str(data),
                    *map(str, inputs)], check=True)
    subprocess.run([llvm_profdata, "show", str(data)], check=True)
    result = {key: metadata[key] for key in ("core", "abi", "commit", "ndk", "binary_sha256", "source_recipe")}
    if not re.fullmatch(r"[0-9a-f]{64}", result["source_recipe"]):
        raise ValueError("Invalid training source recipe")
    result["sha256"] = digest(data)
    result["training_inputs"] = [digest(item) for item in inputs]
    (directory / "metadata.json").write_text(json.dumps(result, indent=2) + "\n")
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("verify")
    p.add_argument("--directory", type=Path, required=True)
    for name in ("core", "abi", "commit", "ndk", "sha256", "source-recipe"):
        p.add_argument("--" + name, required=True)
    p = sub.add_parser("recipe")
    p.add_argument("--root", type=Path, default=Path.cwd())
    p.add_argument("--core", required=True)
    p = sub.add_parser("merge")
    p.add_argument("--llvm-profdata", required=True)
    p.add_argument("--directory", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("inputs", nargs="+", type=Path)
    args = vars(parser.parse_args())
    action = args.pop("action")
    if action == "recipe":
        print(recipe_digest(**args))
    else:
        print(json.dumps((verify if action == "verify" else merge)(**args), indent=2))

if __name__ == "__main__":
    main()
