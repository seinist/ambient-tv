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

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stream",type=Path)
    parser.add_argument("--fate",action="store_true",help="require known FATE metadata vector")
    args=parser.parse_args()
    size=args.stream.stat().st_size
    if not 0<size<=MAX_FILE:
        raise ValueError("file outside bounded corpus size")
    stream=args.stream.read_bytes()
    print("SHA256:",hashlib.sha256(stream).hexdigest())
    with tempfile.TemporaryDirectory() as tmp:
        exe=Path(tmp)/"probe"
        subprocess.run(["cc","-std=c11","-O2","-Wall","-Wextra","-Werror",
                        str(Path(__file__).with_name("hdr10plus_payload_probe.c")),
                        "-lm","-o",str(exe)],check=True)
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
