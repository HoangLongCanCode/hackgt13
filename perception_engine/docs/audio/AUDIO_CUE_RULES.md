# Audio cue rules: deterministic driving logic to audio cues

Status: specification, not implemented yet (2026-09-26). It covers production plan sections 22 (Audio Engine),
23 (ElevenLabs / VoiceProvider) and 24 (voice priority), inside the section 38 safety boundary. The optional LLM
layer (plan section 25) is out of scope: no model decides what is said or when.

Companion files:
- [`AUDIO_ENGINE_RESEARCH.md`](AUDIO_ENGINE_RESEARCH.md): ElevenLabs, Android playback and in-vehicle auditory
  guidance, with sources. Numbers here that are not measured on our clips come from there.
- [`audio_cues.v1.json`](audio_cues.v1.json): the machine-readable catalog. It is the single source for every
  number and phrase in sections 5 to 11; code loads it (section 12.6). If it and this file disagree, fix both in
  the same change.

| Shorthand | Path |
|---|---|
| `BRIDGE/` | `perception_engine/android/perception-bridge/src/main/kotlin/com/drivingassist/copilot/` |
| `APP/` | `driving_assist/app/src/main/java/com/drivingassist/glass/perception/` |
| navigation engine | `spatial/phase1/` on `main` (the same code as `src/phase1/` at commit `a3f432c`, branch `louis`) |
| relay | `perception_engine/nav/relay_core.js` |
| contracts | `perception_engine/contracts/` (`PROTOCOL_v2.md`, `schemas/`, `samples/v2/`) |

**Where the numbers come from.** "Measured" means end-to-end runs on 2026-09-26: the real perception server driven
by `bridge-cli` on the three demo clips `b1ff4656-0435391e` (city, day), `b1f4491b-cf446195` (highway, day) and
`b23adb0d-8a7aaced` (city, night) with their demo navigation sessions: 6 sim runs plus 1 live run on the city clip
with GPS replay (7 runs, 280 s), and 6 recorded logs in the gitignored `perception_engine/outputs/e2e/logs/`.
"Simulated" means a replay of those runs' 10 Hz Driving Context through a Python model of these rules (section 13.3).
The demo navigation sessions are synthetic (a fixed mock route rescaled to each clip, constant speed), so every
navigation time and every speed-based number describes the rules, not real driving.

---

## 1. Why this layer is needed

The Driving Context Engine (`BRIDGE/context/DrivingContextEngine.kt`) emits deterministic, edge-triggered
`DrivingEvent`s with a plan section 24 `Priority` and an optional `speech` string. Speaking them directly fails:

| Problem (measured) | Consequence if spoken as is |
|---|---|
| Speech-bearing events arrive at 38.9-66.4 per minute (pooled 48.1/min); estimated talk time is 93-169 % of drive time | A first-in, first-out speaker falls behind for good. In a simulation, CRITICAL *speech* waited up to 35 s, and only 49 % of spoken cues were still true when spoken |
| `FOLLOWING_CLOSE` / `FOLLOWING_NORMAL` flip about 16 and 15 times per minute; the lead track id changes every 0.32 s (median) | "Car ahead, 29 meters." several times a minute, at distances up to 54 m |
| `VEHICLE_TOO_CLOSE` fires 9.6 times per minute; 30 of 45 fired with the lead at 7 m or more. Headway uses the navigation speed, which is synthetic in the demo sessions; long-range alarms come from box-scale TTC spikes | "Vehicle too close." for a car 20-50 m away: nuisance alarms, which erode trust in every later alarm |
| The nearest known light changes colour 60-113 times per minute; after the Kotlin debounce and 5 m stickiness, 46-48. YELLOW precision is 0.19; side-facing heads often read GREEN | "Yellow light." / "Light is green." for the wrong head. GREEN must never sound like permission (AGENTS rule 7) |
| The navigation engine's `audio` changes on every packet (it embeds the distance), is upper case for turns (`TURN RIGHT in 116 m.`), metric below one mile, says `Merge merge in …`, and says "You have arrived" 177-591 m early | Unnatural or wrong speech, and about 1,600 distance variants per maneuver (not cacheable) |
| The Driving Context speaks each maneuver at most twice (first sight, crossing 300 m) and never near the turn | No prompt close to the turn |
| Every staleness blip re-emits every condition: on a phone hotspot "Vehicle too close." was spoken 5 times in 0.93 s | Repeats |
| On a reconnect the server re-sends the previous session's last `navigation.packet` (ARRIVE, 0 m), twice | A false "You have arrived" at session start |

This spec adds two pure, deterministic layers between the Driving Context and the speaker: a cue policy that
decides whether and what to say, and a voice arbiter that decides when, with preemption. Section 13.3 has the
simulated result of these rules on the measured runs.

---

## 2. Pipeline

```text
 laptop (RTX 5060)                                   tablet (Galaxy Tab S9)
 ─────────────────                                   ───────────────────────
 perception server ──perception.frame/update──▶ PerceptionBridge.world ──▶ DrivingContextEngine
 navigation engine ──navigation.packet────────▶ PerceptionBridge.navigation         │ context (levels)
   (spatial/phase1 via the relay)                                                    ▼
                                                  ┌─────────── AudioCoordinator (only non-pure part) ─────────┐
                                                  │ samples one AudioInput per step (sec. 3)                   │
                                                  │  ├─ AudioCuePolicy: perception rules (5), NavPromptScheduler│
                                                  │  │  (6), text rendering (8)            → CueRequest        │
                                                  │  └─ VoiceArbiter (7): priority, preemption, TTL, gaps       │
                                                  └───────────────┬────────────────────────────────────────────┘
                                                                  ▼ VoiceCommand (prepare | play | cut)
 /tts proxy on the perception server ◀── miss ── VoiceProviders (10): voice pack (RAM) → proxy → Android TTS clips
   ElevenLabs API (the key stays on the laptop)                   ▼ PCM 16-bit mono 24 kHz + earcons (9)
                                                   VoiceBus: one AudioTrack, one writer thread ──▶ speaker / glasses
```

Determinism: `AudioCuePolicy` and `VoiceArbiter` receive every input, time included, as arguments. The same sequence
of `AudioInput`s and provider/sink events gives the same cues and commands. Tests drive them with a virtual clock and
a fake sink.

---

## 3. Inputs

`AudioCoordinator` builds one immutable `AudioInput` per step (on every `context` emission and on its own 50 ms
timer; the bridge's tick is private) and passes it to both pure parts:

| Field | Source | Used for |
|---|---|---|
| `nowNs` | injected monotonic clock (`BridgeConfig.clockNs`; `SystemClock.elapsedRealtimeNanos` on Android) | every timer |
| `context` | `PerceptionBridge.context` (`BRIDGE/context/DrivingTypes.kt`) | `following` (state, leadTrackId, distanceMeters, ttcSeconds, relativeSpeedMps, headwaySeconds), `trafficLight` (trackId, state, distanceMeters), `pedestriansInPath`, `laneGuidance.action`, `speedLimit`, `perceptionStale` |
| `world` | a projection of `PerceptionBridge.world` made in the same step (`WorldAudioView`) | lanes confidence and age; for the selected light: `rawLightState`, `lightConfidence`, `lateralMeters`; `signs[]` (`key`, `signClass`, `speedLimit`, `isStop`, `confidence`, `lastSeenPts`, `distanceMeters`) |
| `navigation` | `PerceptionBridge.navigation` (`NavigationUpdate`: `routeState`, verbatim `packet`, `ptsSeconds`, `tripTimestampMs`, `receivedAtNs`, `stale`) | all navigation cues. Maneuver and label are mapped from this same update with `NavigationMapper.toNavigationState(update.routeState, update.packet)`; `bridge.navigationState` is read only for manual navigation (6.2) |
| `link` | `PerceptionBridge.link` (`LinkStatus`) | `state`, `mode`, `sessionId`, `role`, `takenOver`; additive: `firstTripStateMs`, `lastTripSpeedMps`, `lastTripAccuracyMeters` (12.3) |
| `media` | SIM only, additive bridge API (12.3): position now, playing, rate, epoch | pause and seek handling, navigation age, leftover filter |
| `hostVisible` | additive `PerceptionRuntime.hostStartedFlow` | mute in the background |
| `focusGranted`, `voiceMode` | Android layer (7.5, 12.4) | gates |

**Never used for speech:** the app's `RouteState.audio` (Tom's HUD caption, which also carries placeholders such as
`Waiting for route from the laptop`); the navigation engine's `routeState.audio` / `audioInstructions[0].content`
(logged only); `DrivingEvent.speech` (kept for logs and the console); `DrivingEvent.ptsSeconds` for timing (it is
`0.00` before the first result and frozen at the last result while stale, and restarts every live session).

---

## 4. Invariants (each one is a test)

1. **CRITICAL_SAFETY never waits on the network and never waits behind a lower priority.** Its audio is always in
   RAM. The only delays before a CRITICAL cue starts are its persistence (5.2), the session warm-up (invariant 2),
   and at most one other CRITICAL utterance already playing (7.2). It is never TTL-dropped while it waits for that.
2. **Gates by cue class** (defined in 5.0):
   - PERCEPTION cues are created and started only while perception is trustworthy: not `perceptionStale`,
     `link.state == CONNECTED`, not `takenOver`, not a watcher, SIM playing, host visible, voice mode allows it, and
     not inside `perceptionWarmupMs` (500 ms) after perception first becomes good in a session (the first 3-4 frames
     use the fallback ego-path polygon). A CRITICAL condition that reaches persistence during the warm-up is deferred,
     not dropped: if it still holds when the warm-up ends, it plays then.
   - NAVIGATION cues need: host visible, SIM playing, not `takenOver`, not a watcher, voice mode allows it.
   - SYSTEM cues need: host visible, SIM playing, voice mode allows it.
   - While `takenOver` or a watcher, the queue is dropped and non-CRITICAL playback is cut.
3. **Never voiced:** GREEN, YELLOW or UNKNOWN light states; any numeric TTC; any object distance; lane numbers or
   lane counts; "safe", "clear", "go" or "stop"; the maneuver STOP. Distances in speech are navigation distances only.
4. **Every spoken string comes from the catalog** (section 8), has no digits, matches `^[A-Z][A-Za-z ,'-]*\.$` and
   fits its word budget.
5. **One utterance at a time.** At most 3 presentations of one perception episode or one navigation event key.
6. **Memory survives staleness and reconnects.** Episodes and spoken-stage flags are not reset by
   `PERCEPTION_LOST` / `PERCEPTION_RESTORED` edges, a navigation stale gap or a socket reconnect. Perception memory
   resets when `link.sessionId` changes to a value other than `idle` while this client is the controller. Navigation
   memory resets only on a new route key. A media epoch change (a seek or loop reported by `PlaybackClock.update`)
   resets both.
7. **Route logic stays in the navigation engine.** The audio layer decides only when and how to say values the
   engine provides. Its only progress estimates are `distance - speed x age` with the age capped at 2 s, and a speed
   estimated from differences of the engine's own `distanceTraveledMeters` (6.3).
8. **Visual counterparts.** Every CRITICAL_SAFETY and TRAFFIC_ALERT cue corresponds to an alert already on screen
   (the status chip's top alert today); navigation cues correspond to the route caption. Cues with no visual
   counterpart today (`nav.off_route`, `nav.back_on_route`, `nav.paused`, `info.speed_limit`) are off by default
   until the AR app shows them (14.4).

---

## 5. Perception and system cues

### 5.0 Terms

| Term | Definition |
|---|---|
| Cue class | PERCEPTION = `safety.*`, `alert.*`, `info.speed_limit`, `lane.*`. SYSTEM = `info.road_alerts_*`. NAVIGATION = `nav.*`. Gates per class: invariant 2 |
| Episode | Tracked per cue id. The only shared pair is `alert.pedestrian` / `safety.pedestrian_critical` (one pedestrian episode). The JSON `family` field is for logs only |
| Unknown condition | While a gate of the cue's class is closed, its condition is unknown: persistence restarts from 0, the episode-end timer stops, nothing is created |
| Presentation | A cue that reached `Play` (speech, or earcon-only), including one cut later. `maxPerEpisode` counts presentations. A CRITICAL request dropped before `Play` is rolled back (count and `minRepeat` timestamp) and re-arms while its condition holds. A dropped non-CRITICAL perception request counts as presented (no re-arm, so dropped reds do not respawn) |
| Speech finished | The playback head passed the last frame of the speech (12.2), not the last `write()` |
| `vMeasured` | Measured ego speed: `link.lastTripSpeedMps` in LIVE when the last GPS fix had a speed (0 means stopped); null in SIM (the demo sessions' speed is synthetic) and when the fix had no speed. Used only by safety rules |
| `vNav` | Navigation speed (6.3). Used only by navigation timing |

### 5.1 Episode model

- An episode starts when its condition becomes true and ends when the condition has been false for `episodeEndMs`.
- The condition must hold continuously for `persistenceMs` before anything is created.
- A request is created when persistence is reached, presentations < `maxPerEpisode`, at least `minRepeatMs` has
  passed since the last presentation of the same cue id (across episodes), and every gate of the class holds.
- The earcon-only fallback applies only when `minRepeatMs` or `maxPerEpisode` blocks speech and the rule names it,
  never when a gate fails, and never for a re-entry inside the same episode.

### 5.2 Rule table

"Visual" is the counterpart on screen today. "Off" cues are in the catalog but disabled by default.

| Cue id | Class, priority | Condition | Persist | Episode ends | Max / min repeat | Earcon | Text | TTL | Re-validate at start | Visual |
|---|---|---|---|---|---|---|---|---|---|---|
| `safety.vehicle_too_close` | PERCEPTION, CRITICAL_SAFETY | the voiced VTC condition (5.3) | 250 ms, same `leadTrackId` throughout | 2,000 ms without the voiced condition | 3 / 8,000 ms; inside an episode or within 8 s of the last speech, speech again only on escalation (5.3), else a new episode plays E1 only | E1 | `Vehicle too close.` | 1,000 ms (7.2) | voiced condition holds | chip `TOO CLOSE` |
| `safety.pedestrian_critical` | PERCEPTION, CRITICAL_SAFETY | nearest `pedestriansInPath` has `distanceMeters ≤ 12` or `ttcSeconds ≤ 3` | 200 ms | 2,000 ms with no pedestrian in path (shared) | 2 / 6,000 ms; see the escalation rules below | E1 | `Pedestrian very close.` | 1,000 ms (7.2) | a pedestrian was in path within the last 1,000 ms | chip `PEDESTRIAN` |
| `alert.pedestrian` | PERCEPTION, TRAFFIC_ALERT | `pedestriansInPath` not empty, not critical | 200 ms | shared, as above | 1 / 6,000 ms | E2 | `Pedestrian ahead.` | 1,500 ms | a pedestrian was in path within the last 1,000 ms | chip `PEDESTRIAN` |
| `alert.red_light` | PERCEPTION, TRAFFIC_ALERT | `trafficLight.state == RED` on the **same** `trackId` for the whole persistence; at creation and at start `rawLightState == RED` and `lightConfidence` null or ≥ 0.6; `lateralMeters` null or \|·\| ≤ 6 m; `15 ≤ distanceMeters ≤ 60`; if `vMeasured` is known: `vMeasured ≥ 2.8 m/s` and `v² / (2 x max(0.1, d - v x 1.0 s)) ≤ 4.9 m/s²` (0.5 g, actionable) | 500 ms | 3,000 ms with no RED selected | 1 / 20,000 ms | E2 | `Red light ahead.` | 1,500 ms | same track still RED | chip `RED LIGHT` |
| `alert.stop_sign` (off) | PERCEPTION, TRAFFIC_ALERT | `speakStopSigns`; ≥ 3 reads (5.0) with `isStop` on the same sign key, `confidence ≥ 0.8`, `distanceMeters ≤ 50` | 0 | 5,000 ms unseen | 1 / 20,000 ms | E2 | `Stop sign ahead.` | 1,500 ms | sign still present | chip `STOP SIGN` |
| `alert.vehicle_close` (off) | PERCEPTION, TRAFFIC_ALERT | `speakVehicleClose`; `following.state == CLOSE` | 1,000 ms | 3,000 ms NORMAL | 1 / 15,000 ms | E2 | `Vehicle close ahead.` | 1,500 ms | still CLOSE or CRITICAL | chip `CLOSE` |
| `info.speed_limit` (off) | PERCEPTION, GENERAL_INFORMATION | `speakSpeedLimit`; N in {10, 15, …, 85} read ≥ 3 times with `confidence ≥ 0.9` on one sign key, no other value read on any sign in the last 10 s, and if `vMeasured` is known not `vMeasured - 0.447 N > 9 m/s` | the reads span ≥ 1,000 ms of `lastSeenPts` | value change | 1 per value / 10,000 ms between different values | none | `Speed limit {N}.` | 5,000 ms; a newer value replaces a queued one | `context.speedLimit == N` | none today |
| `info.road_alerts_paused` | SYSTEM, GENERAL_INFORMATION | stale for ≥ 2,500 ms of the last 3,000 ms (a restore shorter than 500 ms counts as stale), or `link.state != CONNECTED` or `takenOver` for 3,000 ms; after ≥ 1 s of good perception since app start | (in the condition) | not stale for 2,000 ms | 1 / 60,000 ms; memory survives a `sessionId` change | none | `Road alerts paused.` | 5,000 ms | still stale | chip `STALE` |
| `info.road_alerts_back` | SYSTEM, GENERAL_INFORMATION | 2,000 ms of continuous good perception after a spoken `info.road_alerts_paused` | 2,000 ms | - | 1 per paused episode | none | `Road alerts back.` | 5,000 ms | not stale | chip clears |
| `lane.change_left` / `lane.change_right` (off) | PERCEPTION, UPCOMING_NAVIGATION | see 6.5 | 3,000 ms | - | 1 per event key | none | 6.5 | 3,000 ms | action unchanged, confidence holds | none today |

**Pedestrian escalation** (the shared episode):
1. If the critical condition starts within 500 ms of the episode start, `alert.pedestrian` is skipped (a queued one is removed).
2. If `alert.pedestrian` is playing when the critical cue is created, it is not cut: the critical cue starts right after it ends, with no gap, as E1 only.
3. If `alert.pedestrian` finished its speech less than 4 s ago, the critical cue plays E1 only.
4. If `alert.pedestrian` was cut before its speech finished, or more than 4 s have passed, the critical cue plays E1 + speech.

The same words are never used for both urgencies: the critical text is stronger (NHTSA crash-warning guidance).

### 5.3 The voiced VTC condition

The Driving Context enters CRITICAL when any of distance, TTC or headway is below its threshold
(`DrivingContextConfig.following`). The audio layer re-derives which one fired, using the sticky exit values
(9 m, 2.5 s, 1.0 s), and voices the state only when it is corroborated:

| Trigger | Voiced when | Otherwise |
|---|---|---|
| distance (`distanceMeters < 9`) | the lead is closing (`relativeSpeedMps ≤ -0.5`), or `vMeasured ≥ 2.8 m/s` | display only, `drop(stationary)`: stopped or crawling behind a car is not an alarm (AGENTS known gap 4) |
| TTC (`ttcSeconds < 2.5`, distance ≥ 9 m) | `relativeSpeedMps ≤ -1.0` and the range-rate TTC `distanceMeters / -relativeSpeedMps ≤ 3.0 s`, and when `vMeasured` is known, `-relativeSpeedMps ≤ vMeasured + 3` | display only, `drop(implausible)`: a box-scale TTC spike with no matching range rate |
| headway only | never in v1 | display only, `drop(headwayOnly)`: headway uses the navigation speed, which is synthetic in SIM and usually missing on the Wi-Fi Tab S9 |

**Escalation inside an episode** (speech again, E1 + text): the lead is at most 0.6 x the distance at the last
speech, or `ttcSeconds ≤ 1.5 s` when the last speech had TTC > 1.5 s or none, and at least 2 s have passed since the
last speech. At most 3 presentations per episode. A condition that merely re-enters is silent.

There is no distance cap: a cap of `max(20 m, 1.5 s x v)` removed none of the persistent measured alarms and would
have delayed real stopped-lead warnings at speed (it only allows speech from TTC 1.5 s, while NCAP expects a warning
by 2.1 s).

### 5.4 Why (data and guidance)

| Choice | Reason |
|---|---|
| CRITICAL persistence 200-250 ms | Following CRITICAL runs: median 0.81 s, 33 % last ≤ 0.3 s; all four far TTC spikes lasted about 0.2 s. NHTSA crash-warning guidance asks for a minimum persistence |
| Distance trigger needs closing or a measured speed | 16 of 24 persistent CRITICAL runs were distance-only; 5 of those had an opening gap or no range rate (stopped or crawling) |
| Headway-only CRITICAL not voiced | 4 of the spoken VTC in an earlier simulation were headway-only at 19-22 m with TTC 8-13 s, driven by the synthetic 24.58 m/s |
| Escalation inside an episode | Without it, a headway alarm at 19 m kept an episode open that silenced the closest lead of the highway drive (6.0-6.9 m) 4 s later |
| RED: same track, raw state, 15-60 m, actionability | 30 % of selected-RED frames on the city clip had a raw state that was not RED (the confidence belongs to the raw state). Light distances never exceeded 30 m (size prior), so 60 m is not a real filter. Red-light warnings should be actionable (required deceleration ≤ 0.5 g, FHWA). A false red-light alert caused abrupt braking in CICAS-V |
| RED repeat 20 s | Sensitivity on the runs: 6 / 12 / 20 s gave 15.8 / 15.6 / 15.2 utterances per minute; 20 s had the most cues still true |
| YELLOW never voiced | YELLOW precision 0.19 |
| GREEN never voiced | AGENTS rule 7; unlit or side-facing heads read GREEN (25 of 164) |
| Stop signs, "vehicle close", speed limits, lane changes off by default | Typed stop-sign precision 0.38; `FOLLOWING_CLOSE` flips 16 times per minute; speed-limit OCR values differ from run to run of the same clip (30, 45, 50 vs 50 only) with confidence 0.99, and a wrong low limit on a highway could prompt hard braking; lane state is exact on 42 % of labelled images. Drivers stop trusting alerts below about 70 % accuracy (FHWA). Turn each on only after it is validated on labelled clips |
| No object distances in speech | Plan section 18; NHTSA guidance (no TTC or distance numbers in speech); sim distances may read 35-40 % low (assumed focal length) |
| "Road alerts paused." is spoken although it is status | NHTSA 1996 advises against audio for status. Exception: on audio-only glasses silence would read as "no hazards". Once per 60 s; the paired "Road alerts back." closes it |

Cyclists and riders in the path produce no alert today (the Driving Context's lead rule excludes bicycles and its
pedestrian rule is pedestrians only). That is an upstream gap (14.2).

### 5.5 Disposition of every `DrivingEventType`

The policy reads levels, not events, but every value of `DrivingEventType` has a row here and in `eventDispositions`
in the JSON. A unit test iterates `DrivingEventType.entries` and fails on a missing value.

| `DrivingEventType` | Produced today by | Audio | Cue ids | Reason when silent |
|---|---|---|---|---|
| `KEEP_LANE` | lane guidance | display only | - | a confirmation adds talk time |
| `CHANGE_LANE_LEFT`, `CHANGE_LANE_RIGHT` | lane guidance (side inferred from the turn) | off by default | `lane.change_*` | lane state exact on 42 % of labelled images; the side is inferred (6.5) |
| `TURN_LEFT`, `TURN_RIGHT` | navigation engine (mock, Google) | spoken | `nav.prepare`, `nav.immediate` | - |
| `KEEP_LEFT`, `KEEP_RIGHT` | a hand-written `route.json` only | spoken | `nav.prepare`, `nav.immediate` | - |
| `MERGE` | navigation engine (Google `merge`) | spoken | `nav.prepare`, `nav.immediate` | - |
| `MERGE_LEFT`, `MERGE_RIGHT` | nothing today (the engine sends `turnDirection: "merge"`) | spoken | `nav.prepare`, `nav.immediate` | - |
| `EXIT` | `EXIT_HIGHWAY` from a hand-written `route.json`; `bridge-cli --nav-stub` | spoken | `nav.prepare`, `nav.immediate` | - |
| `ENTER_HIGHWAY` | nothing today | spoken | `nav.prepare`, `nav.immediate` | - |
| `FOLLOW_ROAD` from `GO_STRAIGHT` / `START_ROUTE` | navigation engine | long segments only | `nav.continue` | a short straight needs no prompt |
| `FOLLOW_ROAD` from an unknown engine type | `NavigationMapper.maneuverFor` fallback | display only, `drop(unknownManeuver)` | - | an unknown maneuver is never spoken as "continue" |
| `STOP` | nothing today | display only | - | a bare "Stop." sounds like a braking command (plan section 38) |
| `ARRIVE` | navigation engine | spoken | `nav.arrive_prepare`, `nav.arrived` | - |
| `VEHICLE_TOO_CLOSE` | following CRITICAL | spoken when voiced (5.3) | `safety.vehicle_too_close` | - |
| `FOLLOWING_CLOSE` | following CLOSE | off by default | `alert.vehicle_close` | flips about 16 times per minute |
| `FOLLOWING_NORMAL` | following NORMAL | display only | - | status |
| `TRAFFIC_LIGHT_RED` | light | spoken | `alert.red_light` | - |
| `TRAFFIC_LIGHT_YELLOW` | light | display only | - | precision 0.19 |
| `TRAFFIC_LIGHT_GREEN` | light | display only | - | never permission (AGENTS rule 7) |
| `PEDESTRIAN_IN_PATH` | pedestrians | spoken | `alert.pedestrian`, `safety.pedestrian_critical` | - |
| `STOP_SIGN` | signs | off by default | `alert.stop_sign` | precision 0.38 |
| `SPEED_LIMIT` | signs | off by default | `info.speed_limit` | run-to-run OCR differences |
| `ROAD_SIGN` (yield, doNotEnter, pedestrianCrossing) | signs | display only | - | not validated; doNotEnter was never correct in the checked set |
| `PERCEPTION_LOST`, `PERCEPTION_RESTORED` | staleness edges | through debounced cues | `info.road_alerts_paused`, `info.road_alerts_back` | the raw edges flap on every blip |

---

## 6. Navigation cues (NavPromptScheduler)

### 6.1 Principle

The navigation engine provides the route and its values. Its spoken string cannot be used (section 1), so the audio
layer renders speech from the engine's structured fields with fixed templates: deterministic text and a small,
cacheable phrase set. **This is a documented exception** to plan section 23 ("the navigation engine generates
deterministic text") and to AGENTS rule 3 ("spoken text comes from phase1"); section 14.5 has the text that amends
the rule in the same change, and section 14.1 asks the navigation engine owners to adopt these templates.

### 6.2 Packets, keys and targets

- **Accepted packet.** SIM: `|packet.ptsSeconds - media position at receivedAtNs| ≤ 2.0 s`. LIVE: `tripTimestampMs ≥ link.firstTripStateMs` (the first `client.trip_state` this bridge sent on the current connection). Everything else (the replayed packet of a previous connection or session) is logged and ignored.
- **Missing fields.** No navigation cue from a packet without `packet.routeId`, `packet.activeManeuver.eventId`, or a finite, non-negative `routeState.distanceMeters`. A null distance never becomes 0.
- **Route key** `routeKey = routeId + "|" + (tripId ?: "")`. A new route key resets navigation memory.
- **Target maneuver.** The first entry of `packet.upcomingManeuvers` (sorted by position along the route, starting with the active maneuver, empty after the ARRIVE position) at or after `activeManeuver` whose `type` is not `GO_STRAIGHT` or `START_ROUTE`. If there is none, the active maneuver itself when it is not one of those types. Every stage, the event key and the "then" chain use the target. Its maneuver: `NavigationMapper.maneuverFor(target.type, turnDirection)`, with `routeState.turnDirection` only when the target is the active maneuver. Its label: `target.roadName` (for EXIT: `Exit {target.exitNumber}` when present).
- **Event key** `eventKey = routeKey + "|" + target.eventId`.
- **Settle.** An event key produces cues only after 2 accepted packets with different `ptsSeconds` (SIM) or `tripTimestampMs` (LIVE), or 500 ms after its first accepted packet.
- **Passed.** A settled change of target from A to B with position(B) > position(A) means A was passed at that step. Only then are queued cues of A dropped (supersede).
- **Stale.** While `navigation.stale` (no packet for 10 s) no navigation cue is created except `nav.paused` on the step where stale becomes true, and queued navigation cues are dropped.
- **Manual navigation.** When the navigation input was set with `PerceptionBridge.setNavigation` (additive flag `navigationIsManual`), it is the only source for speech: `routeKey = "manual"`, `eventKey = "manual|" + maneuver + "|" + label`, `d = navigationState.distanceMeters` extrapolated with its `egoSpeedMps` (else 0) since it last changed, destination `your destination`, no settle, no "then", no `nav.start`. Packets are logged only while manual navigation is active.

### 6.3 Speed, distance and thresholds

- **`vNav`**, in order: `packet.progress.speedMps` when above 0.5 m/s; else, in LIVE, `link.lastTripSpeedMps` when known (0 = stopped); else `vEst` = the change of `progress.distanceTraveledMeters` over the change of `tripTimestampMs` across the accepted packets of the last 3-10 s (at least 2 packets, clamped to 0.5-45 m/s); else unknown.
- **Thresholds** use `vNav`, or `defaultSpeedMps` = 11.2 m/s (25 mph) when unknown. **Extrapolation** uses `vNav`, or 0 when unknown: a car waiting at a light is never moved forward by an assumed speed.
- **Age.** SIM: `(media position now - packet.ptsSeconds) / rate` (media time: frozen while paused). LIVE: `(nowNs - receivedAtNs) / 1e9`. Both capped at `maxExtrapolationS` = 2.0 s and floored at 0. While SIM is paused no stage is evaluated, created, marked spoken or skipped.
- **Distance** `d = max(0, d_packet - v_extrap x age)`, with `d_packet = routeState.distanceMeters` when the target is the active maneuver, else `target.distanceMeters - progress.distanceTraveledMeters`.
- **`leadS`** = 1.0 s: speech latency plus the time to reach the number in the sentence.
- **`P(v)`**, prepare distance: 300 m below 19.4 m/s (43.5 mph); 805 m (half a mile) from 19.4 to 25 m/s (56 mph); 1,609 m (one mile) above. These are the approach distances the Waze community documents per speed band. The first band is 300 m, not 305 m, so its spoken value is `one thousand feet`, not `a quarter mile`.
- **`I(v)`**, immediate distance: `clamp(v x 7 s, 45 m, 200 m)`: 53 m at 7.6 m/s, 172 m at 24.6 m/s. About 7 s before the maneuver, close to OsmAnd's 6.7 s "turn now" and the engine's own 50 m tier.

### 6.4 Stages

| Stage | Trigger (evaluated every step) | Priority | Template | TTL | Default |
|---|---|---|---|---|---|
| `nav.start` | first settled target of a new route key | UPCOMING_NAVIGATION | `Starting route to {destination}.` | 6,000 ms | on |
| `nav.continue` | a new settled target with `d ≥ max(60 s x v, 1,609 m)` | UPCOMING_NAVIGATION | `Continue for {distance}.` | 6,000 ms | on |
| `nav.prepare` | `d ≤ P(v) + v x leadS`; not within `postManeuverHoldMs` (3,000 ms) after a pass; skipped for good (logged) when `d - I(v) < v x 4 s` | UPCOMING_NAVIGATION | `In {distance}, {maneuver}{onto}.` | 6,000 ms | on |
| `nav.immediate` | `10 m ≤ d ≤ I(v)`; after a chained immediate, the next event's immediate also waits `postManeuverHoldMs` unless `d < 3 s x v` | IMMEDIATE_NAVIGATION | `{Maneuver}{onto}{then}.`; with distance in LIVE (below) | 3,000 ms; dropped once `d < 10 m` | on |
| `nav.arrive_prepare` | target is ARRIVE; same trigger, hold and skip as `nav.prepare`, with `I` replaced by `arrivedMeters` | UPCOMING_NAVIGATION | `In {distance}, you will arrive at {destination}.` | 6,000 ms | on |
| `nav.arrived` | target is ARRIVE and `d ≤ arrivedMeters` (15 m); in LIVE only when `packet.source.provider != "mock"` (live mock routes are not rescaled, so they report 0 m hundreds of metres early) | IMMEDIATE_NAVIGATION | `You have arrived at {destination}.` | 5,000 ms | on |
| `nav.off_route` | `offRoute` true for `max(3,000 ms, 60 m of travel)`; in LIVE only when `lastTripAccuracyMeters ≤ 25` | UPCOMING_NAVIGATION | `You are off route.` | 6,000 ms, 60 s repeat gap | off |
| `nav.back_on_route` | `offRoute` false for 3,000 ms after a spoken `nav.off_route` | GENERAL_INFORMATION | `Back on route.` | 5,000 ms | off |
| `nav.paused` | `navigation.stale` becomes true while a route was active (LIVE, or SIM playing) | GENERAL_INFORMATION | `Navigation paused.` | 5,000 ms | off |

### 6.5 Stage rules

- **Once per event key:** `nav.prepare`, `nav.immediate`, `nav.arrive_prepare`, `nav.arrived` and the lane cue. At most 3 presentations per event key across them, re-queues included; the lane cue is dropped first.
- **A stage dropped before `Play` stays armed** while its trigger holds.
- **Off route:** while the *debounced* off-route state holds, `nav.prepare`, `nav.immediate` and lane cues are suppressed (the engine does not reroute, so the next instruction may be wrong). An unspoken `nav.immediate` may still fire. After the debounced state clears, stages not yet spoken re-arm.
- **Late binding:** `{distance}` is rendered when the cue is bound (7.3), not when it is created, so a prompt that waited behind another one speaks the right bucket.
- **"Then" chaining:** at `nav.immediate`, `next` = the first entry after the target in `upcomingManeuvers` whose type is not `GO_STRAIGHT` or `START_ROUTE`. No chain when the target is not in the list. When `next.distanceMeters - target.distanceMeters ≤ max(12 s x v, 60 m)`, `{then}` = `, then {maneuver of next}` (no `{onto}`), or `, then arrive at {destination}`, and the next event's prepare is marked spoken.
- **LIVE position uncertainty:** in LIVE, when `lastTripAccuracyMeters` is unknown or above 20 m, or `{onto}` is empty, `nav.immediate` renders `In {distance}, {maneuver}{onto}.` instead of the bare form (at least `one hundred feet`), and it does not fire when `d < lastTripAccuracyMeters`.
- **Lane cues (`speakLaneChange`, off by default):** created when `context.laneGuidance.action` is `CHANGE_LANE_LEFT` / `RIGHT` for 3,000 ms, `world` lanes confidence ≥ 0.7 over that time and lanes age ≤ 1 s, not off route, `d > I(v)` (before the immediate prompt), and the lane side came from the route (`laneHintInferred == false`) unless `allowInferredLaneSide`. Text is informational: `Right lane for the turn.` / `Left lane for the turn.` (`… for the exit.` for EXIT). Priority UPCOMING_NAVIGATION; lane cues never cut anything (7.2) and are PERCEPTION cues for the gates.
- **Workload lockout:** GENERAL_INFORMATION and SOCIAL cues do not start from the start of a `nav.immediate` utterance until the earliest of: 5 s after that maneuver is passed, 20 s after the immediate prompt started, navigation stale, off route, or a new route key.
- **Never spoken:** the engine's `GO_STRAIGHT` at 0 m (`Continue straight.`), its "arrived" text at any distance, and app `RouteState` strings.

### 6.6 Maneuver phrases

| Maneuver | `{maneuver}` | `{onto}` |
|---|---|---|
| `TURN_LEFT` / `TURN_RIGHT` | `turn left` / `turn right` | ` onto {street}` |
| `KEEP_LEFT` / `KEEP_RIGHT` | `keep left` / `keep right` | ` onto {street}` |
| `MERGE` | `merge` | ` onto {street}` |
| `MERGE_LEFT` / `MERGE_RIGHT` | `merge left` / `merge right` | none |
| `EXIT` | `take exit {number}` when the label is `Exit {number}`, else `take the exit` | none |
| `ENTER_HIGHWAY` | `take the ramp` | ` onto {street}` |
| `STOP` | display only | - |
| `FOLLOW_ROAD` | only through `nav.continue` | - |
| `ARRIVE` | only through `nav.arrive_prepare` / `nav.arrived` | - |

`{Maneuver}` is the phrase with a capital first letter. `{street}` is the normalized label (8.3), omitted when it is
missing or longer than 4 words. For the Google provider (`packet.source.provider == "google"`), `roadName` holds the
whole instruction: keep only the text after the last ` onto `, else omit it; other providers' `roadName` is used as
is. `{destination}` is the text of `packet.destination.label` before the first comma, normalized, or
`your destination` when it is missing, contains digits after normalization, or is longer than 4 words.

### 6.7 Spoken distances (US customary, Valhalla's rounding)

`{distance}` = `speakDistance(d - v x leadS)`. Round the feet first:

| Distance | Spoken as |
|---|---|
| under 95 ft | not spoken (the immediate form is used) |
| nearest 100 ft is 1,000 ft or less | `one hundred feet` … `one thousand feet` |
| above that, below 0.625 mi | nearest quarter mile: `a quarter mile`, `half a mile` |
| 0.625-2 mi | nearest half mile: `half a mile`, `one mile`, `one and a half miles`, `two miles` |
| over 2 mi | nearest whole mile: `three miles`, `four miles`, … |

Rounding is half up. `speakDistance(243 m)` (797 ft) is `eight hundred feet`, the plan section 22 distance. Through
the scheduler, a MERGE_LEFT first seen at 243 m at 11.2 m/s settles 0.5 s later and subtracts the lead: 226 m, so it
is spoken `In seven hundred feet, merge left.` (the template puts the distance first on purpose: key item first or
last, FHWA). Both are unit tests. A metric table (10 m steps to 90 m, 100 m steps to 900 m, half kilometres to 3 km,
then whole kilometres) is used when `speechUnits = METRIC`; the default is IMPERIAL.

The Glass route caption is the navigation engine's metric, upper-case `RouteState.audio` (`APP/RouteSource.kt`,
drawn by `AROverlay`). No `BridgeConfig` setting changes it, and `DrivingContextEngine.formatNavDistance` in IMPERIAL
shows 243 m as `0.2 mi`. Screen and voice agree only once the caption is rendered from the same renderer and bucket
(14.4).

### 6.8 Expected navigation cues on the demo sessions

A reference model of sections 6.2-6.7 with late binding, run on the relay's 0.5 s `navigation.packet` timelines,
gives exactly these cues when `laneGuidance` is null (lane cues need recorded perception and are checked by the
replay tests). The model plays each utterance for `words / 2.6 + 0.2 s`, then waits the 700 ms minimum gap; tests use
the same durations through a fake sink.

| Clip (speed) | Created (s) | Starts (s) | Stage | d at start | Text |
|---|---|---|---|---|---|
| city `b1ff4656-0435391e` (7.64 m/s) | 0.5 | 0.5 | `nav.start` | - | Starting route to Piedmont Park. |
| | 0.5 | 3.35 | `nav.prepare` | 98 m | In three hundred feet, turn right onto Mock Street. |
| | 9.2 | 9.2 | `nav.immediate` | 53 m | Turn right onto Mock Street. |
| | 19.5 | 19.5 | `nav.arrive_prepare` | 154 m | In five hundred feet, you will arrive at Piedmont Park. |
| | 37.65 | 37.65 | `nav.arrived` | 15 m | You have arrived at Piedmont Park. |
| highway `b1f4491b-cf446195` (24.58 m/s) | 0.5 | 0.5 | `nav.start` | - | Starting route to Amtrak station in Atlanta. |
| | 0.5 | 4.1 | `nav.prepare` | 306 m | In nine hundred feet, turn right onto Mock Street. |
| | 9.5 | 9.5 | `nav.immediate` | 172 m | Turn right onto Mock Street. |
| | 19.5 | 19.5 | `nav.arrive_prepare` | 517 m | In a quarter mile, you will arrive at Amtrak station in Atlanta. |
| | 39.95 | 39.95 | `nav.arrived` | 14 m | You have arrived at Amtrak station in Atlanta. |
| night `b23adb0d-8a7aaced` (10.03 m/s) | 0.5 | 0.5 | `nav.start` | - | Starting route to Colony Square. |
| | 0.5 | 3.35 | `nav.prepare` | 128 m | In four hundred feet, turn right onto Mock Street. |
| | 9.1 | 9.1 | `nav.immediate` | 70 m | Turn right onto Mock Street. |
| | 19.5 | 19.5 | `nav.arrive_prepare` | 209 m | In seven hundred feet, you will arrive at Colony Square. |
| | 38.9 | 38.9 | `nav.arrived` | 15 m | You have arrived at Colony Square. |

For comparison, today's Driving Context on the city clip speaks `Continue straight.`, then `TURN RIGHT in 116 m.` at
1.0 s, then `You have arrived at your destination.` at 16.5 s with 177 m still to go, and nothing near the turn. On
the highway clip it also repeats TURN RIGHT at 300 m and ARRIVE at 300 m.

### 6.9 Plan scenarios (sections 31-33)

| Scenario | Source today | Expected speech with defaults | Missing |
|---|---|---|---|
| City intersection | session `b1ff4656-0435391e` | the 6.8 rows, plus `Red light ahead.` when a RED 15-60 m ahead holds on one track for 500 ms | nothing |
| Night driving | session `b23adb0d-8a7aaced` | the 6.8 rows, plus `Red light ahead.` under the same rule | nothing |
| Following distance | any clip | `Vehicle too close.` only for a voiced CRITICAL (5.3); CLOSE is display only | the demo script must say the "contextual alert" is visual for CLOSE |
| Lane change | session `b1f4491b-cf446195` (right lane inferred within 300 m) | silent by default; with `speakLaneChange` and `allowInferredLaneSide`: `Right lane for the turn.` after the prepare prompt | a route with `requiredLane`, and validated lane state |
| Highway exit (`Exit 23B`) | none: no provider emits `exit` or sets `exitNumber` | `In half a mile, take exit twenty-three B.`, then `Take exit twenty-three B.` | a hand-written session on the highway clip with `maneuver: "exit"`, `exitNumber: "23B"`, `requiredLane: "right"`, or manual navigation (`bridge-cli --nav-stub`) |
| Connected driving | none | none | SOCIAL is reserved (7.8) |

---

## 7. Voice arbiter

One channel. The arbiter receives `CueRequest`s and provider and sink events, and emits `VoiceCommand`s
(`Prepare`, `Play`, `Cut`, `CancelPrepare`).

### 7.1 Cue keys and admission

- **Cue key** = (cue id, scope). Scope is the event key for navigation stages and lane cues, the route key for
  `nav.start`, `nav.off_route`, `nav.back_on_route` and `nav.paused`, and the episode index for perception and system cues.
- **Dedupe:** a request whose key is playing is dropped (`drop(dedupe)`). A request whose key is queued replaces the queued payload in place, keeping the older creation time.
- **Queue:** at most 4 entries, ordered by `Priority.rank`, then creation time. On overflow, insert, then evict the lowest priority (oldest among equals), never a CRITICAL cue (`drop(overflow)`).

### 7.2 Preemption

| New cue | Cuts what is playing when it is | Otherwise |
|---|---|---|
| CRITICAL_SAFETY | any non-CRITICAL cue, except `alert.pedestrian` of its own episode (5.2 escalation rule 2) | behind a different CRITICAL cue: queued first, never TTL-dropped while that cue plays, its TTL starts when that cue ends, and it starts right after it with its own E1 (a clearly distinguishable cue between two warnings). The same cue key is dropped |
| TRAFFIC_ALERT | UPCOMING_NAVIGATION, GENERAL_INFORMATION, SOCIAL; IMMEDIATE_NAVIGATION when more than 1,000 ms of it remains | starts 500 ms after the blocking utterance ends (not `minGapMs`), and its TTL starts at that end |
| IMMEDIATE_NAVIGATION | GENERAL_INFORMATION, SOCIAL, and the UPCOMING prompt or lane cue of the same event key | queued |
| UPCOMING_NAVIGATION, GENERAL_INFORMATION, SOCIAL | nothing | queued |
| Lane cue | nothing, ever | queued behind the playing prompt; its TTL starts at that prompt's end; dropped when `nav.immediate` of the same event key has started |
| Earcon-only | never cuts speech. A TRAFFIC earcon-only cue is dropped when anything is playing. A CRITICAL earcon-only cue cuts non-CRITICAL speech of another family; after speech of its own family it starts right when that speech ends | not queued otherwise; not subject to `minGapMs` |

A cut is executed by the voice bus writer between 20 ms blocks (12.2). There is no app-side fade.

### 7.3 Bind and start

The queue head is **bound** at the first step where conditions 1-4 hold: its text is rendered (late binding) and
frozen, its readiness deadline starts (10.3), and a `Prepare` command is sent. TTL still counts from creation (or
from the end of the utterance it was allowed to wait for, 7.2). The head starts when:

1. nothing is playing, and at least `minGapMs` (700 ms) has passed since the last utterance ended (CRITICAL ignores the gap; 7.2 gives TRAFFIC 500 ms);
2. its TTL has not passed;
3. its re-validation holds, and every gate of its class holds;
4. no workload lockout applies (6.5);
5. its audio is ready, or its readiness deadline has passed and the fallback of 10.3 applies;
6. for `nav.prepare` / `nav.arrive_prepare`: `d - I(v) ≥ v x (its duration + 1 s)` (with `I = arrivedMeters` for arrival), else it is dropped and logged as a skip.

A TTL expiry or failed re-validation while waiting for readiness is `drop(ttl)` / `drop(stale)`.

### 7.4 After a cut

A cut navigation cue is re-queued once (and bound again when it next reaches the head) if it is still valid:
`nav.immediate` while `d ≥ 3 s x v`; `nav.prepare` / `nav.arrive_prepare` while `d - I(v) ≥ v x (its duration + 1 s)`;
`nav.start` while no maneuver prompt of the route has started. Other cut cues are not re-queued.

### 7.5 Pause, seek, session, takeover, focus and audibility

- **SIM paused:** cut non-CRITICAL, drop the queue; a CRITICAL cue already playing finishes.
- **Media epoch change** (seek or loop): cut everything, drop the queue, reset perception and navigation memory.
- **New non-idle `sessionId`** while controller: reset perception memory only (invariant 6).
- **Taken over or watcher:** drop the queue, cut non-CRITICAL; only `info.road_alerts_paused` may still play.
- **Host not visible:** cut everything, drop the queue.
- **Audio focus:** CRITICAL_SAFETY requests `AUDIOFOCUS_GAIN_TRANSIENT` (other apps are expected to pause, speech apps included); every other cue requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, always with the voice bus attributes. Focus is abandoned 500 ms after the bus goes idle. If a request is refused (for example the Android 15 rule that only the top app or a foreground service may take focus): CRITICAL and TRAFFIC cues play anyway (logged `focus-denied`); others retry every 500 ms until their TTL. The app keeps the activity on top with the screen on during LIVE and SIM; a `mediaPlayback` foreground service is needed only for split-screen or background use. This is not an FMVSS 127 compliance claim.
- **Audibility:** at session start and on each route or volume change, if the media volume is below 40 % of maximum the chip shows `VOICE LOW`; if the route is Bluetooth and `AudioTrack.getTimestamp()` latency exceeds 250 ms the chip shows `VOICE DELAYED`. The app never changes the system volume.

### 7.6 Log line

Every decision is one line, for the tablet log and `bridge-cli --voice`:

```text
VOICE play|queue|bind|cut|requeue|skip|drop(ttl|stale|dedupe|overflow|superseded|lockout|focus|paused|host|takenover|epoch|offroute|warmup|stationary|implausible|headwayOnly|unknownManeuver|notready|disabled) [PRIORITY] cueId key="…" text="…" src=bank|proxy|local|earcon t=<nowNs/1e9, 3 decimals> media=<position or ->
```

Driver controls log as `VOICE control mode=… | repeat | volume=…`.

### 7.7 Debug visibility (plan section 33)

`VoiceArbiter` exposes a `StateFlow<VoiceDebugState>`: now playing (cue id, priority, source), the queue (with
priority and age), the last 20 log lines, drop counts by reason, utterances in the last 60 s, provider health (voice
pack phrases loaded / expected, proxy `/tts/health`, local TTS ready) and the route latency. In GLASS mode the status
chip shows only degraded states (`VOICE LOCAL`, `VOICE OFF`, `VOICE LOW`, `VOICE DELAYED`, `VOICE MUTED`). In DEBUG
mode it shows a third line, for example
`VOICE play nav.immediate [IMMEDIATE] bank | q 2 | drop ttl 1 | 14/min | pack 40/40 | proxy 312 ms | local 0`.
`bridge-cli --voice` prints the same every `--interval`. This needs one additive parameter from the AR app (14.4).

### 7.8 SOCIAL (reserved)

No v1 cue uses SOCIAL (plan sections 26-28 are out of scope). The arbiter still implements the tier: TTL 30,000 ms,
readiness 1,000 ms, at most 15 words, no earcon, voice profile `social` (a different voice, so chat is never mistaken
for a warning), never cuts anything, cut by every other tier, never re-queued, blocked by the workload lockout and by
the `ALERTS` and `MUTED` voice modes.

---

## 8. Text rendering

### 8.1 Rules

1. Only catalog templates are rendered, and slots are filled from normalized values only.
2. Numbers are words; hyphenate 21-99 (`twenty-three`); no decimals.
3. Units are words (`feet`, `mile`, `miles`); never `ft`, `mi`, `m`, `mph`.
4. Sentence case; capitalize names only. The AR `text` field (upper case with `|`) never reaches text-to-speech.
5. Every string ends with a period. No exclamation marks, ellipses, quotes, parentheses, SSML or `[tags]`.
6. The final string must match `^[A-Z][A-Za-z ,'-]*\.$` and its word budget.
7. **Overflow chain:** if a rendered string breaks rule 6, drop `{then}`; then replace `{destination}` with `your destination`; then drop `{onto}`; then use the banked street-less form of the same stage. Log `render(fallback=<step>)`.

### 8.2 Word budgets

| Priority | Max words | About |
|---|---|---|
| CRITICAL_SAFETY | 3 | 1.2 s |
| TRAFFIC_ALERT | 4 | 1.6 s |
| IMMEDIATE_NAVIGATION | 9 (12 with a "then" chain) | 3.5 s |
| UPCOMING_NAVIGATION | 12 | 4.5 s |
| GENERAL_INFORMATION | 6 | 2.5 s |
| SOCIAL | 15 | 6 s |

The template test renders every template with worst-case slots: a 4-word street, a 4-word destination, `one and a
half miles` and a "then" chain.

### 8.3 Normalizing road names and labels

Applied to `{street}` and `{destination}` before rendering:

| Input | Spoken |
|---|---|
| `St` after a name / before a name | `Street` / `Saint` |
| `Dr` | `Drive` (never `Doctor` in a road name) |
| `Ave`, `Blvd`, `Rd`, `Ln`, `Ct`, `Pl`, `Pkwy`, `Hwy`, `Fwy`, `Expy`, `Cir`, `Trl`, `Ter`, `Sq` | `Avenue`, `Boulevard`, `Road`, `Lane`, `Court`, `Place`, `Parkway`, `Highway`, `Freeway`, `Expressway`, `Circle`, `Trail`, `Terrace`, `Square` |
| `N`, `S`, `E`, `W`, `NE`, `NW`, `SE`, `SW` as the last token | `North` … `Southwest` |
| `I-85`, `I 85` | `I eighty-five`; three digits in groups: `I-285` is `I two eighty-five` |
| `US-29`, `U.S. 29` | `U S twenty-nine` |
| `GA-400`, `SR 400` | `Georgia four hundred`, `State Route four hundred` |
| `Exit 23B` | `exit twenty-three B` |
| ordinals such as `10th` | `Tenth` |
| other digits | words; drop the label if digits remain |
| `&` | `and` |
| HTML remnants, parentheses, slashes | removed; `I-75/85` becomes `I seventy-five eighty-five` |

A small alias table (local names that text-to-speech mispronounces) is applied afterwards. It lives in our code, so
it also covers the Android fallback voice. How ElevenLabs reads any of these forms is unverified until someone
listens (13.4).

---

## 9. Earcons

At most four sounds, generated in code as 16-bit PCM at 24 kHz with 5 ms raised-cosine ramps. No sound files and no
ElevenLabs Sound Effects (non-deterministic, 0.5 s minimum, and its policy restricts standalone distribution).

| Id | Used for | Design | Before speech | Alone | Peak level |
|---|---|---|---|---|---|
| E1 | CRITICAL_SAFETY only | 55 ms pulses with 40 ms gaps (about 10 pulses per second, duty about 0.6); complex tone: 1,000 Hz fundamental plus a 3,000 Hz component at -6 dB | 2 pulses (150 ms), then 40 ms of silence: 190 ms to the first word | 3 pulses (245 ms) | -1 dBFS |
| E2 | TRAFFIC_ALERT | 880 Hz then 660 Hz, 100 ms each, 40 ms gap; fundamental plus second harmonic at -8 dB | 240 ms, then 60 ms | same | -4 dBFS |
| E3 | navigation (off by default) | one 150 ms chime, 523 Hz + 784 Hz | 150 ms, then 60 ms | same | -10 dBFS |
| none | GENERAL_INFORMATION, SOCIAL | - | - | - | - |

E1 is reserved for CRITICAL_SAFETY so its meaning never dilutes. Its shape follows FMVSS 127 (fundamental ≥ 800 Hz,
6-12 pulses per second, duty 0.25-0.95) and the ISO 15006 example mix (800 + 3,000 Hz). Putting E1 before speech
follows FAA HFDS 7.3.1.3 (precede speech with a non-speech alert when the voice channel also carries other
messages). FAA HFDS 7.2.6.1 asks for a 0.5 s alerting signal while NHTSA 1996 (2.4.6.10) advises against any tone
before crash speech because it costs about 0.5 s; 190 ms is our compromise, not a value from either source.

---

## 10. Voice providers and caching

### 10.1 Providers

Plan section 23 mapping: `speak(text)` = arbiter admission, then `VoiceProvider.prepare`, then `VoiceBus.play`;
`stop()` = `VoiceBus.cut` plus cancelling the pending request. ElevenLabs = the voice pack, the route cache and the
laptop proxy; Native TTS = Android `TextToSpeech`; an on-device neural "Local TTS" is not in v1. Output hardware
(plan section 34) is the Android media route: the voice bus follows it and re-creates its track on a routing change.

| Provider | What it is | Used for |
|---|---|---|
| Voice pack | pre-generated ElevenLabs clips loaded into RAM at startup (10.6) | every fixed phrase, including every CRITICAL and TRAFFIC phrase; for the demo, every phrase the demo sessions can produce |
| Route cache | clips fetched through the proxy when a route appears (10.2) | navigation phrases of the current route |
| Laptop proxy | `POST /tts` on the perception server, which calls ElevenLabs (10.5) | misses for navigation and general phrases |
| Native | Android `TextToSpeech` (Google engine `com.google.android.tts` requested when installed, engine logged), pre-rendered with `synthesizeToFile` for fixed phrases at startup and route phrases at prefetch time. The WAV header is parsed and the clip converted to 16-bit mono and resampled to 24 kHz before it counts as ready (after `onDone`). `speak()` is never used for cues | fallback when no ElevenLabs clip is ready in time, and when there is no key or no laptop |

### 10.2 Phrase sets

- **Fixed (about 40 phrases):** `fixedPhrases` in the JSON: every fixed text of sections 5, 6 and 8, the 16 speed limits, the street-less immediate forms (`Turn right.`, `Keep left.`, `Take the exit.`, …) and `Starting route to your destination.`.
- **Route prefetch:** when a new route key appears, the tablet renders, for the next 3 target maneuvers first and then the rest: `nav.start`; for each target, every `speakDistance` bucket from `P(v)` down to 100 ft in `nav.prepare`, `nav.immediate` with and without its "then" form and in its LIVE distance form, and the same buckets for `nav.arrive_prepare`; `nav.arrived`. It sends them in one `POST /tts/prefetch`.
- **Demo sessions:** `bridge-cli phrases --nav-session <dir>` lists every phrase the scheduler can produce for a session with the same Kotlin renderer, and `make_voice_pack.py --phrases` adds them to the pack, so a rehearsed demo makes no runtime ElevenLabs call at all.

### 10.3 Readiness

1. The voice pack is looked up synchronously.
2. On a miss, the proxy request and the native pre-render lookup start in parallel.
3. At the deadline, play the proxy clip if it is ready, else the native clip.
4. IMMEDIATE_NAVIGATION with neither ready: play the banked street-less form of the same stage (it keeps the ElevenLabs voice).
5. CRITICAL and TRAFFIC cues play only from RAM. With no clip at all (no pack, native voice not ready), play the earcon alone and log `src=earcon`.
6. A clip that arrives after its deadline is cached for next time but not played.

| Priority | Deadline (placeholder until day-one full-clip P95, 13.4) |
|---|---|
| CRITICAL_SAFETY, TRAFFIC_ALERT | 0 (RAM only) |
| IMMEDIATE_NAVIGATION | 400 ms |
| UPCOMING_NAVIGATION | 800 ms |
| GENERAL_INFORMATION, SOCIAL | 1,000 ms |

The proxy returns whole clips, so "ready" means generation plus transfer, which is longer than time to first byte.

### 10.4 ElevenLabs request (made by the laptop only)

| Part | Value |
|---|---|
| Endpoint | `POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=pcm_24000` (`output_format` is a **query** parameter; in the body it is ignored and the default `mp3_44100_128` is returned) |
| Header | `xi-api-key: $ELEVENLABS_API_KEY` |
| Body `model_id` | `eleven_flash_v2_5` for everything, so pack, prefetch and runtime clips sound alike. `eleven_multilingual_v2` may replace it for the fixed pack only if a blind listening test prefers it |
| Body `language_code` | `en` for Flash only (Multilingual v2 does not accept it) |
| Body `apply_text_normalization` | `off` (the text is already normalized) |
| Body `voice_settings` | always sent in full: `stability` 0.6, `similarity_boost` 0.75, `style` 0, `use_speaker_boost` true, `speed` 1.0 (`nav`) or 1.1 (`alert`: CRITICAL and TRAFFIC) |
| Body `seed` | fixed (best effort only; caching is what makes audio reproducible) |
| Output | expected headerless signed 16-bit little-endian mono at 24 kHz (not documented by ElevenLabs; verified on day one: no RIFF header, even length, bytes / 48,000 ≈ clip seconds) |
| Voice | chosen by a listening test (13.4). The replacement voices are Voice Library voices, which the API does not serve on the Free plan, and the old default voices (expiring 2026-12-31) are not visible to accounts created after March 2026: a paid plan must be active first. `ELEVENLABS_VOICE_ID` lives in the environment, never in code. `make_voice_pack.py` checks `GET /v1/voices/{id}` and fails loudly on 401/402/403 |
| Cache key | lowercase hex `sha256` of `text|voice_id|model_id|settings|output_format|seed|language_code|apply_text_normalization|dictionary`, where `settings` is JSON with sorted keys and no spaces, and `dictionary` is `id@version` or empty |

### 10.5 Laptop proxy contract

`perception_engine/perception/realtime/tts_proxy.py`, added to the existing FastAPI app, using the installed `httpx`
(no new dependency). The key comes from the environment or `perception_engine/.env` (gitignored), read by a
10-line `KEY=VALUE` parser (the environment wins); it is never logged or sent to the tablet.

| Route | Request | Responses |
|---|---|---|
| `POST /tts` | JSON `{"text": ≤ 200 chars matching the speech regex, "voice": "nav" or "alert", "cacheOnly": bool}` | `200` `application/octet-stream`, headers `X-Audio-Format: pcm_s16le;rate=24000;channels=1`, `Content-Length`, `X-TTS-Cache: hit|miss`, `X-TTS-Key: <sha256>`; body = the whole clip, leading silence trimmed to ≤ 20 ms. `404 notCached` (cacheOnly miss), `400 badText`, `403 notLoopback`, `503 notConfigured`, `429 spendGuard` or `upstreamBusy` with `Retry-After`, `502` upstream error or invalid audio, `504` upstream slower than 2.5 s |
| `POST /tts/prefetch` | `{"texts": [...], "voice": ...}` | `202 {"accepted": n, "alreadyCached": m}`; fills the laptop disk cache in the background |
| `GET /tts/health` | - | `{configured, modelId, voiceId, format, cacheEntries, lastError, budget: {runtimePerMinLeft, prefetchPerMinLeft, dayLeft}}` |
| `GET /tts/pack`, `GET /tts/pack/{sha256}` | - | the voice pack manifest and clips (10.6) |

- **Audio validation:** the proxy rejects an upstream body that starts with `ID3`, `RIFF` or an MPEG frame sync (`0xFF` then `0xE0`-`0xFF`), or has an odd length, with `502`. Unvalidated bytes never reach the tablet.
- **Clients:** loopback only (`adb reverse` arrives as 127.0.0.1) unless the server is started with `--tts-allow-lan`, which Wi-Fi and hotspot demos need. uvicorn runs with `proxy_headers=False`, because its default trusts `X-Forwarded-For` from loopback.
- **Spend guard:** 200 characters per request; runtime 1,000 characters per minute and 2 requests in flight; prefetch 6,000 characters per minute and 2 in flight; 30,000 characters per day shared. `make_voice_pack.py` calls ElevenLabs directly with its own `--max-chars`.
- **429 from ElevenLabs:** read `detail.code` (fall back to `detail.status`, which ElevenLabs marks legacy). `concurrent_limit_exceeded` / `too_many_concurrent_requests`: hold the request until an in-flight one completes. `system_busy`: retry once after 250 ms. `rate_limit_exceeded`: exponential backoff from 500 ms. Never retry past the cue's deadline; always log `detail.request_id`.
- **Tablet side:** base URL = `perception.url` with `ws` → `http` and `wss` → `https`, same host and port, or the `perception.ttsUrl` extra (validated like the server URL). Its own OkHttp client: connect 1,000 ms, call 2,500 ms (the bridge's client has no read timeout). `503` or `403`: proxy off for this bridge lifetime, chip `VOICE LOCAL`. `429`, `502`, `504` or an IO error: native audio for this cue, retry the proxy on the next cue.

### 10.6 The voice pack

1. `python scripts/make_voice_pack.py [--phrases FILE] [--max-chars N]` (from `perception_engine/`) renders `fixedPhrases` plus any phrase list, trims leading silence to ≤ 20 ms, normalizes speech to about -16 LUFS with peaks ≤ -1 dBTP, and writes `outputs/voice_pack/<packId>/manifest.json` plus `<key>.pcm` files. The manifest: `{schema: "voice_pack.v1", packId, voiceId, modelId, outputFormat: "pcm_24000", seed, settings: {nav, alert}, entries: [{text, profile, key, file, samples, leadingSilenceMs, lufs}]}`.
2. The tablet loads `<app external files>/voice/<packId>/` (the same convention as `sim/`), filled by `adb push outputs/voice_pack/<packId> /sdcard/Android/data/com.drivingassist.glass/files/voice/` or at session start from `GET /tts/pack`.
3. The tablet keys its caches by (voiceId, profile, text) and rejects a pack whose `voiceId`, `modelId` or `outputFormat` differs from `/tts/health`: it then uses native clips and the chip shows `VOICE LOCAL (pack mismatch)`.
4. `outputs/` is gitignored: generated audio is never committed and never goes into `app/src/main/assets/` (that path is not ignored).

---

## 11. Configuration

`audio_cues.v1.json` is the single source for every number in sections 5-11. `AudioConfig` is built from it at
startup; its Kotlin defaults exist only for tests, and a unit test asserts they equal the JSON. Every value is a
prototype placeholder to tune on recorded clips, like `DrivingContextConfig`; none is a validated safety limit.

| Name | Default | Section |
|---|---|---|
| `audio` (launch extra `perception.audio`) | `auto` in LIVE and SIM; MOCK is always silent | 12.5 |
| `voiceMode` (launch extra `perception.voice`) | `ALL` | 12.4 |
| `speechUnits` | IMPERIAL | 6.7 |
| `perceptionWarmupMs` | 500 | 4 |
| VTC: persistence, episode end, min repeat, max per episode | 250, 2,000, 8,000, 3 | 5.2 |
| VTC: distance / TTC / headway exit values used to classify the trigger | 9 m / 2.5 s / 1.0 s (read from `DrivingContextConfig`) | 5.3 |
| VTC: closing for a distance trigger, closing for a TTC trigger, range-rate TTC max, moving speed | -0.5 m/s, -1.0 m/s, 3.0 s, 2.8 m/s | 5.3 |
| VTC escalation: distance ratio, TTC, min gap | 0.6, 1.5 s, 2,000 ms | 5.3 |
| Pedestrian: persistence, episode end, min repeat, escalation earcon-only window, skip window, re-validation window | 200, 2,000, 6,000, 4,000, 500, 1,000 | 5.2 |
| Red light: persistence, min / max distance, min confidence, max lateral, episode end, min repeat, max deceleration, reaction | 500, 15 / 60 m, 0.6, 6 m, 3,000, 20,000, 4.9 m/s², 1.0 s | 5.2 |
| `speakStopSigns`, `speakVehicleClose`, `speakSpeedLimit`, `speakLaneChange`, `allowInferredLaneSide` | false | 5.2, 6.5 |
| `speakOffRoute`, `speakBackOnRoute`, `speakNavPaused` | false | 6.4 |
| Road alerts paused: window, stale share, short restore, min good perception, min repeat; back: good time | 3,000, 2,500, 500, 1,000, 60,000; 2,000 | 5.2 |
| `defaultSpeedMps`, `maxExtrapolationS`, `leadS` | 11.2, 2.0, 1.0 | 6.3 |
| `immediateSeconds`, `immediateMinMeters`, `immediateMaxMeters` | 7.0, 45, 200 | 6.3 |
| `prepareMeters` by speed band | 300 / 805 / 1,609 (bands at 19.4 and 25 m/s) | 6.3 |
| `skipPrepareIfImmediateWithinS`, `postManeuverHoldMs`, `arrivedMeters`, `minExecutableMeters` | 4.0, 3,000, 15, 10 | 6.4 |
| `thenChainSeconds`, `thenChainMinMeters`, `continueMinSeconds`, `continueMinMeters` | 12, 60, 60, 1,609 | 6.4, 6.5 |
| `navSettleMs`, `navSettlePackets`, `simLeftoverMaxSeconds` | 500, 2, 2.0 | 6.2 |
| `offRouteDebounceMs`, `offRouteMinTravelMeters`, `offRouteMaxAccuracyMeters`, `immediateMaxAccuracyMeters` | 3,000, 60, 25, 20 | 6.4, 6.5 |
| Lane cue: stable time, min confidence, max age | 3,000, 0.7, 1.0 s | 6.5 |
| `workloadLockoutAfterManeuverMs`, `workloadLockoutMaxMs` | 5,000, 20,000 | 6.5 |
| `minGapMs`, `trafficAfterBlockMs`, `trafficCutsImmediateIfRemainingMs`, `queueMax` | 700, 500, 1,000, 4 | 7.2, 7.3 |
| TTLs | CRITICAL 1,000, TRAFFIC 1,500, IMMEDIATE 3,000, UPCOMING 6,000, GENERAL 5,000, SOCIAL 30,000 | 7 |
| Readiness deadlines | IMMEDIATE 400, UPCOMING 800, GENERAL and SOCIAL 1,000 | 10.3 |
| `focusAbandonMs`, `focusRetryMs`, `lowVolumeFraction`, `maxRouteLatencyMs` | 500, 500, 0.4, 250 | 7.5 |
| `earconNavEnabled` | false | 9 |
| `mutedMaxMs` | 120,000 | 12.4 |

---

## 12. Where the code goes

### 12.1 Files

| Part | Location | Notes |
|---|---|---|
| Pure logic: `AudioConfig`, `CueCatalog`, `SpeechText` (normalizer, number words, `speakDistance`), `AudioCuePolicy`, `NavPromptScheduler`, `VoiceArbiter`, `VoiceProvider`, `LaptopTtsClient` (OkHttp 4.12.0, already a dependency), `AudioCoordinator` | new package `audio` in `BRIDGE/` (`com.drivingassist.copilot.audio`) | pure JVM, no Android APIs, no new Gradle module (one would need Tom's Gradle files); Kotlin 2.0.21, JVM 17 |
| Tests | `perception_engine/android/perception-bridge/src/test/kotlin/com/drivingassist/copilot/audio/`, fixtures in `src/test/resources/audio/` | JUnit 5, coroutines-test virtual time, MockWebServer, `FakeVoiceSink` |
| Android: `VoiceHost` (owner, lifecycle), `VoiceBus`, `Earcons`, `NativeTtsProvider`, `VoiceCache` (pack + route cache), `BridgeMediaClock` | `APP/audio/` (perception-owned) | `VoiceHost` is created in `PerceptionFactory.createViewModel` before the ViewModel starts the bridge, and closed with `vm.addCloseable` |
| Laptop | `perception_engine/perception/realtime/tts_proxy.py` (+ a hook in `server.py` `create_app`, flags `--no-tts`, `--tts-allow-lan`), `perception_engine/scripts/make_voice_pack.py`, `perception_engine/tests/test_tts_proxy.py` | the test is a script like `test_nav_relay.py`: `TestClient(app, client=("127.0.0.1", 50000))` and `httpx.MockTransport`, offline. No torch in this path; it never blocks the asyncio loop |
| Console | `bridge-cli --voice` (section 7.6 lines next to the `EVENT` lines, keys `v` voice mode and `r` repeat), `bridge-cli --dump-context FILE` (13.2), `bridge-cli phrases --nav-session DIR` (10.2) | runs the whole policy on the laptop without a tablet |
| Fixture tool | `python -m perception.realtime.nav_relay … --jsonl --pts-range START STOP STEP` (additive flags) | one compact message per line, no info line |

### 12.2 Android voice bus

One `AudioTrack`, `MODE_STREAM`, 16-bit mono 24 kHz, `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH`,
`PERFORMANCE_MODE_NONE`, capacity at least 200 ms, effective buffer about 100 ms. **The writer thread is the only
thread that touches the track.** It runs at audio priority and writes 20 ms blocks: silence while a drive session is
active (so the output, and Bluetooth, never go to standby), earcon and speech back to back (gapless). A cut sets an
atomic cut token; before each block the writer checks it, then calls `setVolume(0f)`, `pause()`, `flush()`,
`setVolume(1f)`, `play()` and continues, looping on short writes after a flush. Cut latency is at most one block plus
the device buffer. `stop()` is never used to cut (in stream mode it plays out the buffer). An utterance *ends* when the
playback head (`getPlaybackHeadPosition` or `getTimestamp`) passes its last frame, not when its last `write()`
returns; `minGapMs`, TTLs and `Finished` events use that time.

### 12.3 Additive bridge and runtime API (perception-owned, pure JVM)

```kotlin
// PerceptionBridge
fun playbackPositionAt(nowNs: Long): Double?     // SIM playback estimate (PlaybackClock.estimate)
val playbackState: PlaybackClock.State?          // videoId, pts, playing, rate, atNs
val playbackEpoch: Long                          // +1 only when PlaybackClock.update(...) returns true (seek, loop, other clip)
val navigationIsManual: Boolean                  // setNavigation(...) override active
// LinkStatus (new nullable fields)
val firstTripStateMs: Long?                      // timestampMs of the first client.trip_state sent on this connection
val lastTripSpeedMps: Double?                    // speed of the last trip_state whose fix had a speed; null otherwise
val lastTripAccuracyMeters: Double?              // accuracy of the last fix
// PerceptionRuntime (app)
val hostStartedFlow: StateFlow<Boolean>
```

### 12.4 Driver controls

| Control | Input | Effect |
|---|---|---|
| Voice mode `ALL` / `ALERTS` / `MUTED` | tap the VOICE field of the status chip (`APP/BridgeStatusChip.kt`, perception-owned); launch extra `perception.voice`; `bridge-cli` key `v` | `ALERTS` drops UPCOMING_NAVIGATION, GENERAL_INFORMATION and SOCIAL (like Google Maps "Alerts only"). `MUTED` drops everything except CRITICAL_SAFETY and ends by itself after 120 s. The chip shows any mode other than `ALL`. Total silence only with `perception.audio off` at launch |
| Repeat | chip button `REPEAT`; `bridge-cli` key `r` | re-renders the last navigation stage of the current event key with the current distance and queues it (UPCOMING, or IMMEDIATE when `d ≤ I(v)`); safety and traffic cues are never repeated on demand |
| Volume | hardware keys (media stream) | `VOICE LOW` below 40 % (7.5); the app never sets the system volume |

### 12.5 Modes and startup

- `perception.audio`: `off` = no audio; `local` = voice pack + native clips, no proxy; `eleven` = pack + proxy + native fallback; `auto` = `eleven` when `GET /tts/health` reports `configured` within 2 s, else `local`. Added to `PerceptionConfig.KEYS` and `resolve()` with a default in code (no BuildConfig field, so `app/build.gradle.kts` is unchanged).
- **MOCK is always silent:** `PerceptionFactory.createViewModel` returns `MockDataViewModel()` unchanged, the extra is ignored with one log warning, and MOCK route captions are never spoken. `:app:assembleDebug` still passes with no laptop and no voice pack (AGENTS rule 5).
- **Sound check:** `perception.audio test` (LIVE or SIM) plays every fixed phrase once with its earcon, from the pack or native clips, and logs the source and start latency of each.

### 12.6 Catalog packaging

`perception-bridge/build.gradle.kts`: `tasks.processResources { from("../../docs/audio") { include("audio_cues.v1.json"); into("audio") } }`.
`CueCatalog` loads `/audio/audio_cues.v1.json` with `getResourceAsStream` (JVM library resources are packaged into
the APK) and decodes with `ignoreUnknownKeys = true`. Fields listed in the JSON's `normative` array are
authoritative; `condition`, `trigger` and `revalidate` strings are prose for readers. A unit test asserts the
catalog loads.

### 12.7 Interfaces (sketch)

```kotlin
package com.drivingassist.copilot.audio

data class MediaState(val positionAtNowSeconds: Double, val playing: Boolean, val rate: Double, val epoch: Long)
data class SignView(val key: String, val signClass: String, val speedLimit: Int?, val isStop: Boolean,
                    val confidence: Double, val lastSeenPts: Double, val distanceMeters: Double?)
data class WorldAudioView(val stale: Boolean, val laneConfidence: Double?, val laneAgeSeconds: Double?,
                          val lightRawState: LightState?, val lightConfidence: Double?, val lightLateralMeters: Double?,
                          val signs: List<SignView>) {
    companion object { fun of(w: WorldSnapshot, c: DrivingContext): WorldAudioView = TODO() }
}
data class AudioInput(val nowNs: Long, val context: DrivingContext, val world: WorldAudioView,
                      val navigation: NavigationUpdate?, val manualNavigation: NavigationState?, val link: LinkStatus,
                      val media: MediaState?, val hostVisible: Boolean, val focusGranted: Boolean, val voiceMode: VoiceMode)

enum class VoiceMode { ALL, ALERTS, MUTED }
enum class EarconId { E1, E2, E3 }
enum class VoiceProfile { NAV, ALERT, SOCIAL }
enum class AudioSource { BANK, PROXY, NATIVE, EARCON_ONLY }

data class CueKey(val cueId: String, val scope: String)
data class CueRequest(val key: CueKey, val priority: Priority, val createdNs: Long, val ttlNs: Long,
                      val earcon: EarconId?, val speech: Boolean, val profile: VoiceProfile, val requeueCount: Int = 0)

interface CueOracle {                                   // implemented by AudioCuePolicy
    fun stillValid(key: CueKey, input: AudioInput): Boolean
    fun bindText(key: CueKey, input: AudioInput): String?  // late binding; null = drop
    fun onStarted(key: CueKey, nowNs: Long)
    fun onDropped(key: CueKey, reason: String, nowNs: Long) // CRITICAL rollback (5.0)
}
class AudioCuePolicy(config: AudioConfig, catalog: CueCatalog) : CueOracle {
    fun step(input: AudioInput): List<CueRequest> = TODO()   // no clock, flow, lock or I/O access
}

sealed interface ArbiterEvent {
    data class Requests(val requests: List<CueRequest>) : ArbiterEvent
    data class Ready(val token: Long, val source: AudioSource, val durationMs: Long) : ArbiterEvent
    data class Failed(val token: Long, val reason: String) : ArbiterEvent
    data class Finished(val token: Long, val atNs: Long) : ArbiterEvent
}
sealed interface VoiceCommand {
    val token: Long
    data class Prepare(override val token: Long, val text: String, val profile: VoiceProfile, val priority: Priority, val deadlineNs: Long) : VoiceCommand
    data class Play(override val token: Long, val earcon: EarconId?, val earconPulses: Int, val source: AudioSource) : VoiceCommand
    data class Cut(override val token: Long) : VoiceCommand
    data class CancelPrepare(override val token: Long) : VoiceCommand
}
class VoiceArbiter(config: AudioConfig, oracle: CueOracle) {
    fun step(input: AudioInput, events: List<ArbiterEvent>): Pair<List<VoiceCommand>, List<String>> = TODO()
    val debug: StateFlow<VoiceDebugState> get() = TODO()
}

class PreparedAudio(val pcm16le24kMono: ByteArray, val source: AudioSource)
interface VoiceProvider { suspend fun prepare(text: String, profile: VoiceProfile, deadlineNs: Long): PreparedAudio? }
interface VoiceSink {                                    // Android VoiceBus, bridge-cli console sink, FakeVoiceSink
    fun play(token: Long, earcon: EarconId?, earconPulses: Int, audio: PreparedAudio?, onFinished: (Long, Long) -> Unit)
    fun cut(token: Long)
}
```

`AudioCoordinator` is the only non-pure piece: one coroutine on `Dispatchers.Default`, stepped on every
`bridge.context` emission and on its own 50 ms `delay()` loop; provider results and sink callbacks arrive on a
`Channel<ArbiterEvent>(UNLIMITED)` and are passed to the next step. HTTP runs on `Dispatchers.IO`. Nothing runs on
the camera analyzer thread.

---

## 13. Tests and acceptance

### 13.1 Unit tests (pure JVM)

1. `speakDistance`: every row of 6.7, both sides of each boundary (including 0.625 mi and the rounded 1,000 ft), 243 m is `eight hundred feet`, and a 300 m prepare trigger speaks `one thousand feet`.
2. Catalog: loads from the classpath; every template rendered with worst-case slots matches the regex and budget after the overflow chain; every template rendered with missing slots gives a string in `fixedPhrases`; the Kotlin `AudioConfig` defaults equal the JSON.
3. Normalizer: every row of 8.3.
4. Perception rules: persistence, episode end, max per episode, min repeat, the earcon-only fallback, the gates of each class, the warm-up deferral, and the VTC trigger classes of 5.3 (distance while stationary is silent; a TTC spike without range rate is silent; headway-only is silent; escalation speaks again).
5. Pedestrian escalation: each of the four rules in 5.2.
6. Arbiter: every row of 7.2 (CRITICAL behind CRITICAL plays after it with E1; TRAFFIC cuts a long immediate prompt, not a short one; lane cues never cut; earcon-only never cuts speech), dedupe, overflow, TTL, bind, start conditions, re-queue after a cut, lockout and its 20 s bound, pause, epoch reset, takeover.
7. **Five at once** (plan section 24): `safety.vehicle_too_close`, `alert.red_light`, `nav.immediate`, `nav.prepare` of another event and `info.road_alerts_paused` requested in one step. Expected: VTC plays; red light, immediate and prepare are queued; the general cue is evicted on overflow; after VTC ends, red light starts 500 ms later, then the immediate prompt if still valid; the prepare prompt is dropped by TTL or the skip rule; never more than one utterance at a time.
8. `NavPromptScheduler` on the three demo timelines (fixtures in `src/test/resources/audio/nav/<clip>.jsonl`, recorded with the 12.1 fixture tool; the harness sets `receivedAtNs = pts`, steps 50 ms, `laneGuidance = null`, `FakeVoiceSink` durations) produces exactly the 6.8 table.
9. Plan section 22: a MERGE_LEFT first seen at 243 m at 11.2 m/s speaks `In seven hundred feet, merge left.`
10. Leftover packets: in LIVE, two identical ARRIVE 0 m packets 5 ms apart with `tripTimestampMs` older than `firstTripStateMs` and no new packet for 1.2 s produce no cue; in SIM, a packet 40 s away from the playback position produces none.
11. Manual navigation (`bridge-cli --nav-stub` EXIT, `Exit 23B`): speaks `take exit twenty-three B` stages and ignores the packets.
12. Dispositions: every `DrivingEventType` has a row (5.5).

### 13.2 Replay tests

`bridge-cli --dump-context FILE` writes one JSON line per step (on every context emission and every 50 ms tick):
`nowNs` and the `AudioInput` fields. Trimmed recordings of the three clips (≤ 2 MB each) go in
`src/test/resources/audio/replay/`; the test fails, not skips, when they are missing. Replaying them through
`AudioCuePolicy` + `VoiceArbiter` with `FakeVoiceSink` (duration `words / 2.6 + 0.2 s`, earcons as designed) must give:

| Check | Target |
|---|---|
| Utterances per minute, per 40 s clip (each clip carries 5 navigation cues, 7.5 per minute on their own) | ≤ 20, and ≤ 8 in any 20 s window |
| Delay from the end of persistence to the start of a CRITICAL cue, excluding the session warm-up and a CRITICAL already playing | 0 ms in the logic (≤ 20 ms plus the device buffer audible) |
| Perception cues that start while stale, taken over, paused or in the warm-up | none (a CRITICAL cue already playing may finish) |
| GREEN, YELLOW, UNKNOWN, TTC, object distances or "stop" in speech | none |
| `You have arrived` with `d > 15 m` | none |
| Navigation stages per maneuver | one prepare (or a logged skip) and one immediate |
| Perception cues whose full condition (not the re-validation predicate) holds for at least half of the utterance | ≥ 75 % |
| Perception cues whose condition still holds at the end of the utterance | reported |

### 13.3 Simulated result

A Python model of an earlier draft of these rules, run on the 7 measured runs with the 6.8 navigation cues merged in,
gave 12.0-18.1 utterances per minute per run (pooled 15.6) and 48 % talk time, and exposed the lost second CRITICAL
cue, the late pedestrian speech, the headway-driven episode and the cut prepare prompts that sections 5.2, 5.3, 7.2
and 7.4 now fix. The current rules have not been re-simulated: the M2 replay (section 16) is the check, against the
13.2 targets.

### 13.4 Day-one measurements (fill in the Result column)

| # | What | How | Pass | If it fails |
|---|---|---|---|---|
| 1 | ElevenLabs plan tier after the MLH code | account page | Creator or higher | buy Creator monthly (section 15) |
| 2 | The candidate voices work over the API on our plan; record each voice's `category` | one request per voice id; `GET /v1/voices/{id}` | 200, no 402 | choose among those that return 200 |
| 3 | `pcm_24000` is mono 16-bit little-endian | bytes / 48,000 vs clip duration; listen | within 2 % | set the format from the result |
| 4 | Leading silence | first sample above -40 dBFS | < 50 ms | trim in `make_voice_pack.py` |
| 5 | Proxy time to first byte **and full clip**, 20 requests cold and warm, over USB and the hotspot; log `x-region` | `/tts` timing | warm full-clip P95 ≤ 400 ms | set 10.3 deadlines from P95; prefetch more |
| 6 | `/tts` from the tablet over the hotspot | server with and without `--tts-allow-lan` | 200 only with the flag | put the flag in the runbook |
| 7 | Tab S9 Android version; `adb shell getprop ro.audio.flinger_standbytime_ms` | adb | - | the Android 15 focus rule applies from 15 |
| 8 | Routing | `adb shell dumpsys audio` while a cue plays | one output device | change the device, never the usage |
| 9 | Cut and start latency on the speaker and the chosen Bluetooth device; `getTimestamp()` route latency | film a screen flash plus the audio | cut ≤ 50 ms on the speaker | raise `minGapMs`; never lengthen E1 |
| 10 | Bluetooth connect or disconnect during an utterance | toggle the headset mid-cue | no crash, audio continues on the new route | re-create the track on a routing change |
| 11 | Android TTS: Google engine, offline en-US voice, WAV rate of `synthesizeToFile` | `UtteranceProgressListener` | pre-render of 40 phrases ≤ 10 s | pre-render at app start only |
| 12 | Listening pass: 5 voices x 10 hardest phrases, 3 listeners, blind, each voice played over a podcast and a passenger conversation | - | majority choice; the alert voice is told apart from both | alias table (8.3) |

---

## 14. Requests to other owners

### 14.1 Navigation engine (`spatial/phase1/`, branch `louis`)

File each row as a GitHub issue against `spatial/phase1/` on `main`. None blocks the demo.

| # | Request | Evidence | Audio layer meanwhile |
|---|---|---|---|
| 1 | Google `normalizeManeuver`: test `uturn-*`, `roundabout-*`, `ramp-*`, `fork-*` and `keep-*` before the generic left/right test (U-turns and roundabouts need their own event types); map off-ramps to `exit` | `providers/google.js` `normalizeManeuver` turns all of these into plain turns | speaks them as turns (wrong for U-turns and roundabouts) |
| 2 | `routeSemantics.turnDirection` = `left` / `right` for merges when known | `processor.js` `semanticsTurnDirection` returns `merge` | says "merge" without a side |
| 3 | distance-dependent ARRIVE text, sentence case, imperial units, fix `Merge merge`; or adopt the section 6 templates | `processor.js` `buildAudioPrompt`, `formatDistance` | ignores the engine's `audio` |
| 4 | emit `START_ROUTE`; set `exitNumber` and `requiredLane`; street name only in `roadName` | `processor.js` `buildNavigationEvents`, `providers/google.js` | `nav.start` from the first packet; lane side inferred |
| 5 | rerouting on `offRoute`; a clearer name for `speakAtDistanceMeters` (it is the maneuver's position along the route) | `processor.js` | off-route cues off by default |
| 6 | one staleness value (`handoffHints.staleAfterMs` 5,000 vs the bridge's 10,000) | `processor.js` `handoffHints` | uses 10 s |

### 14.2 Driving Context (`BRIDGE/context/`)

1. Corroborate TTC with the range rate and require a stable lead before CRITICAL; make the following thresholds speed-aware (AGENTS known gap 4). The audio rules in 5.3 only hide the symptom.
2. Take the headway speed from a measured source only (the demo sessions' speed is synthetic).
3. Add riders and bicycles in the path.

### 14.3 Bridge (`BRIDGE/bridge/`)

1. The additive API in 12.3.
2. Filter `navigation.packet`s replayed from a previous connection or session (the audio layer does it in 6.2 until then).
3. `BridgeConfig.drivingContext` to IMPERIAL units.

### 14.4 AR app (`driving_assist/`, Tom, branch `tom`)

One additive PR, every touched file listed (AGENTS rule 6):
1. `AndroidManifest.xml`: `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>` (needed for the native fallback voice under package visibility, target SDK 35).
2. `MainActivity.kt`: `BridgeStatusChip(runtime, debug = isDebugMode)` (7.7); optionally `volumeControlStream = AudioManager.STREAM_MUSIC`.
3. Visual counterparts that do not exist yet: an off-route banner, the stale banner "Road alerts paused - navigation only", lane-guidance text, a speed-limit badge, emphasis of the top `activeAlerts` entry (PERCEPTION_INTEGRATION.md section 11). Each one lets a cue that is off by default (invariant 8) be turned on.
4. Optional, agreed with Tom because it changes what the overlay caption shows (the `RouteState` contract in `ENGINEER_A.md`): `BridgeRouteSource` (perception-owned) sets `RouteState.audio` to the caption form of the current navigation stage from the same renderer and bucket, for example `TURN RIGHT ONTO MOCK STREET | 300 FT`, so screen and voice agree.

Keep the SIM ExoPlayer without audio focus handling, or every cue would pause the clip.

### 14.5 Repo guide (in the same change as the code)

1. `perception_engine/AGENTS.md` rule 3 becomes: "No route logic in the Android app or in the relay. Maneuvers, progress, ETA, distances and the off-route flag come from the navigation engine (`spatial/phase1/`). Exception: spoken navigation sentences are rendered on the tablet from its structured fields by the fixed templates in `docs/audio/AUDIO_CUE_RULES.md` section 6, until the navigation engine adopts them; the only progress estimate there is distance minus speed times a capped age."
2. The "Route behaviour" row drops "audio text" and adds "spoken wording: `docs/audio/AUDIO_CUE_RULES.md` section 6".
3. The "Spoken alerts" row becomes: "`perception-bridge/.../audio/` (policy, arbiter) + `app/.../glass/perception/audio/` (playback); rules in `docs/audio/AUDIO_CUE_RULES.md`".
4. `driving_assist/PERCEPTION_INTEGRATION.md` section 11 item 4 becomes: "Speech: see `perception_engine/docs/audio/AUDIO_CUE_RULES.md`; `DrivingEvent.speech` is not spoken."

---

## 15. Open decisions

| Decision | Recommendation |
|---|---|
| ElevenLabs plan | Today: claim the MLH HackGT 13 ElevenLabs code and redeem it on an account that is still on Free (coupons work only there). If the tier is below Creator, subscribe to Creator monthly ($11 the first month) or Starter plus a $5 top-up (Flash only), and cancel before renewal. Generate every demoed or published clip on the paid plan (Free-plan audio is non-commercial and needs "elevenlabs.io" in the title). Create two text-to-speech-only keys with character limits (pack building, demo). Enter "Best Use of ElevenLabs" on the submission form. Record the tier in 13.4 row 1 |
| Who owns navigation wording | The templates in section 6, adopted by the navigation engine later (14.1 row 3). Until then the audio layer renders them from structured fields (14.5) |
| One model or two | Flash v2.5 for everything; switch the fixed pack to Multilingual v2 only if a blind test prefers it |
| Voice | One voice for navigation and alerts, chosen by ear (13.4 row 12); urgency comes from the earcon, the wording and speed 1.1. The candidates are probably professional voice clones, which ElevenLabs ranks slowest; prefetching makes that matter little |
| Cues off by default | Stop signs, "vehicle close", speed limits, lane changes, off-route and navigation-paused stay off until their precision on labelled clips reaches about 90 % or their visual counterpart exists |
| Where audio plays | Tablet speaker for the demo; open-ear glasses or a single earbud only. Georgia restricts headphones that impair hearing while driving (O.C.G.A. section 40-6-250); not legal advice |

---

## 16. Implementation order (demo first)

Each milestone ends in something you can hear or test, and dropping a later one does not break an earlier one.

| Milestone | Work | Gate |
|---|---|---|
| M0 Accounts (30 min) | MLH code on a Free account; tier recorded; two text-to-speech-only keys; the demo key in `perception_engine/.env` as `ELEVENLABS_API_KEY` | 13.4 row 1 |
| M1 Pure logic | `SpeechText` (tests 1, 3), `CueCatalog` (test 2, 12), `NavPromptScheduler` (tests 8-11), `AudioCuePolicy` (tests 4-5), `VoiceArbiter` (tests 6-7) | `gradlew.bat :perception-bridge:test` green |
| M2 Laptop replay | `bridge-cli --dump-context`, `--voice`, `phrases`; record the 3 clips; 13.2 targets met; keep the transcripts as `perception_engine/docs/examples/<clip>_voice.txt` | 13.2 table |
| M3 Voice on the laptop | `tts_proxy.py` + `test_tts_proxy.py` (offline); 13.4 rows 2-6; listening pass; `ELEVENLABS_VOICE_ID`; `make_voice_pack.py` with the fixed phrases and every demo-session phrase | pack manifest complete |
| M4 Tablet, SIM over USB | `VoiceBus`, `Earcons`, `VoiceCache`, `VoiceHost`, `NativeTtsProvider`, `perception.audio`, the chip VOICE state; AR PR items 1-2 | 13.4 rows 7-11; the city clip speaks the 6.8 rows with no `src=native` lines |
| M5 Route prefetch and runtime proxy | street names and destinations of live routes | the second loop of each clip has no cache miss |
| M6 Demo polish | controls (12.4), DEBUG panel (7.7), pre-warm every demo clip, rehearse each 6.9 scenario, fallback `perception.audio local` if ElevenLabs is down | rehearsal |

Cut line: M0-M4 is a complete demo. Without M5, live-route prompts that are not in the pack use the native voice.
