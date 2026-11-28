#!/usr/bin/env python3
"""Independent double-precision HDR10+ frame oracle.

The fixtures exercise the authored-target model (not libplacebo's proprietary
display adaptation), legacy local-window blend, peak grids and Annex B.5
saturation policy. Generate wire metadata independently, then compute every
pixel from the quantized fields and source samples. No production imports.
"""
from dataclasses import dataclass, replace
import math

W, H = 17, 13
N = W * H
KR, KG, KB = .2627, .6780, .0593

def clamp(x):
    return min(1., max(0., x))

def pq(nits):
    p = (max(0., nits) / 10000.) ** (2610 / 16384)
    return ((3424 / 4096 + 2413 / 128 * p) /
            (1 + 2392 / 128 * p)) ** (2523 / 32)

def eotf(code):
    p = clamp(code) ** (32 / 2523)
    return 10000 * (max(0., p - 3424 / 4096) /
                    (2413 / 128 - 2392 / 128 * p)) ** (16384 / 2610)

@dataclass(frozen=True)
class Window:
    peak: int
    avg: int
    fraction: int
    kx: int
    ky: int
    anchors: tuple
    saturation: int = 0
    geometry: tuple = ()
    # geometry: inclusive rectangle, center, angle, inner/outer/minor, mode

G1 = (1, 0, 15, 11, 6, 5, 35, 2, 7, 4, 0)
G2 = (3, 1, 16, 12, 10, 7, 120, 3, 8, 5, 0)
GLOBAL = Window(10000, 2800, 250, 1024, 819, (102, 716, 921))
LOCAL1 = Window(15000, 5000, 750, 2048, 2457, (300, 800), 8, G1)
LOCAL2 = Window(20000, 7000, 500, 4095, 1638, (), 32, G2)
MG = ((2, 3, 4), (5, 7, 9), (8, 11, 15))
TG = ((9, 10, 11), (11, 12, 13), (13, 14, 15))

def grid_factor(grid, w):
    if not grid or not w.fraction:
        return 1.
    row = w.fraction / 1000 * (len(grid) - 1)
    col = w.avg / 100000 * (len(grid[0]) - 1)
    i, j = int(row), int(col)
    a, b = row-i, col-j
    i2, j2 = min(i+1, len(grid)-1), min(j+1, len(grid[0])-1)
    return sum(value * weight for value, weight in (
        (grid[i][j], (1-a)*(1-b)), (grid[i][j2], (1-a)*b),
        (grid[i2][j], a*(1-b)), (grid[i2][j2], a*b))) / 15

def curve(w, x):
    x = clamp(x)
    kx, ky = w.kx / 4095, w.ky / 4095
    if kx == 1 or x < kx:
        return ky*x/kx
    t = (x-kx)/(1-kx)
    points = (0., *(v/1023 for v in w.anchors), 1.)
    degree = len(points)-1
    # Bernstein sum, deliberately not the production de Casteljau/LUT.
    return ky+(1-ky)*sum(math.comb(degree,i)*v*t**i*(1-t)**(degree-i)
                        for i,v in enumerate(points))

def weight(g, x, y):
    if not g:
        return 0.
    l,t,r,b,cx,cy,angle,inner,outer,minor,_ = g
    if not l <= x <= r or not t <= y <= b:
        return 0.
    th = math.radians(angle)
    dx,dy = x-cx,y-cy
    radius = math.hypot((dx*math.cos(th)+dy*math.sin(th))/outer,
                        (dy*math.cos(th)-dx*math.sin(th))/minor)
    if radius >= 1:
        return 0.
    ratio = inner/outer
    if radius <= ratio:
        return 1.
    return math.cos(math.pi/2*(radius-ratio)/(1-ratio))**2

def map_window(rgb, w, peak, mastering, mg, tg):
    domain = 10000*grid_factor(mg,w) if mg and w.fraction else w.peak/10
    target = peak*grid_factor(tg,w)
    value = max(rgb)
    if value <= 0:
        return (0.,0.,0.)
    s = min(value/domain,1.)
    mapped = target*curve(w,s)
    out = [clamp(v/domain)*mapped/s for v in rgb]
    if not w.saturation or mapped <= 0:
        return out
    # Annex B.11 paired peak grids or characterized mastering/display scales.
    source_scale, target_scale = mastering, peak
    if mg and tg and w.fraction:
        source_scale = grid_factor(mg,w)*w.peak/10
        target_scale = grid_factor(tg,w)*w.peak/10
    source_nits = s*source_scale
    target_nits = clamp(mapped/target)*target_scale
    if target_nits <= 1.0001 or source_nits <= target_nits:
        return out
    gain = min(4.,1+w.saturation/8*max(0.,math.log(source_nits)/math.log(target_nits)-1))
    enc = [clamp(v/target)**(1/2.4) for v in out]
    y = sum(a*b for a,b in zip(enc,(KR,KG,KB)))
    for v in enc:
        d = v-y
        if d > 0:
            gain = min(gain,(1-y)/d)
        elif d < 0:
            gain = min(gain,-y/d)
    return [target*clamp(y+gain*(v-y))**2.4 for v in enc]

def reference(rgb, windows, peak, mastering, mg, tg, x, y):
    mapped = [map_window(rgb,w,peak,mastering,mg,tg) for w in windows]
    if len(windows) == 1:
        return mapped[0]
    a = weight(windows[1].geometry,x,y)
    b = weight(windows[2].geometry,x,y) if len(windows)==3 else 0.
    if windows[1].geometry[-1]:
        weights = (1-a,a,0.) if a else (1-b,0.,b)
    elif a+b:
        weights = (1-(a*a+b*b)/(a+b),a*a/(a+b),b*b/(a+b))
    else:
        weights = (1.,0.,0.)
    return [sum(weight*color[k] for weight,color in zip(weights,mapped)) for k in range(3)]

def metadata(windows, peak, mg, tg, version):
    bits = []
    def put(value, width):
        assert 0 <= value < 2**width
        bits.extend(map(int, f"{value:0{width}b}"))
    def grid(g):
        put(bool(g),1)
        if g:
            put(len(g),5); put(len(g[0]),5)
            for row in g:
                for v in row:
                    put(v,4)
    put(version,8); put(len(windows),2)
    for w in windows[1:]:
        for v,width in zip(w.geometry,(16,16,16,16,16,16,8,16,16,16,1)):
            put(v,width)
    put(peak,27); grid(tg)
    for w in windows:
        for v in (w.peak, w.peak*3//4, w.peak//2, w.avg):
            put(v,17)
        if version == 1:
            # Required v1 distribution, including reserved 5/10 markers.
            hist = ((1,0),(5,0),(10,255),(25,100),(50,500),
                    (75,1000),(90,3000),(95,5000),(99,w.peak))
        else:
            hist = ((50,w.avg),(99,w.peak))
        put(len(hist),4)
        for percent,value in hist:
            put(percent,7); put(value,17)
        put(w.fraction,10)
    grid(mg)
    for w in windows:
        put(1,1); put(w.kx,12); put(w.ky,12)
        put(len(w.anchors),4)
        for a in w.anchors:
            put(a,10)
        put(bool(w.saturation),1)
        if w.saturation:
            put(w.saturation,6)
    bits += [0]*(-len(bits)%8)
    return [0xb5,0,0x3c,0,1,4]+[sum(bits[i+j]<<(7-j) for j in range(8))
                                for i in range(0,len(bits),8)]

def source(x,y,kind,neutral):
    i = y*W+x
    if kind:
        # Independent quantized native P010 identity sampler, Cr/Y/Cb.
        yy = .20+.48*((i*17)%97)/96
        cb = (((i*11)%23)-11)/110
        cr = (((i*7)%29)-14)/140
        if neutral:
            cb=cr=0.
        yo,ys,cs = (64,876,896) if kind==1 else (0,1023,1023)
        yc,uc,vc = round(yo+ys*yy),round(512+cs*cb),round(512+cs*cr)
        yv,uv,vv = (yc-yo)/ys,(uc-512)/cs,(vc-512)/cs
        r = yv+2*(1-KR)*vv
        b = yv+2*(1-KB)*uv
        g = (yv-KR*r-KB*b)/KG
        return (vc/1023,yc/1023,uc/1023), tuple(eotf(v) for v in (r,g,b))
    # Non-repeating asymmetric raster, black and highlight clipping included.
    v = 0. if i%29==0 else 2000*((i*37)%211/210)**2
    rgb = (v,v,v) if neutral else (v,v*(.15+.8*((i*13)%31)/30),v*(.1+.85*((i*7)%19)/18))
    if neutral == 2:
        # In-gamut but non-neutral colors for an independent SDR/HLG
        # transfer reference, avoiding renderer-specific gamut compression.
        v *= .4  # remain inside both the display range and BT.709 gamut
        rgb=(v,v*.94,v*.91)
    if neutral != 1:
        k=i%3
        rgb=rgb[k:]+rgb[:k]
    return tuple(pq(v) for v in rgb),rgb

def hlg_rgb(rgb,peak,black):
    gamma=max(1.,1.2+.42*math.log10(peak/1000))
    beta=math.sqrt(3*(black/peak)**(1/gamma))
    norm=[v/peak for v in rgb]
    lum=sum(a*b for a,b in zip(norm,(KR,KG,KB)))
    if lum<=0:
        return (0.,0.,0.)
    # Inverse BT.2100 display OOTF operates on scene luminance, not
    # each color channel independently. OETF is applied per channel.
    factor=12*lum**((1-gamma)/gamma)
    result=[]
    for v in norm:
        scene=v*factor
        signal=.5*math.sqrt(scene) if scene<=1 else .17883277*math.log(scene-.28466892)+.55991073
        result.append(clamp((signal-beta)/(1-beta)))
    return result

def sdr_rgb(rgb,peak,black):
    # BT.2020 to BT.709, D65, derived from the standard xy primaries.
    mat=((1.660491,-.587641,-.072850),(-.124550,1.132900,-.008349),
         (-.018151,-.100579,1.118730))
    lin=[sum(a*b for a,b in zip(row,rgb)) for row in mat]
    assert all(-1e-4<=v<=peak+1e-4 for v in lin), lin
    lb,lw=black**(1/2.4),peak**(1/2.4)
    return [clamp((max(v,black)**(1/2.4)-lb)/(lw-lb)) for v in lin]

def emit_array(name,rows):
    print(f"static const float {name}[REF_N][3] = {{")
    for row in rows:
        print("    {"+",".join(f"{v:.12e}f" for v in row)+"},")
    print("};")

def main():
    layer1=replace(LOCAL1,geometry=G1[:-1]+(1,))
    layer2=replace(LOCAL2,geometry=G2[:-1]+(1,))
    moved=replace(LOCAL1,geometry=(0,0,12,12,4,8,70,2,7,4,0))
    v1=replace(GLOBAL,fraction=0,saturation=0)
    # name, windows, display peak, grids, wire version, input format, flip, target, neutral
    cases=[
        ("weighted_rgb",(GLOBAL,LOCAL1,LOCAL2),600,(),(),0,0,False,0,False),
        ("layered_rgb",(GLOBAL,layer1,layer2),600,(),(),0,0,False,0,False),
        ("moving_window",(GLOBAL,moved,LOCAL2),600,(),(),0,0,False,0,False),
        ("grids_rgb",(GLOBAL,LOCAL1,LOCAL2),600,MG,TG,0,0,False,0,False),
        ("grids_p010_limited",(GLOBAL,LOCAL1,LOCAL2),600,MG,TG,0,1,False,0,False),
        ("p010_full_flipped",(GLOBAL,LOCAL1,LOCAL2),600,(),(),0,2,True,0,False),
        ("v1_pq",(v1,),1500,(),(),1,0,False,0,False),
        ("sdr_prepass",(GLOBAL,LOCAL1,LOCAL2),203,(),(),0,0,False,1,False),
        ("hlg_neutral",(GLOBAL,LOCAL1,LOCAL2),600,(),(),0,0,False,2,True),
        ("hlg_color_1000",(v1,),1000,(),(),1,0,False,2,2),
        ("hlg_color_400",(v1,),400,(),(),1,0,False,2,2),
        ("sdr_final_neutral",(v1,),203,(),(),1,0,False,1,True),
        ("sdr_final_color",(v1,),203,(),(),1,0,False,1,2),
        ("max_degree",(replace(GLOBAL,kx=0,ky=0,anchors=(1023,)*15),),600,(),(),0,0,False,0,False),
    ]
    print("/* Independent authored-target reference; generated, not a normative corpus. */")
    print(f"enum {{ REF_W={W}, REF_H={H}, REF_N={N} }};")
    descriptors=[]
    for name,windows,peak,mg,tg,version,kind,flip,target,neutral in cases:
        wire=metadata(windows,peak,mg,tg,version)
        print(f"static const unsigned char {name}_wire[] = {{"+",".join(map(str,wire))+"};")
        inp=[]; expected=[]; final=[]
        coverage=[0,0,0]
        for y in range(H):
            for x in range(W):
                pixels,_=source(x,H-1-y if flip else y,kind,neutral)
                _,rgb=source(x,y,kind,neutral)
                result=reference(rgb,windows,peak,4000,mg,tg,x,y)
                assert all(math.isfinite(v) and 0<=v<=peak+1e-8 for v in result)
                inp.append(pixels); expected.append(tuple(pq(v) for v in result))
                if target==0:
                    final.append(tuple(pq(v) for v in result))
                elif target==2:
                    final.append(tuple(hlg_rgb(result,peak,.005)))
                elif target==1 and neutral:
                    final.append(tuple(sdr_rgb(result,peak,peak/1000)))
                if len(windows)==3:
                    a,b=(weight(w.geometry,x,y) for w in windows[1:])
                    coverage[0]+=a>0 and b>0
                    coverage[1]+=0<a<1 or 0<b<1
                    coverage[2]+=a==b==0
        if len(windows)==3:
            assert all(coverage), (name,coverage)
        emit_array(name+"_input",inp);emit_array(name+"_expected",expected)
        if final:
            emit_array(name+"_final",final)
        descriptors.append(f'    {{"{name}",{name}_wire,sizeof({name}_wire),{name}_input,{name}_expected,'+
                           (name+"_final" if final else "NULL")+
                           f",{peak},{kind},{int(flip)},{target},{version}}},")
    print("static const struct frame_reference {")
    print("    const char *name; const unsigned char *wire; size_t wire_size;")
    print("    const float (*input)[3], (*expected)[3], (*final)[3];")
    print("    unsigned peak, kind, flipped, target, version;")
    print("} frames[] = {")
    print("\n".join(descriptors));print("};")

def large_reference():
    # 4K raster with geometry defined in actual source pixels. The compact
    # fixture tiles the existing input; independent reference samples span
    # the entire image, rotated boundaries, overlap and both ellipse centers.
    width,height=3840,2160
    g1=(128,64,2700,1900,1350,970,35,300,1200,650,0)
    g2=(1100,100,3800,2100,2480,1170,120,350,1370,740,0)
    windows=(GLOBAL,replace(LOCAL1,geometry=g1),replace(LOCAL2,geometry=g2))
    peak=600
    wire=metadata(windows,peak,(),(),0)
    print(f"enum {{ LARGE_W={width}, LARGE_H={height} }};")
    print("static const unsigned char large_wire[] = {"+",".join(map(str,wire))+"};")
    points={(0,0),(width-1,height-1),(1350,970),(2480,1170),(1900,1050)}
    points.update((x,y) for y in range(0,height,53) for x in range(0,width,67))
    for w in windows[1:]:
        *_,cx,cy,angle,inner,outer,minor,mode=w.geometry
        for radius in (inner,outer):
            for degree in range(0,360,5):
                theta=math.radians(degree+angle)
                for offset in (-1,0,1):
                    x=round(cx+(radius+offset)*math.cos(theta))
                    y=round(cy+(radius+offset)*math.sin(theta))
                    if 0<=x<width and 0<=y<height:
                        points.add((x,y))
    print("static const struct { unsigned x,y; float pq[3]; } large_samples[] = {")
    coverage=[0,0,0]
    for x,y in sorted(points):
        _,rgb=source(x%W,y%H,0,False)
        out=reference(rgb,windows,peak,4000,(),(),x,y)
        a,b=(weight(w.geometry,x,y) for w in windows[1:])
        coverage[0]+=a>0 and b>0
        coverage[1]+=0<a<1 or 0<b<1
        coverage[2]+=a==b==0
        print("    {"+f"{x},{y},{{"+",".join(f"{pq(v):.12e}f" for v in out)+"}},")
    assert all(coverage),coverage
    print("};")

if __name__=="__main__":
    main()
    large_reference()
