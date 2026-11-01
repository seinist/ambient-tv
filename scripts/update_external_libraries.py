#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

import svp_dependency as svp
import update_monogram_pin as monogram

ROOT = Path(__file__).resolve().parents[1]
PROPS = ROOT / "gradle" / "external-versions.properties"
CATALOG = ROOT / "gradle" / "libs.versions.toml"
UA = "ambient-tv-library-updater/1.0"


def get_bytes(url: str) -> bytes:
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    # Unauthenticated GitHub API calls share a 60/h limit per IP; a token (GITHUB_TOKEN or
    # GH_TOKEN) lifts it. Only sent to api.github.com, never to download hosts.
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def get_json(url: str):
    return json.loads(get_bytes(url).decode("utf-8"))


def github(path: str):
    return get_json("https://api.github.com" + path)


def load_props() -> dict[str, str]:
    out: dict[str, str] = {}
    for raw in PROPS.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def render_props(values: dict[str, str]) -> str:
    lines = []
    for raw in PROPS.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#") or "=" not in raw:
            lines.append(raw)
            continue
        key = raw.split("=", 1)[0].strip()
        lines.append(f"{key}={values.get(key, raw.split('=',1)[1])}")
    return "\n".join(lines) + "\n"


def catalog_version(key: str) -> str:
    m = re.search(rf'(?m)^{re.escape(key)}\s*=\s*"([^"]+)"\s*$', CATALOG.read_text(encoding="utf-8"))
    if not m:
        raise RuntimeError(f"Version key {key!r} not found in {CATALOG}")
    return m.group(1)


def render_catalog_version(key: str, value: str) -> str:
    text = CATALOG.read_text(encoding="utf-8")
    new, count = re.subn(
        rf'(?m)^({re.escape(key)}\s*=\s*)"[^"]+"\s*$',
        rf'\1"{value}"',
        text,
        count=1,
    )
    if count != 1:
        raise RuntimeError(f"Could not update version key {key!r}")
    return new


def latest_release(repo: str, stable_filter=None) -> dict:
    releases = github(f"/repos/{repo}/releases?per_page=30")
    for rel in releases:
        if rel.get("draft") or rel.get("prerelease"):
            continue
        if stable_filter and not stable_filter(rel):
            continue
        return rel
    raise RuntimeError(f"No stable release found for {repo}")


def resolve_tag_commit(repo: str, tag: str) -> str:
    encoded = urllib.parse.quote(tag, safe="")
    ref = github(f"/repos/{repo}/git/ref/tags/{encoded}")
    obj = ref["object"]
    while obj.get("type") == "tag":
        obj = github(f"/repos/{repo}/git/tags/{obj['sha']}")["object"]
    if obj.get("type") != "commit":
        raise RuntimeError(f"Tag {repo}@{tag} does not resolve to a commit")
    return obj["sha"]


def smarttube_latest() -> dict[str, str]:
    rel = latest_release("yuliskov/SmartTube", lambda r: "stable" in (r.get("name") or "").lower())
    tag = rel["tag_name"]
    commit = resolve_tag_commit("yuliskov/SmartTube", tag)
    tree = github(f"/repos/yuliskov/SmartTube/git/trees/{commit}").get("tree", [])
    entries = {e.get("path"): e.get("sha") for e in tree}
    msc = entries.get("MediaServiceCore")
    shared = entries.get("SharedModules")
    if not msc or not shared:
        raise RuntimeError("SmartTube release does not expose MediaServiceCore/SharedModules submodule pins")
    name = (rel.get("name") or tag).strip()
    return {
        "SMARTTUBE_VERSION": name,
        "SMARTTUBE_TAG": tag,
        "SMARTTUBE_REF": commit,
        "SMARTTUBE_MEDIA_SERVICE_REF": msc,
        "SMARTTUBE_SHARED_MODULES_REF": shared,
    }


def mpv_latest() -> dict[str, str]:
    rel = latest_release("mpv-android/mpv-android")
    tag = rel["tag_name"]
    commit = resolve_tag_commit("mpv-android/mpv-android", tag)
    name = (rel.get("name") or tag).strip()
    m = re.search(r"\d{4}-\d{2}-\d{2}", name) or re.search(r"\d{4}-\d{2}-\d{2}", tag)
    version = m.group(0) if m else name
    return {"MPV_ANDROID_VERSION": version, "MPV_ANDROID_REF": commit}


def ffmpeg_latest() -> dict[str, str]:
    # Keep this known-good release fixed. Advancing release/9.0 changed playback
    # timing on 32-bit Android TV, so ordinary library updates must not undo the pin.
    ref = "n9.0.2"
    return {"FFMPEG_REF": ref, "FFMPEG_COMMIT": resolve_tag_commit("FFmpeg/FFmpeg", ref)}


def projectm_latest() -> dict[str, str]:
    rel = latest_release("projectM-visualizer/projectm")
    tag = rel["tag_name"]
    return {"PROJECTM_REF": tag, "PROJECTM_COMMIT": resolve_tag_commit("projectM-visualizer/projectm", tag)}


def monogram_latest() -> dict[str, str]:
    # Pre-releases count: upstream publishes every Monogram release that way.
    pins, _meta = monogram.resolve_latest()
    return pins


def svp_latest() -> dict[str, str]:
    releases = github(f"/repos/{svp.REPO}/releases?per_page=100")
    published = [r for r in releases if not r.get("draft") and not r.get("prerelease")]
    stable = [r for r in published if re.fullmatch(r"v?\d+\.\d+\.\d+", r.get("tag_name", ""))]
    # Upstream currently publishes nightlies with prerelease=false. Keep that
    # channel explicit; do not mislabel a nightly as a stable release.
    candidates = stable or [r for r in published if r.get("tag_name", "").startswith("nightly-")]
    if not candidates:
        raise RuntimeError("No published Open-SVPFlow versioned release or nightly found")
    tag = candidates[0]["tag_name"]
    return {"SVPFLOW_VERSION": tag, "SVPFLOW_REF": resolve_tag_commit(svp.REPO, tag)}


def rusty_latest() -> dict[str, str]:
    try:
        rel = latest_release("SerafiniJose/rusty")
        tag = rel["tag_name"]
    except (RuntimeError, urllib.error.HTTPError):
        tags = github("/repos/SerafiniJose/rusty/tags?per_page=30")
        if not tags:
            raise RuntimeError("No tags/releases found for SerafiniJose/rusty")
        tag = tags[0]["name"]
    return {"SPOTIFY_RUSTY_REF": tag}


def libvlc_latest_stable(current_version: str | None = None) -> str:
    xml = get_bytes("https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml")
    root = ET.fromstring(xml)
    versions = [v.text or "" for v in root.findall("./versioning/versions/version")]
    stable = [v for v in versions if re.fullmatch(r"\d+\.\d+\.\d+", v)]
    if current_version and re.fullmatch(r"\d+\.\d+\.\d+", current_version):
        current_major = current_version.split(".", 1)[0]
        stable = [v for v in stable if v.split(".", 1)[0] == current_major]
    if not stable:
        raise RuntimeError("No stable libVLC versions found in Maven metadata")
    return max(stable, key=lambda v: tuple(int(p) for p in v.split(".")))


def same_pin(old: str, new: str, key: str) -> bool:
    if old == new:
        return True
    # Git pins are intentionally allowed to be short hashes in the checked-in properties.
    if key.endswith("_REF") and re.fullmatch(r"[0-9a-f]{7,40}", old or "") and re.fullmatch(r"[0-9a-f]{7,40}", new or ""):
        return old.startswith(new) or new.startswith(old)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Check or update managed external library pins")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true")
    group.add_argument("--apply", action="store_true")
    parser.add_argument("--only", choices=("svp", "monogram"),
                        help="Check/update only Open-SVPFlow or Monogram")
    args = parser.parse_args()
    include_rest = args.only is None
    include_svp = args.only in (None, "svp")
    include_monogram = args.only in (None, "monogram")

    current = load_props()
    if include_svp:
        svp.verify(ROOT / "native/ambientsvp", svp.pinned_revision(ROOT))
    managed = [PROPS]
    if include_svp:
        managed.extend(ROOT / "native/ambientsvp" / name for name in svp.UPDATE_FILES)
    if include_rest:
        managed.append(CATALOG)
    originals = {path: path.read_bytes() for path in managed}
    latest: dict[str, str] = {}
    current_libvlc = latest_libvlc = None
    if include_rest:
        latest.update(smarttube_latest())
        latest.update(mpv_latest())
        latest.update(ffmpeg_latest())
        latest.update(projectm_latest())
        latest.update(rusty_latest())
        current_libvlc = catalog_version("libvlc")
        latest_libvlc = libvlc_latest_stable(current_libvlc)
    if include_svp:
        latest.update(svp_latest())
    if include_monogram:
        latest.update(monogram_latest())

    labels = [
        ("SmartTube", "SMARTTUBE_VERSION"),
        ("SmartTube commit", "SMARTTUBE_REF"),
        ("MediaServiceCore", "SMARTTUBE_MEDIA_SERVICE_REF"),
        ("SharedModules", "SMARTTUBE_SHARED_MODULES_REF"),
        ("mpv-android", "MPV_ANDROID_VERSION"),
        ("mpv-android commit", "MPV_ANDROID_REF"),
        ("FFmpeg", "FFMPEG_REF"),
        ("FFmpeg commit", "FFMPEG_COMMIT"),
        ("projectM", "PROJECTM_REF"),
        ("projectM commit", "PROJECTM_COMMIT"),
        ("Spotify/Rusty", "SPOTIFY_RUSTY_REF"),
        ("Open-SVPFlow", "SVPFLOW_VERSION"),
        ("Open-SVPFlow commit", "SVPFLOW_REF"),
        ("Monogram", "MONOGRAM_VERSION"),
        ("Monogram commit", "MONOGRAM_REF"),
        ("Monogram mono-rs", "MONOGRAM_MONO_RS_REF"),
        ("Monogram rustls", "MONOGRAM_RUSTLS_PLATFORM_VERIFIER_VERSION"),
    ]
    changed = False
    monogram_changed = False
    manual_rusty_update = "SPOTIFY_RUSTY_REF" in latest and not same_pin(current.get("SPOTIFY_RUSTY_REF", "?"), latest["SPOTIFY_RUSTY_REF"], "SPOTIFY_RUSTY_REF")
    print("Managed external libraries:")
    for label, key in labels:
        if key not in latest:
            continue
        old, new = current.get(key, "?"), latest[key]
        is_same = same_pin(old, new, key)
        if key == "SPOTIFY_RUSTY_REF" and not is_same:
            marker = "MANUAL"
        else:
            marker = "ok" if is_same else "UPDATE"
            changed |= not is_same
            if key.startswith("MONOGRAM_"):
                monogram_changed |= not is_same
        print(f"  {label:22} {old:44} -> {new:44} [{marker}]")
    if ("MONOGRAM_VERSION" in latest
            and current.get("MONOGRAM_VERSION") == latest["MONOGRAM_VERSION"]
            and not same_pin(current.get("MONOGRAM_REF", ""), latest["MONOGRAM_REF"], "MONOGRAM_REF")):
        print(f"  WARNING: Monogram release tag {latest['MONOGRAM_VERSION']} moved: "
              f"{current.get('MONOGRAM_REF')} -> {latest['MONOGRAM_REF']}")
    if current_libvlc is not None:
        marker = "UPDATE" if current_libvlc != latest_libvlc else "ok"
        changed |= current_libvlc != latest_libvlc
        print(f"  {'libVLC':22} {current_libvlc:44} -> {latest_libvlc:44} [{marker}]")

    if manual_rusty_update:
        print("  NOTE: Spotify/Rusty is not auto-updated: its JNI contract (dev.rusty.app.NativeBridge, SpotifyService, audio/PcmOutput) must be checked against the new release.")
        print("        When moving SPOTIFY_RUSTY_REF, also set SPOTIFY_RUSTY_COMMIT and the SPOTIFY_CORE_SHA256_* pins; fetch_spotify_engine.sh refuses unverified prebuilt cores.")

    if args.apply:
        safe_latest = dict(latest)
        # Rusty releases change JNI signatures and Kotlin-side classes the core calls into
        # (e.g. startDevice gained a parameter, 2.7 moved audio output to audio/PcmOutput).
        # Moving this pin without porting those changes builds fine but breaks at runtime.
        if "SPOTIFY_RUSTY_REF" in safe_latest:
            safe_latest["SPOTIFY_RUSTY_REF"] = current.get("SPOTIFY_RUSTY_REF", safe_latest["SPOTIFY_RUSTY_REF"])
        updates = {}
        if "SVPFLOW_REF" in latest and current["SVPFLOW_REF"] != latest["SVPFLOW_REF"]:
            print("Preparing matching SVP Rust pins, Cargo.lock, Vulkan shader and notices...")
            updates.update(svp.stage_update(ROOT, latest["SVPFLOW_REF"], get_bytes))
        current.update(safe_latest)
        updates[PROPS] = render_props(current).encode()
        if latest_libvlc is not None:
            updates[CATALOG] = render_catalog_version("libvlc", latest_libvlc).encode()
        svp.apply_files(updates, {path: originals[path] for path in updates})
        if include_svp:
            print("\nUpdated managed pins and matching SVP source assets (no native build).")
            print("The next normal build detects changed native inputs and rebuilds affected runtimes automatically.")
            print("SVP only: ./build.sh svp. Upstream API/shader changes still require build and playback regression tests.")
        else:
            print("\nUpdated managed pins (no native build).")
        if include_rest:
            print("SmartTube is refreshed automatically when its compatible release/submodule pins change.")
        if monogram_changed:
            print("Monogram pin updated. Confirm scripts/patch_monogram_leave.py still applies, "
                  "rebuild with ./build.sh monogram, and review the UniFFI ABI, auth, streaming and GPL license.")
    elif changed or manual_rusty_update:
        print("\nUpdates are available. Apply with: ./build.sh libs-update" + (f" {args.only}" if args.only else ""))
        if manual_rusty_update:
            print("Spotify/Rusty requires porting its Kotlin/JNI changes before changing its pin.")
    else:
        print("\nAll managed external library pins are current.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
