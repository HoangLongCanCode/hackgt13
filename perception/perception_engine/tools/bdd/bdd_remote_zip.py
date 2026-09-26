#!/usr/bin/env python3
"""bdd_remote_zip.py - pull individual members out of huge remote ZIP archives
with HTTP Range requests, without downloading the archive.

Stdlib only (urllib, zlib, struct, csv). Written for the Knuckle Sandwich
Robotics Inc. (KSR) perception prototype, to fetch a handful of BDD100K
videos / images / labels for smoke tests.

Supported layouts
  * a single remote .zip (any HTTP server that honours Range, e.g. HF /resolve/)
  * a zip that was split byte-for-byte into N parts (the built-in 'videos' source:
    HF linxxx3/bdd100k_videos, bdd100k_videos.zip.part-00000..00018); a range that
    crosses a part boundary is split into one request per part
  * ZIP64 (EOCD64 locator + record, zip64 extra 0x0001 in the central directory)
  * methods 0 (stored) and 8 (deflate); CRC32 is always verified

Usage (from perception_engine/; `python` = the project venv's interpreter, stdlib is enough)
  python tools/bdd/bdd_remote_zip.py sources
  python tools/bdd/bdd_remote_zip.py index [--source videos]           # fetch + cache CD
  python tools/bdd/bdd_remote_zip.py list  --split val --limit 20      # default source=videos
  python tools/bdd/bdd_remote_zip.py list  --grep b1c66a42 --source videos
  python tools/bdd/bdd_remote_zip.py get bdd100k/videos/100k/val/b1c66a42-6f7d68ca.mov --out data/bdd100k/videos/val --flat
  python tools/bdd/bdd_remote_zip.py get b1c66a42-6f7d68ca --by-id --out data/bdd100k/videos/val --flat
  python tools/bdd/bdd_remote_zip.py get --from-file names.txt --out DIR [--flat | --strip N]
  python tools/bdd/bdd_remote_zip.py --source solesensei list --grep labels/        # other mirrors
  python tools/bdd/bdd_remote_zip.py --url https://host/x.zip list --limit 50   # ad-hoc zip

The central directory is fetched once and cached as a CSV under <data>/bdd100k/index/
(<data> = KSR_DATA_DIR, default perception_engine/data). scripts/fetch_bdd_samples.py wraps this tool
to rebuild the whole KSR sample set from a pinned member list.
Requests are sequential with retries + exponential backoff (be gentle with HF).
"""
from __future__ import annotations

import argparse
import csv
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.request
import zlib
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent                  # perception_engine/tools/bdd
# BDD100K sample root = <KSR_DATA_DIR or perception_engine/data>/bdd100k (index CSVs are cached in its index/)
ROOT = Path(os.environ.get("KSR_DATA_DIR") or HERE.parents[1] / "data").absolute() / "bdd100k"
INDEX_DIR = ROOT / "index"
UA = "ksr-perception-smoketest/0.1 (+stdlib urllib; range reads)"

HF = "https://huggingface.co/datasets"
PART_SIZE = 107_374_182_400             # 100 GiB
VIDEOS_TOTAL = 1_969_155_915_058

# Known sources.  'parts' = list of (url, size); size None -> probed with a Range request.
SOURCES = {
    "videos": {
        "desc": "ORIGINAL bdd100k_videos.zip (1.97 TB, 100k .mov) split into 19 x 100 GiB parts",
        "parts": [
            (f"{HF}/linxxx3/bdd100k_videos/resolve/main/bdd100k_videos/bdd100k_videos.zip.part-{i:05d}",
             PART_SIZE if i < 18 else VIDEOS_TOTAL - 18 * PART_SIZE)
            for i in range(19)
        ],
        "index": "videos_zip_index.csv",
    },
    "hirundo_val": {
        "desc": "hirundo-io/bdd100k-val: BDD100K val images + labels (570 MB zip)",
        "parts": [(f"{HF}/hirundo-io/bdd100k-val/resolve/main/bdd100k_val_hirundo.zip", None)],
        "index": "hirundo_val_zip_index.csv",
    },
    "solesensei": {
        "desc": "Xoner1/bdd100k-client: BDD100k.zip (8.17 GB, same size as the Kaggle solesensei/solesensei_bdd100k "
                "archive; 2inf/bdd100k has an identical-size copy but is gated)",
        "parts": [(f"{HF}/Xoner1/bdd100k-client/resolve/main/BDD100k.zip", None)],
        "index": "solesensei_zip_index.csv",
    },
    "images100k": {
        "desc": "Xing1210/bdd100k: bdd100k_images_100k.zip (5.7 GB; train/val/test 100k images)",
        "parts": [(f"{HF}/Xing1210/bdd100k/resolve/main/bdd100k_images_100k.zip", None)],
        "index": "images100k_zip_index.csv",
    },
    "mot_labels": {
        "desc": "jaffe03195/bdd100k_sot: bdd100k.zip (5.4 GB) - labels/box_track_20/{train,val}/*.json (MOT GT), "
                "labels/100k/{train,val}/*.json (per-image 2018 labels), images/track/val (MOT val frames)",
        "parts": [(f"{HF}/jaffe03195/bdd100k_sot/resolve/main/bdd100k.zip", None)],
        "index": "mot_labels_zip_index.csv",
    },
    "mot_val1": {
        "desc": "vanthanh/BDD100kMOT: images20-track-val-1.zip (5 GB; MOT val frames @5fps)",
        "parts": [(f"{HF}/vanthanh/BDD100kMOT/resolve/main/images20-track-val-1.zip", None)],
        "index": "mot_val1_zip_index.csv",
    },
}

INDEX_FIELDS = ["name", "split", "local_header_offset", "compressed_size",
                "uncompressed_size", "method", "crc32"]


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------- HTTP
class Fetcher:
    """Sequential Range reads with retry/backoff; tracks bytes downloaded."""

    def __init__(self, retries: int = 6, timeout: int = 120, pause: float = 0.15):
        self.retries, self.timeout, self.pause = retries, timeout, pause
        self.bytes = 0
        self.requests = 0

    def _open(self, url: str, headers: dict):
        req = urllib.request.Request(url, headers={"User-Agent": UA, **headers})
        return urllib.request.urlopen(req, timeout=self.timeout)

    def total_size(self, url: str) -> int:
        for attempt in range(self.retries):
            try:
                with self._open(url, {"Range": "bytes=0-0"}) as r:
                    r.read(1)                  # never drain a 200 (Range ignored) body
                    cr = r.headers.get("Content-Range", "")
                    if "/" in cr:
                        return int(cr.rsplit("/", 1)[1])
                    cl = r.headers.get("Content-Length")
                    if r.status == 200 and cl:
                        return int(cl)
                    raise IOError(f"no Content-Range for {url}")
            except (urllib.error.URLError, IOError, TimeoutError, ConnectionError) as e:
                self._backoff(attempt, e)
        raise IOError(f"could not size {url}")

    def get(self, url: str, start: int, length: int, sink=None) -> bytes | None:
        """GET bytes [start, start+length).  If sink is given, stream chunks into
        sink(bytes) and return None; resumes mid-stream on failure."""
        if length <= 0:
            return b"" if sink is None else None
        got = 0
        buf = bytearray() if sink is None else None
        attempt = 0
        while got < length:
            a, b = start + got, start + length - 1
            try:
                time.sleep(self.pause)
                self.requests += 1
                with self._open(url, {"Range": f"bytes={a}-{b}"}) as r:
                    if r.status != 206:
                        raise IOError(f"expected 206, got {r.status} (server ignores Range?)")
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        chunk = chunk[: length - got]
                        got += len(chunk)
                        self.bytes += len(chunk)
                        if sink is None:
                            buf += chunk
                        else:
                            sink(chunk)
                        if got >= length:
                            break
                if got < length:
                    raise IOError(f"short read {got}/{length}")
            except (urllib.error.URLError, IOError, TimeoutError, ConnectionError, OSError) as e:
                if attempt >= self.retries:
                    raise
                self._backoff(attempt, e)
                attempt += 1
        return bytes(buf) if sink is None else None

    def _backoff(self, attempt, err):
        if isinstance(err, urllib.error.HTTPError) and 400 <= err.code < 500 and err.code not in (408, 429):
            raise err                          # 401/403/404: retrying will not help
        wait = min(60, 2 ** attempt)
        if isinstance(err, urllib.error.HTTPError) and err.code == 429:
            wait = max(wait, 30)
        log(f"  [retry {attempt + 1}] {err!r}; sleeping {wait}s")
        time.sleep(wait)


# --------------------------------------------------------------------------- ZIP
@dataclass
class Entry:
    name: str
    split: str
    local_header_offset: int
    compressed_size: int
    uncompressed_size: int
    method: int
    crc32: int


def guess_split(name: str) -> str:
    """'bdd100k/videos/val/x.mov' -> 'val'; also matches tokens like 'images20-track-val-1'."""
    comps = [c.lower() for c in name.replace("\\", "/").split("/") if c]
    alias = {"train": "train", "val": "val", "validation": "val", "test": "test"}
    for c in comps[:-1] or comps:
        if c in alias:
            return alias[c]
    for c in comps:
        for tok in re.split(r"[-_.]", c):
            if tok in alias:
                return alias[tok]
    return ""


class RemoteZip:
    def __init__(self, parts, index_path: Path, fetcher: Fetcher | None = None):
        self.f = fetcher or Fetcher()
        self.parts = []
        for url, size in parts:
            if size is None:
                size = self.f.total_size(url)
            self.parts.append((url, size))
        self.total = sum(s for _, s in self.parts)
        self.index_path = index_path
        self._entries: list[Entry] | None = None

    # -- random access over the concatenation of parts
    def _segments(self, offset: int, length: int):
        pos = 0
        for url, size in self.parts:
            if offset < pos + size and offset + length > pos:
                a = max(offset, pos)
                b = min(offset + length, pos + size)
                yield url, a - pos, b - a
            pos += size

    def read(self, offset: int, length: int) -> bytes:
        if offset < 0 or offset + length > self.total:
            raise ValueError("read beyond archive")
        return b"".join(self.f.get(u, o, n) for u, o, n in self._segments(offset, length))

    def stream(self, offset: int, length: int, sink):
        for u, o, n in self._segments(offset, length):
            self.f.get(u, o, n, sink=sink)

    # -- central directory
    def entries(self, refresh: bool = False) -> list[Entry]:
        if self._entries is not None and not refresh:
            return self._entries
        if self.index_path.exists() and not refresh:
            self._entries = load_index(self.index_path)
            return self._entries
        self._entries = self._fetch_central_directory()
        save_index(self.index_path, self._entries)
        log(f"index cached -> {self.index_path} ({len(self._entries)} entries)")
        return self._entries

    def _fetch_central_directory(self) -> list[Entry]:
        tail_len = min(self.total, 65536 + 22 + 20 + 56)
        tail_off = self.total - tail_len
        tail = self.read(tail_off, tail_len)
        i = tail.rfind(b"PK\x05\x06")
        if i < 0:
            raise IOError("EOCD not found (not a zip, or comment > 64 KiB)")
        (_, disk, cd_disk, n_disk, n_total, cd_size, cd_off, clen) = struct.unpack(
            "<IHHHHIIH", tail[i:i + 22])
        # ZIP64?
        j = tail.rfind(b"PK\x06\x07", 0, i)
        if j >= 0 and j == i - 20:
            (_, _, eocd64_off, _) = struct.unpack("<IIQI", tail[j:j + 20])
            if eocd64_off >= tail_off:
                rec = tail[eocd64_off - tail_off: eocd64_off - tail_off + 56]
            else:
                rec = self.read(eocd64_off, 56)
            if rec[:4] != b"PK\x06\x06":
                raise IOError("bad EOCD64 record")
            (_, _, _, _, _, _, n_disk, n_total, cd_size, cd_off) = struct.unpack(
                "<IQHHIIQQQQ", rec)
            log(f"ZIP64: {n_total} entries, CD {cd_size:,} B @ {cd_off:,}")
        else:
            log(f"ZIP: {n_total} entries, CD {cd_size:,} B @ {cd_off:,}")
        if cd_off >= tail_off:
            cd = tail[cd_off - tail_off: cd_off - tail_off + cd_size]
        else:
            log(f"fetching central directory ({cd_size / 1e6:.1f} MB) ...")
            cd = self.read(cd_off, cd_size)
        return parse_central_directory(cd, n_total)

    # -- extraction
    def local_data_offset(self, e: Entry) -> int:
        h = self.read(e.local_header_offset, 30)
        return self._data_offset_from_header(e, h)

    @staticmethod
    def _data_offset_from_header(e: Entry, h: bytes) -> int:
        if h[:4] != b"PK\x03\x04":
            raise IOError(f"bad local header for {e.name}")
        fnlen, exlen = struct.unpack("<HH", h[26:30])
        return e.local_header_offset + 30 + fnlen + exlen

    def extract(self, e: Entry, out_path: Path, chunk: int = 16 << 20) -> int:
        """Stream one member to out_path; verifies CRC32 and size.  Returns bytes written."""
        if e.method not in (0, 8):
            raise NotImplementedError(f"compression method {e.method} ({e.name})")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = out_path.with_suffix(out_path.suffix + ".part")
        # one request for header (+ first bit of data when member is small)
        guess = 30 + len(e.name.encode()) + 64
        head_len = min(guess + min(e.compressed_size, 1 << 20), self.total - e.local_header_offset)
        head = self.read(e.local_header_offset, head_len)
        data_off = self._data_offset_from_header(e, head)
        skip = data_off - e.local_header_offset
        crc = 0
        written = 0
        dec = zlib.decompressobj(-15) if e.method == 8 else None
        with open(tmp, "wb") as fh:
            def sink(b: bytes):
                nonlocal crc, written
                if dec is not None:
                    b = dec.decompress(b)
                if b:
                    crc = zlib.crc32(b, crc)
                    written += len(b)
                    fh.write(b)

            have = head[skip:skip + e.compressed_size]
            sink(have)
            rest = e.compressed_size - len(have)
            pos = data_off + len(have)
            while rest > 0:
                n = min(chunk, rest)
                self.stream(pos, n, sink)
                pos += n
                rest -= n
            if dec is not None:
                tail = dec.flush()
                if tail:
                    crc = zlib.crc32(tail, crc)
                    written += len(tail)
                    fh.write(tail)
        if written != e.uncompressed_size or (crc & 0xFFFFFFFF) != e.crc32:
            tmp.unlink(missing_ok=True)
            raise IOError(f"verify failed {e.name}: size {written}/{e.uncompressed_size} "
                          f"crc {crc:08x}/{e.crc32:08x}")
        os.replace(tmp, out_path)
        return written

    def extract_many(self, entries: list[Entry], out_dir: Path, flat: bool,
                     strip: int = 0, gap: int = 1 << 20, max_block: int = 48 << 20,
                     skip_existing: bool = True) -> list[tuple[Entry, Path, int]]:
        """Extract several (small) members, coalescing nearby ones into one Range
        request.  Big members fall back to streaming extract()."""
        todo = []
        for e in sorted(entries, key=lambda x: x.local_header_offset):
            p = dest_path(e, out_dir, flat, strip)
            if skip_existing and p.exists() and p.stat().st_size == e.uncompressed_size:
                continue
            todo.append((e, p))
        done = []
        i = 0
        while i < len(todo):
            e, p = todo[i]
            span_end = e.local_header_offset + 30 + len(e.name.encode()) + 256 + e.compressed_size
            if e.compressed_size > max_block // 2:
                n = self.extract(e, p)
                done.append((e, p, n))
                log(f"  ok {e.name} ({n:,} B)")
                i += 1
                continue
            group = [(e, p)]
            k = i + 1
            while k < len(todo):
                e2, p2 = todo[k]
                end2 = e2.local_header_offset + 30 + len(e2.name.encode()) + 256 + e2.compressed_size
                if e2.local_header_offset - span_end > gap or end2 - e.local_header_offset > max_block:
                    break
                group.append((e2, p2))
                span_end = max(span_end, end2)
                k += 1
            span_end = min(span_end, self.total)
            blob = self.read(e.local_header_offset, span_end - e.local_header_offset)
            for eg, pg in group:
                rel = eg.local_header_offset - e.local_header_offset
                h = blob[rel:rel + 30]
                doff = self._data_offset_from_header(eg, h) - e.local_header_offset
                if doff + eg.compressed_size > len(blob):
                    n = self.extract(eg, pg)          # extra field longer than guessed
                else:
                    raw = blob[doff:doff + eg.compressed_size]
                    data = zlib.decompress(raw, -15) if eg.method == 8 else raw
                    if len(data) != eg.uncompressed_size or zlib.crc32(data) != eg.crc32:
                        raise IOError(f"verify failed {eg.name}")
                    pg.parent.mkdir(parents=True, exist_ok=True)
                    pg.write_bytes(data)
                    n = len(data)
                done.append((eg, pg, n))
            log(f"  ok {len(group)} member(s) in one request ({len(blob) / 1e6:.1f} MB)")
            i = k
        return done


def parse_central_directory(cd: bytes, expected: int | None = None) -> list[Entry]:
    out = []
    p = 0
    n = len(cd)
    while p + 46 <= n and cd[p:p + 4] == b"PK\x01\x02":
        (_, _vm, _vn, _flags, method, _t, _d, crc, csize, usize, fnlen, exlen, cmlen,
         disk, _ia, _ea, lho) = struct.unpack("<IHHHHHHIIIHHHHHII", cd[p:p + 46])
        name = cd[p + 46:p + 46 + fnlen].decode("utf-8", "replace")
        extra = cd[p + 46 + fnlen:p + 46 + fnlen + exlen]
        if 0xFFFFFFFF in (csize, usize, lho) or disk == 0xFFFF:
            q = 0
            while q + 4 <= len(extra):
                hid, hlen = struct.unpack("<HH", extra[q:q + 4])
                body = extra[q + 4:q + 4 + hlen]
                if hid == 0x0001:
                    vals = []
                    for k in range(0, len(body) - len(body) % 8, 8):
                        vals.append(struct.unpack("<Q", body[k:k + 8])[0])
                    it = iter(vals)
                    if usize == 0xFFFFFFFF:
                        usize = next(it)
                    if csize == 0xFFFFFFFF:
                        csize = next(it)
                    if lho == 0xFFFFFFFF:
                        lho = next(it)
                    break
                q += 4 + hlen
        out.append(Entry(name, guess_split(name), lho, csize, usize, method, crc))
        p += 46 + fnlen + exlen + cmlen
    if expected is not None and len(out) != expected:
        log(f"warning: parsed {len(out)} CD entries, EOCD says {expected}")
    return out


def save_index(path: Path, entries: list[Entry]):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(INDEX_FIELDS)
        for e in entries:
            w.writerow([e.name, e.split, e.local_header_offset, e.compressed_size,
                        e.uncompressed_size, e.method, f"{e.crc32:08x}"])
    os.replace(tmp, path)


def load_index(path: Path) -> list[Entry]:
    with open(path, newline="", encoding="utf-8") as fh:
        r = csv.DictReader(fh)
        return [Entry(row["name"], row["split"], int(row["local_header_offset"]),
                      int(row["compressed_size"]), int(row["uncompressed_size"]),
                      int(row["method"]), int(row["crc32"], 16)) for row in r]


def dest_path(e: Entry, out_dir: Path, flat: bool, strip: int = 0) -> Path:
    # member names are untrusted: drop '', '.', '..' and drive-letter components
    parts = [x for x in e.name.replace("\\", "/").split("/") if x not in ("", ".", "..") and ":" not in x]
    if flat:
        parts = parts[-1:]
    elif strip:
        parts = parts[strip:] or parts[-1:]
    return out_dir.joinpath(*parts)


# --------------------------------------------------------------------------- CLI
def open_source(args) -> RemoteZip:
    f = Fetcher()
    if args.url:
        bits = args.url.split("?")[0].rstrip("/").split("/")
        if "datasets" in bits:                  # HF: <owner>_<repo>_<file>
            k = bits.index("datasets")
            name = "_".join([bits[k + 1], bits[k + 2], bits[-1]])
        else:
            name = bits[-1]
        name = re.sub(r"[^A-Za-z0-9_-]+", "_", name)
        idx = Path(args.index) if args.index else INDEX_DIR / f"{name}_index.csv"
        return RemoteZip([(args.url, None)], idx, f)
    src = SOURCES[args.source]
    idx = Path(args.index) if args.index else INDEX_DIR / src["index"]
    return RemoteZip(src["parts"], idx, f)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", default="videos", choices=sorted(SOURCES))
    ap.add_argument("--url", help="ad-hoc single-file remote zip (overrides --source)")
    ap.add_argument("--index", help="index CSV path (default: <data>/bdd100k/index/<source>_index.csv)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("sources", help="list known sources")
    pi = sub.add_parser("index", help="fetch/refresh the central-directory index")
    pi.add_argument("--refresh", action="store_true")
    pl = sub.add_parser("list", help="list members")
    pl.add_argument("--split")
    pl.add_argument("--grep", help="substring filter on member name")
    pl.add_argument("--limit", type=int, default=50)
    pl.add_argument("--dirs", action="store_true", help="include directory entries")
    pg = sub.add_parser("get", help="extract members (CRC-verified)")
    pg.add_argument("names", nargs="*")
    pg.add_argument("--from-file", help="text file with one member name per line")
    pg.add_argument("--out", default=".")
    pg.add_argument("--flat", action="store_true", help="drop directories, keep basename")
    pg.add_argument("--strip", type=int, default=0, help="strip N leading path components")
    pg.add_argument("--by-id", action="store_true",
                    help="names are bare ids/substrings (e.g. b1c66a42-6f7d68ca); first match wins")
    args = ap.parse_args(argv)

    if args.cmd == "sources":
        for k, v in SOURCES.items():
            print(f"{k:12s} {v['desc']}")
        return 0
    rz = open_source(args)
    if args.cmd == "index":
        es = rz.entries(refresh=args.refresh)
        print(f"{len(es)} entries -> {rz.index_path}")
    elif args.cmd == "list":
        n = 0
        for e in rz.entries():
            if not args.dirs and e.name.endswith("/"):
                continue
            if args.split and e.split != args.split:
                continue
            if args.grep and args.grep not in e.name:
                continue
            print(f"{e.compressed_size:>12,d}  m{e.method}  {e.crc32:08x}  {e.name}")
            n += 1
            if args.limit and n >= args.limit:
                break
    elif args.cmd == "get":
        names = list(args.names)
        if args.from_file:
            names += [l.strip() for l in open(args.from_file, encoding="utf-8") if l.strip()]
        es = rz.entries()
        by_name = {e.name: e for e in es}
        want = []
        for nm in names:
            if nm in by_name:
                want.append(by_name[nm])
            elif args.by_id:
                m = [e for e in es if nm in e.name and not e.name.endswith("/")]
                if not m:
                    log(f"not found: {nm}")
                    continue
                want.append(m[0])
            else:
                log(f"not found: {nm}  (use --by-id for substring match)")
        out = Path(args.out)
        log(f"{len(want)} member(s), {sum(e.compressed_size for e in want) / 1e6:,.1f} MB compressed "
            f"(existing files with the right size are skipped)")
        done = rz.extract_many(want, out, args.flat, args.strip)
        for e, p, n in done:
            print(f"{n:>12,d}  crc32 OK  {p}")
        log(f"downloaded {rz.f.bytes:,} bytes in {rz.f.requests} range request(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
