#!/usr/bin/env python3
"""Real HDR10+ frame comparison against an independently evaluated authored
curve or no-compression path. No renderer-derived expected pixels.
The AOM file is NC-ND: run only with permission, never publish derived frames.
DVB CC-BY input may be used with attribution; still keep frames temporary.
"""
import argparse
import hashlib
import json
import math
import pathlib
import re
import subprocess
import tempfile
import numpy as np

PREFIX = bytes.fromhex("b5003c000104")
AOM_SHA = "79d2bd39fe7583055548628c2c06649a14dee05742887eeb71959938007e72d9"

def run(*args):
    return subprocess.check_output(args, timeout=180)

def leb(data, p):
    value = 0
    for i in range(8):
        if p >= len(data):
            raise ValueError("truncated leb128")
        b = data[p]
        p += 1
        value |= (b & 127) << (7*i)
        if b < 128:
            return value, p
    raise ValueError("invalid leb128")

def av1_payloads(data):
    p = 0
    while p < len(data):
        h = data[p]
        p += 1
        if h & 0x81 or not h & 2:
            raise ValueError("not a low-overhead AV1 OBU")
        if h & 4:
            p += 1
        n, p = leb(data,p)
        if p+n > len(data):
            raise ValueError("truncated OBU")
        body = data[p:p+n]
        p += n
        if (h >> 3) & 15 == 5:
            kind, off = leb(body,0)
            if kind == 4 and body[off:].startswith(PREFIX):
                if body[-1] != 128:
                    raise ValueError("unexpected AV1 trailing bits")
                yield body[off:-1]

def hevc_payloads(data):
    starts = list(re.finditer(b"\\x00\\x00(?:\\x00)?\\x01",data))
    for i, marker in enumerate(starts):
        end = starts[i+1].start() if i+1<len(starts) else len(data)
        nal = data[marker.end():end]
        if len(nal)<3 or (nal[0]>>1)&63 not in (39,40):
            continue
        # Remove emulation prevention only from the escaped input, not
        # recursively from the resulting RBSP. Invalid escapes fail closed.
        rbsp = bytearray()
        zeroes = 0
        escaped = nal[2:]
        for j, b in enumerate(escaped):
            if zeroes >= 2 and b == 3:
                if j+1 >= len(escaped) or escaped[j+1] > 3:
                    raise ValueError("invalid HEVC emulation prevention")
                zeroes = 0
                continue
            rbsp.append(b)
            zeroes = zeroes+1 if b == 0 else 0
        rbsp = bytes(rbsp)
        p = 0
        while p<len(rbsp) and rbsp[p:] != b"\x80":
            kind = n = 0
            while p<len(rbsp) and rbsp[p]==255:
                kind+=255;p+=1
            if p==len(rbsp): raise ValueError("truncated SEI type")
            kind+=rbsp[p];p+=1
            while p<len(rbsp) and rbsp[p]==255:
                n+=255;p+=1
            if p==len(rbsp): raise ValueError("truncated SEI size")
            n+=rbsp[p];p+=1
            if p+n>len(rbsp): raise ValueError("truncated SEI")
            body=rbsp[p:p+n];p+=n
            if kind==4 and body.startswith(PREFIX): yield body

def metadata(payload):
    if not payload.startswith(PREFIX):
        raise ValueError("wrong provider")
    bits = "".join(f"{b:08b}" for b in payload[6:])
    p=0
    def take(n):
        nonlocal p
        if p+n>len(bits): raise ValueError("truncated HDR10+")
        v=int(bits[p:p+n],2);p+=n;return v
    version=take(8)
    if version not in (0,1) or take(2)!=1:
        raise ValueError("oracle supports one global window only")
    target=take(27)
    if take(1): raise ValueError("target grid not supported by this oracle")
    maxscl=[take(17) for _ in range(3)]
    average=take(17)
    cfd=[(take(7),take(17)) for _ in range(take(4))]
    fraction=take(10)
    if take(1): raise ValueError("mastering grid not supported by this oracle")
    curve=None
    if take(1):
        kx,ky=take(12)/4095,take(12)/4095
        anchors=[take(10)/1023 for _ in range(take(4))]
        curve=(kx,ky,anchors)
    if take(1): raise ValueError("saturation not supported by this oracle")
    if len(bits)-p>7 or "1" in bits[p:]: raise ValueError("invalid padding")
    peak=max(maxscl)/10
    if not peak and cfd: peak=cfd[-1][1]/10
    if not 0<peak<=10000: raise ValueError("invalid source peak")
    if curve is not None and not 100<=target<=10000:
        raise ValueError("authored target outside supported HDR range")
    return dict(version=version,peak=peak,target=target,curve=curve,maxscl=maxscl,
        average=average,cfd=cfd,fraction=fraction)

def eotf(code):
    x=np.power(np.clip(code,0,1),32/2523)
    return 10000*np.power(np.maximum(x-3424/4096,0)/(2413/128-(2392/128)*x),16384/2610)

def oetf(nits):
    x=np.power(np.clip(nits/10000,0,1),2610/16384)
    return np.power((3424/4096+(2413/128)*x)/(1+(2392/128)*x),2523/32)

def expected(rgb,m):
    linear=eotf(rgb)
    peak=m["peak"]
    v=linear.max(axis=2)
    s=np.minimum(v/peak,1)
    if m["curve"] is None:
        # No authored mapping. Characterize a 10000-nit display so no
        # creative/adaptive tone-map policy is needed, only source clipping.
        mapped=np.minimum(v,peak)
        target=10000
    else:
        target=m["target"]
        kx,ky,anchors=m["curve"]
        if kx==1:
            y=ky*s
        else:
            t=np.clip((s-kx)/(1-kx),0,1)
            points=[0]+anchors+[1]
            degree=len(points)-1
            bezier=sum(math.comb(degree,j)*a*t**j*(1-t)**(degree-j)
                       for j,a in enumerate(points))
            y=ky+(1-ky)*bezier
            if kx>0: y=np.where(s<kx,ky*s/kx,y)
        mapped=y*target
    normalized=np.minimum(linear/peak,1)
    result=normalized*np.divide(mapped,s,out=np.zeros_like(s),where=s>0)[:,:,None]
    return oetf(result),target

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("source",type=pathlib.Path)
    ap.add_argument("--kind",choices=("aom","dvb"),required=True)
    ap.add_argument("--sha256",required=True)
    ap.add_argument("--gpu-runner",type=pathlib.Path,required=True)
    ap.add_argument("--report",type=pathlib.Path,required=True)
    ap.add_argument("--permission-confirmed",action="store_true")
    args=ap.parse_args()
    with args.source.open("rb") as f: digest=hashlib.file_digest(f,"sha256").hexdigest()
    if digest!=args.sha256.lower(): raise ValueError("source hash mismatch")
    if args.kind=="aom" and (digest!=AOM_SHA or not args.permission_confirmed):
        raise ValueError("AOM fixture requires pinned source and confirmed NC-ND-compatible use")
    with tempfile.TemporaryDirectory() as td:
        tmp=pathlib.Path(td)
        av1=args.kind=="aom"
        elementary=tmp/("frame.obu" if av1 else "frame.hevc")
        command=["ffmpeg","-v","error","-i",str(args.source),"-map","0:v:0","-c","copy","-frames:v","1"]
        if not av1: command+=["-bsf:v","hevc_mp4toannexb"]
        run(*command,"-f","obu" if av1 else "hevc","-y",str(elementary))
        data=elementary.read_bytes()
        payloads=list(av1_payloads(data) if av1 else hevc_payloads(data))
        if len(payloads)!=1: raise ValueError("first AU must contain exactly one HDR10+ payload")
        payload=payloads[0]
        m=metadata(payload)
        # Decode the SAME isolated access unit, avoiding display/decode-order
        # pairing ambiguity. If it is not independently decodable, fail.
        probe=json.loads(run("ffprobe","-v","error","-show_frames","-select_streams","v:0","-of","json",str(elementary)))
        frames=probe["frames"]
        if len(frames)!=1: raise ValueError("exactly one decoded frame required")
        frame=frames[0]
        if frame.get("color_primaries")!="bt2020" or frame.get("color_transfer")!="smpte2084":
            raise ValueError("not BT.2020 PQ")
        w,h=frame["width"],frame["height"]
        if w*h>16777216: raise ValueError("frame too large")
        raw=run("ffmpeg","-v","error","-i",str(elementary),"-frames:v","1","-vf",
            "zscale=matrixin=2020_ncl:rangein=limited:primariesin=2020:transferin=smpte2084:matrix=gbr:range=full:primaries=2020:transfer=smpte2084,format=gbrpf32le",
            "-f","rawvideo","-pix_fmt","gbrpf32le","-")
        planes=np.frombuffer(raw,dtype="<f4").reshape(3,h,w)
        rgb=np.clip(planes[[2,0,1]].transpose(1,2,0),0,1).astype("<f4")
        # Double precision reference, bounded row blocks to keep 4K memory small.
        inp,exp,meta=tmp/"input.rgb",tmp/"reference.rgb",tmp/"metadata.t35"
        rgb.tofile(inp);meta.write_bytes(payload)
        target=0
        with exp.open("wb") as out:
            for start in range(0,h,64):
                ref,target=expected(rgb[start:start+64].astype(np.float64),m)
                ref.astype("<f4").tofile(out)
        result=subprocess.run([str(args.gpu_runner.resolve()),"hdr10plus",str(w),str(h),
            str(inp),str(meta),str(exp),"0.0025","0.005",str(target),"10000"],
            capture_output=True,text=True,timeout=180)
        print(result.stdout,end="");print(result.stderr,end="")
        metrics=[json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        report={"source_kind":args.kind,"source_sha256":digest,"frame":0,"width":w,"height":h,
            "metadata_sha256":hashlib.sha256(payload).hexdigest(),"reference":"independent-double-precision",
            "target_nits":target,"metadata":m,"metrics":metrics,"passed":result.returncode==0 and bool(metrics)}
        args.report.write_text(json.dumps(report,indent=2)+"\n")
        if not report["passed"]: raise SystemExit(1)

def self_test():
    # A T.35 payload with a genuine zero-byte run, escaped for HEVC only.
    payload=PREFIX+bytes.fromhex("01000001020000030304")
    body=bytes((4,len(payload)))+payload+bytes((128,))
    escaped=bytearray()
    zeroes=0
    for b in body:
        if zeroes>=2 and b<=3:
            escaped.append(3);zeroes=0
        escaped.append(b)
        zeroes=zeroes+1 if b==0 else 0
    stream=bytes.fromhex("000000014e01")+escaped
    assert list(hevc_payloads(stream))==[payload]
    obu_body=bytes((4,))+payload+bytes((128,))
    obu=bytes((0x2a,len(obu_body)))+obu_body
    assert list(av1_payloads(obu))==[payload]
    code=np.array([0.0,0.25,0.5,0.75,1.0],dtype=np.float64)
    assert np.max(np.abs(oetf(eotf(code))[1:]-code[1:]))<1e-10
    print("Public reference intake: AV1 OBU, HEVC RBSP and PQ self-tests passed")

if __name__=="__main__":
    import sys
    if sys.argv[1:]==["--self-test"]:
        self_test()
    else:
        main()
