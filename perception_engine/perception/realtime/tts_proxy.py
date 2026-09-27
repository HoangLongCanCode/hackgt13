"""ElevenLabs text-to-speech proxy on the perception server: POST /tts and GET /tts/health.

AI Spatial Driving Copilot, HackGT 13 prototype. Design: docs/audio/AUDIO_CUE_RULES.md sections 10.4 (the
ElevenLabs request) and 10.5 (this contract). Model, seed, voice profiles and the speech regex come from
docs/audio/audio_cues.v1.json (keys `elevenlabs`, `voiceProfiles`, `speechRegex`), read once at start-up; the
literal values below are only a fallback for a missing file.

The tablet app never holds the ElevenLabs key (ElevenLabs' terms forbid shipping it in an APK). It posts cue text
that is already normalized and gets the whole clip back as headerless s16le mono PCM at 24 kHz:

    POST /tts         {"text": "Turn right onto Main Street.", "voice": "nav"|"alert", "cacheOnly": false}
                      200 application/octet-stream + X-Audio-Format, X-TTS-Cache hit|miss, X-TTS-Key
                      400 badText|badVoice|badRequest, 403 notLoopback, 404 notCached, 503 notConfigured,
                      429 spendGuard|upstreamBusy (+ Retry-After), 502 upstream|invalidAudio, 504 upstreamTimeout
    GET  /tts/health  {configured, modelId, voiceIdSet, format, cacheEntries, lastError, allowLan, budget, ...}

Key: ELEVENLABS_API_KEY and ELEVENLABS_VOICE_ID from the environment, else from perception_engine/.env (gitignored).
Neither value is ever logged or returned; the key goes only to api.elevenlabs.io in the xi-api-key header.
Clients: loopback only (adb reverse arrives as 127.0.0.1) unless the server runs with --tts-allow-lan.
Cache: outputs/tts_cache/<sha256>.pcm (gitignored), leading silence trimmed to <= 20 ms before it is written.
Everything runs on the server's asyncio loop, which also sends every WebSocket message, so nothing here blocks it:
one shared httpx.AsyncClient (keep-alive), disk I/O in worker threads.

Not implemented yet (10.5): POST /tts/prefetch, GET /tts/pack[/{sha256}], the separate prefetch budget, and the
429 retry policy by detail.code (an upstream 429 is passed on at once as upstreamBusy).
"""
from __future__ import annotations

import asyncio
import datetime
import hashlib
import json
import math
import os
import re
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional
from urllib.parse import quote

import httpx
import numpy as np
from fastapi import Request                     # module-level: FastAPI resolves endpoint annotations from globals
from fastapi.responses import JSONResponse, Response

from perception.common.paths import OUTPUTS_ROOT, PROJECT_ROOT

CATALOG_PATH = PROJECT_ROOT / "docs" / "audio" / "audio_cues.v1.json"
ENV_PATH = PROJECT_ROOT / ".env"
CACHE_DIR = OUTPUTS_ROOT / "tts_cache"
API_BASE = "https://api.elevenlabs.io"
API_KEY_ENV, VOICE_ID_ENV = "ELEVENLABS_API_KEY", "ELEVENLABS_VOICE_ID"

OUTPUT_FORMAT = "pcm_24000"                     # the whole contract (header, trim, validation) assumes this
AUDIO_FORMAT_HEADER = "pcm_s16le;rate=24000;channels=1"
SAMPLE_RATE = 24000
MAX_TEXT_CHARS = 200
# Upstream characters per rolling minute: a cold-cache warm-up of the tablet's fixed pack (audio_cues.v1.json
# fixedPhrases, about 1,200 characters) plus the nav sentences that arrive in the same minute.
MINUTE_CHARS = 2500
SILENCE_ABS = 200                               # |sample| below this counts as silence
MAX_LEAD_SILENCE = SAMPLE_RATE * 20 // 1000     # keep at most 20 ms (480 samples) of leading silence
MAX_BODY_BYTES = 4096
LOOPBACK = frozenset({"127.0.0.1", "::1", "::ffff:127.0.0.1"})
SETTING_KEYS = ("stability", "similarity_boost", "style", "use_speaker_boost", "speed")

# fallback only: audio_cues.v1.json is the source of truth
_FALLBACK_PROFILES = {
    "nav": {"stability": 0.6, "similarity_boost": 0.75, "style": 0.0, "use_speaker_boost": True, "speed": 1.0},
    "alert": {"stability": 0.6, "similarity_boost": 0.75, "style": 0.0, "use_speaker_boost": True, "speed": 1.1},
}
_FALLBACK_REGEX = r"^[A-Z][A-Za-z ,'-]*\.$"


def _log(msg: str) -> None:
    print(f"[tts] {msg}", flush=True)


# ----------------------------------------------------------------------------- catalog + keys
@dataclass(frozen=True)
class TtsCatalog:
    model_id: str = "eleven_flash_v2_5"
    seed: int = 20260926
    language_code: str = "en"                   # "" when the model does not accept one (Multilingual v2)
    apply_text_normalization: str = "off"
    speech_regex: str = _FALLBACK_REGEX
    profiles: dict = field(default_factory=lambda: {k: dict(v) for k, v in _FALLBACK_PROFILES.items()})
    source: str = "built-in defaults"


def load_catalog(path: Path = CATALOG_PATH) -> TtsCatalog:
    """The ElevenLabs part of audio_cues.v1.json; built-in defaults (the same values) if the file is unreadable."""
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        _log(f"catalog {path} unreadable ({type(e).__name__}); using built-in defaults")
        return TtsCatalog()
    d = TtsCatalog()
    el = data.get("elevenlabs") or {}
    model = str(el.get("modelId") or d.model_id)
    only = el.get("languageCodeOnlyForModels", [d.model_id])
    lang = str(el.get("languageCode") or d.language_code) if model in only else ""
    fmt = el.get("outputFormat", OUTPUT_FORMAT)
    if fmt != OUTPUT_FORMAT:
        _log(f"catalog outputFormat {fmt!r} ignored: the proxy always requests {OUTPUT_FORMAT}")
    vp = data.get("voiceProfiles") or {}
    profiles = {}
    for name, fb in _FALLBACK_PROFILES.items():
        p = vp.get(name) if isinstance(vp.get(name), dict) else {}
        profiles[name] = {k: p.get(k, fb[k]) for k in SETTING_KEYS}
    regex = data.get("speechRegex") or d.speech_regex
    try:
        re.compile(regex)
    except re.error:
        regex = d.speech_regex
    return TtsCatalog(model_id=model, seed=int(el.get("seed", d.seed)), language_code=lang,
                      apply_text_normalization=str(el.get("applyTextNormalization") or d.apply_text_normalization),
                      speech_regex=regex, profiles=profiles, source=str(path))


def read_env_file(path: Path) -> dict[str, str]:
    """Minimal KEY=VALUE parser (no python-dotenv): skips blank and # lines, splits on the first '=',
    strips whitespace and one pair of matching quotes."""
    out: dict[str, str] = {}
    try:
        lines = Path(path).read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return out
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = (s.strip() for s in line.split("=", 1))
        k = k[len("export "):].strip() if k.startswith("export ") else k
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        out[k] = v
    return out


def load_keys(env_path: Path = ENV_PATH) -> tuple[str, str]:
    """(api_key, voice_id): each from the environment if set there, else from the .env file, else ''."""
    file_vals = read_env_file(env_path)
    get = lambda name: (os.environ.get(name) or "").strip() or file_vals.get(name, "").strip()  # noqa: E731
    return get(API_KEY_ENV), get(VOICE_ID_ENV)


# ----------------------------------------------------------------------------- audio helpers
def cache_key(text: str, voice_id: str, model_id: str, settings: dict, output_format: str, seed: int,
              language_code: str, apply_text_normalization: str, dictionary: str = "") -> str:
    """AUDIO_CUE_RULES 10.4: sha256 of text|voice_id|model_id|settings|output_format|seed|language_code|
    apply_text_normalization|dictionary, settings as JSON with sorted keys and no spaces."""
    s = json.dumps(settings, sort_keys=True, separators=(",", ":"))
    raw = "|".join([text, voice_id, model_id, s, output_format, str(seed), language_code,
                    apply_text_normalization, dictionary])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _is_mpeg_header(b: bytes) -> bool:
    """An MPEG audio frame sync (0xFF then 0xE0-0xFF) that also has a valid version, layer, bitrate and sample
    rate. The extra fields keep near-silent PCM that starts with sample -1 (bytes FF FF) from being rejected."""
    if len(b) < 2 or b[0] != 0xFF or (b[1] & 0xE0) != 0xE0:
        return False
    if len(b) < 4:
        return True
    version, layer, bitrate, rate = (b[1] >> 3) & 3, (b[1] >> 1) & 3, b[2] >> 4, (b[2] >> 2) & 3
    return version != 1 and layer != 0 and 0 < bitrate < 15 and rate != 3


def audio_problem(body: bytes) -> Optional[str]:
    """Why `body` is not headerless s16le PCM (MP3 / WAV came back), or None if it looks fine."""
    if not body:
        return "empty body"
    if body[:3] == b"ID3":
        return "ID3 header (MP3)"
    if body[:4] == b"RIFF":
        return "RIFF header (WAV)"
    if _is_mpeg_header(body[:4]):
        return "MPEG frame sync (MP3)"
    if len(body) % 2:
        return f"odd length {len(body)}"
    return None


def trim_leading_silence(pcm: bytes) -> tuple[bytes, int]:
    """Cut leading silence (|x| < SILENCE_ABS) down to MAX_LEAD_SILENCE samples; returns (pcm, samples cut).
    An all-silent clip is returned unchanged."""
    x = np.frombuffer(pcm, dtype="<i2")
    loud = np.flatnonzero(np.abs(x.astype(np.int32)) >= SILENCE_ABS)
    if loud.size == 0:
        return pcm, 0
    start = max(0, int(loud[0]) - MAX_LEAD_SILENCE)
    return pcm[2 * start:], start


# ----------------------------------------------------------------------------- spend guard
class SpendGuard:
    """In-memory budget for upstream calls: characters per rolling minute, calls in flight, characters per local
    calendar day. Only upstream calls count (cache hits never reach it). A call counts when it starts, whatever
    ElevenLabs answers (conservative)."""

    def __init__(self, minute_chars: int = MINUTE_CHARS, max_in_flight: int = 2, day_chars: int = 30000):
        self.minute_chars, self.max_in_flight, self.day_chars = minute_chars, max_in_flight, day_chars
        self.in_flight = 0
        self._window: deque[tuple[float, int]] = deque()
        self._day, self._day_used = datetime.date.today(), 0

    def _refresh(self, now: float) -> None:
        while self._window and self._window[0][0] <= now - 60.0:
            self._window.popleft()
        today = datetime.date.today()
        if today != self._day:
            self._day, self._day_used = today, 0

    def reserve(self, n: int) -> Optional[int]:
        """Take `n` characters and one in-flight slot; returns None, or Retry-After seconds if over a limit."""
        now = time.monotonic()
        self._refresh(now)
        if self.in_flight >= self.max_in_flight:
            return 1
        if self._day_used + n > self.day_chars:
            midnight = datetime.datetime.combine(self._day + datetime.timedelta(days=1), datetime.time())
            return max(1, math.ceil((midnight - datetime.datetime.now()).total_seconds()))
        used = sum(c for _, c in self._window)
        if used + n > self.minute_chars:
            need, freed = used + n - self.minute_chars, 0
            for t, c in self._window:
                freed += c
                if freed >= need:
                    return max(1, math.ceil(t + 60.0 - now))
            return 60
        self._window.append((now, n))
        self._day_used += n
        self.in_flight += 1
        return None

    def release(self) -> None:
        self.in_flight = max(0, self.in_flight - 1)

    def left(self) -> dict[str, int]:
        self._refresh(time.monotonic())
        return {"perMinuteLeft": max(0, self.minute_chars - sum(c for _, c in self._window)),
                "dayLeft": max(0, self.day_chars - self._day_used), "inFlight": self.in_flight}


# ----------------------------------------------------------------------------- proxy
class TtsError(Exception):
    def __init__(self, status: int, body: dict, headers: Optional[dict] = None):
        super().__init__(f"{status} {body}")
        self.status, self.body, self.headers = status, body, headers

    def response(self) -> JSONResponse:
        return JSONResponse(self.body, status_code=self.status, headers=self.headers)


def _err(status: int, error: str, **extra: Any) -> JSONResponse:
    return JSONResponse({"error": error, **extra}, status_code=status)


class TtsProxy:
    """The /tts logic, independent of the perception engine (tests mount it on a bare FastAPI app).
    `transport` replaces the network (httpx.MockTransport in tests)."""

    def __init__(self, api_key: str = "", voice_id: str = "", *, enabled: bool = True, allow_lan: bool = False,
                 cache_dir: Path = CACHE_DIR, catalog: Optional[TtsCatalog] = None,
                 transport: Optional[httpx.AsyncBaseTransport] = None, timeout_s: float = 2.5,
                 minute_chars: int = MINUTE_CHARS, max_in_flight: int = 2, day_chars: int = 30000,
                 warmup: bool = True, base_url: str = API_BASE):
        self._api_key = api_key or ""            # never logged, never returned
        self._voice_id = voice_id or ""          # never returned (only voiceIdSet)
        self.enabled, self.allow_lan = enabled, allow_lan
        self.cache_dir = Path(cache_dir)
        self.catalog = catalog or TtsCatalog()
        self.timeout_s, self.warmup, self.base_url = timeout_s, warmup, base_url.rstrip("/")
        self.guard = SpendGuard(minute_chars, max_in_flight, day_chars)
        self.last_error: Optional[str] = None
        self._speech_re = re.compile(self.catalog.speech_regex)
        self._transport = transport
        self._client: Optional[httpx.AsyncClient] = None
        self._pending: dict[str, asyncio.Future] = {}     # cache key -> upstream fetch shared by duplicate requests
        self._warm_task: Optional[asyncio.Task] = None

    @classmethod
    def from_env(cls, *, enabled: bool = True, allow_lan: bool = False, env_path: Path = ENV_PATH,
                 catalog_path: Path = CATALOG_PATH, **kw: Any) -> "TtsProxy":
        key, voice = load_keys(env_path) if enabled else ("", "")
        proxy = cls(key, voice, enabled=enabled, allow_lan=allow_lan, catalog=load_catalog(catalog_path), **kw)
        _log(f"ElevenLabs TTS proxy: {proxy.state}")
        return proxy

    @property
    def configured(self) -> bool:
        return self.enabled and bool(self._api_key) and bool(self._voice_id)

    @property
    def state(self) -> str:
        if not self.enabled:
            return "disabled (--no-tts)"
        if self.configured:
            return "configured"
        missing = "/".join(n for n, v in ((API_KEY_ENV, self._api_key), (VOICE_ID_ENV, self._voice_id)) if not v)
        return f"not configured (no {missing}; set it in the environment or perception_engine/.env)"

    # -- lifecycle (server lifespan; the client is also created lazily on first use)
    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                transport=self._transport, timeout=httpx.Timeout(self.timeout_s),
                limits=httpx.Limits(max_connections=4, max_keepalive_connections=2, keepalive_expiry=60.0))
        return self._client

    async def start(self) -> None:
        self._http()
        if self.configured and self.warmup:        # open the TLS connection early; never fails start-up
            self._warm_task = asyncio.create_task(self._warm())

    async def _warm(self) -> None:
        try:
            await self._http().head(self.base_url + "/", timeout=2.0)
        except Exception:                           # offline at start-up is fine
            pass

    async def close(self) -> None:
        tasks = [t for t in [self._warm_task, *self._pending.values()] if t is not None and not t.done()]
        for t in tasks:
            t.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # -- helpers
    def client_allowed(self, request: Request) -> bool:
        return self.allow_lan or (request.client is not None and request.client.host in LOOPBACK)

    def key_for(self, text: str, voice: str) -> str:
        c = self.catalog
        return cache_key(text, self._voice_id, c.model_id, c.profiles[voice], OUTPUT_FORMAT, c.seed,
                         c.language_code, c.apply_text_normalization)

    def _note_error(self, msg: str) -> None:
        if self._api_key:
            msg = msg.replace(self._api_key, "<key>")
        self.last_error = f"{time.strftime('%H:%M:%S')} {msg}"
        _log(msg)

    def _cache_path(self, key: str) -> Path:
        return self.cache_dir / f"{key}.pcm"

    def _cache_read(self, key: str) -> Optional[bytes]:
        try:
            return self._cache_path(key).read_bytes()
        except OSError:
            return None

    def _cache_write(self, key: str, pcm: bytes) -> None:
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        tmp = self.cache_dir / f"{key}.{uuid.uuid4().hex[:8]}.tmp"
        tmp.write_bytes(pcm)
        os.replace(tmp, self._cache_path(key))    # atomic: a reader never sees half a clip

    def _cache_count(self) -> int:
        try:
            return sum(1 for _ in self.cache_dir.glob("*.pcm"))
        except OSError:
            return 0

    @staticmethod
    def _audio(pcm: bytes, key: str, cache: str) -> Response:
        return Response(pcm, media_type="application/octet-stream",
                        headers={"X-Audio-Format": AUDIO_FORMAT_HEADER, "X-TTS-Cache": cache, "X-TTS-Key": key})

    # -- routes
    async def tts(self, request: Request) -> Response:
        if not self.client_allowed(request):
            return _err(403, "notLoopback")
        try:
            if int(request.headers.get("content-length") or 0) > MAX_BODY_BYTES:
                return _err(400, "badRequest")
            body = await request.json()
        except (ValueError, UnicodeDecodeError):
            return _err(400, "badRequest")
        if not isinstance(body, dict):
            return _err(400, "badRequest")
        text, voice, cache_only = body.get("text"), body.get("voice", "nav"), body.get("cacheOnly", False)
        if not isinstance(text, str) or len(text) > MAX_TEXT_CHARS or not self._speech_re.fullmatch(text):
            return _err(400, "badText")
        if voice not in self.catalog.profiles:
            return _err(400, "badVoice")
        if not isinstance(cache_only, bool):
            return _err(400, "badRequest")
        if not self.configured:
            return _err(503, "notConfigured")
        key = self.key_for(text, voice)
        pcm = await asyncio.to_thread(self._cache_read, key)
        if pcm is not None:
            return self._audio(pcm, key, "hit")
        if cache_only:
            return _err(404, "notCached")
        try:
            pcm = await self._fetch_shared(key, text, voice)
        except TtsError as e:
            return e.response()
        return self._audio(pcm, key, "miss")

    async def health(self, request: Request) -> Response:
        if not self.client_allowed(request):
            return _err(403, "notLoopback")
        return JSONResponse({"configured": self.configured, "enabled": self.enabled,
                             "apiKeySet": bool(self._api_key), "voiceIdSet": bool(self._voice_id),
                             "modelId": self.catalog.model_id, "format": OUTPUT_FORMAT,
                             "cacheEntries": await asyncio.to_thread(self._cache_count),
                             "lastError": self.last_error, "allowLan": self.allow_lan, "budget": self.guard.left()})

    # -- upstream
    async def _fetch_shared(self, key: str, text: str, voice: str) -> bytes:
        """One upstream call per key: a duplicate request while one is in flight waits for the same result (and
        costs nothing). The call is shielded, so a request that gives up still fills the cache for next time."""
        fut = self._pending.get(key)
        if fut is None:
            retry = self.guard.reserve(len(text))
            if retry is not None:
                raise TtsError(429, {"error": "spendGuard"}, {"Retry-After": str(retry)})
            fut = asyncio.ensure_future(self._fetch(key, text, voice))
            self._pending[key] = fut

            def done(f: asyncio.Future, k: str = key) -> None:
                self._pending.pop(k, None)
                if not f.cancelled():
                    f.exception()                     # mark retrieved (no "never retrieved" warning)
            fut.add_done_callback(done)
        return await asyncio.shield(fut)

    async def _fetch(self, key: str, text: str, voice: str) -> bytes:
        c = self.catalog
        payload: dict[str, Any] = {"text": text, "model_id": c.model_id, "voice_settings": dict(c.profiles[voice]),
                                   "seed": c.seed, "apply_text_normalization": c.apply_text_normalization}
        if c.language_code:
            payload["language_code"] = c.language_code
        url = f"{self.base_url}/v1/text-to-speech/{quote(self._voice_id, safe='')}"
        t0 = time.perf_counter()
        try:
            try:
                r = await asyncio.wait_for(
                    self._http().post(url, params={"output_format": OUTPUT_FORMAT}, json=payload,
                                      headers={"xi-api-key": self._api_key}),
                    self.timeout_s)                   # total budget; httpx's own timeout is per phase
            except (TimeoutError, httpx.TimeoutException):
                self._note_error(f"upstream timeout after {self.timeout_s:.1f} s for {text!r}")
                raise TtsError(504, {"error": "upstreamTimeout"})
            except httpx.HTTPError as e:
                self._note_error(f"upstream unreachable: {type(e).__name__}")
                raise TtsError(502, {"error": "upstream", "status": None})
        finally:
            self.guard.release()
        ms = (time.perf_counter() - t0) * 1000.0
        if r.status_code == 429:
            self._note_error(self._describe(r))
            raise TtsError(429, {"error": "upstreamBusy"}, {"Retry-After": "1"})
        if not 200 <= r.status_code < 300:
            self._note_error(self._describe(r))
            raise TtsError(502, {"error": "upstream", "status": r.status_code})
        body = r.content
        problem = audio_problem(body)
        if problem:
            self._note_error(f"invalid audio from upstream: {problem} ({len(body)} B, "
                             f"content-type {r.headers.get('content-type')})")
            raise TtsError(502, {"error": "invalidAudio"})
        pcm, cut = trim_leading_silence(body)
        await asyncio.to_thread(self._cache_write, key, pcm)
        _log(f"miss {voice} {text!r}: {len(pcm) / (2 * SAMPLE_RATE):.2f} s in {ms:.0f} ms "
             f"(trimmed {cut / SAMPLE_RATE * 1000:.0f} ms lead) key {key[:12]}")
        return pcm

    @staticmethod
    def _describe(r: httpx.Response) -> str:
        """ElevenLabs error summary: status, detail.code (fallback detail.status), request_id, message."""
        code = req = msg = None
        try:
            detail = r.json().get("detail")
            if isinstance(detail, dict):
                code, req, msg = detail.get("code") or detail.get("status"), detail.get("request_id"), detail.get("message")
            elif isinstance(detail, str):
                msg = detail
        except Exception:
            pass
        req = req or r.headers.get("request-id") or r.headers.get("x-request-id")
        parts = [f"upstream HTTP {r.status_code}"]
        parts += [f"code={code}"] if code else []
        parts += [f"request_id={req}"] if req else []
        parts += [f"message={str(msg)[:160]!r}"] if msg else []
        return " ".join(parts)


def add_tts_routes(app: Any, proxy: TtsProxy) -> None:
    """Mount POST /tts and GET /tts/health on a FastAPI app (the perception server, or a bare app in tests)."""

    @app.post("/tts")
    async def tts(request: Request):
        return await proxy.tts(request)

    @app.get("/tts/health")
    async def tts_health(request: Request):
        return await proxy.health(request)
