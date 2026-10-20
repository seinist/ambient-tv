// LICENSE
// =======
// Copyright (c) 2017-2019 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
// files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
// modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
// ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

// FidelityFX CAS by AMD, mpv port by agyild. Single-pass HDR variant for Ambient TV:
// the 3x3 neighbourhood is expanded from limited range and linearised in registers
// (PQ or HLG), sharpened and re-encoded in the same pass. No linear-light texture is
// written, so dark PQ values (1 cd/m2 ~ 1e-4) never lose precision in an FBO.

//!HOOK LUMA
//!BIND HOOKED
//!DESC FidelityFX Sharpening (HDR, single pass)

#define SOURCE_TRC 4 // 2 = PQ, 5 = HLG, 4 = BT.1886 (unused for HDR)
#define TARGET_TRC 4 // Must match SOURCE_TRC.
#define CUSTOM_GAMMA 2.2 // Unused; kept for the shared validation.
#define SHARPENING 0.0 // 0.0 to 1.0, as in the original CAS.
#define CAS_BLEND 1.0 // Fraction of the sharpened result (Light/Medium use less than 1.0).
#define LIMITED_RANGE 1 // 1 = 10-bit limited range (64..940) luma, 0 = full range.

const float RANGE_LO = 64.0 / 1023.0;
const float RANGE_SCALE = 876.0 / 1023.0;

float expandRange(float v) {
#if (LIMITED_RANGE == 1)
	return clamp((v - RANGE_LO) / RANGE_SCALE, 0.0, 1.0);
#else
	return clamp(v, 0.0, 1.0);
#endif
}

float compressRange(float v) {
#if (LIMITED_RANGE == 1)
	return v * RANGE_SCALE + RANGE_LO;
#else
	return v;
#endif
}

float toLinear(float code) {
	float v = expandRange(code);
#if (SOURCE_TRC == 2)
	float p = pow(v, 0.0126833);
	return pow(clamp(p - 0.835938, 0.0, 1.0) / (18.8516 - 18.6875 * p), 6.27739);
#elif (SOURCE_TRC == 5)
	if (v <= 0.5) return v * v / 3.0;
	return (exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
#else
	return pow(v, 2.4);
#endif
}

float fromLinear(float l) {
	l = clamp(l, 0.0, 1.0);
	float v;
#if (TARGET_TRC == 2)
	float p = pow(l, 0.159302);
	v = pow((0.835938 + 18.8516 * p) / (1.0 + 18.6875 * p), 78.8438);
#elif (TARGET_TRC == 5)
	v = l <= 1.0 / 12.0 ? sqrt(3.0 * l) : 0.17883277 * log(12.0 * l - 0.28466892) + 0.55991073;
#else
	v = pow(l, 1.0 / 2.4);
#endif
	return compressRange(clamp(v, 0.0, 1.0));
}

#define TAP(x, y) toLinear(HOOKED_texOff(vec2(x, y)).r)

vec4 hook() {
	//  a b c
	//  d e f
	//  g h i
	float a = TAP(-1.0, -1.0);
	float b = TAP( 0.0, -1.0);
	float c = TAP( 1.0, -1.0);
	float d = TAP(-1.0,  0.0);
	float e = TAP( 0.0,  0.0);
	float f = TAP( 1.0,  0.0);
	float g = TAP(-1.0,  1.0);
	float h = TAP( 0.0,  1.0);
	float i = TAP( 1.0,  1.0);

	// Soft min and max (CAS_BETTER_DIAGONALS), 2x scaled as in the original.
	float mnL = min(min(min(d, e), min(f, b)), h);
	float mxL = max(max(max(d, e), max(f, b)), h);
	mnL += min(mnL, min(min(a, c), min(g, i)));
	mxL += max(mxL, max(max(a, c), max(g, i)));

	// Exact (CAS_GO_SLOWER) math; the guard keeps pure black from dividing by zero.
	float ampL = clamp(min(mnL, 2.0 - mxL) / max(mxL, 1e-10), 0.0, 1.0);
	ampL = sqrt(ampL);
	float peak = -mix(8.0, 5.0, clamp(SHARPENING, 0.0, 1.0));
	float wL = ampL / peak;
	float sharpened = ((b + d + f + h) * wL + e) / (1.0 + 4.0 * wL);
	float outL = mix(e, max(sharpened, 0.0), float(CAS_BLEND));

	vec4 pix = HOOKED_tex(HOOKED_pos);
	pix.r = fromLinear(outL);
	return pix;
}
