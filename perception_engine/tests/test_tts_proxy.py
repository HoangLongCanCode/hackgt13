"""ElevenLabs TTS proxy tests (perception.realtime.tts_proxy), plain Python, no server, no GPU, no network.

AI Spatial Driving Copilot. Run from perception_engine/:

    python tests/test_tts_proxy.py

A bare FastAPI app mounts only the /tts routes (add_tts_routes); httpx.MockTransport plays ElevenLabs and
httpx.ASGITransport plays the tablet, with a chosen peer address for the loopback rule. Each test uses its own temp
cache dir and fake credentials; the real environment and perception_engine/.env are never read (except by the
.env parser test, which uses its own temp file and restores the environment).
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import sys
import tempfile
import traceback
from pathlib import Path
from typing import Any, Callable

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault("YOLO_AUTOINSTALL", "False")

import httpx  # noqa: E402
import numpy as np  # noqa: E402
from fastapi import FastAPI  # noqa: E402

from perception.realtime.tts_proxy import (  # noqa: E402
    AUDIO_FORMAT_HEADER, CATALOG_PATH, MAX_LEAD_SILENCE, TtsProxy, add_tts_routes, audio_problem, cache_key,
    load_catalog, load_keys, trim_leading_silence)

FAKE_KEY = "sk_test_fake_key_do_not_use_0123456789"
FAKE_VOICE = "TestVoiceId0123456789"
NAV = {"stability": 0.6, "similarity_boost": 0.75, "style": 0.0, "use_speaker_boost": True, "speed": 1.0}


def pcm_clip(lead_silence: int = 2400, speech: int = 4800, amp: int = 3000, first: tuple = ()) -> bytes:
    """`lead_silence` near-silent samples (|x| <= 60) then a 440 Hz cosine; `first` overrides the first samples."""
    rng = np.random.default_rng(0)
    sil = rng.integers(-60, 61, lead_silence).astype(np.int16)
    t = np.arange(speech) / 24000.0
    tone = (amp * np.cos(2 * np.pi * 440.0 * t)).astype(np.int16)   # loud from its first sample
    x = np.concatenate([sil, tone]).astype("<i2")
    x[:len(first)] = first
    return x.tobytes()


class FakeEleven:
    """httpx.MockTransport handler: records requests, answers with `respond(request)` (sync or async)."""

    def __init__(self, respond: Callable[[httpx.Request], Any]):
        self.respond, self.calls = respond, []

    async def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        r = self.respond(request)
        return await r if asyncio.iscoroutine(r) else r


class Harness:
    def __init__(self, respond=None, *, key=FAKE_KEY, voice=FAKE_VOICE, **kw):
        self.tmp = tempfile.TemporaryDirectory(prefix="tts_test_")
        self.cache = Path(self.tmp.name) / "tts_cache"
        self.fake = FakeEleven(respond or (lambda req: httpx.Response(200, content=pcm_clip())))
        kw.setdefault("warmup", False)
        self.proxy = TtsProxy(key, voice, cache_dir=self.cache, catalog=load_catalog(CATALOG_PATH),
                              transport=httpx.MockTransport(self.fake), **kw)
        self.app = FastAPI()
        add_tts_routes(self.app, self.proxy)

    async def request(self, method: str, path: str, body: Any = None, peer: str = "127.0.0.1") -> httpx.Response:
        transport = httpx.ASGITransport(app=self.app, client=(peer, 50000))
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as c:
            return await c.request(method, path, json=body)

    async def tts(self, text: str, voice: str = "nav", cache_only: bool = False, peer: str = "127.0.0.1"):
        return await self.request("POST", "/tts", {"text": text, "voice": voice, "cacheOnly": cache_only}, peer)

    async def health(self, peer: str = "127.0.0.1") -> httpx.Response:
        return await self.request("GET", "/tts/health", peer=peer)

    async def __aenter__(self) -> "Harness":
        await self.proxy.start()
        return self

    async def __aexit__(self, *exc) -> None:
        await self.proxy.close()
        self.tmp.cleanup()


def err(r: httpx.Response) -> str:
    return r.json().get("error")


# ============================================================================= tests
async def test_bad_text():
    async with Harness() as h:
        for text in ["turn right.", "Turn right", "Take exit 23.", "Turn right.\n", "", "A" + "a" * 199 + ".",
                     "Turn <b>right</b>."]:
            r = await h.tts(text)
            assert r.status_code == 400 and err(r) == "badText", (text, r.status_code, r.text)
        r = await h.request("POST", "/tts", {"voice": "nav"})
        assert r.status_code == 400 and err(r) == "badText", r.text
        r = await h.tts("Turn right.", voice="social")
        assert r.status_code == 400 and err(r) == "badVoice", r.text
        r = await h.request("POST", "/tts", ["Turn right."])
        assert r.status_code == 400 and err(r) == "badRequest", r.text
        ok = "T" + "a" * 198 + "."                                   # exactly 200 characters is allowed
        assert (await h.tts(ok)).status_code == 200
        assert len(h.fake.calls) == 1
    return "7 bad texts, missing text, bad voice, non-object body -> 400; 200 chars accepted"


async def test_not_configured():
    for kw, what in [({"key": ""}, "no key"), ({"voice": ""}, "no voice id"), ({"enabled": False}, "--no-tts")]:
        async with Harness(**kw) as h:
            r = await h.tts("Turn right.")
            assert r.status_code == 503 and err(r) == "notConfigured", (what, r.status_code, r.text)
            hj = (await h.health()).json()
            assert hj["configured"] is False, (what, hj)
            assert not h.fake.calls
    return "no key / no voice id / --no-tts -> 503 notConfigured, health configured=false, no upstream call"


async def test_miss_then_hit():
    raw = pcm_clip(lead_silence=2400, speech=4800)                   # 100 ms lead silence
    async with Harness(lambda req: httpx.Response(200, content=raw)) as h:
        r = await h.tts("Turn right onto Main Street.")
        assert r.status_code == 200, r.text
        assert r.headers["content-type"] == "application/octet-stream", r.headers
        assert r.headers["x-audio-format"] == AUDIO_FORMAT_HEADER == "pcm_s16le;rate=24000;channels=1"
        assert r.headers["x-tts-cache"] == "miss"
        key = r.headers["x-tts-key"]
        s = json.dumps(NAV, sort_keys=True, separators=(",", ":"))
        want = hashlib.sha256(f"Turn right onto Main Street.|{FAKE_VOICE}|eleven_flash_v2_5|{s}|pcm_24000|20260926|en|off|"
                              .encode()).hexdigest()
        assert key == want, (key, want)
        # leading silence trimmed to 20 ms: 480 of the 2400 silent samples kept
        assert len(r.content) == 2 * (MAX_LEAD_SILENCE + 4800), len(r.content)
        assert r.content == raw[2 * (2400 - MAX_LEAD_SILENCE):]
        assert int(r.headers["content-length"]) == len(r.content)
        # the upstream request
        assert len(h.fake.calls) == 1
        q = h.fake.calls[0]
        assert q.method == "POST" and q.url.host == "api.elevenlabs.io", q.url
        assert q.url.path == f"/v1/text-to-speech/{FAKE_VOICE}", q.url.path
        assert dict(q.url.params) == {"output_format": "pcm_24000"}, q.url.params
        assert q.headers["xi-api-key"] == FAKE_KEY
        body = json.loads(q.content)
        assert body == {"text": "Turn right onto Main Street.", "model_id": "eleven_flash_v2_5", "language_code": "en",
                        "apply_text_normalization": "off", "seed": 20260926, "voice_settings": NAV}, body
        assert (h.cache / f"{key}.pcm").read_bytes() == r.content
        # second request: from the disk cache, no upstream call
        r2 = await h.tts("Turn right onto Main Street.")
        assert r2.status_code == 200 and r2.headers["x-tts-cache"] == "hit" and r2.headers["x-tts-key"] == key
        assert r2.content == r.content and len(h.fake.calls) == 1
        # alert profile: its own key and speed 1.1
        r3 = await h.tts("Turn right onto Main Street.", voice="alert")
        assert r3.status_code == 200 and r3.headers["x-tts-key"] != key and r3.headers["x-tts-cache"] == "miss"
        assert json.loads(h.fake.calls[1].content)["voice_settings"]["speed"] == 1.1
        hj = (await h.health()).json()
        assert hj["cacheEntries"] == 2, hj
    return f"miss -> hit with 1 upstream call, 100 ms lead cut to 20 ms ({len(r.content)} B), key {key[:12]}"


async def test_cache_only():
    async with Harness() as h:
        r = await h.tts("Keep left.", cache_only=True)
        assert r.status_code == 404 and err(r) == "notCached", r.text
        assert not h.fake.calls
        assert (await h.tts("Keep left.")).status_code == 200
        r = await h.tts("Keep left.", cache_only=True)
        assert r.status_code == 200 and r.headers["x-tts-cache"] == "hit" and len(h.fake.calls) == 1
    return "cacheOnly miss -> 404 notCached (no upstream call), cacheOnly after a fetch -> 200 hit"


async def test_invalid_audio():
    bad = {"ID3": b"ID3\x04\x00" + bytes(1000), "RIFF": b"RIFF" + bytes(1000),
           "MPEG sync": b"\xff\xfb\x90\x64" + bytes(1000), "odd length": pcm_clip()[:-1], "empty": b""}
    for what, content in bad.items():
        async with Harness(lambda req, c=content: httpx.Response(200, content=c)) as h:
            r = await h.tts("Turn right.")
            assert r.status_code == 502 and err(r) == "invalidAudio", (what, r.status_code, r.text)
            assert not list(h.cache.glob("*.pcm")) if h.cache.exists() else True, what
            assert (await h.health()).json()["lastError"], what
    # near-silent PCM that starts with sample -1 (bytes FF FF) is not an MPEG header
    assert audio_problem(pcm_clip(first=(-1, 3))) is None and audio_problem(pcm_clip(first=(-1, -1))) is None
    assert trim_leading_silence(bytes(960)) == (bytes(960), 0)       # all silent: unchanged
    return f"{', '.join(bad)} -> 502 invalidAudio, nothing cached; PCM starting FF FF accepted"


async def test_upstream_errors():
    detail = {"detail": {"status": "internal_error", "code": "internal_error", "message": "boom",
                         "request_id": "req_abc123"}}
    async with Harness(lambda req: httpx.Response(500, json=detail)) as h:
        r = await h.tts("Turn right.")
        assert r.status_code == 502 and r.json() == {"error": "upstream", "status": 500}, r.text
        hj = (await h.health()).json()
        assert "req_abc123" in hj["lastError"] and "internal_error" in hj["lastError"], hj
        dumped = json.dumps(hj)
        assert FAKE_KEY not in dumped and FAKE_VOICE not in dumped, dumped
    busy = {"detail": {"code": "concurrent_limit_exceeded", "request_id": "req_busy"}}
    async with Harness(lambda req: httpx.Response(429, json=busy)) as h:
        r = await h.tts("Turn right.")
        assert r.status_code == 429 and err(r) == "upstreamBusy" and r.headers["retry-after"] == "1", r.text

    async def slow(req):
        await asyncio.sleep(0.5)
        return httpx.Response(200, content=pcm_clip())
    async with Harness(slow, timeout_s=0.1) as h:
        r = await h.tts("Turn right.")
        assert r.status_code == 504 and err(r) == "upstreamTimeout", r.text
        assert h.proxy.guard.in_flight == 0

    def down(req):
        raise httpx.ConnectError("offline", request=req)
    async with Harness(down) as h:
        r = await h.tts("Turn right.")
        assert r.status_code == 502 and err(r) == "upstream", r.text
    return "500 -> 502 (detail code + request_id in lastError, no key / voice id), 429 -> upstreamBusy, " \
           "slow -> 504, offline -> 502"


async def test_spend_guard():
    async with Harness(minute_chars=40) as h:
        assert (await h.tts("Turn right onto Main St.")).status_code == 200          # 24 chars
        assert (await h.tts("Keep left.")).status_code == 200                         # 34
        r = await h.tts("Take the exit.")                                             # 48 > 40
        assert r.status_code == 429 and err(r) == "spendGuard", r.text
        assert int(r.headers["retry-after"]) >= 1, r.headers
        r = await h.tts("Keep left.")                                                 # cache hits never count
        assert r.status_code == 200 and r.headers["x-tts-cache"] == "hit"
        b = (await h.health()).json()["budget"]
        assert b["perMinuteLeft"] == 6 and b["dayLeft"] == 30000 - 34, b
        assert len(h.fake.calls) == 2
    async with Harness(day_chars=20) as h:
        assert (await h.tts("Keep left.")).status_code == 200
        r = await h.tts("Take the exit.")
        assert r.status_code == 429 and err(r) == "spendGuard" and int(r.headers["retry-after"]) >= 1, r.text
    gate = asyncio.Event()

    async def gated(req):
        await gate.wait()
        return httpx.Response(200, content=pcm_clip())
    async with Harness(gated) as h:                                                   # 2 in flight at most
        held = [asyncio.create_task(h.tts(t)) for t in ("Turn left.", "Turn right.", "Turn left.")]
        await asyncio.sleep(0.1)
        r = await h.tts("Keep right.")
        assert r.status_code == 429 and err(r) == "spendGuard" and r.headers["retry-after"] == "1", r.text
        gate.set()
        rs = await asyncio.gather(*held)
        assert [x.status_code for x in rs] == [200, 200, 200], [x.text for x in rs]
        assert rs[0].content == rs[2].content and len(h.fake.calls) == 2              # duplicate shared one call
    # The default minute budget holds a cold-cache warm-up of the whole fixed pack plus 1,000 runtime characters.
    pack = sum(len(p) for p in json.loads(CATALOG_PATH.read_text(encoding="utf-8"))["fixedPhrases"])
    minute = TtsProxy(FAKE_KEY, FAKE_VOICE).guard.minute_chars
    assert minute >= pack + 1000, (minute, pack)
    return "minute 40 chars: 3rd call 429 + Retry-After, hit still served; day limit 429; 3rd concurrent call 429, " \
           f"duplicate joins the in-flight call; default {minute}/min >= pack {pack} + 1000"


async def test_loopback():
    async with Harness() as h:
        for peer in ("192.168.1.50", "10.0.0.7"):
            r = await h.tts("Turn right.", peer=peer)
            assert r.status_code == 403 and err(r) == "notLoopback", (peer, r.text)
            r = await h.health(peer=peer)
            assert r.status_code == 403 and err(r) == "notLoopback", (peer, r.text)
        assert not h.fake.calls
        assert (await h.tts("Turn right.", peer="::1")).status_code == 200
        assert (await h.health(peer="127.0.0.1")).json()["allowLan"] is False
    async with Harness(allow_lan=True) as h:
        r = await h.tts("Turn right.", peer="192.168.1.50")
        assert r.status_code == 200, r.text
        hj = (await h.health(peer="192.168.1.50")).json()
        assert hj["allowLan"] is True and hj["configured"] is True and hj["voiceIdSet"] is True, hj
        assert hj["modelId"] == "eleven_flash_v2_5" and hj["format"] == "pcm_24000", hj
    return "LAN peer -> 403 on /tts and /tts/health, ::1 allowed, --tts-allow-lan lets the LAN peer in"


async def test_catalog_and_env():
    c = load_catalog(CATALOG_PATH)
    assert c.source == str(CATALOG_PATH) and c.model_id == "eleven_flash_v2_5" and c.seed == 20260926, c
    assert c.profiles["nav"] == NAV and c.profiles["alert"]["speed"] == 1.1, c.profiles
    fb = load_catalog(Path(tempfile.gettempdir()) / "no_such_audio_cues.json")
    assert (fb.model_id, fb.seed, fb.profiles, fb.speech_regex) == (c.model_id, c.seed, c.profiles, c.speech_regex)
    names = ("ELEVENLABS_API_KEY", "ELEVENLABS_VOICE_ID")
    saved = {n: os.environ.pop(n, None) for n in names}
    try:
        with tempfile.TemporaryDirectory() as d:
            env = Path(d) / ".env"
            env.write_text("# comment\n\nELEVENLABS_API_KEY = 'file-key'\nexport ELEVENLABS_VOICE_ID=\"file-voice\"\n"
                           "OTHER=a=b\n", encoding="utf-8")
            assert load_keys(env) == ("file-key", "file-voice")
            os.environ["ELEVENLABS_VOICE_ID"] = "env-voice"                 # the environment wins
            assert load_keys(env) == ("file-key", "env-voice")
            assert load_keys(Path(d) / "missing.env") == ("", "env-voice")
    finally:
        for n, v in saved.items():
            os.environ.pop(n, None)
            if v is not None:
                os.environ[n] = v
    k = cache_key("A.", "v", "m", {"b": 1, "a": True}, "pcm_24000", 1, "en", "off")
    assert k == hashlib.sha256(b'A.|v|m|{"a":true,"b":1}|pcm_24000|1|en|off|').hexdigest()
    return "catalog values + identical fallback, .env parser (comments, quotes, export, env wins), key format"


def main() -> int:
    tests = [test_bad_text, test_not_configured, test_miss_then_hit, test_cache_only, test_invalid_audio,
             test_upstream_errors, test_spend_guard, test_loopback, test_catalog_and_env]
    failed = 0
    for t in tests:
        try:
            info = asyncio.run(t())
            print(f"PASS {t.__name__}: {info}", flush=True)
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {t.__name__}: {type(e).__name__}: {e}", flush=True)
            traceback.print_exc()
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
