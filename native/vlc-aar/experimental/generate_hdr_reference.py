#!/usr/bin/env python3
"""Independent synthetic reference: ST 2084, authored ST 2094-40 Bezier subset,
and experimental FEL prediction/NLQ/matrix model. No renderer imports."""
import math

def pq(linear):
    x = max(0.0, linear) ** (2610 / 16384)
    return ((3424 / 4096 + 2413 / 128 * x) / (1 + 2392 / 128 * x)) ** (2523 / 32)

def light(code):
    x = max(0.0, code) ** (32 / 2523)
    return (max(0.0, x - 3424 / 4096) / (2413 / 128 - 2392 / 128 * x)) ** (16384 / 2610)

def curve(x):
    if x < .25:
        return .2 * x / .25
    t = (x - .25) / .75
    p = (0, .1, .7, .9, 1)
    return .2 + .8 * sum(math.comb(4, i) * v * t**i * (1-t)**(4-i) for i, v in enumerate(p))

def emit(name, rows):
    print("static const float " + name + "[256][6] = {")
    for row in rows:
        print("    {" + ", ".join(f"{x:.12e}f" for x in row) + "},")
    print("};")

hdr, fel_in, fel_out = [], [], []
for i in range(256):
    peak = 1000 * (i / 255)**2
    rgb = [peak, peak * ((i * 13) % 29) / 28, peak * ((i * 7) % 31) / 30]
    j = i % 3
    rgb = rgb[j:] + rgb[:j]
    output = [0 if not peak else v / peak * 600 * curve(peak / 1000) for v in rgb]
    hdr.append([pq(v / 10000) for v in rgb] + [pq(v / 10000) for v in output])
    # Bounded nonneutral colors near the neutral axis keep matrix output positive.
    base = 400 + ((i * 19) % 201)
    codes = [base + ((i * 7 + k * 11) % 17) - 8 for k in range(3)]
    el = [512 + ((i * 11 + k * 9) % 17) - 8 for k in range(3)]
    signal = []
    for b, e in zip(codes, el):
        delta = e - 512
        residual = math.copysign(min(4096, (abs(delta)-.5)*256+128), delta)/65536 if delta else 0
        signal.append(max(0, min(1, (6554 + 58982 * b / 1024)/65536 + residual)))
    lms = [light(signal[0] - .02 + .05 * signal[1]), light(signal[1]), light(signal[2])]
    lms[1] += .04 * lms[2]
    mat = ((3.06441879,-2.16597676,.10155818),(-.65612108,1.78554118,-.12943749),(.01736321,-.04725154,1.03004253))
    rgb_out = [pq(sum(x*y for x,y in zip(row,lms))) for row in mat]
    fel_in.append([b / 1023 for b in codes] + [e / 1023 for e in el])
    fel_out.append(rgb_out + [0,0,0])

print("/* Generated independently by generate_hdr_reference.py. */")
emit("hdr_vectors",hdr)
emit("fel_inputs",fel_in)
emit("fel_expected",fel_out)
