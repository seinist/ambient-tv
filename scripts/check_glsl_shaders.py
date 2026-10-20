#!/usr/bin/env python3
"""Compile checks and a numeric reference for Ambient TV's own mpv CAS shaders.

* Every variant the app writes to its shader cache (PQ/HLG, limited/full range,
  Light/Medium/AMD CAS blend) is compiled with glslangValidator as GLSL 450 (Vulkan,
  gpu-next) and GLSL ES 3.00/3.10 (OpenGL ES, vo=gpu). The upstream two-pass CAS with
  the injected Light/Medium blend is compiled the same way.
* The HDR CAS PQ lookup table is decoded and compared with the exact SMPTE ST 2084 EOTF.
* The optimised HDR CAS (LUT, min/max on codes, flat early-out) is emulated on the CPU
  and compared with the reference CAS (per-tap EOTF, exact constants).

Mirror of mpvShaderSourceFor / mpvCasBlendSource (MpvImageShaders.kt).
"""
from __future__ import annotations

import math
import random
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "app/src/main/res/raw"
HDR_CAS = RAW / "quality_cas_hdr.glsl"
UPSTREAM_CAS = RAW / "quality_cas_a945e104.glsl"
BLENDS = ("1.0", "0.5", "0.8")  # AMD CAS, MPV_CAS_LIGHT_BLEND, MPV_CAS_MEDIUM_BLEND

M1, M2 = 2610 / 16384, 2523 / 4096 * 128
C1, C2, C3 = 3424 / 4096, 2413 / 4096 * 32, 2392 / 4096 * 32


def pq_eotf(v: float) -> float:
    v = min(max(v, 0.0), 1.0)
    p = v ** (1 / M2)
    return (max(p - C1, 0.0) / (C2 - C3 * p)) ** (1 / M1)


def pq_oetf(l: float) -> float:
    l = min(max(l, 0.0), 1.0)
    p = l ** M1
    return ((C1 + C2 * p) / (1 + C3 * p)) ** M2


def hlg_eotf(v: float) -> float:
    v = min(max(v, 0.0), 1.0)
    return v * v / 3 if v <= 0.5 else (math.exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12


def hlg_oetf(l: float) -> float:
    l = min(max(l, 0.0), 1.0)
    return math.sqrt(3 * l) if l <= 1 / 12 else 0.17883277 * math.log(12 * l - 0.28466892) + 0.55991073


def expand(code: float, limited: bool) -> float:
    return min(max((code - 64 / 1023) / (876 / 1023), 0.0), 1.0) if limited else min(max(code, 0.0), 1.0)


def compress(v: float, limited: bool) -> float:
    return v * 876 / 1023 + 64 / 1023 if limited else v


# --- variants the app materialises ----------------------------------------------------------

def hdr_variant(source: str, trc: int, limited: bool, blend: str) -> str:
    out = source.replace("#define SOURCE_TRC 4", f"#define SOURCE_TRC {trc}")
    out = out.replace("#define TARGET_TRC 4", f"#define TARGET_TRC {trc}")
    if not limited:
        out = out.replace("#define LIMITED_RANGE 1", "#define LIMITED_RANGE 0")
    return out.replace("#define CAS_BLEND 1.0", f"#define CAS_BLEND {blend}")


def upstream_variant(source: str, blend: str | None) -> str:
    if blend is None:
        return source
    return source.replace("#define CAS_BETTER_DIAGONALS 1", f"#define CAS_BLEND {blend}\n#define CAS_BETTER_DIAGONALS 1") \
        .replace("\tpix.r = clamp(pix.r, 0.0, 1.0);", "\tpix.r = mix(e, pix.r, float(CAS_BLEND));\n\tpix.r = clamp(pix.r, 0.0, 1.0);")


# --- glslang harness ------------------------------------------------------------------------

def hook_blocks(source: str) -> list[tuple[list[str], str]]:
    """(bound user textures, GLSL body) per //!HOOK block; //!TEXTURE blocks are data."""
    blocks, current, binds, in_hook = [], [], [], False
    for line in source.splitlines():
        if line.startswith("//!HOOK") or line.startswith("//!TEXTURE"):
            if in_hook:
                blocks.append((binds, "\n".join(current)))
            current, binds, in_hook = [], [], line.startswith("//!HOOK")
            continue
        if not in_hook:
            continue
        if line.startswith("//!BIND"):
            name = line.split()[1]
            if name != "HOOKED":
                binds.append(name)
            continue
        if line.startswith("//!"):
            continue
        current.append(line)
    if in_hook:
        blocks.append((binds, "\n".join(current)))
    return blocks


def harness(binds: list[str], body: str, version: str) -> str:
    es = version.endswith("es")
    head = [f"#version {version}"]
    if es:
        head += ["precision highp float;", "precision mediump sampler2D;", "precision highp int;"]
        head += ["uniform sampler2D HOOKED_raw;", "uniform vec2 HOOKED_pt;", "uniform vec2 HOOKED_size;", "in vec2 HOOKED_pos;"]
        head += [f"uniform sampler2D {name};" for name in binds]
        tail = "out vec4 out_color;\nvoid main() { out_color = hook(); }"
    else:
        head += ["layout(binding = 0) uniform sampler2D HOOKED_raw;",
                 "layout(binding = 1) uniform Params { vec2 HOOKED_pt; vec2 HOOKED_size; };",
                 "layout(location = 0) in vec2 HOOKED_pos;"]
        head += [f"layout(binding = {2 + n}) uniform sampler2D {name};" for n, name in enumerate(binds)]
        tail = "layout(location = 0) out vec4 out_color;\nvoid main() { out_color = hook(); }"
    head += ["const float HOOKED_mul = 1.0;",
             "#define HOOKED_tex(pos) (HOOKED_mul * vec4(texture(HOOKED_raw, pos)))",
             "#define HOOKED_texOff(off) HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(off))"]
    return "\n".join(head) + "\n" + body + "\n" + tail + "\n"


def compile_all(validator: str) -> list[str]:
    failures = []
    hdr = HDR_CAS.read_text()
    upstream = UPSTREAM_CAS.read_text()
    variants = [(f"hdr trc={t} limited={l} blend={b}", hdr_variant(hdr, t, l, b))
                for t in (2, 5) for l in (True, False) for b in BLENDS]
    variants += [(f"upstream blend={b}", upstream_variant(upstream, b)) for b in (None, "0.5", "0.8")]
    with tempfile.TemporaryDirectory(prefix="glsl-") as tmp:
        for label, text in variants:
            for n, (binds, body) in enumerate(hook_blocks(text)):
                for version in ("450", "300 es", "310 es"):
                    path = Path(tmp) / f"s{abs(hash((label, n, version)))}.frag"
                    path.write_text(harness(binds, body, version))
                    args = [validator, "-V", str(path), "-o", str(path) + ".spv"] if version == "450" else [validator, str(path)]
                    result = subprocess.run(args, capture_output=True, text=True)
                    if result.returncode != 0:
                        failures.append(f"{label} pass {n} GLSL {version}:\n{result.stdout}{result.stderr}")
    return failures


# --- LUT and CPU emulation ------------------------------------------------------------------

def read_lut(source: str) -> list[list[float]]:
    after = source.split("//!TEXTURE CAS_HDR_PQ_LUT", 1)[1]
    lines = [l.strip() for l in after.splitlines()[1:]]
    hexes = []
    for l in lines:
        if l.startswith("//!") and hexes:
            break
        if l and not l.startswith("//"):
            hexes.append(l)
    raw = bytes.fromhex("".join(hexes))
    values = [struct.unpack("<f", raw[i:i + 4])[0] for i in range(0, len(raw), 4)]
    return [values[:1024], values[1024:2048]]


def lut_lookup(row: list[float], code: float) -> float:
    x = min(max(code, 0.0), 1.0) * 1023
    base = min(math.floor(x), 1022)
    t = x - base
    return row[base] * (1 - t) + row[base + 1] * t


def cas_core(taps: list[float], blend: float) -> tuple[float, float]:
    a, b, c, d, e, f, g, h, i = taps
    mn = min(d, e, f, b, h)
    mx = max(d, e, f, b, h)
    mn += min(mn, a, c, g, i)
    mx += max(mx, a, c, g, i)
    amp = math.sqrt(min(max(min(mn, 2 - mx) / max(mx, 1e-10), 0.0), 1.0))
    w = amp / -8.0
    sharpened = ((b + d + f + h) * w + e) / (1 + 4 * w)
    return e, e + (max(sharpened, 0.0) - e) * blend


def reference(codes: list[float], trc: int, limited: bool, blend: float) -> float:
    eotf, oetf = (pq_eotf, pq_oetf) if trc == 2 else (hlg_eotf, hlg_oetf)
    _, out = cas_core([eotf(expand(c, limited)) for c in codes], blend)
    return compress(min(max(oetf(out), 0.0), 1.0), limited)


def optimised(codes: list[float], trc: int, limited: bool, blend: float, lut: list[list[float]]) -> float:
    a, b, c, d, e, f, g, h, i = codes
    if max(codes) - min(codes) <= 0.000244140625:
        return e
    row = lut[0 if limited else 1]
    lin = (lambda x: lut_lookup(row, x)) if trc == 2 else (lambda x: hlg_eotf(expand(x, limited)))
    oetf = pq_oetf if trc == 2 else hlg_oetf
    diag_min, diag_max = min(a, c, g, i), max(a, c, g, i)
    lb, ld, le, lf, lh = (lin(x) for x in (b, d, e, f, h))
    # Diagonals enter through their min/max only; same result as linearising all four.
    taps = [lin(diag_min), lb, lin(diag_max), ld, le, lf, lin(diag_min), lh, lin(diag_max)]
    le, out = cas_core(taps, blend)
    if out == le:
        return e
    return compress(min(max(oetf(out), 0.0), 1.0), limited)


def neighbourhoods(seed: int = 7, count: int = 4000):
    rnd = random.Random(seed)
    for _ in range(count):
        centre = rnd.randint(0, 1023)
        spread = rnd.choice((0, 1, 4, 16, 64, 256))
        yield [min(max(centre + rnd.randint(-spread, spread), 0), 1023) / 1023 for _ in range(9)]
        # 12-bit (or not exactly 10-bit normalised) input lands between LUT entries.
        yield [min(max(centre * 4 + rnd.randint(-4 * spread, 4 * spread), 0), 4095) / 4095 for _ in range(9)]
    for low, high in ((64, 80), (64, 940), (100, 140), (300, 400), (700, 900), (0, 1023), (900, 940)):
        yield [low / 1023] * 4 + [high / 1023] + [low / 1023] * 4
        yield [high / 1023] * 4 + [low / 1023] + [high / 1023] * 4


def max_deviation_codes(lut, trc: int, limited: bool, blend: float) -> float:
    worst = 0.0
    for codes in neighbourhoods():
        ref = reference(codes, trc, limited, blend)
        # Untouched pixels keep their source code, also outside the legal range (footroom,
        # headroom); the reference clamps those through its round trip, so compare in range.
        out = optimised(codes, trc, limited, blend, lut)
        if limited:
            out = min(max(out, 64 / 1023), 940 / 1023)
        worst = max(worst, abs(out - ref) * 1023)
    return worst


def main() -> int:
    lut = read_lut(HDR_CAS.read_text())
    lut_error = max(abs(lut[r][c] - struct.unpack("<f", struct.pack("<f", pq_eotf(expand(c / 1023, r == 0))))[0])
                    for r in (0, 1) for c in range(1024))
    print(f"PQ LUT max abs error vs float32(EOTF): {lut_error:.3g}")
    status = 0 if lut_error == 0 else 1
    for trc in (2, 5):
        for limited in (True, False):
            for blend in (1.0, 0.5, 0.8):
                dev = max_deviation_codes(lut, trc, limited, blend)
                print(f"trc={trc} limited={limited} blend={blend}: max deviation {dev:.4f} codes")
                if dev > 0.25:
                    status = 1
    validator = shutil.which("glslangValidator")
    if validator is None:
        print("glslangValidator not found; compile check skipped", file=sys.stderr)
        return status if "--require-glslang" not in sys.argv else 1
    failures = compile_all(validator)
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f"GLSL compile check: {'FAILED' if failures else 'ok'}")
    return 1 if failures else status


if __name__ == "__main__":
    sys.exit(main())
