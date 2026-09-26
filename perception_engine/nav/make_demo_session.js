#!/usr/bin/env node
'use strict';
// Build phase1 session folders (session_manifest.json, route.json, trip_state.jsonl) for the
// BDD100K sim clips, so `sim` mode has a navigation timeline next to the video.
//
// SYNTHETIC DEMO DATA: the route comes from the phase1 route provider (mock by default, Google
// when a key is configured) around Georgia Tech, and the trip is a drive along that route timed to
// the clip length. It does NOT match what the BDD clip shows. Real sessions come from phase1's
// android-collector (GPS -> trip_state.jsonl) plus a route.json (see nav/README.md).
//
//   node nav/make_demo_session.js                    # all presets -> nav/demo_sessions/<clip>/
//   node nav/make_demo_session.js --clip b1ff4656-0435391e
//   node nav/make_demo_session.js --clip my-clip --duration 38.5 --destination "Piedmont Park" \
//        [--origin "33.7756,-84.3963"|<query>] [--provider mock|google] [--speed 8] [--hz 2] \
//        [--video-file my-clip.mp4] [--out <dir>] [--start-ms 1730000000000] [--no-rescale] [--phase1-dir <dir>]

const fs = require('fs');
const path = require('path');
const core = require('./relay_core');

// Mock-provider destinations below were picked so the mock route length fits the clip at a
// plausible speed (mock destinations are a hash of the query; see phase1 providers/mock.js).
const DEFAULT_ORIGIN = '33.7756,-84.3963'; // phase1 mock default origin (Georgia Tech)
const PRESETS = {
  'b1ff4656-0435391e': {
    scene: 'city',
    durationSeconds: 40.13,
    destination: 'Piedmont Park', // mock: ~307 m -> ~7.7 m/s
    maxSpeedMps: 9,
  },
  'b1f4491b-cf446195': {
    scene: 'highway',
    durationSeconds: 40.08,
    destination: 'Amtrak station in Atlanta', // mock: ~985 m -> ~24.6 m/s
    maxSpeedMps: 27,
    highwayName: 'I-75/85 (synthetic)',
  },
  'b23adb0d-8a7aaced': {
    scene: 'night',
    durationSeconds: 40.26,
    destination: 'Colony Square', // mock: ~404 m -> ~10 m/s
    maxSpeedMps: 11,
  },
};
const DEFAULT_START_MS = 1730000000000; // fixed so regenerated sessions are byte-identical
const DEFAULT_HZ = 2;

function parseArgs(argv) {
  const args = { rescale: true };
  const valueFlags = {
    '--clip': 'clip', '--duration': 'duration', '--destination': 'destination', '--origin': 'origin',
    '--provider': 'provider', '--speed': 'speed', '--hz': 'hz', '--video-file': 'videoFile',
    '--out': 'out', '--start-ms': 'startMs', '--phase1-dir': 'phase1Dir', '--scene': 'scene',
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (valueFlags[arg]) {
      if (index + 1 >= argv.length) throw new Error(`${arg} needs a value`);
      args[valueFlags[arg]] = argv[index + 1];
      index += 1;
    } else if (arg === '--no-rescale') {
      args.rescale = false;
    } else if (arg === '--all') {
      args.all = true;
    } else if (arg === '-h' || arg === '--help') {
      args.help = true;
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  return args;
}

function toNumber(value, name) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`${name} must be a number, got ${value}`);
  return number;
}

function bearingDegrees(a, b) {
  const toRad = (d) => (d * Math.PI) / 180;
  const y = Math.sin(toRad(b.lng - a.lng)) * Math.cos(toRad(b.lat));
  const x = Math.cos(toRad(a.lat)) * Math.sin(toRad(b.lat)) -
    Math.sin(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.cos(toRad(b.lng - a.lng));
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

function lerpPoint(a, b, t) {
  return { lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t };
}

// Points of `geometry` from its start up to `meters` along it, resampled to `count` points evenly
// spaced by distance (so phase1's index-space sampling becomes constant-speed sampling).
function resampleByDistance(geometry, meters, count, haversine) {
  const cumulative = [0];
  for (let i = 1; i < geometry.length; i += 1) {
    cumulative.push(cumulative[i - 1] + haversine(geometry[i - 1], geometry[i]));
  }
  const total = Math.min(meters, cumulative[cumulative.length - 1]);
  const out = [];
  let segment = 1;
  for (let k = 0; k < count; k += 1) {
    const target = (total * k) / (count - 1);
    while (segment < geometry.length - 1 && cumulative[segment] < target) segment += 1;
    const segLength = cumulative[segment] - cumulative[segment - 1];
    const t = segLength > 0 ? (target - cumulative[segment - 1]) / segLength : 0;
    out.push(lerpPoint(geometry[segment - 1], geometry[segment], Math.max(0, Math.min(1, t))));
  }
  return { points: out, meters: total };
}

function round(value, digits) {
  const f = 10 ** digits;
  return Math.round(value * f) / f;
}

async function buildSession(p1, clip, options) {
  const preset = PRESETS[clip] || {};
  const durationSeconds = options.duration != null ? toNumber(options.duration, '--duration') : preset.durationSeconds;
  if (!durationSeconds || durationSeconds <= 0) {
    throw new Error(`clip ${clip} has no preset: pass --duration <seconds> (and --destination)`);
  }
  const hz = options.hz != null ? toNumber(options.hz, '--hz') : DEFAULT_HZ;
  const startMs = options.startMs != null ? Math.round(toNumber(options.startMs, '--start-ms')) : DEFAULT_START_MS;
  const provider = options.provider || process.env.PHASE1_ROUTE_PROVIDER ||
    (process.env.GOOGLE_MAPS_API_KEY ? 'google' : 'mock');
  const destination = options.destination || preset.destination || 'Piedmont Park';
  const originArg = options.origin || DEFAULT_ORIGIN;
  const originCoord = core.parseLatLng(originArg);
  const maxSpeedMps = options.speed != null ? toNumber(options.speed, '--speed') : preset.maxSpeedMps || 12;
  const scene = options.scene || preset.scene || 'custom';

  // 1. Route from the phase1 provider (same call + post-processing as phase1's scripts).
  const snapshot = await p1.buildRouteSnapshot({
    origin: originCoord || undefined,
    originQuery: originCoord ? undefined : originArg,
    destinationQuery: destination,
    providerName: provider,
    providerOptions: {
      apiKey: process.env.GOOGLE_MAPS_API_KEY,
      origin: originCoord || undefined,
      highwayName: preset.highwayName,
    },
  });
  const route = snapshot.route;
  route.routeId = `demo_${clip}_${route.provider || provider}`;
  route.destination = snapshot.destination;
  route.origin = snapshot.origin.coordinate;
  route.originLabel = snapshot.origin.label;
  route.createdAtMs = startMs;
  p1.validateRoute(route);

  // 2. The mock provider's steps are a fixed 120 m + 180 m template whatever its geometry; scale the
  //    step distances to the geometry length so maneuvers happen where the car actually is.
  const geometryMeters = p1.computeRouteGeometry(route.geometry).totalMeters;
  let rescaled = null;
  const stepsMeters = route.steps.reduce((sum, step) => sum + (step.distanceMeters || 0), 0);
  if (options.rescale && stepsMeters > 0 && Math.abs(geometryMeters - stepsMeters) / geometryMeters > 0.1) {
    const scale = geometryMeters / stepsMeters;
    for (const step of route.steps) {
      step.distanceMeters = Math.round((step.distanceMeters || 0) * scale);
      step.durationSeconds = Math.round((step.durationSeconds || 0) * scale);
    }
    route.totalDistanceMeters = route.steps.reduce((sum, step) => sum + step.distanceMeters, 0);
    route.totalDurationSeconds = route.steps.reduce((sum, step) => sum + step.durationSeconds, 0);
    rescaled = { fromMeters: stepsMeters, toMeters: route.totalDistanceMeters };
  }

  // 3. Trip states: phase1's sampleRouteTripStates along the (distance-resampled) route, re-timed
  //    to span the clip; speed and heading recomputed from the re-timed positions.
  const coverMeters = Math.min(geometryMeters, maxSpeedMps * durationSeconds);
  const resampled = resampleByDistance(route.geometry, coverMeters, 400, p1.haversineMeters);
  const count = Math.max(2, Math.round(durationSeconds * hz) + 1);
  const raw = p1.sampleRouteTripStates({ geometry: resampled.points }, count);
  const dtMs = (durationSeconds * 1000) / (count - 1);
  const positions = raw.map((sample) => sample.location);
  const tripStates = raw.map((sample, i) => {
    const a = positions[Math.max(0, i === 0 ? 0 : i - 1)];
    const b = positions[i === 0 ? 1 : i];
    const next = positions[Math.min(positions.length - 1, i + 1)];
    const prev = positions[Math.max(0, i - 1)];
    const speedMps = p1.haversineMeters(a, b) / (dtMs / 1000);
    const heading = i < positions.length - 1 ? bearingDegrees(positions[i], next) : bearingDegrees(prev, positions[i]);
    return {
      timestampMs: startMs + Math.round(i * dtMs),
      location: { lat: round(sample.location.lat, 7), lng: round(sample.location.lng, 7) },
      heading: round(heading, 1),
      speedMps: round(speedMps, 2),
      accuracyMeters: sample.accuracyMeters != null ? sample.accuracyMeters : 3,
    };
  });

  const videoFile = options.videoFile || `${clip}.mov`;
  const manifest = {
    sessionId: `demo_${clip}`,
    capturedAtMs: startMs,
    videoFile,
    videoId: path.parse(videoFile).name,
    routeFile: 'route.json',
    tripStateFile: 'trip_state.jsonl',
    source: 'simulated',
    notes:
      'SYNTHETIC DEMO SESSION: route from the phase1 ' + (route.provider || provider) + ' provider, trip samples ' +
      'interpolated along it and timed to the clip. It does not match the video content (demo only). ' +
      'Real sessions come from the phase1 android-collector + route.json.',
    demo: {
      generator: 'perception_engine/nav/make_demo_session.js',
      clip,
      scene,
      provider: route.provider || provider,
      origin: originArg,
      destinationQuery: destination,
      clipDurationSeconds: durationSeconds,
      sampleHz: hz,
      samples: tripStates.length,
      routeMeters: Math.round(geometryMeters),
      coveredMeters: Math.round(resampled.meters),
      meanSpeedMps: round(resampled.meters / durationSeconds, 2),
      stepsRescaled: rescaled,
    },
  };
  return { manifest, route, tripStates };
}

function writeSession(outDir, session) {
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, 'session_manifest.json'), `${JSON.stringify(session.manifest, null, 2)}\n`);
  fs.writeFileSync(path.join(outDir, 'route.json'), `${JSON.stringify(session.route, null, 2)}\n`);
  fs.writeFileSync(
    path.join(outDir, 'trip_state.jsonl'),
    `${session.tripStates.map((sample) => JSON.stringify(sample)).join('\n')}\n`
  );
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    const header = fs.readFileSync(__filename, 'utf8').split(/\r?\n/).slice(2);
    const end = header.findIndex((line) => !line.startsWith('//'));
    process.stdout.write(`${header.slice(0, end).map((line) => line.replace(/^\/\/ ?/, '')).join('\n')}\n`);
    return;
  }
  const phase1Dir = core.resolvePhase1Dir(args.phase1Dir);
  const env = core.loadPhase1Env(phase1Dir);
  const p1 = core.loadPhase1(phase1Dir);
  process.stderr.write(`phase1: ${phase1Dir} (GOOGLE_MAPS_API_KEY ${env.googleKey ? 'configured' : 'not configured'})\n`);

  const clips = args.clip && !args.all ? [args.clip] : Object.keys(PRESETS);
  if (args.out && clips.length > 1) throw new Error('--out needs a single --clip');
  for (const clip of clips) {
    const session = await buildSession(p1, clip, args);
    const outDir = path.resolve(args.out || path.join(__dirname, 'demo_sessions', clip));
    writeSession(outDir, session);
    const demo = session.manifest.demo;
    process.stderr.write(
      `${clip}: ${demo.provider} route ${demo.routeMeters} m, ${demo.samples} samples over ${demo.clipDurationSeconds} s ` +
        `(${demo.meanSpeedMps} m/s)${demo.stepsRescaled ? `, steps rescaled ${demo.stepsRescaled.fromMeters}->${demo.stepsRescaled.toMeters} m` : ''} -> ${path.relative(process.cwd(), outDir) || outDir}\n`
    );
  }
}

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`make_demo_session: ${error.message}\n`);
    process.exitCode = 1;
  });
}

module.exports = { PRESETS, buildSession, writeSession };
