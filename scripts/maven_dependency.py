#!/usr/bin/env python3
"""Resolve direct Gradle catalog pins from published Maven metadata.

No Gradle execution, dynamic version ranges, transitive dependency rewriting or
unpublished Git revisions. Only the shared, checked-in pin is changed.
"""
from __future__ import annotations

from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import re
import tomllib
import urllib.error
import xml.etree.ElementTree as ET

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo.maven.apache.org/maven2"
PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
CHANNELS = ("stable", "prerelease")
VERSION = re.compile(
    r"([0-9]+(?:\.[0-9]+){0,3})(?:[-.]?(alpha|beta|rc|milestone|m)[.-]?([0-9]*)|[-.](final|ga|release))?",
    re.IGNORECASE,
)
# Catalog pins participate in regular stable updates. Compatibility is checked
# by the validated build-deps transaction; incompatible major versions remain
# opt-in and libVLC 4.x still needs a separate renderer migration.
MANUAL: dict[str, str] = {}
DATE_BASED = {"composeBom"}
# Chromium Cronet release numbers are not a semantic API-major series.
# Only the replacement Cronet artifact is tracked; deprecated aliases must not freeze updates.
NON_SEMVER = {"chromiumCronet"}


@dataclass(frozen=True, order=True)
class Artifact:
    group: str
    name: str
    plugin: bool = False

    @property
    def coordinate(self) -> str:
        return f"{self.group}:{self.name}"


@dataclass(frozen=True)
class Pin:
    key: str
    current: str
    artifacts: tuple[Artifact, ...]
    section: str = "versions"
    alias: str = ""


@dataclass(frozen=True)
class Finding:
    pin: Pin
    candidate: str
    status: str
    reason: str = ""


def version_key(version: str) -> tuple[int, ...] | None:
    match = VERSION.fullmatch(version)
    if not match:
        return None
    numbers, stage, sequence, _stable_suffix = match.groups()
    parts = tuple(map(int, numbers.split(".")))
    ranks = {"alpha": 0, "beta": 1, "m": 2, "milestone": 2, "rc": 3, None: 4}
    return (*parts, *((0,) * (4 - len(parts))),
            ranks[stage.lower() if stage else None], int(sequence or 0))


def catalog_pins(text: str) -> dict[str, Pin]:
    data = tomllib.loads(text)
    versions = data.get("versions", {})
    grouped: dict[str, set[Artifact]] = defaultdict(set)
    inline: dict[str, Pin] = {}
    for section in ("libraries", "plugins"):
        for alias, entry in data.get(section, {}).items():
            if isinstance(entry, str):
                if section != "libraries":
                    raise ValueError(f"Unsupported plugin notation: {alias}")
                group, name, version = entry.split(":", 2)
                artifact = Artifact(group, name)
            else:
                if section == "plugins":
                    plugin_id = entry["id"]
                    artifact = Artifact(plugin_id, plugin_id + ".gradle.plugin", True)
                elif "module" in entry:
                    artifact = Artifact(*entry["module"].split(":", 1))
                else:
                    artifact = Artifact(entry["group"], entry["name"])
                version = entry.get("version")
            if version is None:
                # BOM-managed dependencies follow the BOM's own version pin.
                continue
            if isinstance(version, dict) and "ref" in version:
                grouped[version["ref"]].add(artifact)
            elif isinstance(version, str):
                key = f"{section}.{alias}"
                inline[key] = Pin(key, version, (artifact,), section, alias)
            else:
                raise ValueError(f"Unsupported rich version for {section}.{alias}; review manually")
    pins = {}
    for key, artifacts in grouped.items():
        current = versions.get(key)
        if not isinstance(current, str):
            raise ValueError(f"Missing or non-string shared version pin: {key}")
        pins[key] = Pin(key, current, tuple(sorted(artifacts)))
    pins.update(inline)
    return pins


def artifact_repositories(artifact: Artifact) -> list[str]:
    # Google-hosted groups are queried there first; for Central libraries this
    # avoids needless Google 404s. No JitPack builds or arbitrary repositories
    # are triggered by a dependency check.
    google_first = artifact.group.startswith(("androidx.", "com.android.", "com.google.android."))
    repos = [GOOGLE, CENTRAL] if google_first else [CENTRAL, GOOGLE]
    if artifact.plugin:
        repos.append(PLUGIN_PORTAL)
    return repos


def artifact_versions(artifact: Artifact, get_bytes) -> set[str]:
    path = f"{artifact.group.replace('.', '/')}/{artifact.name}/maven-metadata.xml"
    for repo in artifact_repositories(artifact):
        try:
            root = ET.fromstring(get_bytes(f"{repo}/{path}"))
        except urllib.error.HTTPError as exc:
            if exc.code in (404, 410):
                continue
            raise
        versions = {node.text.strip() for node in root.findall("./versioning/versions/version")
                    if node.text and node.text.strip()}
        if versions:
            return versions
    return set()


def published_pom(artifact: Artifact, version: str, get_bytes) -> bool:
    """Confirm the selected release has an actual Maven descriptor, not just a metadata entry."""
    if version_key(version) is None:
        raise ValueError(f"Unsupported candidate version: {version}")
    path = f"{artifact.group.replace('.', '/')}/{artifact.name}/{version}/{artifact.name}-{version}.pom"
    # Resolve descriptors in the same order as settings.gradle.kts.
    repos = [GOOGLE, CENTRAL] + ([PLUGIN_PORTAL] if artifact.plugin else [])
    for repo in repos:
        try:
            root = ET.fromstring(get_bytes(f"{repo}/{path}"))
        except urllib.error.HTTPError as exc:
            if exc.code in (404, 410):
                continue
            raise
        if root.tag.rsplit("}", 1)[-1] != "project":
            raise ValueError(f"Invalid Maven POM for {artifact.coordinate}:{version}")
        artifact_id = next((n.text for n in root if n.tag.rsplit("}", 1)[-1] == "artifactId"), None)
        if artifact_id != artifact.name:
            raise ValueError(f"Mismatched Maven POM for {artifact.coordinate}:{version}")
        return True
    return False


def find_update(pin: Pin, versions: set[str], channel: str, allow_major: bool = False) -> Finding:
    current = version_key(pin.current)
    if current is None:
        return Finding(pin, pin.current, "MANUAL", "unsupported current version notation")
    candidates = {v: key for v in versions if (key := version_key(v)) is not None
                  and (channel == "prerelease" or key[-2] == 4)}
    if not candidates:
        return Finding(pin, pin.current, "UNAVAILABLE", "no common published version in channel")
    newest = max(candidates, key=lambda v: (candidates[v], v))
    if candidates[newest] <= current:
        return Finding(pin, pin.current, "ok")
    if pin.key in MANUAL:
        return Finding(pin, newest, "MANUAL", MANUAL[pin.key])
    # libVLC 4.x is a separate renderer/API integration, not an in-place upgrade.
    same_major = pin.key == "libvlc" or (not allow_major and pin.key not in DATE_BASED | NON_SEMVER)
    if same_major and candidates[newest][0] != current[0]:
        eligible = [v for v, key in candidates.items() if key[0] == current[0] and key > current]
        if not eligible:
            return Finding(pin, newest, "MANUAL", "major-version change requires review")
        compatible = max(eligible, key=lambda v: (candidates[v], v))
        return Finding(pin, compatible, "UPDATE", f"major release {newest} requires review")
    return Finding(pin, newest, "UPDATE")


def plan(text: str, get_bytes, *, key: str | None = None, channel: str = "stable",
         media3_channel: str | None = None, allow_major: bool = False) -> list[Finding]:
    if channel not in CHANNELS or (media3_channel is not None and media3_channel not in CHANNELS):
        raise ValueError("Use stable or prerelease for MAVEN_CHANNEL / MEDIA3_CHANNEL")
    pins = catalog_pins(text)
    if key is not None:
        if key not in pins:
            raise ValueError(f"Unknown Maven pin {key!r}; available: {', '.join(sorted(pins))}")
        pins = {key: pins[key]}
    artifacts = sorted({a for pin in pins.values() for a in pin.artifacts})
    with ThreadPoolExecutor(max_workers=6) as pool:
        versions = dict(zip(artifacts, pool.map(lambda a: artifact_versions(a, get_bytes), artifacts)))
    findings = []
    for pin in pins.values():
        # A staged publication is not enough: every directly used module
        # referencing the same version needs to advertise the candidate.
        common = set.intersection(*(versions[a] for a in pin.artifacts))
        selected_channel = (media3_channel or channel) if pin.key == "media3" else channel
        findings.append(find_update(pin, common, selected_channel, allow_major))
    # A repository may publish metadata before all release POMs appear. Do
    # not advance a shared pin until every direct descriptor is available.
    requested = sorted({(artifact, finding.candidate) for finding in findings
                        if finding.status == "UPDATE" for artifact in finding.pin.artifacts})
    with ThreadPoolExecutor(max_workers=6) as pool:
        published = dict(zip(requested, pool.map(
            lambda item: published_pom(*item, get_bytes), requested)))
    checked = []
    for finding in findings:
        if finding.status == "UPDATE":
            missing = [a.coordinate for a in finding.pin.artifacts
                       if not published[a, finding.candidate]]
            if missing:
                finding = Finding(finding.pin, finding.candidate, "UNAVAILABLE",
                                  "release POM not published: " + ", ".join(missing))
        checked.append(finding)
    return sorted(checked, key=lambda finding: finding.pin.key)


def render_catalog(text: str, findings: list[Finding]) -> str:
    """Patch only the selected simple pins, keeping TOML comments and ordering."""
    original = catalog_pins(text)
    expected = {key: pin.current for key, pin in original.items()}
    for finding in findings:
        if finding.status != "UPDATE":
            continue
        pin = finding.pin
        if original.get(pin.key) != pin:
            raise ValueError(f"Catalog pin changed while planning: {pin.key}")
        section = re.search(rf"(?m)^\[{re.escape(pin.section)}\][ \t]*(?:#.*)?\n"
                            r"(?P<body>[\s\S]*?)(?=^\[|\Z)", text)
        if not section:
            raise ValueError(f"Missing catalog section: {pin.section}")
        body = section.group("body")
        alias = pin.alias or pin.key
        line = re.search(rf'(?m)^[ \t]*{re.escape(alias)}[ \t]*=[^\n]*', body)
        if not line:
            raise ValueError(f"Cannot locate simple catalog pin: {pin.key}")
        old_line = line.group()
        if pin.section == "versions":
            pattern = rf'(^[^=]+=[ \t]*)"{re.escape(pin.current)}"'
        elif old_line.partition("=")[2].lstrip().startswith('"'):
            pattern = rf'(:){re.escape(pin.current)}"'
        else:
            pattern = rf'(\bversion[ \t]*=[ \t]*)"{re.escape(pin.current)}"'
        replacement = (lambda m: m[1] + finding.candidate + '"') if (
            pin.section != "versions" and old_line.partition("=")[2].lstrip().startswith('"')
        ) else (lambda m: m[1] + '"' + finding.candidate + '"')
        new_line, count = re.subn(pattern, replacement, old_line, count=1)
        if count != 1:
            raise ValueError(f"Cannot rewrite catalog pin: {pin.key}")
        start = section.start("body") + line.start()
        text = text[:start] + new_line + text[start + len(old_line):]
        expected[pin.key] = finding.candidate
    if {key: pin.current for key, pin in catalog_pins(text).items()} != expected:
        raise ValueError("Catalog verification failed after rendering updates")
    return text
