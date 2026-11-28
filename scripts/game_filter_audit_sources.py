#!/usr/bin/env python3
"""Export the production Kotlin/pack GLSL literals for the real GLES audit.

No filter algorithm is copied into this module. The narrow Kotlin interfaces are
asserted before extracting their raw strings, so a changed catalog or dispatcher
cannot silently leave a filter out of the native color regression.
"""
import json
from pathlib import Path
import re
import textwrap

from import_game_upscalers import presets, stage_sources

ROOT = Path(__file__).resolve().parents[1]
GAMES = ROOT / "app/src/main/java/com/ambient/tv/ui/games"


def constants(path):
    return dict(re.findall(r'(?:private |internal )?const val (\w+) = """(.*?)"""',
                           path.read_text(), re.S))


def raw(value):
    assert ')AUDIT"' not in value
    return 'R"AUDIT(' + value + ')AUDIT"'


def shader_pass(vertex, fragment, linear=False, scale=1.0, floating=False):
    return '{' + ','.join((raw(vertex), raw(fragment), str(linear).lower(),
                          str(float(scale)) + 'f', str(floating).lower())) + '}'


def native_defaults(kotlin_type):
    """Read the actual Kotlin-to-JNI parameters (native defaults are different)."""
    native_java = ROOT / "third_party/libretrodroid/src/main/java/com/swordfish/libretrodroid"
    declarations = (native_java / "ShaderConfig.kt").read_text()
    block = re.search(r'data class ' + kotlin_type + r'\((.*?)\) : ShaderConfig', declarations, re.S).group(1)
    fields = dict(re.findall(r'val (\w+): \w+ = ([^,\n]+)', block))
    view = (native_java / "GLRetroView.kt").read_text()
    branch = view.split("is ShaderConfig." + kotlin_type + " ->", 1)[1].split("is ShaderConfig.", 1)[0]
    bindings = re.findall(r'LibretroDroid\.(\w+) to toParam\(config\.(\w+)\)', branch)
    labels = dict(re.findall(r'public static final String (\w+) = "([^"\n]+)";',
                             (native_java / "LibretroDroid.java").read_text()))
    assert {field for _, field in bindings} == fields.keys()
    result = {}
    for constant, field in bindings:
        value = fields[field].strip()
        result[labels[constant]] = {"true": "1", "false": "0"}.get(value, value.removesuffix('f'))
    return result


def write_audit_header(destination):
    retro_path = GAMES / "GameRetroShaders.kt"
    retro = constants(retro_path)
    dispatch = re.findall(r'GameShader\.(\w+) -> pass\((\w+), strength(, linear = true)?\)',
                          retro_path.read_text())
    assert len(dispatch) == 15, "Update the audit for the changed compact-filter dispatcher"
    picture = constants(GAMES / "GamePictureShaders.kt")
    hdr_path = GAMES / "GameHdrToneMapShader.kt"
    hdr_vertex = constants(hdr_path)["VERTEX"]
    hdr_body = re.search(r'return """(.*?)"""\.trimIndent\(\)', hdr_path.read_text(), re.S).group(1)
    hdr_body = textwrap.dedent(hdr_body).strip()
    assert hdr_body.count('$hlg') == 1
    native = {"SHARP": "SHADER_SHARP", "PIXEL": "SHADER_DEFAULT", "SMOOTH": "SHADER_UPSCALE_CUT3",
              "CUT2": "SHADER_UPSCALE_CUT2", "CRT": "SHADER_CRT", "LCD": "SHADER_LCD"}
    config_source = (GAMES / "GameShaderConfig.kt").read_text()
    adapter_source = (GAMES / "BundledRetroShaderAdapter.kt").read_text()
    assert "import com.ambient.tv.games.RetroShaderConfig as ShaderConfig" in config_source
    assert "import com.swordfish.libretrodroid.ShaderConfig" in adapter_source
    for name, kotlin in {"SHARP": "Sharp", "PIXEL": "Pixel", "SMOOTH": "CUT3",
                         "CUT2": "CUT2", "CRT": "CRT", "LCD": "LCD"}.items():
        assert f"GameShader.{name} -> ShaderConfig.{kotlin}" in config_source
        vendor = kotlin + "()" if kotlin in ("CUT2", "CUT3") else kotlin
        assert f"RetroShaderConfig.{kotlin} -> ShaderConfig.{vendor}" in adapter_source
    assert "GameShader.BICUBIC -> GamePictureShaders.bicubic()" in config_source
    assert "GameShader.ADAPTIVE -> GamePictureShaders.adaptiveSharpen()" in config_source
    upstream = presets()
    stages = stage_sources()
    names = set(native) | {"BICUBIC", "ADAPTIVE"} | {name for name, _, _ in dispatch} | set(upstream)
    settings = (ROOT / "app/src/main/java/com/ambient/tv/games/GameSettings.kt").read_text()
    enum_block = settings.split("enum class GameShader(", 1)[1].split("companion object", 1)[0]
    actual = set(re.findall(r'^    ([A-Z][A-Z0-9_]*)\(', enum_block, re.M))
    assert names == actual and len(names) == 31, f"Uncovered game filters: {actual ^ names}"
    effects = re.search(r'val effects: List<GameShader> = listOf\((.*?)\)', settings, re.S).group(1)
    effect_names = re.findall(r'GameShader\.(\w+)', effects)
    assert effect_names == ["NTSC_COMPOSITE", "VHS_SOFT", "COLOR_BOOST", "FILM_GRAIN"]
    player = (GAMES / "GamePlayerScreen.kt").read_text()
    slider = re.search(r'label = stringResource\(R.string.game_shader_strength\),.*?'
                       r'valueRange = (\d+)\.\.(\d+),\s*step = (\d+),', player, re.S)
    assert slider, "Update the audit for the changed filter-intensity UI"
    minimum, maximum, step = map(int, slider.groups())
    assert step > 0 and (maximum - minimum) % step == 0
    intensities = [value / 100.0 for value in range(minimum, maximum + 1, step)]
    assert 1.0 in intensities and 0.5 <= minimum / 100.0 <= maximum / 100.0 <= 1.5
    catalog = json.loads((ROOT / "shader_packs/catalog.json").read_text())
    assert len(catalog["presets"]) == 5
    pack_vertex = constants(ROOT / "app/src/main/java/com/ambient/tv/games/GameShaderPackStore.kt")["VERTEX"]

    lines = ["#pragma once", '#include "shadermanager.h"', "#include <vector>",
             "struct AuditFilter { std::string name; libretrodroid::ShaderManager::Config config;",
             "bool compact = false; bool pack = false; bool flatPreserving = false; bool achromatic = true; };",
             "inline std::vector<AuditFilter> gameAuditFilters(float intensity = 1.0f) {",
             "using Type = libretrodroid::ShaderManager::Type;",
             "std::vector<AuditFilter> result;"]
    for name, kind in native.items():
        parameters = native_defaults("CUT3" if name == "SMOOTH" else "CUT2") if name in ("SMOOTH", "CUT2") else {}
        if name == "PIXEL":
            parameters["AMBIENT_NEAREST"] = "1"
        params = '{' + ','.join('{"' + key + '","' + value + '"}' for key, value in parameters.items()) + '}'
        lines.append('result.push_back({"' + name + '",{Type::' + kind + ',' + params + '},false,false,' +
                     str(name not in ("CRT", "LCD")).lower() + ',true});')
    for name in ("BICUBIC", "ADAPTIVE"):
        lines.append('result.push_back({"' + name + '",{Type::SHADER_CUSTOM,{}, {' +
                     shader_pass(picture["VERTEX"], picture[name]) + '}},false,false,true,true});')
    for name, body, linear in dispatch:
        assert "uniform float ambientFilterIntensity;" in retro["HEADER"]
        fragment = 'std::string(' + raw(retro["HEADER"]) + ')+' + raw(retro[body])
        pass_source = '{' + raw(retro["VERTEX"]) + ',' + fragment + ',' + str(bool(linear)).lower() + ',1.0f,false,intensity}'
        neutral = name not in ("CRT_APERTURE", "GAMEBOY_DMG", "GBA_COLOR")
        flat = name in ("SHARP_BILINEAR", "PIXEL_AA", "NTSC_COMPOSITE")
        lines.append('result.push_back({"' + name + '",{Type::SHADER_CUSTOM,{}, {' + pass_source +
                     '}},true,false,' + str(flat).lower() + ',' + str(neutral).lower() + '});')
    for name, passes in upstream.items():
        converted = [shader_pass(*stages[identifier], linear, scale, floating)
                     for identifier, scale, linear, floating in passes]
        lines.append('result.push_back({"' + name + '",{Type::SHADER_CUSTOM,{}, {' + ','.join(converted) +
                     '}},false,false,true,true});')
    for preset in catalog["presets"]:
        name = preset["id"]
        fragment = (ROOT / "shader_packs/shaders" / (name + ".frag")).read_text()
        lines.append('result.push_back({"pack/' + name + '",{Type::SHADER_CUSTOM,{}, {' +
                     shader_pass(pack_vertex, fragment) + '}},false,true,' +
                     str(name in ("adaptive-deblur", "pal-color-bleed")).lower() + ',' +
                     str(name not in ("lcd-rgb-mask", "vintage-warm")).lower() + '});')
    lines += ["return result; }", "inline libretrodroid::ShaderManager::Pass gameAuditHdrPass(bool hlg, bool linear) {",
              "return {" + raw(hdr_vertex) + ",hlg ? " + raw(hdr_body.replace('$hlg', '1')) + " : " +
              raw(hdr_body.replace('$hlg', '0')) + ",linear,1.0f,false}; }",
              "inline std::vector<std::string> gameAuditEffects() { return {" +
              ','.join('"' + name + '"' for name in effect_names) + "}; }",
              "inline std::vector<float> gameAuditIntensities() { return {" +
              ','.join(str(value) + 'f' for value in intensities) + "}; }"]
    destination.write_text('\n'.join(lines) + '\n')
