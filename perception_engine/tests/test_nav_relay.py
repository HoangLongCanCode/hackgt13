"""Tests for the phase1 navigation relay (perception/realtime/nav_relay.py + nav/phase1_relay.js).

Plain Python (no pytest needed); run from perception_engine/:

    python tests/test_nav_relay.py [--phase1-dir <navigation engine folder>] [-k <substring>]

The navigation engine ("phase1") is found like the server finds it: --phase1-dir > env PHASE1_DIR >
<repo>/spatial (on main) > <repo> with a legacy src/phase1 > ../hackgt13-phase1 next to the repo. Both
layouts work for an explicit folder: <dir>/phase1/index.js (spatial/) or <dir>/src/phase1/index.js.
Needs Node 18+ on PATH and the jsonschema package.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from pathlib import Path

ENGINE_DIR = Path(__file__).resolve().parents[1]
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

import jsonschema  # noqa: E402

from perception.realtime.nav_relay import RELAY_JS, NavRelay, NavRelayError, resolve_phase1_dir  # noqa: E402

CONTRACTS = ENGINE_DIR / "contracts"          # perception_engine/contracts/
SESSIONS = ENGINE_DIR / "nav" / "demo_sessions"
CITY = SESSIONS / "b1ff4656-0435391e"
HIGHWAY = SESSIONS / "b1f4491b-cf446195"
PHASE1_DIR: str | None = None  # set from --phase1-dir
MEASUREMENTS: dict[str, str] = {}


class Skip(Exception):
    pass


def _validator(name: str) -> jsonschema.Draft202012Validator:
    schema = json.loads((CONTRACTS / "schemas" / name).read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema)


NAV_SCHEMA = _validator("navigation.packet.schema.json")
TRIP_SCHEMA = _validator("client.trip_state.schema.json")


def validate_nav(message: dict) -> None:
    errors = sorted(NAV_SCHEMA.iter_errors(message), key=lambda e: list(e.path))
    assert not errors, "navigation.packet schema: " + "; ".join(f"{list(e.path)}: {e.message}" for e in errors[:5])


def read_trip(session: Path) -> list[dict]:
    return [json.loads(line) for line in (session / "trip_state.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]


def new_relay() -> NavRelay:
    return NavRelay(PHASE1_DIR)


def check_routestate_matches_packet(message: dict) -> None:
    rs, packet = message["routeState"], message["packet"]
    maneuver = packet.get("activeManeuver")
    assert rs["action"] == (maneuver["type"] if maneuver else "GO_STRAIGHT")
    assert rs["audio"] == (packet["audioInstructions"][0]["content"] if packet["audioInstructions"] else "")
    assert rs["ui"] == (packet["spatialInstructions"][0]["type"] if packet["spatialInstructions"] else "DISTANCE_LABEL")
    assert rs["offRoute"] == packet["progress"]["offRoute"]
    assert rs["etaSeconds"] == packet["progress"]["etaSeconds"]
    assert rs["remainingDistanceMeters"] == packet["progress"]["remainingDistanceMeters"]
    assert rs["turnDirection"] == packet["routeSemantics"].get("turnDirection")
    match = re.search(r"in (\d+) m\.", rs["audio"])
    if match:  # the distance phase1 speaks is the distance routeState carries
        assert int(match.group(1)) == rs["distanceMeters"], (rs["audio"], rs["distanceMeters"])


# ---------------------------------------------------------------------------------------- setup errors


def test_missing_phase1_dir_error_message():
    bogus = Path(tempfile.gettempdir()) / "no-such-phase1-checkout"
    try:
        NavRelay(str(bogus))
    except NavRelayError as exc:
        text = str(exc)
        assert "phase1 route engine not found" in text, text
        assert str(bogus.resolve()) in text and "--phase1-dir" in text, text
        assert "spatial/" in text and "PHASE1_DIR" in text, text
    else:
        raise AssertionError("NavRelay accepted a missing phase1 dir")

    old = os.environ.get("PHASE1_DIR")
    os.environ["PHASE1_DIR"] = str(bogus)
    try:
        resolve_phase1_dir(None)
    except NavRelayError as exc:
        assert "env PHASE1_DIR" in str(exc), str(exc)
    else:
        raise AssertionError("a bad PHASE1_DIR was accepted")
    finally:
        if old is None:
            os.environ.pop("PHASE1_DIR", None)
        else:
            os.environ["PHASE1_DIR"] = old


def test_node_relay_reports_missing_phase1_dir():
    bogus = Path(tempfile.gettempdir()) / "no-such-phase1-checkout"
    proc = subprocess.run(
        ["node", str(RELAY_JS), "--phase1-dir", str(bogus)], input=b"", capture_output=True, timeout=20, cwd=ENGINE_DIR
    )
    assert proc.returncode == 2, proc.returncode
    fatal = json.loads(proc.stdout.decode().strip().splitlines()[-1])
    assert fatal["ok"] is False and fatal["event"] == "fatal" and "phase1 route engine not found" in fatal["error"], fatal
    assert b"spatial/" in proc.stderr and b"PHASE1_DIR" in proc.stderr, proc.stderr


def test_phase1_dir_accepts_both_layouts():
    """An explicit folder may be spatial/ (phase1/index.js), a repo root holding spatial/, or a legacy checkout
    (src/phase1/index.js); Python and nav/relay_core.js resolve it to the same root. Without --phase1-dir /
    PHASE1_DIR, <repo>/spatial wins when it exists."""
    relay_core = RELAY_JS.with_name("relay_core.js")
    js = ("const c = require(process.argv[1]); const r = c.resolvePhase1(process.argv[2]);"
          "process.stdout.write(JSON.stringify({root: r.root, layout: r.layout}));")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp).resolve()
        spatial = tmp / "repo" / "spatial"
        (spatial / "phase1").mkdir(parents=True)
        (spatial / "phase1" / "index.js").write_text("module.exports = {};\n", encoding="utf-8")
        legacy = tmp / "legacy"
        (legacy / "src" / "phase1").mkdir(parents=True)
        (legacy / "src" / "phase1" / "index.js").write_text("module.exports = {};\n", encoding="utf-8")
        for given, root, layout in ((spatial, spatial, "spatial"), (tmp / "repo", spatial, "spatial"),
                                    (legacy, legacy, "legacy")):
            assert resolve_phase1_dir(str(given)) == root, (given, resolve_phase1_dir(str(given)))
            out = subprocess.run(["node", "-e", js, str(relay_core), str(given)], capture_output=True, timeout=20,
                                 check=True, cwd=ENGINE_DIR)
            got = json.loads(out.stdout.decode())
            assert Path(got["root"]).resolve() == root and got["layout"] == layout, (given, got)
    repo_spatial = ENGINE_DIR.parent / "spatial"
    if PHASE1_DIR is None and not os.environ.get("PHASE1_DIR") and (repo_spatial / "phase1" / "index.js").is_file():
        assert resolve_phase1_dir(None) == repo_spatial.resolve(), resolve_phase1_dir(None)


def test_missing_node_error_message():
    try:
        NavRelay(PHASE1_DIR, node_exe="definitely-not-node-4711")
    except NavRelayError as exc:
        assert "Node.js executable not found" in str(exc), str(exc)
    else:
        raise AssertionError("NavRelay accepted a missing node executable")


# ---------------------------------------------------------------------------------------- sim


def _sweep(relay: NavRelay, pts_values: list[float]) -> list[dict]:
    out = []
    for pts in pts_values:
        message = relay.packet_at_pts(pts)
        assert message is not None, f"no packet at pts {pts}"
        validate_nav(message)
        check_routestate_matches_packet(message)
        out.append(message)
    return out


def _check_monotonic(messages: list[dict]) -> None:
    traveled = [m["packet"]["progress"]["distanceTraveledMeters"] for m in messages]
    remaining = [m["packet"]["progress"]["remainingDistanceMeters"] for m in messages]
    stamps = [m["packet"]["generatedAtMs"] for m in messages]
    assert all(b >= a for a, b in zip(traveled, traveled[1:])), traveled
    assert all(b <= a for a, b in zip(remaining, remaining[1:])), remaining
    assert all(b >= a for a, b in zip(stamps, stamps[1:])), stamps
    assert traveled[-1] > traveled[0] and remaining[-1] < remaining[0]


def test_sim_city_packets():
    with new_relay() as relay:
        relay.start_sim(str(CITY))
        info = relay.info
        assert info["videoId"] == "b1ff4656-0435391e" and info["videoFile"] == "b1ff4656-0435391e.mov", info
        assert info["samples"] == 81 and abs(info["durationSeconds"] - 40.13) < 0.01, info
        t0 = info["t0Ms"]
        pts_values = [i * 0.5 for i in range(0, 91)] + [47.0, 60.0]
        messages = _sweep(relay, pts_values)
        for pts, m in zip(pts_values, messages):
            assert m["type"] == "navigation.packet" and m["schemaVersion"] == 2
            assert m["ptsSeconds"] == pts, (m["ptsSeconds"], pts)
            assert m["tripTimestampMs"] == t0 + round(1000 * pts), (m["tripTimestampMs"], pts)
            assert m["packet"]["generatedAtMs"] <= m["tripTimestampMs"]
            assert m["packet"]["tripId"] == "demo_b1ff4656-0435391e"
            assert abs(m["serverTimeMs"] - time.time() * 1000) < 60000
        _check_monotonic(messages)
        actions = [m["routeState"]["action"] for m in messages]
        order = [a for i, a in enumerate(actions) if i == 0 or a != actions[i - 1]]
        assert order == ["GO_STRAIGHT", "TURN_RIGHT", "ARRIVE"], order
        countdown = [m["routeState"]["distanceMeters"] for m in messages if m["routeState"]["action"] == "TURN_RIGHT"]
        assert countdown == sorted(countdown, reverse=True) and countdown[0] > 80 and countdown[-1] < 10, countdown
        assert messages[-1]["routeState"]["remainingDistanceMeters"] == 0
        # seeking backwards gives the earlier state again (the timeline is stateless)
        again = relay.packet_at_pts(10.0)
        first = messages[pts_values.index(10.0)]
        assert again["routeState"] == first["routeState"] and again["packet"] == first["packet"]
        MEASUREMENTS["sim city route"] = f"{order}, TURN_RIGHT countdown {countdown[0]} -> {countdown[-1]} m"


def test_sim_highway_packets():
    with new_relay() as relay:
        relay.start_sim(str(HIGHWAY))
        messages = _sweep(relay, [i * 1.0 for i in range(0, 42)])
        _check_monotonic(messages)
        last = messages[-1]["packet"]
        assert last["routeSemantics"]["highwayName"] == "I-75/85 (synthetic)"
        speeds = [m["packet"]["progress"]["speedMps"] for m in messages]
        assert all(20 < s < 30 for s in speeds), speeds
        assert last["progress"]["distanceTraveledMeters"] >= 0.97 * last["route"]["totalDistanceMeters"], last["progress"]


def test_demo_sessions_are_consistent():
    sessions = sorted(p for p in SESSIONS.iterdir() if (p / "session_manifest.json").is_file())
    assert len(sessions) >= 2, sessions
    for session in sessions:
        manifest = json.loads((session / "session_manifest.json").read_text(encoding="utf-8"))
        route = json.loads((session / "route.json").read_text(encoding="utf-8"))
        trip = read_trip(session)
        clip = session.name
        assert manifest["videoFile"] == f"{clip}.mov" and manifest["videoId"] == clip, manifest
        assert manifest["source"] == "simulated" and "SYNTHETIC" in manifest["notes"], manifest
        stamps = [s["timestampMs"] for s in trip]
        assert stamps == sorted(stamps) and len(set(stamps)) == len(stamps)
        span_s = (stamps[-1] - stamps[0]) / 1000
        assert abs(span_s - manifest["demo"]["clipDurationSeconds"]) < 0.002, (clip, span_s)
        assert route["geometry"] and route["steps"] and route["routeId"].startswith("demo_"), clip
        for sample in trip:  # every line is a valid client.trip_state body
            TRIP_SCHEMA.validate({"type": "client.trip_state", **sample})
        blob = json.dumps(manifest) + json.dumps(route)
        assert ":\\" not in blob and "/Users/" not in blob, f"absolute path in {clip}"


# ---------------------------------------------------------------------------------------- live


def _live_samples(session: Path, base_ms: int = 1790000000000) -> list[dict]:
    trip = read_trip(session)
    t0 = trip[0]["timestampMs"]
    return [{"type": "client.trip_state", **s, "timestampMs": base_ms + s["timestampMs"] - t0} for s in trip]


def test_live_with_route_json():
    samples = _live_samples(CITY)
    with new_relay() as relay:
        relay.start_live(route_json=str(CITY / "route.json"))
        assert relay.info["routeReady"] is True and relay.info["route"]["routeId"] == "demo_b1ff4656-0435391e_mock", relay.info
        messages = []
        for sample in samples[::4]:
            TRIP_SCHEMA.validate(sample)
            message = relay.on_trip_state(sample)
            assert message is not None
            validate_nav(message)
            check_routestate_matches_packet(message)
            assert message["ptsSeconds"] is None
            assert message["tripTimestampMs"] == sample["timestampMs"] == message["packet"]["generatedAtMs"]
            assert message["packet"]["tripId"].startswith("live_")
            messages.append(message)
        _check_monotonic(messages)
        assert {m["routeState"]["action"] for m in messages} >= {"TURN_RIGHT", "ARRIVE"}


def test_live_destination_built_from_first_trip_state():
    sample = {"type": "client.trip_state", "timestampMs": 1790000000123, "location": {"lat": 40.4406, "lng": -79.9959},
              "heading": 91.2, "speedMps": 6.1, "accuracyMeters": 4.1}
    with new_relay() as relay:
        relay.start_live(destination="Georgia Tech", provider="mock")
        assert relay.info["routeReady"] is False and relay.info["pendingDestination"] == "Georgia Tech", relay.info
        message = relay.on_trip_state(sample)
        assert message is not None
        validate_nav(message)
        dest = message["packet"]["destination"]["coordinate"]
        # the mock provider places the destination near the first GPS fix (here: Pittsburgh, not Atlanta)
        assert abs(dest["lat"] - 40.4406) < 0.05 and abs(dest["lng"] + 79.9959) < 0.05, dest
        assert message["packet"]["progress"]["distanceTraveledMeters"] == 0
        # after a crash the SAME route is restored (not rebuilt from the next position)
        relay._proc.kill()
        moved = dict(sample, timestampMs=sample["timestampMs"] + 1000, location={"lat": 40.4410, "lng": -79.9955})
        message2 = relay.on_trip_state(moved)
        assert message2 is not None and relay.restarts == 1
        assert message2["packet"]["destination"] == message["packet"]["destination"]
        assert message2["packet"]["routeId"] == message["packet"]["routeId"]


def test_live_destination_with_origin():
    with new_relay() as relay:
        relay.start_live(origin="33.7756,-84.3963", destination="Piedmont Park", provider="mock")
        assert relay.info["routeReady"] is True and relay.info["route"]["destination"] == "Piedmont Park", relay.info
        message = relay.on_trip_state(_live_samples(CITY)[10])
        validate_nav(message)
        assert message["routeState"]["action"] == "TURN_RIGHT", message["routeState"]


def test_search_mock_provider():
    """op search (client.place_search): the mock provider's made-up places near `near`, in any mode, without
    disturbing live navigation; bad input raises (and does not restart the child)."""
    with new_relay() as relay:
        near = {"lat": 40.4406, "lng": -79.9959}
        places = relay.search("coffee", near=near, provider="mock")
        assert [p["label"] for p in places] == ["coffee (mock 1)", "coffee (mock 2)", "coffee (mock 3)"], places
        for p in places:
            assert set(p) == {"placeId", "label", "address", "location"} and p["placeId"].startswith("mock_"), p
            assert abs(p["location"]["lat"] - near["lat"]) < 0.02 and abs(p["location"]["lng"] - near["lng"]) < 0.02, p
        assert relay.search("coffee", near=near) == places, "mock search is deterministic"
        atlanta = relay.search("coffee")  # no near: around the mock default origin (Georgia Tech)
        assert abs(atlanta[0]["location"]["lat"] - 33.7756) < 0.02, atlanta[0]
        relay.start_live(route_json=str(CITY / "route.json"))
        assert relay.search("Piedmont Park", near=near)[0]["label"] == "Piedmont Park (mock 1)"
        assert relay.on_trip_state(_live_samples(CITY)[0]) is not None, "live navigation still running after a search"
        pid = relay.status()["pid"]
        for kwargs in ({"query": ""}, {"query": "x" * 201}, {"query": "coffee", "provider": "bogus"},
                       {"query": "coffee", "near": {"lat": 95.0, "lng": 0.0}}):
            try:
                relay.search(**kwargs)
            except NavRelayError as exc:
                assert "search failed" in str(exc), str(exc)
            else:
                raise AssertionError(f"search({kwargs}) accepted")
        assert relay.status()["pid"] == pid and relay.restarts == 0, "bad searches must not restart the child"
        MEASUREMENTS["search (mock)"] = f"{len(places)} places, first {places[0]['label']!r}"


def test_live_destination_place_built_from_next_trip_state():
    """client.destination with a location: the route goes to exactly that point (no geocode of the label), built
    from the next trip state; a crash before that replays the place, not a geocode of the label."""
    place = {"label": "Foxtail Coffee - Society Atlanta", "placeId": "ChIJexample1",
             "coordinate": {"lat": 40.4450, "lng": -79.9900}}
    sample = {"type": "client.trip_state", "timestampMs": 1790000000123, "location": {"lat": 40.4406, "lng": -79.9959},
              "heading": 91.2, "speedMps": 6.1, "accuracyMeters": 4.1}
    with new_relay() as relay:
        relay.start_live(destination_place=place, provider="mock")
        assert relay.info["routeReady"] is False and relay.info["pendingDestination"] == place["label"], relay.info
        relay._proc.kill()  # restored from the start_live request, destinationPlace included
        message = relay.on_trip_state(sample)
        assert message is not None and relay.restarts == 1, relay.last_error
        validate_nav(message)
        dest = message["packet"]["destination"]
        assert dest == {"label": place["label"], "placeId": place["placeId"], "coordinate": place["coordinate"]}, dest
        assert message["packet"]["progress"]["distanceTraveledMeters"] == 0
        with_origin = NavRelay(PHASE1_DIR)
        try:
            with_origin.start_live(origin="40.4406,-79.9959", destination_place=place, provider="mock")
            assert with_origin.info["routeReady"] is True and with_origin.info["route"]["destination"] == place["label"]
        finally:
            with_origin.close()
    for bad in ({"label": "", "coordinate": place["coordinate"]}, {"label": "x", "coordinate": {"lat": 95.0, "lng": 0}},
                {"label": "x"}):
        with new_relay() as relay:
            try:
                relay.start_live(destination_place=bad, provider="mock")
            except NavRelayError as exc:
                assert "destination_place" in str(exc) or "destinationPlace" in str(exc), str(exc)
            else:
                raise AssertionError(f"start_live(destination_place={bad}) accepted")


def test_live_bad_inputs():
    with new_relay() as relay:
        for kwargs in ({}, {"origin": "Somewhere"}):
            try:
                relay.start_live(**kwargs)
            except NavRelayError as exc:
                assert "route_json or destination" in str(exc)
            else:
                raise AssertionError(f"start_live({kwargs}) accepted")
        try:
            relay.start_live(destination="x", provider="bogus")
        except NavRelayError as exc:
            assert "unknown provider" in str(exc), str(exc)
        else:
            raise AssertionError("bogus provider accepted")
        if not relay.relay_info.get("googleKeyConfigured"):
            try:
                relay.start_live(destination="Georgia Tech", provider="google")
            except NavRelayError as exc:
                assert "GOOGLE_MAPS_API_KEY" in str(exc), str(exc)
            else:
                raise AssertionError("google provider accepted without a key")
        try:
            relay.start_live(route_json=str(CITY / "missing-route.json"))
        except NavRelayError as exc:
            assert "route file not found" in str(exc)
        else:
            raise AssertionError("missing route.json accepted")

        relay.start_live(route_json=str(CITY / "route.json"))
        pid = relay.status()["pid"]
        good = _live_samples(CITY)[0]
        for bad in ({"type": "client.trip_state"}, {"timestampMs": 1, "location": {"lat": "x", "lng": 0}},
                    {"timestampMs": 1, "location": {"lat": 95.0, "lng": 0.0}}, {"timestampMs": float("nan"), "location": good["location"]},
                    "not a dict"):
            assert relay.on_trip_state(bad) is None, bad
        assert relay.status()["pid"] == pid and relay.restarts == 0, "bad samples must not restart the child"
        # heading / speed missing (no bearing / speed in the fix): accepted as 0, like the android-collector
        message = relay.on_trip_state({"timestampMs": good["timestampMs"], "location": good["location"]})
        assert message is not None and message["packet"]["progress"]["heading"] == 0
        assert relay.packet_at_pts(1.0) is None, "packet_at_pts must return None in live mode"


def test_sim_bad_inputs():
    with new_relay() as relay:
        assert relay.packet_at_pts(1.0) is None and relay.on_trip_state(_live_samples(CITY)[0]) is None
        try:
            relay.start_sim(str(SESSIONS / "no-such-session"))
        except NavRelayError as exc:
            assert "not a phase1 session folder" in str(exc)
        else:
            raise AssertionError("missing session accepted")
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp)
            (bad / "session_manifest.json").write_text(json.dumps({"sessionId": "bad"}), encoding="utf-8")
            (bad / "route.json").write_text(json.dumps({"routeId": "r", "geometry": [], "steps": []}), encoding="utf-8")
            (bad / "trip_state.jsonl").write_text("", encoding="utf-8")
            try:
                relay.start_sim(str(bad))
            except NavRelayError as exc:
                assert "route.geometry" in str(exc), str(exc)  # phase1's own validateRoute message
            else:
                raise AssertionError("bad route accepted")
        relay.start_sim(str(CITY))
        for pts in (float("nan"), float("inf"), "abc", None):
            assert relay.packet_at_pts(pts) is None, pts
        assert relay.on_trip_state(_live_samples(CITY)[0]) is None, "on_trip_state must return None in sim mode"
        assert relay.packet_at_pts(-3.0) is not None  # before the first sample: first sample stands in


def test_manifest_video_start_offset():
    with tempfile.TemporaryDirectory() as tmp:
        session = Path(tmp)
        manifest = json.loads((CITY / "session_manifest.json").read_text(encoding="utf-8"))
        manifest["videoStartTimestampMs"] = manifest["capturedAtMs"] - 5000  # video started 5 s before GPS
        (session / "session_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        for name in ("route.json", "trip_state.jsonl"):
            (session / name).write_bytes((CITY / name).read_bytes())
        with new_relay() as relay:
            relay.start_sim(str(session))
            shifted = relay.packet_at_pts(15.0)
            relay.start_sim(str(CITY))
            plain = relay.packet_at_pts(10.0)
            assert shifted["packet"] == plain["packet"], "videoStartTimestampMs offset not applied"
            assert shifted["tripTimestampMs"] == plain["tripTimestampMs"]


# ---------------------------------------------------------------------------------------- robustness


def test_child_crash_between_calls_restarts_and_restores_sim():
    with new_relay() as relay:
        relay.start_sim(str(CITY))
        before = relay.packet_at_pts(12.3)
        pid = relay.status()["pid"]
        time.sleep(relay.MIN_RESTART_INTERVAL_S)  # measure a restart outside the crash-loop guard
        relay._proc.kill()
        relay._proc.wait(5)
        t = time.perf_counter()
        after = relay.packet_at_pts(12.3)
        restart_ms = (time.perf_counter() - t) * 1000
        assert after is not None, "no packet after the child crashed"
        validate_nav(after)
        assert after["packet"] == before["packet"] and after["routeState"] == before["routeState"]
        assert relay.restarts == 1 and relay.status()["pid"] != pid and relay.status()["alive"]
        MEASUREMENTS["crash -> restart + restore + packet"] = f"{restart_ms:.0f} ms"


def test_child_crash_during_request():
    with new_relay() as relay:
        relay.start_sim(str(HIGHWAY))
        try:  # test hook: the child exits without replying
            with relay._lock:
                relay._call({"op": "_crash"}, 5.0, replay=True, retry=False)
        except NavRelayError as exc:
            assert "child exited" in str(exc), str(exc)
        else:
            raise AssertionError("a dying child did not raise")
        message = relay.packet_at_pts(20.0)
        assert message is not None and message["packet"]["tripId"] == "demo_b1f4491b-cf446195"
        assert relay.restarts == 1


def test_hung_child_times_out_and_restarts():
    try:
        import psutil
    except ImportError:
        raise Skip("psutil not installed")
    with new_relay() as relay:
        relay.start_sim(str(CITY))
        relay.CALL_TIMEOUT_S = 1.0
        psutil.Process(relay.status()["pid"]).suspend()
        t = time.perf_counter()
        message = relay.packet_at_pts(5.0)
        elapsed = time.perf_counter() - t
        assert message is not None and relay.restarts == 1, (message, relay.restarts)
        assert elapsed < 6.0, elapsed
        MEASUREMENTS["hung child -> timeout + restart + packet"] = f"{elapsed * 1000:.0f} ms (timeout 1.0 s)"


def test_thread_safety():
    with new_relay() as relay:
        relay.start_sim(str(CITY))
        t0 = relay.info["t0Ms"]
        errors: list[str] = []

        def worker(k: int) -> None:
            for i in range(60):
                pts = round((k * 7 + i * 3) % 450 / 10, 3)
                message = relay.packet_at_pts(pts)
                if message is None or message["ptsSeconds"] != pts or message["tripTimestampMs"] != t0 + round(1000 * pts):
                    errors.append(f"thread {k} pts {pts}: {message and message['ptsSeconds']}")

        threads = [threading.Thread(target=worker, args=(k,)) for k in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(60)
        assert not errors, errors[:5]
        assert relay.restarts == 0


def test_latency():
    with new_relay() as relay:
        relay.start_sim(str(CITY))
        for pts in range(0, 41):  # warm the per-sample packet cache like a playing clip does
            relay.packet_at_pts(float(pts))
        timings = []
        for i in range(400):
            t = time.perf_counter()
            relay.packet_at_pts((i % 400) / 10)
            timings.append((time.perf_counter() - t) * 1000)
        timings.sort()
        p50, p95 = statistics.median(timings), timings[int(0.95 * len(timings))]
        MEASUREMENTS["packet_at_pts round trip"] = f"p50 {p50:.2f} ms, p95 {p95:.2f} ms, max {timings[-1]:.2f} ms (n=400)"
        assert p95 < 25, p95
        relay.start_live(route_json=str(CITY / "route.json"))
        live_t = []
        for sample in _live_samples(CITY):
            t = time.perf_counter()
            assert relay.on_trip_state(sample) is not None
            live_t.append((time.perf_counter() - t) * 1000)
        live_t.sort()
        MEASUREMENTS["on_trip_state round trip"] = (
            f"p50 {statistics.median(live_t):.2f} ms, p95 {live_t[int(0.95 * len(live_t))]:.2f} ms (n={len(live_t)})"
        )
        size = len(json.dumps(relay.on_trip_state(_live_samples(CITY)[-1]), separators=(",", ":")))
        MEASUREMENTS["navigation.packet size (mock route)"] = f"{size} bytes"


def test_startup_time_and_close():
    t = time.perf_counter()
    relay = new_relay()
    MEASUREMENTS["NavRelay() start (node + phase1 load)"] = f"{(time.perf_counter() - t) * 1000:.0f} ms"
    proc = relay._proc
    relay.start_sim(str(CITY))
    relay.close()
    relay.close()  # idempotent
    assert proc.poll() is not None, "child still running after close()"
    assert relay.packet_at_pts(1.0) is None
    try:
        relay.start_sim(str(CITY))
    except NavRelayError as exc:
        assert "closed" in str(exc)
    else:
        raise AssertionError("start_sim after close() accepted")


def test_child_exits_when_parent_stdin_closes():
    phase1 = str(resolve_phase1_dir(PHASE1_DIR))
    proc = subprocess.Popen(["node", str(RELAY_JS), "--phase1-dir", phase1], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd=ENGINE_DIR)
    ready = json.loads(proc.stdout.readline())
    assert ready["event"] == "ready", ready
    proc.stdin.close()
    assert proc.wait(10) == 0
    proc.stdout.close()


def test_contract_samples_match_relay():
    samples = CONTRACTS / "samples" / "v2"
    TRIP_SCHEMA.validate(json.loads((samples / "client.trip_state.json").read_text(encoding="utf-8")))
    for path in sorted(samples.glob("navigation.packet.*.json")):
        validate_nav(json.loads(path.read_text(encoding="utf-8")))
    city = json.loads((samples / "navigation.packet.sim_city.json").read_text(encoding="utf-8"))
    live = json.loads((samples / "navigation.packet.live.json").read_text(encoding="utf-8"))
    trip = json.loads((samples / "client.trip_state.json").read_text(encoding="utf-8"))
    with new_relay() as relay:
        relay.start_sim(str(CITY))
        fresh = relay.packet_at_pts(city["ptsSeconds"])
        assert fresh["packet"] == city["packet"] and fresh["routeState"] == city["routeState"], "regenerate: node nav/make_contract_samples.js"
        relay.start_live(route_json=str(CITY / "route.json"))
        fresh_live = relay.on_trip_state(trip)
        fresh_live["packet"]["tripId"] = live["packet"]["tripId"]  # live_<epoch ms> differs per run
        assert fresh_live["packet"] == live["packet"] and fresh_live["routeState"] == live["routeState"]


# ---------------------------------------------------------------------------------------- runner


def main() -> int:
    global PHASE1_DIR
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase1-dir", default=None)
    parser.add_argument("-k", default="", help="run tests whose name contains this")
    args = parser.parse_args()
    PHASE1_DIR = args.phase1_dir
    print(f"phase1: {resolve_phase1_dir(PHASE1_DIR)}")
    tests = [(name, fn) for name, fn in globals().items() if name.startswith("test_") and callable(fn) and args.k in name]
    failed = skipped = 0
    for name, fn in tests:
        t = time.perf_counter()
        try:
            fn()
        except Skip as exc:
            skipped += 1
            print(f"SKIP {name}: {exc}")
            continue
        except Exception:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}\n{traceback.format_exc()}")
            continue
        print(f"PASS {name} ({(time.perf_counter() - t) * 1000:.0f} ms)")
    for key, value in MEASUREMENTS.items():
        print(f"  {key}: {value}")
    print(f"{len(tests) - failed - skipped} passed, {failed} failed, {skipped} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
