#!/usr/bin/env python3
"""SDK-free source checks. This does NOT compile Kotlin or replace Android lint/tests."""
from pathlib import Path
from collections import Counter
import re
import struct
import sys
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main'
errors = []
xml_files = list(MAIN.rglob('*.xml'))
for path in xml_files:
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        errors.append(f'{path.relative_to(ROOT)}: {exc}')
strings = {}
untranslatable = set()
for directory in ['values', 'values-de', 'values-en']:
    # Strings are split across several files per directory (notifications.xml, livetv.xml, ...).
    elements = [e for path in sorted((MAIN / 'res' / directory).glob('*.xml'))
                for e in ET.parse(path).getroot().findall('string')]
    names = [e.attrib['name'] for e in elements]
    errors += [f'{directory}: duplicate string {name}' for name, count in Counter(names).items() if count > 1]
    strings[directory] = {e.attrib['name']: ''.join(e.itertext()) for e in elements}
    if directory == 'values':
        untranslatable = {e.attrib['name'] for e in elements if e.attrib.get('translatable') == 'false'}
def placeholders(text):
    return sorted(re.findall(r'%(?!%)(?:\d+\$)?[dsf]', text.replace('%%', '')))
for locale in ['values-de', 'values-en']:
    for name, value in strings['values'].items():
        if name in untranslatable:
            continue
        translated = strings[locale].get(name)
        if translated is None:
            errors.append(f'{locale}: missing string {name}')
        elif placeholders(value) != placeholders(translated):
            errors.append(f'{locale}: placeholder mismatch in {name}')
kt = list((MAIN / 'java').rglob('*.kt'))
for path in kt:
    text = path.read_text()
    for name in set(re.findall(r'(?<!android\.)\bR\.string\.([A-Za-z0-9_]+)', text)):
        if name not in strings['values']:
            errors.append(f'{path.relative_to(ROOT)}: missing R.string.{name}')
    imports = re.findall(r'^import (.+)$', text, re.M)
    errors += [f'{path.relative_to(ROOT)}: duplicate import {name}' for name, count in Counter(imports).items() if count > 1]

# Spotify native implementation details must stay behind SpotifyPlaybackEngine.
legacy_spotify_symbols = ('SpotifyNativeBridge', 'SpotifyNativeStateBus')
allowed_native_bridge = {
    Path('app/src/main/java/com/ambient/tv/spotify/playback/LibrespotPlaybackEngine.kt'),
    Path('app/src/main/java/dev/rusty/app/NativeBridge.kt'),
    Path('app/src/main/java/dev/rusty/app/SpotifyService.kt'),
}
allowed_librespot_bus = {
    Path('app/src/main/java/com/ambient/tv/spotify/playback/LibrespotPlaybackEngine.kt'),
    Path('app/src/main/java/com/ambient/tv/spotify/playback/LibrespotNativeStateBus.kt'),
    Path('app/src/main/java/dev/rusty/app/SpotifyService.kt'),
}
for path in kt:
    rel = path.relative_to(ROOT)
    text = path.read_text()
    for symbol in legacy_spotify_symbols:
        if symbol in text:
            errors.append(f'{rel}: legacy Spotify native symbol leaked: {symbol}')
    if 'dev.rusty.app.NativeBridge' in text and rel not in allowed_native_bridge:
        errors.append(f'{rel}: NativeBridge bypasses SpotifyPlaybackEngine boundary')
    if 'LibrespotNativeStateBus' in text and rel not in allowed_librespot_bus:
        errors.append(f'{rel}: Librespot native state bus leaked outside adapter boundary')
# NNEDI3 quality choices must stay complete, pinned to one upstream model family and bundled.
nnedi_catalog = (ROOT / 'app/src/main/java/com/ambient/tv/data/local/Nnedi3QualityCatalog.kt').read_text()
nnedi_registry = (ROOT / 'app/src/main/java/com/ambient/tv/ui/player/backend/MpvImageShaders.kt').read_text()
for neurons in [16, 32, 64, 128, 256]:
    if f'key = "nnedi3_nns{neurons}"' not in nnedi_catalog:
        errors.append(f'NNEDI3 quality ladder missing nns{neurons}')
    resource = MAIN / 'res' / 'raw' / f'nnedi3_nns{neurons}_win8x4_b3f0a59.hook'
    if not resource.is_file():
        errors.append(f'NNEDI3 bundled resource missing: {resource.relative_to(ROOT)}')
if 'key = "nnedi3_nns256_win8x6"' not in nnedi_catalog:
    errors.append('NNEDI3 Expert win8x6 preset missing')
if 'window = "win8x6"' not in nnedi_catalog:
    errors.append('NNEDI3 Expert win8x6 window is not declared')
if not (MAIN / 'res' / 'raw' / 'nnedi3_nns256_win8x6_b3f0a59.hook').is_file():
    errors.append('NNEDI3 bundled resource missing: app/src/main/res/raw/nnedi3_nns256_win8x6_b3f0a59.hook')
for key in ['nnedi3_nns64', 'nnedi3_nns128', 'nnedi3_nns256', 'nnedi3_nns256_win8x6']:
    if f'"{key}" to bundledNnedi3("{key}")' not in nnedi_registry:
        errors.append(f'NNEDI3 preset {key} is not wired to bundled resources')
if 'b3f0a59d68f33b7162051ea5970a5169558f0ea2' not in nnedi_catalog:
    errors.append('NNEDI3 upstream revision is not pinned')

# Section/subcategory identity must survive view-mode changes across systems.
youtube_screen = (ROOT / 'app/src/main/java/com/ambient/tv/ui/youtube/YouTubeScreen.kt').read_text()
for token in ['SectionedYouTubeContent', 'SectionedYouTubeList', 'SectionedYouTubeWall', 'SectionedYouTubeCinematic']:
    if token not in youtube_screen:
        errors.append(f'YouTube section-preserving renderer missing: {token}')
if 'val videos=rows.flatMap { it.items }' in youtube_screen or 'val videos = rows.flatMap { it.items }' in youtube_screen:
    errors.append('YouTube: rows are flattened before section-preserving view-mode dispatch')

section_screen = (ROOT / 'app/src/main/java/com/ambient/tv/ui/section/SectionScreen.kt').read_text()
for token in ['RecommendationListPage', 'RecommendationWallPage', 'RecommendationCinematicPage']:
    if token not in section_screen:
        errors.append(f'Plex/Jellyfin recommendation section renderer missing: {token}')

search_screen = (ROOT / 'app/src/main/java/com/ambient/tv/ui/search/SearchScreen.kt').read_text()
if 'state.results + state.jellyfinResults' in search_screen:
    errors.append('Search list flattens Plex and Jellyfin source sections')
for token in ['searchMediaGroups(state.results)', 'searchMediaGroups(state.jellyfinResults)', 'spotifyGroups.forEachIndexed']:
    if token not in search_screen:
        errors.append(f'Search list section grouping missing: {token}')

for wrapper in ['youtubeapi', 'sharedutils', 'mediaserviceinterfaces', 'commons-io', 'j2v8']:
    if not (ROOT / 'smarttube_wrappers' / wrapper / 'build.gradle.kts').is_file():
        errors.append(f'missing SmartTube wrapper: {wrapper}')
with (ROOT / 'gradle/libs.versions.toml').open('rb') as file:
    catalog = tomllib.load(file)
for alias, library in catalog['libraries'].items():
    ref = library.get('version', {})
    if isinstance(ref, dict) and 'ref' in ref and ref['ref'] not in catalog['versions']:
        errors.append(f'catalog: unknown version for {alias}')
# Reflection/JNI-only callback classes are invisible to the shrinker's reachability analysis.
proguard = (ROOT / 'app/proguard-rules.pro').read_text()
for cls in ['dev.rusty.app.SpotifyService', 'dev.rusty.app.NativeBridge', 'com.ambient.tv.ui.player.backend.MpvNativeBridge', 'com.ambient.tv.ui.common.projectm.ProjectMNativeBridge']:
    if f'-keep class {cls} ' not in proguard:
        errors.append(f'missing JNI keep rule: {cls}')
libs = list((MAIN / 'jniLibs').glob('*/*.so'))
for path in libs:
    raw = path.open('rb').read(20)
    expected = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}[path.parent.name]
    if raw[:4] != b'\x7fELF' or (raw[4], struct.unpack('<H', raw[18:20])[0]) != expected:
        errors.append(f'wrong ELF ABI: {path.relative_to(ROOT)}')
print(f'XML files: {len(xml_files)}; Kotlin production files: {len(kt)}; strings/locale: ' + ', '.join(f'{k}={len(v)}' for k,v in strings.items()))
print(f'Version catalog parsed; native ELF headers checked: {len(libs)}')
print(f'Source checks: {len(errors)} issue(s)')
for error in errors:
    print(error)
sys.exit(bool(errors))
