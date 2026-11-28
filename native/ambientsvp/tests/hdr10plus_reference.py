#!/usr/bin/env python3
"""Independent ST 2084/ST 2094-40 numeric reference, no shader imports.

Uses Decimal and explicit sorted selected pixels, independent of production
histograms and floating point reductions. Deterministic fixtures only;
not an accredited conformance suite.
"""
from decimal import Decimal as D, localcontext, ROUND_HALF_UP
from fractions import Fraction
import struct
import random
import unittest

def quant(value, scale):
    return int((value*scale).to_integral_value(rounding=ROUND_HALF_UP))

def pq(bits):
    with localcontext() as ctx:
        ctx.prec = 70
        code = D.from_float(struct.unpack("<e", struct.pack("<H", bits))[0])
        p = code ** (D(32)/2523)
        return (max(p-D(3424)/4096,D(0))/(D(2413)/128-D(2392)/128*p)) ** (D(16384)/2610)

def selected(x,y,rect,center,axes,rotation):
    # Exact rational ellipse test for orthogonal test orientations.
    if not (rect[0] <= x < rect[2] and rect[1] <= y < rect[3]):
        return False
    dx,dy = x-center[0], y-center[1]
    if rotation == 90:
        dx,dy = dy,-dx
    elif rotation != 0:
        raise ValueError("fixture requires orthogonal ellipse")
    return Fraction(dx*dx,axes[0]**2)+Fraction(dy*dy,axes[1]**2)<=1

def distribution(values, percentages, version):
    ordered = sorted(values)
    result = []
    for i,p in enumerate(percentages):
        if version == 1 and percentages[1:3] == [5,10] and i in (1,2):
            result.append(0 if i==1 else 255)
            continue
        fraction = Fraction(4999,5000) if p == 99 and (version == 0 or i == 8) else Fraction(p,100)
        rank = max(1,-(-(len(ordered)*fraction.numerator)//fraction.denominator))
        result.append(quant(ordered[rank-1],100000))
    return result

def proxy(frame,w,h):
    # Fraction-based equal-area resampling; non-divisible edge dimensions.
    pw,ph = (w+4)//5,(h+4)//5
    output = []
    for y in range(ph):
        for x in range(pw):
            a,b = Fraction(x*w,pw),Fraction((x+1)*w,pw)
            c,d = Fraction(y*h,ph),Fraction((y+1)*h,ph)
            total = Fraction()
            for iy in range(h):
                for ix in range(w):
                    weight = max(Fraction(),min(b,ix+1)-max(a,ix))*max(Fraction(),min(d,iy+1)-max(c,iy))
                    r,g,blue = frame[iy*w+ix]
                    luma = (2627*r+6780*g+593*blue)/10000
                    total += weight*luma
            output.append(total/((b-a)*(d-c)))
    return output

def fbp(frames):
    index = max(range(len(frames)),key=lambda i:(sum(frames[i])/len(frames[i]),i))
    frame = frames[index]
    peak = max(frame)
    def weight(value):
        diff = peak-value
        if diff <= Fraction(1,255):
            return Fraction(1)
        if diff >= Fraction(5,255):
            return Fraction()
        return (Fraction(5,255)-diff)/Fraction(4,255)
    value = sum(map(weight,frame))/len(frame)
    return index,max(1,(value*1000+Fraction(1,2)).__floor__())

class Reference(unittest.TestCase):
    def test_pq_fp16(self):
        self.assertEqual([quant(pq(b),100000) for b in range(0x37ff,0x3804)],[920,922,927,932,936])

    def test_legacy_distribution_and_window(self):
        values = [D("0.001"),D("0.002"),D("0.003"),D("0.004")]
        self.assertEqual(distribution(values,[1,5,10,25,50,75,90,95,99],0),[100,100,100,100,200,300,400,400,400])
        self.assertEqual(distribution(values,[1,5,10,25,50,75,90,95,99],1),[100,0,255,100,200,300,400,400,400])
        self.assertEqual(sum(selected(x,y,(0,0,8,8),(0,0),(4,2),0) for y in range(8) for x in range(8)),10)
        self.assertEqual(sum(selected(x,y,(0,0,8,8),(0,0),(4,2),90) for y in range(8) for x in range(8)),10)

    def test_portable_gpu_integer_arithmetic(self):
        # Independent arbitrary-precision oracle versus the two-word
        # operations used by the GPU implementation.
        mask=(1<<32)-1
        rng=random.Random(209440)
        def wide_mul(a,b):
            a0,a1=a&65535,a>>16
            b0,b1=b&65535,b>>16
            w0=a0*b0
            t=a1*b0+(w0>>16)
            mid=a0*b1+(t&65535)
            return ((a1*b1+(t>>16)+(mid>>16))<<32)|(((mid<<16)|(w0&65535))&mask)
        def wide_div(n,d):
            rem,q=n>>32,0
            for i in range(31,-1,-1):
                rem=(rem<<1)|((n>>i)&1)
                if rem>=d:
                    rem-=d
                    q|=1<<i
            return q+(rem>=(d+1)//2)
        def weight(eps):
            if eps<=3921568:
                return 4000000000
            if eps<19607844:
                return (705032704-eps*255)&mask
            return 0
        for _ in range(10000):
            a,b=rng.randrange(1<<32),rng.randrange(1<<32)
            self.assertEqual(wide_mul(a,b),a*b)
            d=rng.randrange(1,3840*2160+1)
            n=rng.randrange(d*1000000000+1)
            self.assertEqual(wide_div(n,d),(n+d//2)//d)
            eps=rng.randrange(1000000001)
            self.assertEqual(weight(eps),max(0,min(4000000000,5000000000-eps*255)))
        for eps in (0,3921567,3921568,3921569,19607842,19607843,19607844,1000000000):
            self.assertEqual(weight(eps),max(0,min(4000000000,5000000000-eps*255)))

    def test_proxy_and_fbp(self):
        a = [(Fraction(1,2),)*3]*50
        b = [(Fraction(1),)*3]*25+[(Fraction(0),)*3]*25
        self.assertEqual(proxy(a,5,10),[Fraction(1,2)]*2)
        self.assertEqual(proxy(b,5,10),[Fraction(1),Fraction(0)])
        self.assertEqual(fbp([proxy(a,5,10),proxy(b,5,10)]),(1,500))
        self.assertEqual(fbp([[Fraction(1),Fraction(1)-Fraction(3,255),Fraction()]]),(0,500))
        self.assertEqual(proxy([(Fraction(1,3),)*3]*77,11,7),[Fraction(1,3)]*6)

if __name__ == "__main__":
    unittest.main()
