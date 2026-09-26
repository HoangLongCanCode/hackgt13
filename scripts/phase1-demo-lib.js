// Shared helpers for generating and replaying the local Phase 1 demo sessions.
const fs = require('fs');
const path = require('path');
const { loadPhase1Session, buildSpatialNavigationPacket } = require('../src/phase1');
const { buildRouteSnapshot, sampleRouteTripStates } = require('../src/phase1/capture');

const demoDir = path.join(__dirname, '..', 'demo_sessions', 'session_a_to_b');

function getDemoConfig() {
  return {
    originQuery: process.env.PHASE1_DEMO_ORIGIN || 'Amtrak station in Atlanta',
    destinationQuery: process.env.PHASE1_DEMO_DESTINATION || 'Georgia Tech',
    providerName: process.env.PHASE1_ROUTE_PROVIDER || (process.env.GOOGLE_MAPS_API_KEY ? 'google' : 'mock'),
    providerOptions: {
      apiKey: process.env.GOOGLE_MAPS_API_KEY,
    },
  };
}

async function ensureDemoSession() {
  fs.mkdirSync(demoDir, { recursive: true });

  const config = getDemoConfig();
  const snapshot = await buildRouteSnapshot(config);

  const manifest = {
    sessionId: 'session_a_to_b',
    capturedAtMs: 1730000000000,
    videoFile: 'video.mp4',
    routeFile: 'route.json',
    tripStateFile: 'trip_state.jsonl',
    source: config.providerName,
    notes: 'Phase 1 demo generated from the route provider and replay samples',
  };

  const route = snapshot.route;
  route.routeId = route.routeId || 'route_a_to_b';
  route.destination = snapshot.destination;
  route.origin = snapshot.origin.coordinate;
  route.originLabel = snapshot.origin.label;

  const tripStates = await sampleRouteTripStates(route, 20);

  fs.writeFileSync(path.join(demoDir, 'session_manifest.json'), JSON.stringify(manifest, null, 2));
  fs.writeFileSync(path.join(demoDir, 'route.json'), JSON.stringify(route, null, 2));
  fs.writeFileSync(
    path.join(demoDir, 'trip_state.jsonl'),
    tripStates.map((sample) => JSON.stringify(sample)).join('\n') + '\n'
  );

  return demoDir;
}

function buildFrames(session) {
  return session.tripStates.map((_, index) => {
    const packet = buildSpatialNavigationPacket({
      manifest: session.manifest,
      route: session.route,
      tripStates: session.tripStates.slice(0, index + 1),
    });

    return {
      frameIndex: index + 1,
      timestampMs: packet.generatedAtMs,
      progress: packet.progress,
      activeManeuver: packet.activeManeuver,
      spatialInstructions: packet.spatialInstructions,
      audioInstructions: packet.audioInstructions,
      routeSemantics: packet.routeSemantics,
    };
  });
}

async function buildDemoTimeline() {
  const sessionDir = await ensureDemoSession();
  const session = loadPhase1Session(sessionDir);
  const frames = buildFrames(session);

  return {
    session,
    frames,
  };
}

async function buildDemoTimelineFromSessionDir(sessionDir) {
  const session = loadPhase1Session(sessionDir);
  const frames = buildFrames(session);

  return {
    session,
    frames,
  };
}

module.exports = {
  demoDir,
  getDemoConfig,
  ensureDemoSession,
  buildFrames,
  buildDemoTimeline,
  buildDemoTimelineFromSessionDir,
};
