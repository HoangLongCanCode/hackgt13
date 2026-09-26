'use strict';
// Shared core of the phase1 navigation relay.
//
// Wraps the navigation engine ("phase1", on main under spatial/, folder spatial/phase1) WITHOUT
// copying or modifying it: every route / progress / packet computation is a call into phase1's
// own exports (loadPhase1Session, buildSpatialNavigationPacket, buildRouteSnapshot, searchPlaces,
// sampleRouteTripStates, validateRoute, computeRouteProgress, getLatestTripState).
// This module only adds the timeline (sim: media time -> trip time), the live sample
// history, and the `navigation.packet` envelope defined in contracts/PROTOCOL_v2.md.
//
// Used by phase1_relay.js (stdin/stdout JSON lines), make_demo_session.js and
// make_contract_samples.js.

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const SPATIAL_DIR = path.join(REPO_ROOT, 'spatial');
const SIBLING_DIR = path.resolve(REPO_ROOT, '..', 'hackgt13-phase1');
const SETUP_HINT =
  "The navigation engine is on branch 'main' under spatial/ (bring main into this checkout), " +
  'or set PHASE1_DIR / pass --phase1-dir <dir> (a spatial/ folder, or a legacy checkout with src/phase1/)';
const PROVIDERS = ['mock', 'google'];
const LIVE_HISTORY_MAX = 600; // samples kept in live mode (~10 min at 1 Hz)
const LIVE_CLOCK_RESET_MS = 60000; // a sample this much older than the newest one = client clock reset
const ROUTE_RETRY_MS = 10000; // live: min delay between failed lazy route builds
const SIM_CACHE_MAX = 512; // packets cached per sim session (keyed by sample count)
const SEARCH_MAX_PLACES = 8; // navigation.places lists at most this many

// The navigation engine in either layout. Returns { root, src, layout } or null.
//   spatial (main):  <root>/phase1/index.js      e.g. <repo>/spatial
//   legacy:          <root>/src/phase1/index.js  e.g. an old checkout of branch 'phase1'
// In both, <root>/.env holds GOOGLE_MAPS_API_KEY and <root>/scripts/load-env.js loads it. A repo
// root that contains spatial/ is accepted too (-> <repo>/spatial). `only` limits the layouts tried.
function phase1Layout(dir, only = null) {
  if (!dir) return null;
  const root = path.resolve(dir);
  const has = (...parts) => fs.existsSync(path.join(root, ...parts, 'index.js'));
  if ((!only || only === 'spatial') && has('phase1')) {
    return { root, src: path.join(root, 'phase1'), layout: 'spatial' };
  }
  if ((!only || only === 'legacy') && has('src', 'phase1')) {
    return { root, src: path.join(root, 'src', 'phase1'), layout: 'legacy' };
  }
  if ((!only || only === 'spatial') && has('spatial', 'phase1')) {
    const spatial = path.join(root, 'spatial');
    return { root: spatial, src: path.join(spatial, 'phase1'), layout: 'spatial' };
  }
  return null;
}

function isPhase1Dir(dir) {
  return phase1Layout(dir) !== null;
}

// Resolution order: explicit (--phase1-dir) > env PHASE1_DIR > <repo>/spatial (main) > <repo> with
// a legacy src/phase1 > ../hackgt13-phase1 next to the repo (legacy checkout). An explicit value
// or PHASE1_DIR that does not point at the engine is an error (no silent fallback).
function phase1Candidates(explicitDir) {
  if (explicitDir) {
    return [{ dir: path.resolve(explicitDir), from: '--phase1-dir' }];
  }
  if (process.env.PHASE1_DIR) {
    return [{ dir: path.resolve(process.env.PHASE1_DIR), from: 'env PHASE1_DIR' }];
  }
  return [
    { dir: SPATIAL_DIR, from: 'repo spatial/', only: 'spatial' },
    { dir: REPO_ROOT, from: 'repo root, legacy src/phase1', only: 'legacy' },
    { dir: SIBLING_DIR, from: 'sibling checkout' },
  ];
}

// Full resolution: { root, src, layout, from }.
function resolvePhase1(explicitDir) {
  const candidates = phase1Candidates(explicitDir);
  for (const candidate of candidates) {
    const found = phase1Layout(candidate.dir, candidate.only || null);
    if (found) {
      return { ...found, from: candidate.from };
    }
  }
  const tried = candidates.map((c) => `${c.dir} (${c.from})`).join('; ');
  throw new Error(
    `phase1 route engine not found: no phase1/index.js or src/phase1/index.js in ${tried}. ${SETUP_HINT}.`
  );
}

// The engine's root folder (<repo>/spatial, or a legacy checkout).
function resolvePhase1Dir(explicitDir) {
  return resolvePhase1(explicitDir).root;
}

function requireLayout(phase1Dir) {
  const found = phase1Layout(phase1Dir);
  if (!found) {
    throw new Error(`phase1 route engine not found in ${path.resolve(phase1Dir || '.')}. ${SETUP_HINT}.`);
  }
  return found;
}

// Load <root>/.env (GOOGLE_MAPS_API_KEY) with phase1's own scripts/load-env.js (the only phase1
// script used here: it has no dependencies). Values are never printed; existing environment
// variables win.
function loadPhase1Env(phase1Dir) {
  const { root } = requireLayout(phase1Dir);
  const envPath = path.join(root, '.env');
  let loaded = false;
  if (fs.existsSync(envPath)) {
    try {
      require(path.join(root, 'scripts', 'load-env.js')).loadEnv(envPath);
      loaded = true;
    } catch (error) {
      loaded = fallbackLoadEnv(envPath);
    }
  }
  return { envFile: loaded, googleKey: Boolean(process.env.GOOGLE_MAPS_API_KEY) };
}

function fallbackLoadEnv(envPath) {
  const content = fs.readFileSync(envPath, 'utf8');
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    const eq = line.indexOf('=');
    if (!line || line.startsWith('#') || eq === -1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (/^(['"]).*\1$/.test(value)) value = value.slice(1, -1);
    if (process.env[key] == null || process.env[key] === '') process.env[key] = value;
  }
  return true;
}

function loadPhase1(phase1Dir) {
  const { src } = requireLayout(phase1Dir);
  const index = require(src);
  const processor = require(path.join(src, 'processor'));
  const session = require(path.join(src, 'session'));
  const geo = require(path.join(src, 'geo'));
  const api = {
    loadPhase1Session: index.loadPhase1Session,
    buildSpatialNavigationPacket: index.buildSpatialNavigationPacket,
    buildRouteSnapshot: index.buildRouteSnapshot,
    sampleRouteTripStates: index.sampleRouteTripStates,
    validateRoute: processor.validateRoute,
    computeRouteProgress: processor.computeRouteProgress,
    computeRouteGeometry: processor.computeRouteGeometry,
    getLatestTripState: session.getLatestTripState,
    haversineMeters: geo.haversineMeters,
  };
  const missing = Object.entries(api)
    .filter(([, fn]) => typeof fn !== 'function')
    .map(([name]) => name);
  if (missing.length > 0) {
    throw new Error(`phase1 at ${phase1Dir} does not export ${missing.join(', ')} (phase1 API changed?)`);
  }
  // Optional (destination search, op "search"): an older engine without it still does everything else.
  api.searchPlaces = typeof index.searchPlaces === 'function' ? index.searchPlaces : null;
  return api;
}

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

// Normalise one trip-state sample (client.trip_state body or a trip_state.jsonl line) into the
// phase1 upstream contract. Throws on unusable samples; missing heading/speed become 0 like the
// android-collector does when the fix has no bearing/speed.
function normalizeTripState(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error('trip state sample must be a JSON object');
  }
  const location = raw.location;
  if (!isFiniteNumber(raw.timestampMs)) {
    throw new Error('trip state timestampMs must be a number (epoch ms)');
  }
  if (!location || !isFiniteNumber(location.lat) || !isFiniteNumber(location.lng) ||
      Math.abs(location.lat) > 90 || Math.abs(location.lng) > 180) {
    throw new Error('trip state location.lat / location.lng must be valid coordinates');
  }
  const sample = {
    timestampMs: Math.round(raw.timestampMs),
    location: { lat: location.lat, lng: location.lng },
    heading: isFiniteNumber(raw.heading) ? raw.heading : 0,
    speedMps: isFiniteNumber(raw.speedMps) ? Math.max(0, raw.speedMps) : 0,
  };
  if (isFiniteNumber(raw.accuracyMeters)) {
    sample.accuracyMeters = raw.accuracyMeters;
  }
  return sample;
}

function isLatLng(value) {
  return Boolean(value) && typeof value === 'object' && isFiniteNumber(value.lat) && isFiniteNumber(value.lng) &&
    Math.abs(value.lat) <= 90 && Math.abs(value.lng) <= 180;
}

// A place picked on the tablet (client.destination with a location) -> the phase1 destination shape
// {label, placeId, coordinate}. Throws on unusable input.
function normalizePlace(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error('destinationPlace must be an object {label, placeId, coordinate: {lat, lng}}');
  }
  if (typeof raw.label !== 'string' || !raw.label.trim()) {
    throw new Error('destinationPlace.label must be a non-empty string');
  }
  if (!isLatLng(raw.coordinate)) {
    throw new Error('destinationPlace.coordinate.lat / lng must be valid coordinates');
  }
  return {
    label: raw.label.trim(),
    placeId: typeof raw.placeId === 'string' && raw.placeId ? raw.placeId : null,
    coordinate: { lat: raw.coordinate.lat, lng: raw.coordinate.lng },
  };
}

// "33.7756,-84.3963" -> {lat, lng}; anything else -> null (treated as a place query).
function parseLatLng(value) {
  if (typeof value !== 'string') return null;
  const match = value.trim().match(/^(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)$/);
  if (!match) return null;
  const lat = Number(match[1]);
  const lng = Number(match[2]);
  if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  return { lat, lng };
}

function readJsonFile(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

// The fields the AR app's RouteState(time, action, audio, ui) and the Kotlin Driving Context use,
// derived 1:1 from the phase1 packet (PROTOCOL_v2.md, Navigation).
function toRouteState(packet, progress) {
  const maneuver = packet.activeManeuver;
  const semantics = packet.routeSemantics || {};
  const packetProgress = packet.progress || {};
  const audio = Array.isArray(packet.audioInstructions) ? packet.audioInstructions[0] : null;
  const spatial = Array.isArray(packet.spatialInstructions) ? packet.spatialInstructions[0] : null;
  return {
    action: maneuver && maneuver.type ? maneuver.type : 'GO_STRAIGHT',
    audio: audio && typeof audio.content === 'string' ? audio.content : '',
    ui: spatial && spatial.type ? spatial.type : 'DISTANCE_LABEL',
    // Same arithmetic phase1 uses for its instruction text (unrounded progress).
    distanceMeters: maneuver && isFiniteNumber(maneuver.distanceMeters)
      ? Math.max(0, Math.round(maneuver.distanceMeters - progress.distanceTraveledMeters))
      : null,
    offRoute: Boolean(packetProgress.offRoute),
    etaSeconds: isFiniteNumber(packetProgress.etaSeconds) ? packetProgress.etaSeconds : null,
    remainingDistanceMeters: isFiniteNumber(packetProgress.remainingDistanceMeters)
      ? packetProgress.remainingDistanceMeters
      : null,
    requiredLane: semantics.requiredLane != null ? String(semantics.requiredLane) : null,
    turnDirection: semantics.turnDirection || null,
    roadName: semantics.roadName != null ? String(semantics.roadName) : null,
  };
}

function round3(value) {
  return Math.round(value * 1000) / 1000;
}

// Largest n such that trip[0..n-1].timestampMs <= t (trip sorted ascending).
function countSamplesUpTo(trip, t) {
  let lo = 0;
  let hi = trip.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (trip[mid].timestampMs <= t) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

function describeRoute(route) {
  return {
    routeId: route.routeId,
    provider: route.provider || null,
    destination: route.destination && route.destination.label ? route.destination.label : null,
    totalDistanceMeters: isFiniteNumber(route.totalDistanceMeters) ? route.totalDistanceMeters : null,
    steps: Array.isArray(route.steps) ? route.steps.length : 0,
  };
}

function createNavRelay({ phase1Dir, clock = Date.now, log = () => {} } = {}) {
  const p1 = loadPhase1(phase1Dir);
  let sim = null; // { manifest, route, trip, t0, cache }
  let live = null; // { manifest, route, pending, samples, lastRouteError, lastRouteAttemptMs }

  function buildFromSamples(manifest, route, samples) {
    const packet = p1.buildSpatialNavigationPacket({ manifest, route, tripStates: samples });
    const latest = p1.getLatestTripState(samples);
    const progress = p1.computeRouteProgress(latest, route.geometry, route.totalDistanceMeters);
    return { packet, routeState: toRouteState(packet, progress) };
  }

  function envelope(built, ptsSeconds, tripTimestampMs) {
    return {
      type: 'navigation.packet',
      schemaVersion: 2,
      serverTimeMs: clock(),
      ptsSeconds,
      tripTimestampMs,
      routeState: built.routeState,
      packet: built.packet,
    };
  }

  function startSim(sessionDir) {
    if (typeof sessionDir !== 'string' || !sessionDir) {
      throw new Error('start_sim needs sessionDir (a phase1 session folder)');
    }
    const dir = path.resolve(sessionDir);
    if (!fs.existsSync(path.join(dir, 'session_manifest.json'))) {
      throw new Error(`not a phase1 session folder (no session_manifest.json): ${dir}`);
    }
    const session = p1.loadPhase1Session(dir);
    p1.validateRoute(session.route);
    const trip = [];
    let dropped = 0;
    for (const raw of session.tripStates) {
      try {
        trip.push(normalizeTripState(raw));
      } catch (error) {
        dropped += 1;
      }
    }
    if (trip.length === 0) {
      throw new Error(`no usable samples in ${session.manifest.tripStateFile || 'trip_state.jsonl'} (${dir})`);
    }
    trip.sort((a, b) => a.timestampMs - b.timestampMs);
    const manifest = session.manifest;
    // Optional manifest extension: where media time 0 sits on the trip clock (defaults to the
    // first sample, as PROTOCOL_v2 specifies).
    const t0 = isFiniteNumber(manifest.videoStartTimestampMs) ? manifest.videoStartTimestampMs : trip[0].timestampMs;
    sim = { manifest, route: session.route, trip, t0, cache: new Map() };
    live = null;
    const videoFile = manifest.videoFile || null;
    const info = {
      mode: 'sim',
      sessionDir: dir,
      sessionId: manifest.sessionId || null,
      videoFile,
      videoId: manifest.videoId || (videoFile ? path.parse(videoFile).name : null),
      samples: trip.length,
      droppedSamples: dropped,
      t0Ms: t0,
      durationSeconds: round3((trip[trip.length - 1].timestampMs - t0) / 1000),
      route: describeRoute(session.route),
    };
    log(`sim session ${info.sessionId}: ${trip.length} samples over ${info.durationSeconds} s, route ${info.route.routeId}`);
    return { info };
  }

  function atPts(ptsSeconds) {
    if (!sim) {
      throw new Error('no sim session: send start_sim first');
    }
    if (!isFiniteNumber(ptsSeconds)) {
      throw new Error('ptsSeconds must be a finite number');
    }
    const tripTimestampMs = Math.round(sim.t0 + 1000 * ptsSeconds);
    // Samples up to that time; before the first sample the first one stands in.
    const n = Math.max(1, countSamplesUpTo(sim.trip, tripTimestampMs));
    let built = sim.cache.get(n);
    if (!built) {
      built = buildFromSamples(sim.manifest, sim.route, sim.trip.slice(0, n));
      if (sim.cache.size >= SIM_CACHE_MAX) sim.cache.clear();
      sim.cache.set(n, built);
    }
    return { message: envelope(built, round3(ptsSeconds), tripTimestampMs) };
  }

  function providerOf(provider) {
    const providerName = provider || 'mock';
    if (!PROVIDERS.includes(providerName)) {
      throw new Error(`unknown provider "${providerName}" (expected ${PROVIDERS.join(' | ')})`);
    }
    return providerName;
  }

  // Before a call that reaches the provider (geocode, route, search); a ready route.json needs no key.
  function requireKey(providerName) {
    if (providerName === 'google' && !process.env.GOOGLE_MAPS_API_KEY) {
      throw new Error('provider "google" needs GOOGLE_MAPS_API_KEY (put it in the .env of the engine folder, e.g. spatial/.env, or in the environment)');
    }
  }

  // Destination search (client.place_search): phase1's provider finds the places, this only checks the
  // input and trims the answer to the navigation.places shape.
  async function searchPlaces({ query, near, provider } = {}) {
    const providerName = providerOf(provider);
    requireKey(providerName);
    if (typeof query !== 'string' || !query.trim() || query.length > 200) {
      throw new Error('search needs query (a non-empty string of at most 200 characters)');
    }
    if (near != null && !isLatLng(near)) {
      throw new Error('search near must be {lat, lng} (valid coordinates) or null');
    }
    if (!p1.searchPlaces) {
      throw new Error('this phase1 engine has no searchPlaces export (bring spatial/ up to date with main)');
    }
    const found = await p1.searchPlaces({
      query: query.trim(),
      near: near ? { lat: near.lat, lng: near.lng } : null,
      providerName,
      providerOptions: { apiKey: process.env.GOOGLE_MAPS_API_KEY },
    });
    const places = (Array.isArray(found) ? found : [])
      .filter((place) => place && isLatLng(place.location))
      .slice(0, SEARCH_MAX_PLACES)
      .map((place) => ({
        placeId: typeof place.placeId === 'string' && place.placeId ? place.placeId : null,
        label: String(place.label || place.address || query.trim()),
        address: typeof place.address === 'string' ? place.address : null,
        location: { lat: place.location.lat, lng: place.location.lng },
      }));
    return { places, provider: providerName };
  }

  async function buildRoute({ origin, originQuery, destinationQuery, destinationPlace, provider }) {
    const snapshot = await p1.buildRouteSnapshot({
      origin,
      originQuery,
      destinationQuery,
      destinationPlace: destinationPlace || undefined,
      providerName: provider,
      // apiKey for google; origin makes the mock provider place its destination near the car.
      providerOptions: { apiKey: process.env.GOOGLE_MAPS_API_KEY, origin },
    });
    // Same post-processing as phase1's scripts/process-captured-session.js.
    const route = snapshot.route;
    route.routeId = route.routeId || 'route_live';
    route.destination = snapshot.destination;
    route.origin = snapshot.origin.coordinate;
    route.originLabel = snapshot.origin.label;
    p1.validateRoute(route);
    return route;
  }

  async function startLive({ routeJson, route, origin, destination, destinationPlace, provider } = {}) {
    const providerName = providerOf(provider);
    let liveRoute = null;
    let pending = null;
    if (route && typeof route === 'object') {
      liveRoute = route;
    } else if (routeJson) {
      const file = path.resolve(routeJson);
      if (!fs.existsSync(file)) throw new Error(`route file not found: ${file}`);
      liveRoute = readJsonFile(file);
    } else if (destination || destinationPlace) {
      requireKey(providerName);
      // A picked place is routed to exactly (no geocode of its label); a query is resolved by the provider.
      const place = destinationPlace ? normalizePlace(destinationPlace) : null;
      if (origin) {
        const originCoord = parseLatLng(origin);
        liveRoute = await buildRoute({
          origin: originCoord || undefined,
          originQuery: originCoord ? undefined : origin,
          destinationQuery: destination,
          destinationPlace: place,
          provider: providerName,
        });
      } else {
        // No origin: the route is built from the first client.trip_state position.
        pending = { destination: place ? place.label : destination, place, provider: providerName };
      }
    } else {
      throw new Error('start_live needs routeJson, route, destination or destinationPlace');
    }
    if (liveRoute) p1.validateRoute(liveRoute);
    live = {
      manifest: { sessionId: `live_${clock()}`, source: 'device' },
      route: liveRoute,
      pending,
      samples: [],
      lastRouteError: null,
      lastRouteAttemptMs: 0,
    };
    sim = null;
    const info = {
      mode: 'live',
      routeReady: Boolean(liveRoute),
      route: liveRoute ? describeRoute(liveRoute) : null,
      pendingDestination: pending ? pending.destination : null,
      provider: liveRoute ? liveRoute.provider || providerName : providerName,
    };
    log(liveRoute
      ? `live session: route ${info.route.routeId} (${info.route.totalDistanceMeters} m)`
      : `live session: route to "${pending.destination}" is built from the first trip state`);
    return { info, route: liveRoute };
  }

  async function tripState(rawSample) {
    if (!live) {
      throw new Error('no live session: send start_live first');
    }
    const sample = normalizeTripState(rawSample);
    let routeBuilt = null;
    if (!live.route) {
      const now = clock();
      if (live.lastRouteError && now - live.lastRouteAttemptMs < ROUTE_RETRY_MS) {
        throw new Error(`route not available yet (last error: ${live.lastRouteError})`);
      }
      live.lastRouteAttemptMs = now;
      try {
        live.route = await buildRoute({
          origin: sample.location,
          destinationQuery: live.pending.destination,
          destinationPlace: live.pending.place,
          provider: live.pending.provider,
        });
      } catch (error) {
        live.lastRouteError = error.message;
        throw new Error(`could not build the route to "${live.pending.destination}": ${error.message}`);
      }
      routeBuilt = live.route;
      live.pending = null;
      log(`live route built from the first trip state: ${live.route.routeId}`);
    }
    const history = live.samples;
    if (history.length > 0 && sample.timestampMs < history[history.length - 1].timestampMs - LIVE_CLOCK_RESET_MS) {
      history.length = 0; // client clock went backwards (restart / new drive): start a new history
    }
    history.push(sample);
    if (history.length > LIVE_HISTORY_MAX) history.splice(0, history.length - LIVE_HISTORY_MAX);
    const built = buildFromSamples(live.manifest, live.route, history);
    return { message: envelope(built, null, built.packet.generatedAtMs), route: routeBuilt };
  }

  function status() {
    if (sim) return { mode: 'sim', sessionId: sim.manifest.sessionId || null, samples: sim.trip.length };
    if (live) return { mode: 'live', routeReady: Boolean(live.route), samples: live.samples.length };
    return { mode: null };
  }

  return { phase1: p1, startSim, atPts, startLive, tripState, searchPlaces, status };
}

module.exports = {
  REPO_ROOT,
  SPATIAL_DIR,
  SETUP_HINT,
  phase1Layout,
  isPhase1Dir,
  resolvePhase1,
  resolvePhase1Dir,
  loadPhase1Env,
  loadPhase1,
  normalizeTripState,
  normalizePlace,
  parseLatLng,
  toRouteState,
  createNavRelay,
};
