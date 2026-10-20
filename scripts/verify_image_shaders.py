#!/usr/bin/env python3
"""Validate bundled shader provenance, licenses, registry and YUV copy-back declarations."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / 'SHADER_SOURCES_027.json').read_text())
registry = (ROOT / 'app/src/main/java/com/ambient/tv/ui/player/backend/MpvImageShaders.kt').read_text()
errors = []
keys = set()
for entry in manifest:
    key = entry['key']
    if key in keys:
        errors.append(f'Duplicate shader key: {key}')
    keys.add(key)
    file = ROOT / entry['file']
    if not file.is_file():
        errors.append(f'Missing shader: {key}')
        continue
    raw = file.read_bytes()
    if hashlib.sha256(raw).hexdigest() != entry['sha256']:
        errors.append(f'Hash mismatch: {key}')
    source = raw.decode('utf-8')
    copy_back = bool(re.search(r'//!(?:HOOK|BIND) (?:LUMA|CHROMA)\b', source))
    base = entry.get('derived_from')
    if base:
        # A local derivative (e.g. the HDR CAS variant) is selected by its base shader's key,
        # not registered on its own; it shares the base's upstream and notice.
        if not entry.get('local_patch'):
            errors.append(f'Derived shader without local_patch: {key}')
        if not re.search(rf'R\.raw\.{re.escape(file.stem)}\b', registry) or f'"{file.name}"' not in registry:
            errors.append(f'Derived shader not referenced by the registry: {key}')
        if '//!HOOK' not in source:
            errors.append(f'No MPV shader hook: {key}')
        notice = ROOT / 'third_party_licenses/image_shaders' / f'{base}-notice.txt'
        if not notice.is_file() or not notice.read_text().strip():
            errors.append(f'Missing copyright notice: {key} ({base})')
        continue
    expected = re.compile(
        rf'"{re.escape(key)}"\s+to\s+MpvShaderAsset\(\s*'
        rf'R\.raw\.{re.escape(file.stem)}\s*,\s*'
        rf'"{re.escape(file.name)}"\s*,\s*{str(copy_back).lower()}\b',
        re.MULTILINE,
    )
    if not expected.search(registry):
        errors.append(f'Registry/resource/copy-back mismatch: {key}')
    if '//!HOOK' not in source:
        errors.append(f'No MPV shader hook: {key}')
    notice = ROOT / 'third_party_licenses/image_shaders' / f'{key}-notice.txt'
    if not notice.is_file() or not notice.read_text().strip():
        errors.append(f'Missing copyright notice: {key}')
    if 'Lesser General Public' in source and entry['license'] != 'LGPL-3.0-or-later':
        errors.append(f'License mismatch: {key}')
if not (ROOT / 'app/src/main/res/raw/image_shader_licenses.txt').is_file():
    errors.append('APK license resource missing')
print(f'Pinned shaders: {len(manifest)}; checks: {len(errors)} issue(s)')
for error in errors:
    print(error)
raise SystemExit(bool(errors))
