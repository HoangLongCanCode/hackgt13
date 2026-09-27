# Audio engine research: ElevenLabs, Android playback, in-vehicle audio guidance

Research date: 2026-09-26. This is the background for [`AUDIO_CUE_RULES.md`](AUDIO_CUE_RULES.md), which holds
the rules themselves. ElevenLabs changes quickly: re-check anything here before relying on it after this date.
Nothing was bought, no key was used and no paid API was called, so no claim about how a voice *sounds* has been
tested by ear. Claims that could not be confirmed from a primary source are marked **unverified**.

---

## 1. Decisions and the evidence behind them

| Decision | Evidence | Rules section |
|---|---|---|
| The ElevenLabs key stays on the laptop; the tablet talks only to a `/tts` proxy on the perception server | ElevenLabs' API terms forbid API keys in any mobile app or client artifact; keys pushed to a public GitHub repo are disabled automatically. The tablet may have no internet over USB `adb reverse`, but the laptop does | 10.5 |
| Safety and traffic phrases are pre-generated and played from RAM; they never wait on the network | Warm first-audio estimate through the proxy is about 250-400 ms on Wi-Fi and 300-550 ms on LTE, with a 0.8-2 s tail on LTE in a car (estimate); a whole clip takes longer than its first byte. A cached clip starts in about 40-70 ms on the speaker | 10.1-10.3 |
| `eleven_flash_v2_5`, `pcm_24000` (a query parameter), `apply_text_normalization: off`, our own text normalization | Flash is the recommended real-time model (about 75 ms model time, 50 ms claimed after a February 2026 upgrade). It does not normalize numbers by default, and turning normalization on is Enterprise-only. PCM plays directly on `AudioTrack` with no decoder | 8, 10.4 |
| A paid ElevenLabs plan before the voice pack is built | The replacement default voices are Voice Library voices, which the API does not serve on the Free plan, and the old default voices are hidden from accounts created after March 2026 | 10.4, 15 |
| CRITICAL cues take `AUDIOFOCUS_GAIN_TRANSIENT`; the rest duck | With `MAY_DUCK` other apps are only lowered by about 14 dB, and apps playing speech (podcasts, another navigation voice) are not ducked at all | 7.5 |
| No digits, units or abbreviations reach text-to-speech | ElevenLabs' own advice is to write numbers and symbols as words; its sample rules turn "Dr." into "Doctor", which is wrong for road names | 8 |
| Cache everything; the seed is not trusted | The API calls `seed` best effort ("Determinism is not guaranteed") | 10.4 |
| Earcons synthesized in code, not ElevenLabs Sound Effects | Sound Effects has a 0.5 s minimum, varies on every generation, costs per second, and the usage policy restricts distributing standalone sound-effect files | 9 |
| One `AudioTrack` "voice bus" with navigation-guidance attributes, warm with silence | One cut path, gapless earcon then speech, and the output does not go to standby (3 s by default in AOSP), which avoids clipping the first syllable on Bluetooth | 12 |
| At most 4 distinct sounds; a dedicated sound for CRITICAL only | Human factors standards: at most about 4 absolutely identifiable signals; the imminent crash sound should be reserved for crash warnings | 9 |
| Once per episode, minimum persistence, no auditory cautionary warnings by default | NHTSA crash-avoidance guidance: require a minimum persistence before warning, avoid auditory cautionary warnings unless the benefit is shown, at most 3 presentations per event. Drivers match their response rate to the true-alarm rate ("cry wolf") | 5 |
| No distances or TTC numbers in safety speech; 1-3 words for crash warnings | NHTSA crash-avoidance guidance | 5, 8.2 |
| Navigation: one prepare prompt by speed band, one immediate prompt about 7 s out, "then" chaining within about 12 s | Waze community prompt distances by speed band, OsmAnd's time-based prompts, Valhalla's chaining threshold (13 s) and older research (combine maneuvers within 10 s) | 6 |
| US spoken distances rounded like Valhalla | Valhalla's `FormUsCustomaryLength`: 100 ft steps to 1,000 ft, then quarter, half and whole miles | 6.6 |

---

## 2. ElevenLabs

### 2.1 Text-to-speech models (as of 2026-09-26)

Source: [Models overview](https://elevenlabs.io/docs/overview/models), [API pricing](https://elevenlabs.io/pricing/api).

| Model id | Status | Stated latency (model only) | Languages | Max characters per request | API price per 1K characters | Notes |
|---|---|---|---|---|---|---|
| `eleven_flash_v2_5` | current, recommended for real time | ~75 ms; 50 ms claimed after the Feb 2026 upgrade ([blog](https://elevenlabs.io/blog/text-to-speech-api-up-to-40-faster-globally)) | 32 | 40,000 | $0.05 | no number normalization by default; `apply_text_normalization: "on"` is Enterprise-only |
| `eleven_flash_v2` | current | ~75 ms | English | 30,000 | $0.05 | English-only Flash |
| `eleven_multilingual_v2` | current, the default `model_id` | not stated, slower than Flash | 29 | 10,000 | $0.10 | most stable on long-form, normalizes numbers better; no `language_code` |
| `eleven_v3` | generally available since 2026-02-02 | not stated; the help centre calls it unsuitable for real time | 70+ | 5,000 | $0.10 | audio tags (`[whispers]`, `[shouts]`); takes vary more; not on the text-to-speech WebSocket; no SSML breaks, no request stitching |
| `eleven_v3_conversational` | current (release date unverified) | ~280 ms | 70+ | not stated | $0.05 | documented through the Text-to-Dialogue WebSocket |
| `eleven_turbo_v2_5`, `eleven_turbo_v2` | **deprecated** | - | - | - | - | replace with Flash |
| `eleven_monolingual_v1`, `eleven_multilingual_v1` | **removed** (scheduled 2026-07-09, [changelog](https://elevenlabs.io/docs/changelog/2026/6/8)) | - | - | - | - | - |

### 2.2 Endpoints and parameters

- Base URL `https://api.elevenlabs.io` routes to the nearest region (USA, Netherlands or Singapore; the `x-region` response header says which). `api.us.elevenlabs.io` forces the US. Data-residency hosts exist for the EU, India and Singapore; EU and India residency are Enterprise-only ([latency guide](https://elevenlabs.io/docs/developers/best-practices/latency-optimization)).
- Auth: header `xi-api-key`.
- HTTP (all `POST`, JSON body): `/v1/text-to-speech/{voice_id}` (whole file), `/stream` (chunked), `/with-timestamps` and `/stream/with-timestamps` (character alignment) ([convert](https://elevenlabs.io/docs/api-reference/text-to-speech/convert), [stream](https://elevenlabs.io/docs/api-reference/text-to-speech/stream)).
- Query: `output_format` (default `mp3_44100_128`), `enable_logging` (false = zero-retention mode, Enterprise-only). `output_format` is a **query** parameter: the request body has no such field, so putting it there silently returns the MP3 default ([OpenAPI](https://api.elevenlabs.io/openapi.json)). `optimize_streaming_latency` is deprecated; treat it as a no-op.
- Body: `text`, `model_id`, `language_code`, `voice_settings` (`stability` 0.5, `similarity_boost` 0.75, `style` 0, `use_speaker_boost` true, `speed` 1.0 by default; speed range 0.7-1.2), `pronunciation_dictionary_locators` (at most 3), `seed` (0-4294967295, best effort), `previous_text` / `next_text`, `previous_request_ids` / `next_request_ids` (at most 3 each), `apply_text_normalization` (`auto`, `on`, `off`).
- One third-party report says `speed` is ignored unless `stability` and `similarity_boost` are also sent (**unverified**). The rules always send all settings.
- WebSocket `wss://…/v1/text-to-speech/{voice_id}/stream-input`: text arrives in chunks and is generated by `chunk_length_schedule` (default `[120,160,250,290]`) or `flush: true`; the socket closes after 20 s idle by default (`inactivity_timeout` up to 180 s). A multi-context variant carries up to 5 contexts per connection. ElevenLabs itself recommends HTTP streaming when the full text is known up front, which is always the case for our cues ([WS reference](https://elevenlabs.io/docs/api-reference/text-to-speech/v-1-text-to-speech-voice-id-stream-input)).

### 2.3 Output formats

| Family | Values | Tier |
|---|---|---|
| PCM (expected: headerless signed 16-bit little-endian mono) | `pcm_8000`, `pcm_16000`, `pcm_22050`, `pcm_24000`, `pcm_32000` | any plan (no restriction documented) |
| PCM | `pcm_44100` | Pro or higher; `pcm_48000` tier undocumented |
| WAV | `wav_8000` … `wav_48000`, on the non-streaming convert endpoint only (not `/stream`) | `wav_44100` Pro or higher; `wav_48000` tier undocumented |
| MP3 | `mp3_22050_32`, `mp3_24000_48`, `mp3_44100_32` … `mp3_44100_128`; `mp3_44100_192` | `mp3_44100_192` Creator or higher |
| Opus, telephony | `opus_48000_32` … `_192`; `ulaw_8000`, `alaw_8000` | Opus tier and container undocumented |

ElevenLabs documents no channel count or byte order for text-to-speech PCM output (the only "mono, little-endian"
definition in its spec is for speech-to-text input). Headerless s16le mono is the expectation: verify it on day one
(no `RIFF` header, even length, byte count divided by 48,000 matches the clip length at 24 kHz).

### 2.4 Latency

- ElevenLabs' expected time to first byte with Flash over WebSocket is 100-150 ms in North America ([latency guide](https://elevenlabs.io/docs/developers/best-practices/latency-optimization)); the network round trip typically adds 20-200 ms depending on location, and model time grows with input length ([latency concepts](https://elevenlabs.io/docs/eleven-api/concepts/latency)). A whole clip (what our proxy returns) takes generation plus transfer, longer than the first byte.
- Independent (Coval leaderboard, WebSocket, handshake excluded, leading silence included): Flash v2.5 median 183 ms to first audio, v3 Conversational 317 ms ([benchmark](https://openbenchmarks.com/text-to-speech-benchmark-by-coval)).
- A cold TCP connection can dominate: one test from a high-latency link measured 375 ms of connect time ([VEXYL](https://vexyl.ai/elevenlabs-tts-latency-test-2026-real-world-results/)). Keep one keep-alive HTTP client on the laptop and warm it at startup.
- **Our estimate** for a 5-8 word phrase, tablet to laptop proxy to ElevenLabs from Atlanta, PCM: first audible sound after about 250-400 ms on warm Wi-Fi, 300-550 ms on LTE, plus 100-300 ms on a cold connection. At 30 mph a 400 ms delay is about 5 m of travel. This is why every CRITICAL and TRAFFIC phrase is cached.

### 2.5 Concurrency and rate limits

| Plan | Flash concurrency | Other models | Queue priority |
|---|---|---|---|
| Free | 4 | 2 | 3 |
| Starter | 6 | 3 | 4 |
| Creator | 10 | 5 | 5 |
| Pro | 20 | 10 | 5 |

Over the limit, requests are queued by plan priority (about 50 ms extra) and persistent overload returns `429`
([models](https://elevenlabs.io/docs/overview/models), [rate-limit blog](https://elevenlabs.io/blog/ai-rate-limiting-for-voice)).
The current error format ([errors](https://elevenlabs.io/docs/eleven-api/resources/errors)) has three 429 codes in
`detail.code`: `concurrent_limit_exceeded` (queue locally until a request completes), `system_busy` (retry after a
short delay) and `rate_limit_exceeded` (exponential backoff). `detail.status` is a legacy field; the older help page
still uses `too_many_concurrent_requests` ([help](https://elevenlabs.io/docs/help-center/technical/api-error-code-429)),
so match on `code` first and fall back to `status`. Log `detail.request_id`. Responses carry
`current-concurrent-requests` and `maximum-concurrent-requests` headers.

### 2.6 Controlling what is said

- **Normalization:** `apply_text_normalization` is `auto` by default, but the docs disagree about what that means for Flash; on Flash v2.5 `on` is Enterprise-only ([models](https://elevenlabs.io/docs/models)). Always set it explicitly, and send text that is already normalized ([best practices](https://elevenlabs.io/docs/overview/capabilities/text-to-speech/best-practices)).
- **Pronunciation dictionaries:** W3C PLS files with alias or phoneme rules, at most 3 per request, applied in order, first match wins, case-sensitive. Phoneme rules work only on `eleven_flash_v2` and `eleven_v3`; Multilingual v2 and Flash v2.5 need alias rules ([guide](https://elevenlabs.io/docs/eleven-api/guides/how-to/text-to-speech/pronunciation-dictionaries)). Pin `version_id` for repeatable output. Our alias table lives in our own normalizer instead, so it also covers the Android fallback voice.
- **Pauses:** SSML `<break time="…"/>` up to 3 s on Multilingual v2 and Flash; not on v3. Too many breaks cause artifacts. Gaps between clips are exact silence written by our player instead.
- **v3 audio tags** add expression but are more variable; they are not used for alerts.
- **Request stitching** (`previous_request_ids`) needs request ids younger than 2 hours and does not work on v3; our cues are whole sentences, so it is not needed.
- **Seed:** best effort only. The product guide says identical inputs still vary slightly ([product guide](https://elevenlabs.io/docs/eleven-creative/playground/text-to-speech)). Generate once, check, cache the bytes.
- **Loudness:** the text-to-speech endpoints have no loudness control. Normalize the cue bank ourselves (speech around -16 LUFS, true peak at or below -1 dBTP), and level earcons by peak because they are shorter than the 400 ms loudness gating block.

### 2.7 Voices

- Every old default voice (George, Sarah, Laura and the others) **expires on 2026-12-31** and is only visible to accounts created before March 2026. Legacy voices such as Rachel are already routed to replacements. Do not copy voice ids from examples ([default voices](https://elevenlabs.io/docs/help-center/product/voices/my-voices/what-are-default-voices)).
- Replacement default voices that sound suited to calm instructions, judging by their names only (**unverified by ear**): Elara (`WQP7cQUF5aAS6Axh5yaa`), Caleb (`AaOhDHYJ1XLZk74lXhdE`), Lawrence (`ktkP7Nsj67dw2zcplQYt`), Alicia (`BFd5oBc2DDna33pSi4Gf`), Finley (`fnYMz3F5gMEDGMWcH1ex`). Ids come from ElevenLabs' help page links. Listen to all five on the hardest phrases before choosing.
- **These replacements are Voice Library voices**: the help page links each one into the Voice Library, and "Voice Library voices are not available via the API to free tier users" ([voice library](https://elevenlabs.io/docs/eleven-creative/voices/voice-library)). Pay-as-you-go top-ups on the Free plan do not lift that restriction. Together with the old defaults being hidden from accounts created after March 2026, **a new account has no stock voice it can use over the API until a paid plan (the MLH promo) is active.** The `402` status for this case comes from a third-party report. Some library voices carry a credit multiplier.
- Voice Library voices are probably professional voice clones, which ElevenLabs ranks slowest for latency ("actively working on optimizing PVC latency for Flash v2.5"). Record each candidate's `category` from `GET /v1/voices/{id}` and measure its time to first byte; prefetching makes this matter little for our cues.
- Do not clone a teammate or a public figure: instant cloning requires the right and consent to clone, and a professional clone can only be of your own voice.

### 2.8 Pricing, plans and the hackathon

- API usage is billed in dollars at the per-model rates in section 2.1. Plan prices: Free $0, Starter $6, Creator $22 ($11 the first month), Pro $99 ([pricing](https://elevenlabs.io/pricing), [API pricing](https://elevenlabs.io/pricing/api)). The two pricing pages list different included amounts (credits versus characters); check at checkout.
- New self-serve plans have no overage billing. Extra use comes from prepaid pay-as-you-go top-ups (minimum $5) ([pay as you go](https://elevenlabs.io/docs/overview/administration/pay-as-you-go)).
- The Free plan is non-commercial, needs "elevenlabs.io" in the title of anything published, and blocks library voices over the API ([publishing help](https://elevenlabs.io/docs/help-center/legal/can-i-publish-the-content-i-generate-on-the-platform)). Audio generated during a paid plan may be used commercially, also after cancelling; beta features never.
- **HackGT 13:** MLH's prize page lists "Best Use of ElevenLabs", and MLH distributes an ElevenLabs promo code for a free 3-month subscription to participants ([MLH HackGT 13 prizes](https://www.mlh.com/events/hackgt-13/prizes), [MLH ElevenLabs](https://www.mlh.com/partners/elevenlabs)). The tier the code grants is **unverified**. Coupon links work only on an account that is currently on the Free plan.
- **Cost for this project:** a catalog of about 300 phrases x 35 characters generated 5 times (52,500 characters), plus 2 demo hours with a 35-character phrase every 10 s and no caching (720 phrases, 25,200 characters), is about 78,000 characters: about $3.90 on Flash. Creator (440K Flash characters) has more than 5x headroom; Starter (120K) works on Flash only. With the voice pack covering the demo sessions, demo runtime spend is close to zero.

### 2.9 Caching, retention and privacy

- The terms leave the user all rights in the output; no clause forbidding caching or replaying text-to-speech output was found, and the docs include a caching guide ([terms](https://elevenlabs.io/terms-of-use), [caching guide](https://elevenlabs.io/docs/eleven-api/guides/how-to/text-to-speech/streaming-and-caching-with-supabase)). That caching is permitted is our reading, not a legal opinion.
- History is kept by default; zero-retention mode (`enable_logging=false`) is Enterprise-only. Keep personal data (home addresses, contact names) out of spoken text. Street names and public destinations are fine. History items can be deleted through the API.

### 2.10 API key security

- **API terms, "No Client-Side Exposure":** keys may be used only from a server you control, never embedded in a browser, mobile app or client artifact ([ElevenAPI terms](https://elevenlabs.io/elevenapi-terms)).
- Keys can be restricted by endpoint scope (for example text-to-speech only), a monthly character limit and an IP allowlist of public addresses; user keys can expire after 15 minutes to 30 days ([API keys](https://elevenlabs.io/docs/overview/administration/workspaces/api-keys)). Use two text-to-speech-only keys with character limits: one for building the catalog and one for the demo.
- ElevenLabs is a GitHub secret-scanning partner: a key pushed to a public repository is disabled automatically. One bad commit would silence the demo. The key belongs in the environment or the gitignored `perception_engine/.env` only.
- Single-use tokens (`POST /v1/single-use-token/tts_websocket`, 15 minutes, one use) exist for the text-to-speech WebSocket only, not for REST ([tokens](https://elevenlabs.io/docs/api-reference/tokens/create)). They would let the tablet call ElevenLabs directly without holding a key, but the tablet may have no internet, so the laptop proxy is simpler.

### 2.11 SDKs

- Official REST SDKs: Python `elevenlabs` and JS `@elevenlabs/elevenlabs-js` (both 2.69.0 on 2026-09-24). The proxy does not need the Python SDK: the venv already pins `httpx`, and the repo forbids ad hoc `pip install`.
- The official Android SDK `io.elevenlabs:elevenlabs-android` (0.12.2) covers conversational agents over LiveKit/WebRTC only, not plain text-to-speech. The community Kotlin Multiplatform client needs Kotlin 2.4 (we are locked to 2.0.21). The tablet therefore needs **no** ElevenLabs library: it uses the existing OkHttp 4.12.0 to call the laptop.

---

## 3. Android playback on the Galaxy Tab S9

Sources: Android reference pages for [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[AudioAttributes](https://developer.android.com/reference/android/media/AudioAttributes),
[AudioFocusRequest](https://developer.android.com/reference/android/media/AudioFocusRequest),
[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), the
[audio focus guide](https://developer.android.com/media/optimize/audio-focus) and
[Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15).

- **Player:** `AudioTrack` in `MODE_STREAM`, 16-bit mono PCM at 24 kHz, fed in 20 ms blocks by one thread. `PERFORMANCE_MODE_LOW_LATENCY` only takes the fast path when the rate matches the device (usually 48 kHz), and the gain (tens of ms) is small next to network and Bluetooth delays, so the default mode is fine. ExoPlayer and MediaPlayer add a decoder, a prepare step and startup buffering per clip; ExoPlayer's audio-focus handling accepts only media and game usages.
- **Cutting:** in stream mode `stop()` plays out what is already written; an immediate stop is `pause()` then `flush()`. `flush()` only discards data not yet presented, and a later write can return a short count. So one writer thread owns the track and performs every cut between its 20 ms blocks (`setVolume(0f)`, `pause()`, `flush()`, `setVolume(1f)`, `play()`); a cut from another thread would race with a blocking `write()`. `setVolume` has no ramp, so there is no app-side fade. Expected cut latency is about 20-50 ms on the speaker plus whatever a Bluetooth sink has buffered (**unverified**; measure it). An utterance ends when the playback head passes its last frame, not when its last `write()` returns.
- **Attributes:** `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` with `CONTENT_TYPE_SPEECH` for speech and earcons. It uses the media volume and follows the connected Bluetooth or wired device. Avoid `USAGE_ALARM` (plays on the tablet speaker *and* the headset, audibly doubled), `USAGE_ASSISTANCE_SONIFICATION` (system volume, often muted), `USAGE_NOTIFICATION_EVENT` (Do Not Disturb can silence it) and `FLAG_AUDIBILITY_ENFORCED` (blocks Bluetooth). Samsung may customise the routing policy (**unverified**; check with `adb shell dumpsys audio`).
- **Focus:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is the documented choice for driving directions. Android ducks other apps by about 14 dB, and does not duck an app that plays speech content: that app gets a callback and usually keeps talking. For a collision warning that is not enough (FMVSS 127 asks for unrelated audio to be muted or brought within 5 dB of cabin noise), so CRITICAL cues request `AUDIOFOCUS_GAIN_TRANSIENT`, which asks other apps to pause. **Apps targeting Android 15 (API 35) can request focus only while they are the top app or run a foreground service**, otherwise the request fails. Our app targets 35 and keeps the screen on while driving. Keep the SIM ExoPlayer without focus handling, or each cue would pause the clip.
- **Bluetooth:** A2DP adds roughly 100-300 ms depending on the codec (secondary sources, **unverified**). AudioFlinger puts an idle output into standby after 3 s by default, and restarting it can clip the first syllable, so the voice bus keeps writing silence during a drive session. Never use SCO (telephony, narrowband). Estimate route latency with `AudioTrack.getTimestamp()`.
- **Fallback voice:** Android `TextToSpeech`, preferably the Google engine (`com.google.android.tts`), with an offline voice. Apps targeting Android 11 or later must declare the `TTS_SERVICE` intent in a manifest `<queries>` block. Pre-render phrases with `synthesizeToFile` and play them through the same voice bus. The engine chooses the WAV's sample rate and channels, so parse the header, convert to 16-bit mono and resample to 24 kHz before playing. `speak()` plays on the engine's own output, outside the voice bus, so it is not used for cues. Synthesis time on the Tab S9 is **unverified**.
- **Version pins (no upgrades):** OkHttp 4.12.0 (5.x is built with Kotlin 2.2), Media3 1.5.1 (1.10+ needs compileSdk 36), coroutines 1.9.0, serialization 1.7.3. Ktor is not needed.

---

## 4. In-vehicle auditory guidance

Several primary sources (NHTSA's 2016 human factors guidance DOT HS 812 360, SAE and ISO full texts) were behind
bot protection or paywalls. Numbers below come from sources that could be read; where a value is only reported
second-hand it says so.

### 4.1 Crash and hazard warnings

- **NHTSA "Preliminary Human Factors Guidelines for Crash Avoidance Warning Devices" (1996)** ([PDF](https://rosap.ntl.bts.gov/view/dot/3183/dot_3183_DS1.pdf)): only the highest-priority warning uses audio; crash warnings override everything; give a clearly distinguishable cue between two consecutive warnings; warnings stop by themselves when the condition ends; minimize false and nuisance warnings, including by requiring the condition to persist for a minimum time; audio is recommended for imminent warnings and that sound is reserved for them; avoid auditory cautionary warnings unless their benefit is shown; do not use audio for status (2.4.3). Speech: no TTC or distance numbers, 1-3 words for crash warnings with stronger words for imminent ones, at most 3 presentations per event, meant to be back to back (2.4.6.5; our spaced repeats are a deliberate deviation), about 156 words per minute, and a voice easy to tell apart from other speech in the car (2.4.6.7). It advises *against* a tone before crash speech (2.4.6.10: it adds about 0.5 s).
- **FAA Human Factors Design Standard, chapter 7** ([PDF](https://hf.tc.faa.gov/hfds/download-hfds/hfds_pdfs/Ch7_Alarms_audio_and_voice.pdf)): precede verbal warnings with a non-speech alert when the voice channel also carries other information (7.3.1.3); when reaction time is critical, that alert lasts 0.5 s (7.2.6.1); at most about 4 signals that must be identified absolutely (7.2.2.1). Our channel also carries navigation, so a 190 ms E1 precedes CRITICAL speech: our own compromise between NHTSA (no tone) and FAA (0.5 s), not a value from either. The earcon is also what tells a lifelike ElevenLabs voice apart from a passenger or a podcast.
- **IVBSS (DOT HS 810 905, 2008)** ([PDF](https://rosap.ntl.bts.gov/view/dot/3973/dot_3973_DS1.pdf)): two signals within about 500 ms interfere with each other; 36 % of alerts in an earlier field test were nuisance alerts from stationary non-threats.
- **FMVSS 127** (automatic emergency braking; vehicles built from 2029-09-01) ([text](https://www.law.cornell.edu/cfr/text/49/571.127)): collision-warning sound with a fundamental of at least 800 Hz, 6-12 pulses per second, duty cycle 0.25-0.95, 15-30 dB above cabin noise; other audio is muted or turned down.
- **NCAP timing** as reported by the NTSB ([SIR-15/01](https://www.ntsb.gov/safety/safety-studies/Documents/SIR1501.pdf)): at 45 mph, warn by 2.1 s TTC for a stopped lead vehicle, 2.4 s for a decelerating one and 1.8 s for a slower-moving one; ADAC (the German automobile club) judged one car's 4 s warning too early.
- **ISO 15006:2011** (preview): tonal signals with the main component at 400-2,000 Hz, a mix such as 800 + 3,000 Hz, full loudness within about 30 ms for critical sounds; time-critical safety warnings always go before non-safety signals, including navigation; route guidance may wait up to 10 s.
- **Wording:** in a simulator study with 80 drivers, "Brake now" reduced crashes most, ahead of "Danger", a directional phrase and a beep (Wu and Boyle 2021, Human Factors). The rules keep informational wording ("Vehicle too close.") instead of commands, because the perception is unvalidated and the product only presents information (plan section 38).

### 4.2 Trust and nuisance alarms

- People match their response rate to an alarm's true-alarm rate (Bliss, Gilson and Deaton 1995).
- Random false alarms reduce trust and compliance; alarms with a visible, interpretable reason do much less damage (Lees and Lee 2007). This is why every spoken cue has an on-screen counterpart.
- Drivers switch annoying systems off: lane departure warning was on in only about a third of one maker's vehicles, while forward collision warning stayed on (Reagan and McCartt 2016; IIHS 2017).
- About 70 % accuracy is the minimum before drivers stop using information (FHWA-HRT-15-007, [PDF](https://rosap.ntl.bts.gov/view/dot/39835/dot_39835_DS1.pdf)). Our typed stop-sign precision (0.38) and yellow-light precision (0.19) are below that, so those cues stay silent.
- Suppress a warning once the driver is already responding: in CICAS-V, basing the stop-sign logic on deceleration instead of the brake pedal cut the share of drivers receiving stop-sign alerts from 93 % to 4 % ([DOT HS 811 499](https://rosap.ntl.bts.gov/view/dot/9502/dot_9502_DS1.pdf)). We have no reliable ego deceleration yet, which is another reason the stop-sign cue is off.

### 4.3 Navigation prompts

- **Waze** (community documentation, not official): approach prompt at 1,000 ft below 43.5 mph, half a mile to 56 mph, one mile above; maneuvers chained with "then" when the next is within about 30 s ([Wazeopedia](https://www.waze.com/discuss/t/audible-instructions/377799)).
- **OsmAnd** (official docs): time-based prompts, "turn now" about 6.7 s and "turn in" about 22 s before the maneuver ([OsmAnd](https://osmand.net/docs/technical/algorithms/voice-prompt-triggering/)).
- **Valhalla** (source, 2026-09-21): US distance rounding (section 6.6 of the rules), "then" chaining when the current maneuver lasts under 13 s, at most 1-2 street names per prompt ([narrativebuilder.cc](https://raw.githubusercontent.com/valhalla/valhalla/master/src/odin/narrativebuilder.cc)).
- **FHWA ATIS guidelines** (1998): navigation instructions carry 3-4 information units, key item first or last; combine two maneuvers within 10 s ([FHWA](https://www.fhwa.dot.gov/publications/research/safety/98057/ch03b.cfm)).
- Google and Apple do not publish their prompt schedules. The Google Routes API gives maneuvers, distances and instruction text per step but no lane guidance, so lane cues must come from perception.

### 4.4 Output device and law

- The Samsung Galaxy Glasses are audio-only, so the visual redundancy the guidance assumes is missing on the wearable: safety speech must stand alone there, and every alert is mirrored on the tablet screen.
- Georgia prohibits driving while wearing headphones that impair hearing, except for communication (O.C.G.A. section 40-6-250); California bans devices in or over both ears (VC 27400). Use the tablet speaker, open-ear glasses or a single earbud. This is not legal advice.

---

## 5. What the code and data showed

Measured on 2026-09-26 (details in the rules, section 1):

- **Navigation engine** (`spatial/phase1/processor.js`, same as `src/phase1/processor.js` at `a3f432c`): one audio string per packet for the active maneuver only, regenerated every packet, no trigger distance and no "now" prompt. Templates: `TURN LEFT/RIGHT in {D}.` and `KEEP LEFT/RIGHT in {D}.` (upper case, including `in 0 m.`), `Take the exit in {D}.`, `Merge merge in {D}.`, `Continue straight in {D}.` / `Continue straight.` (at 0 m), and `You have arrived at your destination.` at any distance. `{D}` is whole metres below 1,610 m, then `X.X mi`. `speakAtDistanceMeters` is the maneuver's position along the route, not a trigger distance. No provider sets `exitNumber` or `requiredLane`; `START_ROUTE` is never produced; `offRoute` (more than 35 m from the route) does not trigger rerouting.
- **Driving Context** (`perception_engine/android/perception-bridge/…/context/DrivingContextEngine.kt`): speaks the navigation engine's string verbatim at first sight and when crossing 300 m. Lane guidance speech ("Move right now for Mock Street.") is always "now", because the inferred-lane start (300 m) equals the immediate threshold (300 m). `formatNavDistance` in IMPERIAL switches to miles at 0.1 mi, so 243 m displays as `0.2 mi`, not `800 ft`. The app runs METRIC through `BridgeConfig`.
- **Perception reliability** relevant to audio: light classifier accuracy 0.873 but YELLOW precision 0.19 and RED precision 0.76; typed stop-sign precision 0.38, speed-limit precision 6/6; pedestrian recall 0.60 per frame; lane `currentLane` exact on 79 % of 19 hand-labelled images, lane count within one on 89 %; sim distances may read 35-40 % low because of the assumed focal length.
- **Event rates** without arbitration: 38.9-66.4 spoken lines per minute on the three demo clips; 93-169 % talk time.

---

## 6. Measure on day one

The measurement plan, with pass criteria and what to do when a check fails, is section 13.4 of
[`AUDIO_CUE_RULES.md`](AUDIO_CUE_RULES.md).

---

## 7. Sources

ElevenLabs: [models](https://elevenlabs.io/docs/overview/models), [convert](https://elevenlabs.io/docs/api-reference/text-to-speech/convert),
[stream](https://elevenlabs.io/docs/api-reference/text-to-speech/stream), [WebSocket](https://elevenlabs.io/docs/api-reference/text-to-speech/v-1-text-to-speech-voice-id-stream-input),
[latency guide](https://elevenlabs.io/docs/developers/best-practices/latency-optimization), [best practices](https://elevenlabs.io/docs/overview/capabilities/text-to-speech/best-practices),
[pronunciation dictionaries](https://elevenlabs.io/docs/eleven-api/guides/how-to/text-to-speech/pronunciation-dictionaries),
[request stitching](https://elevenlabs.io/docs/eleven-api/guides/how-to/text-to-speech/request-stitching),
[default voices](https://elevenlabs.io/docs/help-center/product/voices/my-voices/what-are-default-voices),
[sound effects](https://elevenlabs.io/docs/overview/capabilities/sound-effects), [pricing](https://elevenlabs.io/pricing),
[API pricing](https://elevenlabs.io/pricing/api), [pay as you go](https://elevenlabs.io/docs/overview/administration/pay-as-you-go),
[API keys](https://elevenlabs.io/docs/overview/administration/workspaces/api-keys), [tokens](https://elevenlabs.io/docs/api-reference/tokens/create),
[ElevenAPI terms](https://elevenlabs.io/elevenapi-terms), [terms of use](https://elevenlabs.io/terms-of-use),
[usage policy](https://elevenlabs.io/use-policy), [429 help](https://elevenlabs.io/docs/help-center/technical/api-error-code-429),
[changelog](https://elevenlabs.io/docs/changelog), [Android SDK](https://github.com/elevenlabs/elevenlabs-android).

Android: [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[AudioAttributes](https://developer.android.com/reference/android/media/AudioAttributes),
[AudioFocusRequest](https://developer.android.com/reference/android/media/AudioFocusRequest),
[audio focus](https://developer.android.com/media/optimize/audio-focus),
[Android 15 changes](https://developer.android.com/about/versions/15/behavior-changes-15),
[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech),
[navigation apps for cars](https://developer.android.com/training/cars/apps/navigation),
[Oboe Bluetooth note](https://github.com/google/oboe/wiki/TechNote_BluetoothAudio),
[OkHttp changelog](https://github.com/square/okhttp/blob/master/CHANGELOG.md),
[Media3 release notes](https://github.com/androidx/media/blob/release/RELEASENOTES.md).

Human factors and navigation: NHTSA 1996 crash-avoidance guidelines, IVBSS DOT HS 810 905, DOT HS 812 511,
CICAS-V DOT HS 811 499 and FHWA-HRT-15-007 (all on [ROSA P](https://rosap.ntl.bts.gov/)),
[FMVSS 127](https://www.law.cornell.edu/cfr/text/49/571.127), [NTSB SIR-15/01](https://www.ntsb.gov/safety/safety-studies/Documents/SIR1501.pdf),
[ISO 15006 preview](https://www.sis.se/api/document/preview/913798),
[FAA HFDS chapter 7](https://hf.tc.faa.gov/hfds/download-hfds/hfds_pdfs/Ch7_Alarms_audio_and_voice.pdf),
[FHWA-RD-98-057](https://www.fhwa.dot.gov/publications/research/safety/98057/ch05.cfm),
[OsmAnd](https://osmand.net/docs/technical/algorithms/voice-prompt-triggering/),
[Wazeopedia](https://www.waze.com/discuss/t/audible-instructions/377799),
[Valhalla](https://github.com/valhalla/valhalla), [OSRM text instructions](https://github.com/Project-OSRM/osrm-text-instructions),
[Google Routes API](https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes),
[IIHS 2017](https://www.iihs.org/news/detail/lane-maintenance-systems-still-a-turnoff-for-many-drivers),
[O.C.G.A. 40-6-250](https://codes.findlaw.com/ga/title-40-motor-vehicles-and-traffic/ga-code-sect-40-6-250/).
