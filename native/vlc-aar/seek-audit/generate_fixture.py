#!/usr/bin/env python3
"""Generate a small seekable fMP4 with version-1 emsg inside every moof.
All offsets are rebuilt, not patched by searching arbitrary bytes."""
import pathlib
import struct
import subprocess
import sys
import tempfile

def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind) + payload

def boxes(data):
    pos = 0
    while pos < len(data):
        size, kind = struct.unpack_from(">I4s", data, pos)
        if size < 8 or pos + size > len(data):
            raise ValueError("invalid box")
        yield kind, data[pos+8:pos+size]
        pos += size

def fix_traf(payload, extra):
    result = []
    for kind, body in boxes(payload):
        if kind == b"tfhd":
            flags = int.from_bytes(body[:4], "big") & 0xffffff
            assert flags & 0x020000 and not flags & 1
        if kind == b"trun":
            flags = int.from_bytes(body[:4], "big") & 0xffffff
            assert flags & 1
            old = struct.unpack_from(">i", body, 8)[0]
            body = body[:8] + struct.pack(">i", old + extra) + body[12:]
        result.append(box(kind, body))
    return b"".join(result)

def main(path):
    with tempfile.TemporaryDirectory() as tmp:
        raw = pathlib.Path(tmp) / "base.mp4"
        subprocess.run(["ffmpeg","-v","error","-f","lavfi","-i","testsrc2=size=128x72:rate=10",
            "-t","12","-an","-c:v","libx264","-pix_fmt","yuv420p","-g","10","-keyint_min","10",
            "-sc_threshold","0","-bf","0","-movflags","+empty_moov+default_base_moof+frag_keyframe+skip_trailer",
            "-y",str(raw)],check=True)
        init, fragments = [], []
        pending = None
        for kind, body in boxes(raw.read_bytes()):
            if kind in (b"ftyp", b"moov"):
                init.append(box(kind,body))
            elif kind == b"moof":
                assert pending is None
                i = len(fragments)
                event = box(b"emsg",struct.pack(">IIQII",0x01000000,1000,i*1000+500,100,i+1)
                    + b"urn:ambient:seek-audit\0fixture\0" + str(i).encode())
                fixed = b"".join(box(k,fix_traf(b,len(event)) if k==b"traf" else b) for k,b in boxes(body))
                pending = box(b"moof",fixed+event)
            elif kind == b"mdat":
                assert pending is not None
                fragments.append(pending+box(kind,body))
                pending = None
            else:
                raise ValueError(f"unexpected {kind!r}")
        assert pending is None and len(fragments)==12 and len(init)==2
        # Flat sidx points at each moof with precise one-second durations.
        sidx = box(b"sidx",struct.pack(">IIIIIHH",0,1,1000,0,0,0,len(fragments))
                   + b"".join(struct.pack(">III",len(f),1000,0x90000000) for f in fragments))
        path.write_bytes(b"".join(init)+sidx+b"".join(fragments))
        subprocess.run(["ffmpeg","-v","error","-i",str(path),"-f","null","-"],check=True)
if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
