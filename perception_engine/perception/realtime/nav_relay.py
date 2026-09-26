"""Navigation relay: runs the phase1 route engine (Node.js) as a child process.

The perception server forwards phase1 ``SpatialNavigationPacket``s to the tablet on the same
WebSocket as ``navigation.packet`` messages (``contracts/PROTOCOL_v2.md``, Navigation). Route
logic stays in phase1: this class only talks to ``nav/phase1_relay.js`` over JSON lines on the
child's stdin/stdout (its stderr is forwarded to ``logging``).

Usage (from a worker thread, never from the asyncio loop -- every call blocks on the child)::

    relay = NavRelay()                                   # finds phase1, starts node
    relay.start_sim("nav/demo_sessions/b1ff4656-0435391e")
    msg = relay.packet_at_pts(12.3)                      # ready-to-send 'navigation.packet' dict
    relay.start_live(destination="Georgia Tech", provider="mock")
    msg = relay.on_trip_state(client_trip_state_body)    # dict | None
    places = relay.search("coffee", near={"lat": 33.7756, "lng": -84.3963})   # [{placeId, label, address, location}]
    relay.start_live(destination_place={"label": places[0]["label"], "placeId": places[0]["placeId"],
                                        "coordinate": places[0]["location"]})   # routed to exactly that point
    relay.close()

Errors: the constructor, ``start_*`` and ``search`` raise :class:`NavRelayError` with a readable message.
``packet_at_pts`` / ``on_trip_state`` never raise; they return ``None`` (and log, rate-limited)
when there is no packet, and ``last_error`` then says why (it is cleared by the next packet; the
server's NavWorker reports repeated failures to the clients). A crashed or hung child is restarted
on the next call and its last ``start_*`` state is replayed, then the call is retried once.

Server CLI flags (see perception/realtime/server.py): ``--nav-session <dir>`` (sim),
``--nav-route <route.json>`` | ``--nav-destination <query> [--nav-origin <query>]
[--nav-provider mock|google]`` (live), ``--phase1-dir <dir>``.

Manual check::

    python -m perception.realtime.nav_relay --nav-session nav/demo_sessions/b1ff4656-0435391e --pts 0 10 20 30 40
"""

from __future__ import annotations

import collections
import json
import logging
import math
import os
import queue
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Any

__all__ = ["NavRelay", "NavRelayError", "resolve_phase1_dir", "RELAY_JS"]

log = logging.getLogger(__name__)

_ENGINE_DIR = Path(__file__).resolve().parents[2]  # perception_engine/
_REPO_ROOT = _ENGINE_DIR.parent
RELAY_JS = _ENGINE_DIR / "nav" / "phase1_relay.js"
SETUP_HINT = (
    "The navigation engine is on branch 'main' under spatial/ (bring main into this checkout), or set PHASE1_DIR / "
    "pass --phase1-dir <dir> (a spatial/ folder, or a legacy checkout with src/phase1/)"
)


_KEY_PARAM = re.compile(r"(?<![A-Za-z0-9_])(key=)[^&#\s'\"]+", re.IGNORECASE)


def redact(text: object) -> str:
    """Error text goes to the log and to every connected client: never let a `key=` URL parameter through."""
    return _KEY_PARAM.sub(r"\1REDACTED", str(text))


class NavRelayError(RuntimeError):
    """The navigation relay cannot do what was asked (setup, bad session, dead child...)."""

    def __init__(self, message: object = "") -> None:
        super().__init__(redact(message))


class _ChildDied(Exception):
    pass


class _ChildTimeout(Exception):
    pass


_EOF = object()


def _phase1_root(path: Path, only: str | None = None) -> Path | None:
    """The navigation engine's root folder in either layout, or None: ``<root>/phase1/index.js`` (spatial/ on main)
    or ``<root>/src/phase1/index.js`` (legacy checkout); a repo root holding ``spatial/`` gives ``<repo>/spatial``.
    ``only`` ("spatial" | "legacy") limits the layouts tried. Same rules as ``nav/relay_core.js``."""
    if only in (None, "spatial") and (path / "phase1" / "index.js").is_file():
        return path
    if only in (None, "legacy") and (path / "src" / "phase1" / "index.js").is_file():
        return path
    if only in (None, "spatial") and (path / "spatial" / "phase1" / "index.js").is_file():
        return path / "spatial"
    return None


def _is_phase1_dir(path: Path) -> bool:
    return _phase1_root(Path(path).expanduser().resolve()) is not None


def resolve_phase1_dir(phase1_dir: str | os.PathLike | None = None) -> Path:
    """Navigation engine ("phase1") root to use: argument > env PHASE1_DIR > ``<repo>/spatial`` (main) > ``<repo>``
    with a legacy ``src/phase1`` > ``../hackgt13-phase1`` next to the repo (legacy checkout). An explicit value that
    is not the engine in either layout is an error (no silent fallback)."""
    if phase1_dir:
        candidates = [(Path(phase1_dir), "phase1_dir / --phase1-dir", None)]
    elif os.environ.get("PHASE1_DIR"):
        candidates = [(Path(os.environ["PHASE1_DIR"]), "env PHASE1_DIR", None)]
    else:
        candidates = [
            (_REPO_ROOT / "spatial", "repo spatial/", "spatial"),
            (_REPO_ROOT, "repo root, legacy src/phase1", "legacy"),
            (_REPO_ROOT.parent / "hackgt13-phase1", "sibling checkout", None),
        ]
    tried = []
    for path, source, only in candidates:
        path = path.expanduser().resolve()
        root = _phase1_root(path, only)
        if root is not None:
            return root
        tried.append(f"{path} ({source})")
    raise NavRelayError(
        "phase1 route engine not found: no phase1/index.js or src/phase1/index.js in "
        + "; ".join(tried)
        + f". {SETUP_HINT}."
    )


class NavRelay:
    """Thread-safe, synchronous client of ``nav/phase1_relay.js`` (one child process)."""

    START_TIMEOUT_S = 15.0  # node start-up until its 'ready' line
    CALL_TIMEOUT_S = 5.0  # at_pts / trip_state with a ready route
    ROUTE_TIMEOUT_S = 45.0  # start_live / first trip_state may call the Google APIs
    SEARCH_TIMEOUT_S = 10.0  # search may call the Google Places / Geocoding APIs
    MIN_RESTART_INTERVAL_S = 1.0
    WARN_INTERVAL_S = 5.0

    def __init__(self, phase1_dir: str | None = None, node_exe: str = "node"):
        self.phase1_dir = resolve_phase1_dir(phase1_dir)
        node = shutil.which(node_exe) or (node_exe if Path(node_exe).is_file() else None)
        if not node:
            raise NavRelayError(
                f"Node.js executable not found: {node_exe!r}. Install Node 18+ (on PATH) or pass node_exe=<path to node>."
            )
        if not RELAY_JS.is_file():
            raise NavRelayError(f"relay script missing: {RELAY_JS}")
        self._node = node
        self._lock = threading.RLock()
        self._proc: subprocess.Popen | None = None
        self._replies: queue.Queue | None = None
        self._stderr_tail: collections.deque[str] = collections.deque(maxlen=40)
        self._next_id = 0
        self._last_spawn = 0.0
        self._restore: dict[str, Any] | None = None  # last start_* request, replayed after a restart
        self._closed = False
        self._last_warn = 0.0
        self._suppressed_warnings = 0
        self.mode: str | None = None  # 'sim' | 'live' | None
        self.info: dict[str, Any] = {}  # info returned by the last start_*
        self.relay_info: dict[str, Any] = {}  # the child's 'ready' info (phase1Dir, googleKeyConfigured...)
        self.restarts = 0
        self.last_error: str | None = None  # why the last packet_at_pts / on_trip_state returned None
        with self._lock:
            self._spawn()

    # ------------------------------------------------------------------ public API

    def start_sim(self, session_dir: str) -> None:
        """Replay a phase1 session folder (session_manifest.json, route.json, trip_state.jsonl[, video])."""
        path = Path(session_dir).expanduser().resolve()
        if not (path / "session_manifest.json").is_file():
            raise NavRelayError(f"not a phase1 session folder (no session_manifest.json): {path}")
        request = {"op": "start_sim", "sessionDir": str(path)}
        with self._lock:
            reply = self._call(request, self.CALL_TIMEOUT_S * 2, replay=False)
            if not reply.get("ok"):
                raise NavRelayError(f"start_sim failed: {reply.get('error')}")
            self._restore = request
            self.mode = "sim"
            self.info = reply.get("info") or {}

    def start_live(
        self,
        route_json: str | None = None,
        origin: str | None = None,
        destination: str | None = None,
        provider: str = "mock",
        destination_place: dict | None = None,
    ) -> None:
        """Live navigation from client.trip_state samples. Route: ``route_json`` (a phase1 route.json),
        or ``destination_place`` ({label, placeId, coordinate: {lat, lng}}, a place picked from ``search``:
        routed to exactly, not geocoded), or ``destination`` (a query resolved by the phase1 provider); both
        with an optional ``origin`` (a place query or "lat,lng"). Without ``origin`` the route is built from
        the first trip-state position."""
        request: dict[str, Any] = {"op": "start_live", "provider": provider or "mock"}
        if route_json:
            path = Path(route_json).expanduser().resolve()
            if not path.is_file():
                raise NavRelayError(f"route file not found: {path}")
            request["routeJson"] = str(path)
        elif destination_place or destination:
            if destination_place:
                try:
                    coord = destination_place["coordinate"]
                    request["destinationPlace"] = {
                        "label": str(destination_place["label"]),
                        "placeId": destination_place.get("placeId"),
                        "coordinate": {"lat": float(coord["lat"]), "lng": float(coord["lng"])},
                    }
                except (KeyError, TypeError, ValueError) as exc:
                    raise NavRelayError(
                        f"destination_place must be {{label, placeId, coordinate: {{lat, lng}}}}: {exc!r}"
                    ) from None
            else:
                request["destination"] = destination
            if origin:
                request["origin"] = origin
        else:
            raise NavRelayError("start_live needs route_json or destination (or destination_place)")
        with self._lock:
            reply = self._call(request, self.ROUTE_TIMEOUT_S, replay=False)
            if not reply.get("ok"):
                raise NavRelayError(f"start_live failed: {reply.get('error')}")
            route = reply.get("route")
            # Replay the resolved route after a restart (no second geocode / Directions call); a route still
            # pending replays this request, destination / destinationPlace included.
            self._restore = {"op": "start_live", "route": route, "provider": request["provider"]} if route else request
            self.mode = "live"
            self.info = reply.get("info") or {}

    def packet_at_pts(self, pts_seconds: float) -> dict | None:
        """sim: the 'navigation.packet' message for media time ``pts_seconds`` (None if not in sim mode or on error)."""
        try:
            pts = float(pts_seconds)
        except (TypeError, ValueError):
            return self._no_packet(f"packet_at_pts: bad pts {pts_seconds!r}")
        if not math.isfinite(pts):
            return self._no_packet(f"packet_at_pts: bad pts {pts_seconds!r}")
        with self._lock:
            if self.mode != "sim" or self._closed:
                return None
            return self._packet({"op": "at_pts", "ptsSeconds": pts}, self.CALL_TIMEOUT_S)

    def on_trip_state(self, sample: dict) -> dict | None:
        """live: feed one client.trip_state body; returns the 'navigation.packet' message (None on error)."""
        if not isinstance(sample, dict):
            return self._no_packet(f"on_trip_state: sample must be a dict, got {type(sample).__name__}")
        with self._lock:
            if self.mode != "live" or self._closed:
                return None
            route_pending = not (self._restore and self._restore.get("route"))
            timeout = self.ROUTE_TIMEOUT_S if route_pending else self.CALL_TIMEOUT_S
            return self._packet({"op": "trip_state", "sample": sample}, timeout)

    def search(self, query: str, near: dict | None = None, provider: str = "mock") -> list[dict]:
        """Destination search (any mode): places matching free text, best first, as
        ``[{placeId, label, address, location: {lat, lng}}]`` (at most 8). ``near`` ({lat, lng}) biases the
        results. The phase1 provider answers: Google Places Text Search (Geocoding fallback) or made-up mock
        places. Raises NavRelayError (message redacted); one attempt of at most SEARCH_TIMEOUT_S."""
        request: dict[str, Any] = {"op": "search", "query": query, "provider": provider or "mock"}
        if near is not None:
            try:
                request["near"] = {"lat": float(near["lat"]), "lng": float(near["lng"])}
            except (KeyError, TypeError, ValueError) as exc:
                raise NavRelayError(f"search: near must be {{lat, lng}}: {exc!r}") from None
        with self._lock:
            # A dead child is restarted (and its navigation restored) first; a hung search is not retried.
            reply = self._call(request, self.SEARCH_TIMEOUT_S, replay=True, retry=False)
        if not reply.get("ok"):
            raise NavRelayError(f"search failed: {reply.get('error')}")
        places = reply.get("places")
        return places if isinstance(places, list) else []

    def status(self) -> dict[str, Any]:
        with self._lock:
            proc = self._proc
            return {
                "mode": self.mode,
                "alive": bool(proc and proc.poll() is None),
                "pid": proc.pid if proc else None,
                "restarts": self.restarts,
                "phase1Dir": str(self.phase1_dir),
                "googleKeyConfigured": bool(self.relay_info.get("googleKeyConfigured")),
                "info": self.info,
            }

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self.mode = None
            proc = self._proc
            if proc is not None and proc.poll() is None:
                try:
                    self._roundtrip({"op": "close"}, 2.0)
                except Exception:  # noqa: BLE001 - best effort, killed below anyway
                    pass
                try:
                    proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass
            self._kill_child()

    def __enter__(self) -> "NavRelay":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"NavRelay(mode={self.mode!r}, phase1_dir='{self.phase1_dir}', restarts={self.restarts})"

    # ------------------------------------------------------------------ internals

    def _packet(self, request: dict, timeout: float) -> dict | None:
        try:
            reply = self._call(request, timeout, replay=True)
        except NavRelayError as exc:
            return self._no_packet(str(exc))
        if not reply.get("ok"):
            return self._no_packet(f"{request['op']}: {reply.get('error')}")
        if reply.get("route") and self._restore and self._restore.get("op") == "start_live":
            # live route was just built from the first trip state: replay that route after a restart.
            self._restore = {"op": "start_live", "route": reply["route"], "provider": self._restore.get("provider", "mock")}
        message = reply.get("message")
        if not isinstance(message, dict):
            return self._no_packet(f"{request['op']}: reply without message")
        message["serverTimeMs"] = int(time.time() * 1000)
        self.last_error = None
        return message

    def _no_packet(self, why: str) -> None:
        why = redact(why)
        self.last_error = why
        self._warn(why)
        return None

    def _call(self, request: dict, timeout: float, replay: bool, retry: bool = True) -> dict:
        """Send one request and return the reply dict (ok or not). Restarts a dead / hung child
        (replaying the last start_* when ``replay``) and retries once. Raises NavRelayError."""
        if self._closed:
            raise NavRelayError("NavRelay is closed")
        try:  # bad data is the caller's problem, not a reason to restart the child
            json.dumps(request, allow_nan=False)
        except (TypeError, ValueError) as exc:
            raise NavRelayError(f"{request.get('op')}: request is not valid JSON ({exc})") from None
        last_error: Exception | None = None
        for _attempt in range(2 if retry else 1):
            try:
                if self._proc is None or self._proc.poll() is not None:
                    self._restart(replay)
                return self._roundtrip(request, timeout)
            except (_ChildDied, _ChildTimeout, OSError, ValueError) as exc:
                last_error = exc
                log.warning("phase1 relay %s failed (%s: %s); restarting the child", request.get("op"), type(exc).__name__, exc)
                self._kill_child()
        tail = " | ".join(list(self._stderr_tail)[-5:])
        raise NavRelayError(
            f"phase1 relay '{request.get('op')}' failed: {type(last_error).__name__}: {last_error}"
            + (f" (relay stderr: {tail})" if tail else "")
        )

    def _restart(self, replay: bool) -> None:
        self.restarts += 1
        log.warning("restarting phase1 relay (restart #%d)", self.restarts)
        self._spawn()
        if replay and self._restore is not None:
            reply = self._roundtrip(dict(self._restore), self.ROUTE_TIMEOUT_S)
            if not reply.get("ok"):
                raise NavRelayError(f"could not restore {self._restore.get('op')} after a restart: {reply.get('error')}")

    def _spawn(self) -> None:
        wait = self.MIN_RESTART_INTERVAL_S - (time.monotonic() - self._last_spawn)
        if self._last_spawn and wait > 0:
            time.sleep(wait)  # a crash loop costs at most one spawn per second
        self._kill_child()
        cmd = [self._node, str(RELAY_JS), "--phase1-dir", str(self.phase1_dir)]
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=str(_ENGINE_DIR),
                creationflags=creationflags,
            )
        except OSError as exc:
            raise NavRelayError(f"could not start {self._node}: {exc}") from exc
        self._last_spawn = time.monotonic()
        replies: queue.Queue = queue.Queue()
        threading.Thread(target=self._read_stdout, args=(proc, replies), name="nav-relay-stdout", daemon=True).start()
        threading.Thread(target=self._read_stderr, args=(proc,), name="nav-relay-stderr", daemon=True).start()
        self._proc, self._replies = proc, replies
        try:
            ready = self._wait_for(None, self.START_TIMEOUT_S)
        except (_ChildDied, _ChildTimeout) as exc:
            self._kill_child()
            tail = " | ".join(self._stderr_tail) or "no output"
            raise NavRelayError(f"phase1 relay did not start ({type(exc).__name__}); stderr: {tail}") from None
        if not ready.get("ok"):
            self._kill_child()
            raise NavRelayError(f"phase1 relay failed to start: {ready.get('error')}")
        self.relay_info = ready.get("info") or {}
        log.info(
            "phase1 relay pid %s ready (phase1 %s, Google key %s)",
            proc.pid,
            self.relay_info.get("phase1Dir"),
            "configured" if self.relay_info.get("googleKeyConfigured") else "not configured",
        )

    def _roundtrip(self, request: dict, timeout: float) -> dict:
        proc = self._proc
        if proc is None or proc.stdin is None:
            raise _ChildDied("no child process")
        self._next_id += 1
        request_id = self._next_id
        line = json.dumps({**request, "id": request_id}, separators=(",", ":"), allow_nan=False) + "\n"
        proc.stdin.write(line.encode("utf-8"))
        proc.stdin.flush()
        return self._wait_for(request_id, timeout)

    def _wait_for(self, request_id: int | None, timeout: float) -> dict:
        """Next reply with this id (None = the start-up 'ready'/'fatal' line); stale replies are dropped."""
        assert self._replies is not None
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise _ChildTimeout(f"no reply within {timeout:.1f} s")
            try:
                item = self._replies.get(timeout=remaining)
            except queue.Empty:
                continue
            if item is _EOF:
                code = None
                if self._proc is not None:
                    try:
                        code = self._proc.wait(timeout=0.5)
                    except subprocess.TimeoutExpired:
                        pass
                raise _ChildDied(f"child exited (code {code})")
            if request_id is None:
                if item.get("id") is None and item.get("event") in ("ready", "fatal"):
                    return item
            elif item.get("id") == request_id:
                return item
            log.debug("dropping stale relay reply: %.200s", item)

    @staticmethod
    def _read_stdout(proc: subprocess.Popen, replies: queue.Queue) -> None:
        try:
            for raw in iter(proc.stdout.readline, b""):
                text = raw.decode("utf-8", "replace").strip()
                if not text:
                    continue
                try:
                    obj = json.loads(text)
                except json.JSONDecodeError:
                    log.warning("non-JSON line from phase1 relay: %.200s", text)
                    continue
                if isinstance(obj, dict):
                    replies.put(obj)
        except (OSError, ValueError):
            pass
        finally:
            replies.put(_EOF)
            try:  # the reader owns this pipe end (closing it from another thread could block)
                proc.stdout.close()
            except OSError:
                pass

    def _read_stderr(self, proc: subprocess.Popen) -> None:
        try:
            for raw in iter(proc.stderr.readline, b""):
                text = raw.decode("utf-8", "replace").rstrip()
                if text:
                    self._stderr_tail.append(text)
                    log.info("%s", text)
        except (OSError, ValueError):
            pass
        finally:
            try:
                proc.stderr.close()
            except OSError:
                pass

    def _kill_child(self) -> None:
        proc, self._proc = self._proc, None
        if proc is None:
            return
        if proc.poll() is None:
            try:
                proc.kill()
            except OSError:
                pass
        try:
            proc.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            log.warning("phase1 relay pid %s did not exit after kill", proc.pid)
        try:  # stdout / stderr are closed by their reader threads at EOF
            if proc.stdin:
                proc.stdin.close()
        except OSError:
            pass

    def _warn(self, message: str) -> None:
        now = time.monotonic()
        if now - self._last_warn >= self.WARN_INTERVAL_S:
            suffix = f" (+{self._suppressed_warnings} similar)" if self._suppressed_warnings else ""
            log.warning("nav: %s%s", message, suffix)
            self._last_warn = now
            self._suppressed_warnings = 0
        else:
            self._suppressed_warnings += 1


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Print navigation.packet routeStates from the phase1 relay.")
    parser.add_argument("--phase1-dir", default=None)
    parser.add_argument("--nav-session", help="phase1 session folder (sim)")
    parser.add_argument("--pts", type=float, nargs="*", default=[0, 10, 20, 30, 40])
    parser.add_argument("--nav-route", help="route.json (live)")
    parser.add_argument("--nav-destination")
    parser.add_argument("--nav-origin")
    parser.add_argument("--nav-provider", default="mock", choices=["mock", "google"])
    parser.add_argument("--trip-state", help="trip_state.jsonl to feed in live mode")
    parser.add_argument("--full", action="store_true", help="print whole messages")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    def show(message: dict | None) -> None:
        if message is None:
            print("None")
        elif args.full:
            print(json.dumps(message, indent=1))
        else:
            print(json.dumps({"ptsSeconds": message["ptsSeconds"], "tripTimestampMs": message["tripTimestampMs"], **message["routeState"]}))

    with NavRelay(args.phase1_dir) as relay:
        if args.nav_session:
            relay.start_sim(args.nav_session)
            print(json.dumps(relay.info))
            for pts in args.pts:
                show(relay.packet_at_pts(pts))
        elif args.nav_route or args.nav_destination:
            relay.start_live(args.nav_route, args.nav_origin, args.nav_destination, args.nav_provider)
            print(json.dumps(relay.info))
            if args.trip_state:
                for line in Path(args.trip_state).read_text(encoding="utf-8").splitlines():
                    if line.strip():
                        show(relay.on_trip_state({"type": "client.trip_state", **json.loads(line)}))
        else:
            parser.error("give --nav-session or --nav-route / --nav-destination")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
