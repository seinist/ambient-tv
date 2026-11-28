#!/usr/bin/env python3
"""Read external, locally supplied HEVC Annex-B reference streams.

FATE source: https://fate-suite.ffmpeg.org/hevc/hdr10_plus_h265_sample.hevc
Reference: FFmpeg tests/ref/fate/hevc-hdr10-plus-metadata.
The sample contains a nonzero reserved version-1 marker; syntax must
survive, but the strict authoring validator must flag that marker.
This is a bitstream metadata test, not a decoded-image conformance test.
"""
import argparse
import hashlib
import itertools
from pathlib import Path
import re
import subprocess
import tempfile

REGISTERED = bytes.fromhex("b5003c000104")
MAX_FILE = 600 * 1024 * 1024

def rbsp(ebsp):
    result = bytearray()
    zeros = 0
    for i, v in enumerate(ebsp):
        if zeros >= 2 and v == 3 and i+1 < len(ebsp) and ebsp[i+1] <= 3:
            zeros = 0
            continue
        result.append(v)
        zeros = zeros+1 if v == 0 else 0
    return result

def registered_payloads(stream):
    starts = re.finditer(rb"\x00\x00(?:\x00)?\x01", stream)
    prev = next(starts,None)
    if prev is None:
        raise ValueError("Annex B start code missing")
    for marker in itertools.chain(starts,[None]):
        nal = stream[prev.end():marker.start() if marker else len(stream)]
        prev = marker
        if len(nal)<3 or ((nal[0]>>1)&63) not in (39,40):
            continue
        data = rbsp(nal[2:])
        p=0
        def number():
            nonlocal p
            value=0
            while p<len(data):
                v=data[p];p+=1;value+=v
                if v!=255:
                    return value
            raise ValueError("truncated SEI type or length")
        while p<len(data):
            if data[p]==0x80 and not any(data[p+1:]):
                break
            kind=number();size=number()
            if size>len(data)-p:
                raise ValueError("truncated SEI payload")
            if kind==4 and data[p:p+len(REGISTERED)]==REGISTERED:
                yield bytes(data[p:p+size])
            p+=size

def probe(exe, payload):
    r=subprocess.run([str(exe)],input=payload,capture_output=True,check=False,timeout=10)
    if r.returncode==2:
        return None
    r.check_returncode()
    rows=[tuple(map(int,line.split())) for line in r.stdout.decode("ascii").splitlines()]
    if not rows or len(rows)!=rows[0][1]+1:
        raise ValueError("invalid native probe result")
    return rows

def check_fate(result):
    assert result is not None, "production parser rejected FATE reference"
    header, window = result
    assert header == (1,1,400,0), header
    assert window[:6] == (3340,2870,2720,510,1,9), window
    assert window[6:24] == (1,30,5,2940,10,255,25,70,50,1340,
                             75,1600,90,1850,95,1950,99,2940), window
    assert window[24:] == (1,0,0,9,102,205,307,410,512,614,717,819,922), window

def synthetic_payload(version, windows, *, bad_average=False, bad_marker=False):
    """Independent bit writer for generated registered data (not player serialization)."""
    fields=[]
    def put(value,width):
        if not 0<=value<1<<width:
            raise ValueError("fixture field overflow")
        fields.append(f"{value:0{width}b}")
    put(version,8);put(windows,2)
    for i in range(1,windows):
        for v in (10*i,20*i,100*i,110*i,50*i,60*i):
            put(v,16)
        put(45*i,8)
        for v in (5*i,30*i,15*i):
            put(v,16)
        put(i-1,1)
    put(400,27)
    # Legacy optional 2x2 target and mastering grids.
    def grid():
        put(2,5);put(2,5)
        for v in (0,5,10,15):put(v,4)
    put(int(version==0),1)
    if version==0:grid()
    expected=[]
    for i in range(windows):
        peak=1000+100*i
        maxima=[peak,peak-100,peak-200]
        average=peak+1 if bad_average and i==0 else peak//2
        for v in maxima:put(v,17)
        put(average,17)
        if version==0:
            pairs=[(5,100+i),(10,200+i),(99,peak)] if i!=2 else [(50,300),(100,peak)]
            fraction=0
        else:
            pairs=list(zip((1,5,10,25,50,75,90,95,99),(10,0 if not bad_marker else 1,255,100,200,300,400,500,peak)))
            fraction=500
        put(len(pairs),4)
        for p,v in pairs:put(p,7);put(v,17)
        put(fraction,10)
        expected.append(tuple(maxima+[average,fraction,len(pairs)]+[v for pair in pairs for v in pair]))
    put(int(version==0),1)
    if version==0:grid()
    for i in range(windows):
        put(1,1);put(0,12);put(0,12);put(2,4);put(600,10);put(400,10)
        put(int(version==0 and i>0),1)
        if version==0 and i>0:put(8,6)
        expected[i]+=(1,0,0,2,600,400)
    bits="".join(fields)
    bits+="0"*((-len(bits))%8)
    return REGISTERED+int(bits,2).to_bytes(len(bits)//8,"big"),expected

def self_test(exe):
    for version,windows in [(0,1),(0,2),(0,3),(1,1)]:
        payload,expected=synthetic_payload(version,windows)
        assert probe(exe,payload)==[(version,windows,400,1)]+expected
        for end in range(1,len(payload)):
            assert probe(exe,payload[:end]) is None, (version,windows,end)
        assert probe(exe,payload+b"\x00") is None
        payload,expected=synthetic_payload(version,windows,bad_average=True)
        assert probe(exe,payload)==[(version,windows,400,0)]+expected
    payload,expected=synthetic_payload(1,1,bad_marker=True)
    assert probe(exe,payload)==[(1,1,400,0)]+expected
    # Syntactically decodable but forbidden v1 local windows.
    payload,expected=synthetic_payload(1,2)
    assert probe(exe,payload)==[(1,2,400,0)]+expected
    print("Synthetic versioned parser fixtures and exhaustive byte truncation passed")

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stream",type=Path,nargs="?")
    parser.add_argument("--self-test",action="store_true")
    parser.add_argument("--fate",action="store_true",help="require known FATE metadata vector")
    args=parser.parse_args()
    if args.stream is None and not args.self_test:
        parser.error("provide a stream or --self-test")
    with tempfile.TemporaryDirectory() as tmp:
        exe=Path(tmp)/"probe"
        subprocess.run(["cc","-std=c11","-O2","-Wall","-Wextra","-Werror",
                        str(Path(__file__).with_name("hdr10plus_payload_probe.c")),
                        "-lm","-o",str(exe)],check=True)
        if args.self_test:self_test(exe)
        if args.stream is None:return
        size=args.stream.stat().st_size
        if not 0<size<=MAX_FILE:
            raise ValueError("file outside bounded corpus size")
        stream=args.stream.read_bytes()
        print("SHA256:",hashlib.sha256(stream).hexdigest())
        seen=set();count=0;valid=0;strict=0;versions=set();fate_match=False
        for payload in registered_payloads(stream):
            count+=1
            if count>1_000_000:
                raise ValueError("corpus payload count limit")
            if payload in seen:
                continue
            if len(payload)>1024:
                raise ValueError("registered HDR10+ payload exceeds parser bound")
            if len(seen)>=100_000:
                raise ValueError("corpus unique payload limit")
            seen.add(payload)
            result=probe(exe,payload)
            if result is None:
                continue
            valid+=1;strict+=result[0][3];versions.add(result[0][0])
            if args.fate:
                try:
                    check_fate(result)
                    fate_match=True
                except AssertionError:
                    pass
        print(f"Registered payloads: {count}; unique: {len(seen)}; parsed: {valid}; "
              f"strict authoring: {strict}; versions: {sorted(versions)}")
        if not count or not valid:
            raise SystemExit("No usable registered HDR10+ payloads")
        if args.fate and not fate_match:
            raise SystemExit("Expected FATE metadata vector not found")
        if not args.fate and valid!=len(seen):
            raise SystemExit("Some HDR10+ payloads rejected; inspect corpus before claiming compatibility")

if __name__=="__main__":
    main()
