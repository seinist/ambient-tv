#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import os
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from maven_dependency import version_key as maven_version_key
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
    current_key = maven_version_key(current_version) if current_version else None
    if current_version and current_key is None:
        raise RuntimeError(f"Unsupported current libVLC pin {current_version!r}; review manually")
    if current_key is not None:
        stable = [v for v in stable if maven_version_key(v)[0] == current_key[0]]
    if not stable:
        if current_version:
            return current_version
        raise RuntimeError("No stable libVLC versions found in Maven metadata")
    newest = max(stable, key=maven_version_key)
    # Both update commands share this catalog pin. A stable-only check must not
    # undo a newer explicit prerelease pin or downgrade after stale metadata.
    if current_key is not None and maven_version_key(newest) <= current_key:
        return current_version
    return newest


def github_repo(url: str) -> str:
    m = re.fullmatch(r"https://github\.com/([^/]+/[^/]+?)(?:\.git)?/?", url)
    if not m:
        raise RuntimeError(f"Not a GitHub repository URL: {url}")
    return m.group(1)


def libretrodroid_latest(current: dict[str, str]) -> dict[str, str]:
    # Newest default-branch commit that touches the module (its submodule gitlinks included):
    # later launcher-only commits would move the pin without changing what is fetched.
    repo = github_repo(current["LIBRETRODROID_REPO"])
    commits = github(f"/repos/{repo}/commits?path=libretrodroid&per_page=1")
    if not commits:
        raise RuntimeError(f"No commits touching libretrodroid/ in {repo}")
    sha = commits[0]["sha"]
    # About shows rcheevos; fetch_libretrodroid.sh requires its pin to match the gitlink.
    rcheevos = github(f"/repos/{repo}/contents/{RCHEEVOS_PATH}?ref={sha}")
    if not isinstance(rcheevos, dict) or rcheevos.get("type") != "submodule":
        raise RuntimeError(f"{RCHEEVOS_PATH} is no submodule at {sha}")
    rcheevos_sha = rcheevos["sha"]
    return {
        "LIBRETRODROID_COMMIT": sha,
        "LIBRETRODROID_RCHEEVOS_COMMIT": rcheevos_sha,
        "LIBRETRODROID_RCHEEVOS_VERSION": git_tag_for(RCHEEVOS_REPO, rcheevos_sha) or rcheevos_sha[:9],
    }


RCHEEVOS_PATH = "libretrodroid/src/main/cpp/rcheevos"
RCHEEVOS_REPO = "https://github.com/RetroAchievements/rcheevos"
LIBRETRODROID_KEYS = ("LIBRETRODROID_COMMIT", "LIBRETRODROID_RCHEEVOS_COMMIT", "LIBRETRODROID_RCHEEVOS_VERSION")


def git_tag_for(repo: str, sha: str) -> str:
    """The release tag that points at sha (peeled for annotated tags), or empty."""
    out = subprocess.run(["git", "ls-remote", "--tags", repo], capture_output=True, text=True, timeout=120,
                         env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    if out.returncode != 0:
        return ""
    for line in out.stdout.splitlines():
        commit, _, ref = line.partition("\t")
        if commit == sha and ref.startswith("refs/tags/"):
            return ref.removeprefix("refs/tags/").removesuffix("^{}")
    return ""


MOONLIGHT_TAG = re.compile(r"v\d+\.\d+(?:\.\d+)?")


def _version_tuple(tag: str) -> tuple[int, ...]:
    return tuple(int(part) for part in tag.lstrip("v").split("."))


def moonlight_latest(current: dict[str, str]) -> dict[str, str]:
    """Newest stable Moonlight Android release (vX.Y[.Z]) and the commit its tag points to."""
    repo = github_repo(current["MOONLIGHT_ANDROID_REPO"])
    try:
        rel = latest_release(repo, lambda r: MOONLIGHT_TAG.fullmatch(r.get("tag_name", "")) is not None)
        tag = rel["tag_name"]
        return {"MOONLIGHT_ANDROID_TAG": tag, "MOONLIGHT_ANDROID_REF": resolve_tag_commit(repo, tag)}
    except (RuntimeError, urllib.error.URLError) as exc:
        # The GitHub API can be rate limited or unreachable; the tags themselves are public git refs.
        print(f"  NOTE: GitHub releases for {repo} unavailable ({exc}); using the newest release tag instead.")
    out = subprocess.run(["git", "ls-remote", "--tags", current["MOONLIGHT_ANDROID_REPO"]],
                         capture_output=True, text=True, timeout=120,
                         env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    if out.returncode != 0:
        raise RuntimeError(f"Could not list Moonlight tags: {out.stderr.strip() or 'no output'}")
    commits: dict[str, str] = {}
    for line in out.stdout.splitlines():
        sha, _, ref = line.partition("\t")
        name = ref.removeprefix("refs/tags/")
        peeled = name.endswith("^{}")
        name = name.removesuffix("^{}")
        if not MOONLIGHT_TAG.fullmatch(name):
            continue
        # An annotated tag lists the tag object and, peeled, the commit; keep the commit.
        if peeled or name not in commits:
            commits[name] = sha
    if not commits:
        raise RuntimeError("No Moonlight release tags found")
    tag = max(commits, key=_version_tuple)
    return {"MOONLIGHT_ANDROID_TAG": tag, "MOONLIGHT_ANDROID_REF": commits[tag]}


def prepare_moonlight() -> bool:
    """Fetch the pinned release and apply scripts/patches/moonlight; False when a patch no longer applies."""
    return subprocess.run(["bash", str(ROOT / "scripts" / "fetch_moonlight_sources.sh")]).returncode == 0


def cores_path() -> Path:
    return ROOT / "cores" / "cores.json"


def remote_head(repo: str) -> str:
    # git ls-remote instead of the GitHub API: one call per core would exhaust the 60/h limit.
    out = subprocess.run(["git", "ls-remote", repo, "HEAD"], capture_output=True, text=True, timeout=120,
                         env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    sha = out.stdout.split()[0] if out.returncode == 0 and out.stdout.strip() else ""
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise RuntimeError(f"Could not resolve HEAD of {repo}: {out.stderr.strip() or 'no output'}")
    return sha


def cores_latest(cores: list[dict]) -> dict[str, str]:
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        heads = pool.map(lambda c: remote_head(c["repo"]), cores)
        return {c["core"]: sha for c, sha in zip(cores, heads)}


def render_cores(data: dict, commits: dict[str, str]) -> bytes:
    data = json.loads(json.dumps(data))
    for core in data["cores"]:
        core["commit"] = commits.get(core["core"], core["commit"])
    return (json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode()


def prepare_libretrodroid() -> bool:
    """Fetch the pinned module and apply our patches; False when a patch no longer applies."""
    return subprocess.run([str(ROOT / "scripts" / "fetch_libretrodroid.sh")]).returncode == 0


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
    parser.add_argument("--only", choices=("svp", "monogram", "libretro", "moonlight"),
                        help="Check/update only Open-SVPFlow, Monogram, LibretroDroid and the libretro cores, "
                             "or Moonlight")
    args = parser.parse_args()
    include_rest = args.only is None
    include_svp = args.only in (None, "svp")
    include_monogram = args.only in (None, "monogram")
    include_libretro = args.only in (None, "libretro")
    include_moonlight = args.only in (None, "moonlight")

    current = load_props()
    cores_data = json.loads(cores_path().read_text(encoding="utf-8")) if include_libretro else None
    if include_svp:
        svp.verify(ROOT / "native/ambientsvp", svp.pinned_revision(ROOT))
    managed = [PROPS]
    if include_svp:
        managed.extend(ROOT / "native/ambientsvp" / name for name in svp.UPDATE_FILES)
    if include_rest:
        managed.append(CATALOG)
    if include_libretro:
        managed.append(cores_path())
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
    latest_cores: dict[str, str] = {}
    if include_libretro:
        latest.update(libretrodroid_latest(current))
        latest_cores = cores_latest(cores_data["cores"])
    if include_moonlight:
        latest.update(moonlight_latest(current))

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
        ("LibretroDroid", "LIBRETRODROID_COMMIT"),
        ("LibretroDroid rcheevos", "LIBRETRODROID_RCHEEVOS_VERSION"),
        ("rcheevos commit", "LIBRETRODROID_RCHEEVOS_COMMIT"),
        ("Moonlight Android", "MOONLIGHT_ANDROID_TAG"),
        ("Moonlight commit", "MOONLIGHT_ANDROID_REF"),
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
    cores_changed = []
    for core in (cores_data or {}).get("cores", []):
        old, new = core["commit"], latest_cores[core["core"]]
        if old != new:
            cores_changed.append(core["core"])
        print(f"  {'core ' + core['core']:22} {old:44} -> {new:44} [{'ok' if old == new else 'UPDATE'}]")
    changed |= bool(cores_changed)
    libretrodroid_changed = ("LIBRETRODROID_COMMIT" in latest
                             and current.get("LIBRETRODROID_COMMIT") != latest["LIBRETRODROID_COMMIT"])
    moonlight_changed = ("MOONLIGHT_ANDROID_REF" in latest
                         and not same_pin(current.get("MOONLIGHT_ANDROID_REF", ""), latest["MOONLIGHT_ANDROID_REF"],
                                          "MOONLIGHT_ANDROID_REF"))

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
        old_libretrodroid = current.get("LIBRETRODROID_COMMIT", "")
        old_libretrodroid_pins = {key: current.get(key, "") for key in LIBRETRODROID_KEYS}
        old_moonlight = {key: current.get(key, "") for key in ("MOONLIGHT_ANDROID_TAG", "MOONLIGHT_ANDROID_REF")}
        updates = {}
        if cores_changed:
            updates[cores_path()] = render_cores(cores_data, latest_cores)
        if "SVPFLOW_REF" in latest and current["SVPFLOW_REF"] != latest["SVPFLOW_REF"]:
            print("Preparing matching SVP Rust pins, Cargo.lock, Vulkan shader and notices...")
            updates.update(svp.stage_update(ROOT, latest["SVPFLOW_REF"], get_bytes))
        current.update(safe_latest)
        updates[PROPS] = render_props(current).encode()
        if latest_libvlc is not None:
            updates[CATALOG] = render_catalog_version("libvlc", latest_libvlc).encode()
        svp.apply_files(updates, {path: originals[path] for path in updates})
        if libretrodroid_changed:
            # Our patches target exact upstream lines: prove they still apply before keeping
            # the new pin, otherwise the next build would stop at a patch error.
            print("\nPreparing LibretroDroid at the new pin to check scripts/patches/libretrodroid...")
            if not prepare_libretrodroid():
                current.update(old_libretrodroid_pins)
                svp.apply_files({PROPS: render_props(current).encode()})
                prepare_libretrodroid()
                print(f"WARNING: LibretroDroid kept at {old_libretrodroid}: a patch does not apply to "
                      f"{latest['LIBRETRODROID_COMMIT']}. Rebase scripts/patches/libretrodroid, then set "
                      "LIBRETRODROID_COMMIT by hand.")
        if moonlight_changed:
            # The reviewed patches (identity, certificate pinning, stop/quit safety) target exact
            # upstream lines: keep the new release only if every patch still applies.
            print("\nPreparing Moonlight Android at the new release to check scripts/patches/moonlight...")
            if not prepare_moonlight():
                current.update(old_moonlight)
                svp.apply_files({PROPS: render_props(current).encode()})
                prepare_moonlight()
                print(f"WARNING: Moonlight kept at {old_moonlight['MOONLIGHT_ANDROID_TAG']} "
                      f"({old_moonlight['MOONLIGHT_ANDROID_REF']}): a patch does not apply to "
                      f"{latest['MOONLIGHT_ANDROID_TAG']}. Rebase scripts/patches/moonlight, then set "
                      "MOONLIGHT_ANDROID_TAG/MOONLIGHT_ANDROID_REF by hand.")
            else:
                print(f"Moonlight Android updated to {latest['MOONLIGHT_ANDROID_TAG']}. Build with "
                      "MOONLIGHT_NATIVE=1 and test pairing, streaming and input before committing.")
        if cores_changed:
            print(f"Libretro cores updated ({', '.join(cores_changed)}). Pushing cores/cores.json to main "
                  "builds and publishes them; or run ./build.sh cores " + ",".join(cores_changed) + ".")
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
