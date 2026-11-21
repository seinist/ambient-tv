#!/usr/bin/env python3
"""Create a reproducible, SIGNED compact shader pack from reviewed GLSL ES 100 sources.

Usage:
  python3 scripts/build_game_shader_pack.py --input assets/game-shaders --output /tmp/pack.zip --key signing-private.pem

Input catalog.json declares id/title/presets (id/title/category/license/sourceUrl).
Each preset's fragment is input/shaders/<id>.frag, <=16 KiB, GLSL ES 100.
No binary shaders, archives, native code, external LUTs or arbitrary file trees are accepted.
"""
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

ALLOWED = {"scaling", "crt", "handheld", "signal", "effects"}
ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,39}$")
MAX_SOURCE = 16 * 1024

def build(root: Path, output: Path, signing_key: Path) -> None:
    catalog = json.loads((root / "catalog.json").read_text("utf-8"))
    assert ID.fullmatch(catalog["id"]) and catalog["title"]
    assert 0 < len(catalog["presets"]) <= 32
    assert len({p["id"] for p in catalog["presets"]}) == len(catalog["presets"])
    entries = {}
    manifest_presets = []
    for p in catalog["presets"]:
        name = p["id"]
        assert ID.fullmatch(name) and p["category"] in ALLOWED
        assert p["license"] and p["sourceUrl"].startswith("https://")
        path = root / "shaders" / (name + ".frag")
        data = path.read_bytes()
        assert 0 < len(data) <= MAX_SOURCE
        source = data.decode("utf-8")
        assert "void main" in source and "sampler2D texture" in source and "varying" in source
        assert not re.search(r"(?m)^\s*#\s*(include|extension|version)", source)
        entries["shaders/" + name + ".frag"] = data
        manifest_presets.append({
            "id": name, "title": p["title"], "category": p["category"],
            "sha256": hashlib.sha256(data).hexdigest(),
            "license": p["license"], "sourceUrl": p["sourceUrl"]
        })
    manifest = {"version": 1, "id": catalog["id"], "title": catalog["title"],
                "presets": manifest_presets}
    raw = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    assert len(raw) <= 32 * 1024
    with tempfile.TemporaryDirectory() as folder:
        tmp = Path(folder)
        (tmp / "manifest.json").write_bytes(raw)
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(signing_key),
                        "-out", str(tmp / "manifest.sig"), str(tmp / "manifest.json")], check=True)
        signature = (tmp / "manifest.sig").read_bytes()
        assert len(signature) <= 512
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipf:
        for name, data in [("manifest.json", raw), ("manifest.sig", signature)] + sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zipf.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    assert output.stat().st_size <= 512 * 1024
    print(f"Pack {manifest['id']}: {len(entries)} shaders, {output.stat().st_size} archive bytes")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path)
    ap.add_argument("--key", required=True, type=Path)
    a = ap.parse_args()
    build(a.input, a.output, a.key)
