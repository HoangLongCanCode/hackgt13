#!/usr/bin/env python3
"""verify_video.py - stdlib-only sanity check for .mov/.mp4 files (no ffmpeg).

Checks that the file has a 'ftyp' atom (or legacy QuickTime 'wide'/'mdat'
layout) and a 'moov' atom, and reports duration, video codec, resolution,
frame count and fps from mvhd/tkhd/stsd/stts/stsz.

  python tools/bdd/verify_video.py "data/bdd100k/videos/val/*.mov" [--json out.json]   # from perception_engine/
"""
import json
import math
import struct
import sys
from pathlib import Path

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"udta", b"edts", b"dinf"}


def atoms(buf, start=0, end=None):
    end = len(buf) if end is None else end
    p = start
    while p + 8 <= end:
        size, typ = struct.unpack(">I4s", buf[p:p + 8])
        hdr = 8
        if size == 1:
            size = struct.unpack(">Q", buf[p + 8:p + 16])[0]
            hdr = 16
        elif size == 0:
            size = end - p
        if size < hdr or p + size > end:
            yield typ, p, hdr, end - p, False     # truncated
            return
        yield typ, p, hdr, size, True
        p += size


def top_level(path):
    """Walk top-level atoms by seeking (does not read mdat)."""
    out = []
    with open(path, "rb") as fh:
        fh.seek(0, 2)
        total = fh.tell()
        p = 0
        while p + 8 <= total:
            fh.seek(p)
            h = fh.read(16)
            size, typ = struct.unpack(">I4s", h[:8])
            hdr = 8
            if size == 1:
                size = struct.unpack(">Q", h[8:16])[0]
                hdr = 16
            elif size == 0:
                size = total - p
            ok = size >= hdr and p + size <= total
            out.append((typ.decode("latin-1"), p, size, ok))
            if not ok:
                break
            p += size
        return out, total


def parse_moov(path, off, size):
    with open(path, "rb") as fh:
        fh.seek(off)
        moov = fh.read(size)
    info = {"tracks": []}

    def walk(s, e, trak=None):
        for typ, p, hdr, sz, ok in atoms(moov, s, e):
            body = p + hdr
            if typ == b"mvhd":
                v = moov[body]
                if v == 1:
                    ts, dur = struct.unpack(">IQ", moov[body + 20:body + 32])
                else:
                    ts, dur = struct.unpack(">II", moov[body + 12:body + 20])
                info["timescale"], info["duration_units"] = ts, dur
                info["duration_s"] = round(dur / ts, 3) if ts else None
            elif typ == b"trak":
                t = {}
                info["tracks"].append(t)
                walk(body, p + sz, t)
            elif typ == b"tkhd" and trak is not None:
                w, h = struct.unpack(">II", moov[p + sz - 8:p + sz])
                trak["width"], trak["height"] = w >> 16, h >> 16
                a, b = struct.unpack(">ii", moov[p + sz - 44:p + sz - 36])   # matrix a, b (16.16)
                rot = round(math.degrees(math.atan2(b / 65536, a / 65536))) % 360
                trak["matrix_rotation_deg"] = rot
                if rot in (90, 270):
                    trak["display_wh"] = [h >> 16, w >> 16]
                else:
                    trak["display_wh"] = [w >> 16, h >> 16]
            elif typ == b"mdhd" and trak is not None:
                v = moov[body]
                if v == 1:
                    ts, dur = struct.unpack(">IQ", moov[body + 20:body + 32])
                else:
                    ts, dur = struct.unpack(">II", moov[body + 12:body + 20])
                trak["timescale"], trak["duration_s"] = ts, round(dur / ts, 3) if ts else None
            elif typ == b"hdlr" and trak is not None and "handler" not in trak:
                # first hdlr in a trak is the media handler (QuickTime 'mhlr'); the one in
                # minf is the data handler ('dhlr' -> 'alis'/'url ') and is ignored
                trak["handler"] = moov[body + 8:body + 12].decode("latin-1")
            elif typ == b"stsd" and trak is not None:
                trak["codec"] = moov[body + 12:body + 16].decode("latin-1")
            elif typ == b"stsz" and trak is not None:
                trak["samples"] = struct.unpack(">I", moov[body + 8:body + 12])[0]
            elif typ in (b"meta", b"\xa9xyz") or (typ == b"udta"):
                info.setdefault("metadata_atoms", []).append(typ.decode("latin-1"))
                if typ == b"udta":
                    walk(body, p + sz, trak)
            elif typ in CONTAINERS:
                walk(body, p + sz, trak)

    walk(0, len(moov))
    for t in info["tracks"]:
        if t.get("samples") and t.get("duration_s"):
            t["fps"] = round(t["samples"] / t["duration_s"], 3)
    return info


def verify(path):
    tops, total = top_level(path)
    names = [t[0] for t in tops]
    res = {"path": str(path), "bytes": total, "top_atoms": names,
           "ftyp": "ftyp" in names, "moov": "moov" in names,
           "complete": all(t[3] for t in tops)}
    if tops and tops[0][0] == "ftyp":
        with open(path, "rb") as fh:
            fh.seek(8)
            res["brand"] = fh.read(4).decode("latin-1")
    for typ, off, size, ok in tops:
        if typ == "moov" and ok:
            res.update(parse_moov(path, off, size))
    res["ok"] = res["moov"] and res["complete"] and (res["ftyp"] or names[:1] in (["wide"], ["mdat"]))
    return res


def main(argv):
    out_json = None
    if "--json" in argv:
        i = argv.index("--json")
        out_json = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    results = []
    for a in argv:
        for p in sorted(Path().glob(a)) if any(c in a for c in "*?") else [Path(a)]:
            r = verify(p)
            results.append(r)
            v = next((t for t in r.get("tracks", []) if t.get("handler") == "vide"), {})
            print(f"{'OK ' if r['ok'] else 'BAD'} {p.name}  {r['bytes']:,} B  atoms={r['top_atoms']}  "
                  f"brand={r.get('brand')}  dur={r.get('duration_s')}s  "
                  f"{v.get('codec')} coded {v.get('width')}x{v.get('height')} rot={v.get('matrix_rotation_deg')} "
                  f"display={v.get('display_wh')}  frames={v.get('samples')} fps={v.get('fps')}"
                  f"  tracks={[t.get('handler') for t in r.get('tracks', [])]}")
    if out_json:
        json.dump(results, open(out_json, "w", encoding="utf-8"), indent=2)
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
