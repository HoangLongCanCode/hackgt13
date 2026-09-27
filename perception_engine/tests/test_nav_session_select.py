"""Which phase1 session drives sim navigation (perception/realtime/server.py choose_nav_session + Server wiring).

Offline: no Node.js, no models, no clips. The server runs with a stub engine, a stub SimSource and the fake relay
(tests/fake_nav_relay.py); the session folders are made up in a temp dir. Plain Python (no pytest); run from
perception_engine/:

    python tests/test_nav_session_select.py [-k <substring>]

Cases: a clip with its own session (data/sim_videos/real_009/ layout) navigates without any flag; an --nav-session
recorded for another clip is overridden by the clip's own; a BDD100K clip keeps its --nav-session (demo session);
a mismatch (--nav-session for another video, the clip has none) is reported in perception.hello navigation.error and
in one perception.error; switching clips never delivers the previous session's packets.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import io
import json
import sys
import tempfile
import time
import traceback
from pathlib import Path
from types import SimpleNamespace

ENGINE_DIR = Path(__file__).resolve().parents[1]
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

from perception.realtime import server as S  # noqa: E402
from perception.realtime.ws_probe import load_validators  # noqa: E402
from tests.fake_nav_relay import FakeNavRelay  # noqa: E402

VALIDATORS = load_validators(ENGINE_DIR / "contracts" / "schemas")
FAKE_RELAY = "tests.fake_nav_relay:FakeNavRelay"


# ---------------------------------------------------------------------------------------- fixtures


def make_session(folder: Path, video_id: str, clip: str | None = "video.mp4", files=S.NAV_SESSION_FILES) -> Path:
    """A phase1 session folder (contents are never read by the fake relay); `clip` None = no clip inside (like
    nav/demo_sessions/<id>/, whose manifest names <id>.mov)."""
    folder.mkdir(parents=True, exist_ok=True)
    manifest = {"sessionId": video_id, "videoId": video_id, "videoFile": clip or f"{video_id}.mov",
                "routeFile": "route.json", "tripStateFile": "trip_state.jsonl", "source": "device"}
    for name in files:
        body = json.dumps(manifest) if name == "session_manifest.json" else ("{}" if name.endswith(".json") else "")
        (folder / name).write_text(body, encoding="utf-8")
    if clip:
        (folder / clip).write_bytes(b"")
    return folder


def make_tree(root: Path) -> SimpleNamespace:
    clips = root / "clips"
    t = SimpleNamespace(root=root, clips=clips,
                        rec_a=make_session(clips / "rec_a", "rec_a"),          # recorded drive, own session
                        rec_b=make_session(clips / "rec_b", "rec_b"),          # another recorded drive
                        demo_x=make_session(root / "demo" / "clip_x", "clip_x", clip=None))  # BDD demo session
    (clips / "bdd").mkdir(parents=True)
    for name in ("clip_x.mov", "clip_y.mov"):                                     # BDD clips, no session
        (clips / "bdd" / name).write_bytes(b"")
    make_session(clips / "partial", "partial", files=("session_manifest.json", "route.json"))  # no trip_state
    make_session(clips / "renamed", "renamed", clip="drive.mp4").joinpath("drive.mp4").rename(
        clips / "renamed" / "other.mp4")                                           # manifest names another file
    return t


class StubEngine:
    cfg: dict = {}

    def describe(self) -> dict:
        return {}

    def session_camera(self, cam, wh=None):
        w, h = wh or (1280, 720)
        return SimpleNamespace(width=w, height=h,
                               to_dict=lambda: {"focalPx": 700.0 * w / 1280, "principalPoint": [w / 2, h / 2]})


class StubSim:
    """SimSource stand-in: no clip reading; playback runs in real time from its creation."""

    def __init__(self, srv, path, info, video_id, fixed_lookahead, margin_s):
        self.video_id, self.lookahead, self.submitted, self.seeks = video_id, 0.35, 0, 0
        self.t0 = time.perf_counter()

    def start(self) -> None:
        pass

    def stop(self) -> None:
        pass

    def on_playback(self, *args) -> None:
        pass

    def playback_pts(self, now=None) -> float:
        return time.perf_counter() - self.t0


@contextlib.contextmanager
def patched_server():
    saved = S.DEFAULT_VIDEO_DIRS, S.probe_clip, S.SimSource
    S.DEFAULT_VIDEO_DIRS = ()                                          # only the temp clips
    S.probe_clip = lambda path: S.ClipInfo(30.0, 900, 1920, 1080)
    S.SimSource = StubSim
    FakeNavRelay.calls.clear()
    try:
        yield
    finally:
        S.DEFAULT_VIDEO_DIRS, S.probe_clip, S.SimSource = saved


def new_server(tree: SimpleNamespace, *flags: str) -> S.Server:
    a = S.build_parser().parse_args(["--mode", "sim", "--port", "8799", "--video-dir", str(tree.clips),
                                     "--nav-relay-impl", FAKE_RELAY, *flags])
    return S.Server(StubEngine(), a)


def drain(c: S.Client) -> list[dict]:
    """Control messages queued for the client (hello, error, ...), validated against the schemas."""
    out = []
    while c.ctrl:
        m = json.loads(c.ctrl.popleft())
        errors = list(VALIDATORS[m["type"]].iter_errors(m)) if m["type"] in VALIDATORS else []
        assert not errors, f"{m['type']} invalid: {errors[0].message} at {list(errors[0].absolute_path)}"
        out.append(m)
    return out


def hellos(msgs: list[dict]) -> list[dict]:
    return [m for m in msgs if m["type"] == "perception.hello"]


def start_sims() -> list[str]:
    return [str(Path(d).resolve()) for op, d in FakeNavRelay.calls if op == "start_sim"]


async def wait_for(cond, timeout: float, what: str) -> None:
    t0 = time.monotonic()
    while not cond():
        if time.monotonic() - t0 > timeout:
            raise AssertionError(f"timed out waiting for {what}")
        await asyncio.sleep(0.05)


async def play(srv: S.Server, c: S.Client, video_id: str, session: Path | None) -> list[dict]:
    """client.hello for `video_id`; waits until the relay replays `session` (None: navigation off) and returns the
    control messages of the switch."""
    drain(c)
    c.nav = None
    ok = await srv.start_sim_session(c, video_id)
    assert ok, f"clip {video_id} not found"
    if session is not None:
        want = str(session.resolve())
        await wait_for(lambda: start_sims() and start_sims()[-1] == want and srv.nav.running_gen is not None
                       and srv.nav.info()["available"], 5, f"start_sim {session.name}")
        await wait_for(lambda: c.nav is not None, 5, "a navigation.packet")
    await asyncio.sleep(0.15)                                          # the worker's hello after the switch
    return drain(c)


def run(scenario, tree: SimpleNamespace, *flags: str):
    async def main():
        srv = new_server(tree, *flags)
        srv.aloop = asyncio.get_running_loop()
        srv.start_background()
        c = S.Client(None, "test-tablet")
        srv.clients[c.key] = c
        try:
            if srv.nav is not None:                                    # --nav-session: its start-up hello first
                await wait_for(lambda: srv.nav.running_gen is not None or srv.nav.error, 5, "the initial session")
                await asyncio.sleep(0.15)
                drain(c)
            return await scenario(srv, c)
        finally:
            await asyncio.to_thread(srv.stop)
    with patched_server():
        return asyncio.run(main())


# ---------------------------------------------------------------------------------------- tests


def test_choose_nav_session_rules():
    with tempfile.TemporaryDirectory() as tmp:
        t = make_tree(Path(tmp))
        clip_a = t.rec_a / "video.mp4"
        assert S.own_nav_session(clip_a) == t.rec_a
        assert S.choose_nav_session("rec_a", clip_a, None) == (t.rec_a, "clip", None)
        # --nav-session recorded for another drive: the clip's own session wins
        assert S.choose_nav_session("rec_a", clip_a, t.rec_b) == (t.rec_a, "clip", None)
        # BDD clip without a session of its own: --nav-session (its demo session) unchanged
        clip_x = t.clips / "bdd" / "clip_x.mov"
        assert S.own_nav_session(clip_x) is None
        assert S.choose_nav_session("clip_x", clip_x, t.demo_x) == (t.demo_x, "--nav-session", None)
        # ... and for another clip: kept, with the mismatch message
        clip_y = t.clips / "bdd" / "clip_y.mov"
        assert S.choose_nav_session("clip_y", clip_y, t.demo_x) == (
            t.demo_x, "--nav-session", "navigation session is for clip_x, the clip is clip_y")
        assert S.choose_nav_session("clip_y", clip_y, None) == (None, None, None)
        assert S.choose_nav_session(None, None, t.demo_x) == (t.demo_x, "--nav-session", None)   # live / no clip
        # not a session of its own: a file missing, or the manifest names another clip
        assert S.own_nav_session(t.clips / "partial" / "video.mp4") is None
        assert S.own_nav_session(t.clips / "renamed" / "other.mp4") is None
        assert S.nav_session_video_id(t.demo_x) == "clip_x"
        assert S.nav_session_video_id(t.root / "no-such-session") == "no-such-session"


def test_clip_with_own_session_navigates_without_flags():
    """No --nav-session: the recorded drive's own session is used; a clip without one turns navigation off (no
    stale packet); the next recorded drive switches the relay to its session."""
    with tempfile.TemporaryDirectory() as tmp:
        t = make_tree(Path(tmp))

        async def scenario(srv: S.Server, c: S.Client):
            assert srv.nav is None and srv.nav_info()["mode"] == "off", "no relay before a clip needs one"
            msgs = await play(srv, c, "rec_a", t.rec_a)
            h = hellos(msgs)[-1]
            assert h["server"]["navigationSession"] == {"id": "rec_a", "videoId": "rec_a", "source": "clip"}, h
            assert h["navigation"] == {"mode": "sim", "available": True, "error": None, "destination": None}, h
            assert not [m for m in msgs if m["type"] == "perception.error"], msgs
            assert json.loads(c.nav)["type"] == "navigation.packet"
            # a BDD clip without a session and no --nav-session: navigation off, the old packet is gone
            msgs = await play(srv, c, "clip_y", None)
            h = hellos(msgs)[-1]
            assert h["navigation"]["mode"] == "off" and h["server"]["navigationSession"] is None, h
            assert srv.last_nav is None
            c.nav = None                        # (one packet of rec_a may have landed while the clip was probed)
            calls = len(FakeNavRelay.calls)
            await asyncio.sleep(1.2)
            assert c.nav is None and len(FakeNavRelay.calls) == calls, "packets after the switch to no session"
            msgs = await play(srv, c, "rec_b", t.rec_b)
            assert hellos(msgs)[-1]["server"]["navigationSession"]["id"] == "rec_b"
            assert start_sims() == [str(t.rec_a.resolve()), str(t.rec_b.resolve())], start_sims()
        run(scenario, t)


def test_explicit_session_for_another_clip_is_overridden():
    """The reported bug: --nav-session real_010 while the tablet plays real_009 -> real_009's own session."""
    with tempfile.TemporaryDirectory() as tmp:
        t = make_tree(Path(tmp))
        log = io.StringIO()

        async def scenario(srv: S.Server, c: S.Client):
            await wait_for(lambda: start_sims() == [str(t.rec_b.resolve())], 5, "the --nav-session at start-up")
            with contextlib.redirect_stdout(log):
                msgs = await play(srv, c, "rec_a", t.rec_a)
            h = hellos(msgs)[-1]
            assert h["server"]["navigationSession"] == {"id": "rec_a", "videoId": "rec_a", "source": "clip"}, h
            assert h["navigation"]["error"] is None and h["navigation"]["available"] is True, h
            assert not [m for m in msgs if m["type"] == "perception.error"], msgs
            assert start_sims()[-1] == str(t.rec_a.resolve()), start_sims()
            # back to the clip the flag was recorded for: the flag's session again
            msgs = await play(srv, c, "rec_b", t.rec_b)
            assert hellos(msgs)[-1]["server"]["navigationSession"]["id"] == "rec_b"
        run(scenario, t, "--nav-session", str(t.rec_b))
        text = log.getvalue()
        assert "rec_a has its own session (rec_a)" in text and "instead of --nav-session rec_b" in text, text


def test_bdd_clip_keeps_its_nav_session():
    with tempfile.TemporaryDirectory() as tmp:
        t = make_tree(Path(tmp))

        async def scenario(srv: S.Server, c: S.Client):
            msgs = await play(srv, c, "clip_x", t.demo_x)
            h = hellos(msgs)[-1]
            assert h["server"]["navigationSession"] == {"id": "clip_x", "videoId": "clip_x",
                                                        "source": "--nav-session"}, h
            assert h["navigation"]["mode"] == "sim" and h["navigation"]["error"] is None, h
            assert not [m for m in msgs if m["type"] == "perception.error"], msgs
            assert start_sims() == [str(t.demo_x.resolve())], "the relay was restarted for the same session"
        run(scenario, t, "--nav-session", str(t.demo_x))


def test_mismatch_is_reported():
    """--nav-session for clip_x while clip_y (no session of its own) plays: kept, but perception.hello
    navigation.error says so and the controller gets one perception.error (the tablet's status line)."""
    with tempfile.TemporaryDirectory() as tmp:
        t = make_tree(Path(tmp))
        note = "navigation session is for clip_x, the clip is clip_y"

        async def scenario(srv: S.Server, c: S.Client):
            msgs = await play(srv, c, "clip_y", t.demo_x)
            hs = hellos(msgs)
            assert hs and all(h["navigation"]["error"] == note for h in hs), [h["navigation"] for h in hs]
            assert hs[-1]["navigation"]["available"] is True, "packets still flow (kept, not stopped)"
            assert hs[-1]["server"]["navigationSession"]["source"] == "--nav-session"
            errors = [m for m in msgs if m["type"] == "perception.error"]
            assert len(errors) == 1 and errors[0]["message"].startswith(note), errors
            assert errors[0]["detail"]["videoId"] == "clip_y", errors[0]
            order = [m["type"] for m in msgs]
            assert order.index("perception.error") > order.index("perception.hello"), \
                "the error must follow the new session's hello (a new session clears the tablet's error)"
            assert srv.health()["navigation"]["error"] == note
            # the matching clip clears it
            msgs = await play(srv, c, "clip_x", t.demo_x)
            assert hellos(msgs)[-1]["navigation"]["error"] is None
            assert not [m for m in msgs if m["type"] == "perception.error"], msgs
        run(scenario, t, "--nav-session", str(t.demo_x))


def test_packets_of_an_older_session_are_dropped():
    delivered: list[str] = []
    srv = SimpleNamespace(broadcast_nav=delivered.append, post=lambda fn, *args: fn(*args),
                          broadcast_hello=lambda *_: None)
    a = SimpleNamespace(nav_session=None, nav_route=None, nav_destination=None, speed_limits="off")
    w = S.NavWorker(srv, FakeNavRelay, a, mode="sim")
    assert w.info()["mode"] == "off" and w.session_info() is None
    assert w.set_session(Path("rec_a"), "clip", None) and not w.set_session(Path("rec_a"), "clip", None)
    old = w.session_gen
    w._publish({"type": "navigation.packet"}, old)
    assert len(delivered) == 1
    assert w.set_session(Path("rec_b"), "clip", None)
    w._publish({"type": "navigation.packet"}, old)                 # computed for rec_a, delivered after the switch
    assert len(delivered) == 1, "a packet of the previous session reached the clients"


# ---------------------------------------------------------------------------------------- runner


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-k", default="", help="run tests whose name contains this")
    args = parser.parse_args()
    tests = [(name, fn) for name, fn in globals().items() if name.startswith("test_") and callable(fn) and args.k in name]
    failed = 0
    for name, fn in tests:
        t = time.perf_counter()
        try:
            fn()
        except Exception:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}\n{traceback.format_exc()}")
            continue
        print(f"PASS {name} ({(time.perf_counter() - t) * 1000:.0f} ms)")
    print(f"{len(tests) - failed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
