// Merge a pulled Android GPS session with a route snapshot and emit the final Phase 1 packet.
require('./load-env').loadEnv();
const fs = require('fs');
const path = require('path');
const { loadPhase1Session, buildSpatialNavigationPacket, buildRouteSnapshot } = require('../src/phase1');

const DEFAULT_CAPTURE_ROOT = path.join(process.cwd(), 'captured', 'android');
const DEFAULT_ORIGIN_QUERY = 'Amtrak station in Atlanta';
const DEFAULT_DESTINATION_QUERY = 'Georgia Tech';

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function findSessionDirs(rootDir) {
  if (!fs.existsSync(rootDir)) {
    return [];
  }

  const candidateRoots = [rootDir, path.join(rootDir, 'phase1')];
  const sessionDirs = [];

  for (const candidateRoot of candidateRoots) {
    if (!fs.existsSync(candidateRoot)) {
      continue;
    }

    const dirs = fs
      .readdirSync(candidateRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && entry.name.startsWith('session_'))
      .map((entry) => path.join(candidateRoot, entry.name));

    sessionDirs.push(...dirs);
  }

  return sessionDirs.sort();
}

function findLatestSessionDir(rootDir) {
  const dirs = findSessionDirs(rootDir);
  if (dirs.length === 0) {
    throw new Error(`No session folders found under ${rootDir}`);
  }

  return dirs[dirs.length - 1];
}

function resolveConfig() {
  return {
    originQuery: process.env.PHASE1_DEMO_ORIGIN || DEFAULT_ORIGIN_QUERY,
    destinationQuery: process.env.PHASE1_DEMO_DESTINATION || DEFAULT_DESTINATION_QUERY,
    providerName: process.env.PHASE1_ROUTE_PROVIDER || (process.env.GOOGLE_MAPS_API_KEY ? 'google' : 'mock'),
    providerOptions: {
      apiKey: process.env.GOOGLE_MAPS_API_KEY,
    },
  };
}

async function processSession(sessionDir) {
  const manifestPath = path.join(sessionDir, 'session_manifest.json');
  if (!fs.existsSync(manifestPath)) {
    throw new Error(`Missing session manifest: ${manifestPath}`);
  }

  const manifest = readJson(manifestPath);
  const config = resolveConfig();
  const snapshot = await buildRouteSnapshot(config);

  const route = snapshot.route;
  route.routeId = route.routeId || `route_${manifest.sessionId}`;
  route.destination = snapshot.destination;
  route.origin = snapshot.origin.coordinate;
  route.originLabel = snapshot.origin.label;

  const routePath = path.join(sessionDir, 'route.json');
  fs.writeFileSync(routePath, JSON.stringify(route, null, 2));

  const session = loadPhase1Session(sessionDir);
  const packet = buildSpatialNavigationPacket(session);

  const outputPath = path.join(sessionDir, 'phase1_packet.json');
  fs.writeFileSync(outputPath, JSON.stringify(packet, null, 2));

  return {
    sessionDir,
    routePath,
    outputPath,
    packet,
  };
}

async function main() {
  const captureRoot = path.resolve(process.argv[2] || DEFAULT_CAPTURE_ROOT);
  const sessionDir = findLatestSessionDir(captureRoot);
  const result = await processSession(sessionDir);

  process.stdout.write(JSON.stringify({
    sessionDir: result.sessionDir,
    routePath: result.routePath,
    outputPath: result.outputPath,
    packetType: result.packet.packetType,
    tripId: result.packet.tripId,
    routeId: result.packet.routeId,
    generatedAtMs: result.packet.generatedAtMs,
    remainingDistanceMeters: result.packet.progress.remainingDistanceMeters,
    activeManeuver: result.packet.activeManeuver ? result.packet.activeManeuver.type : null,
    spatialInstructionCount: result.packet.spatialInstructions.length,
    audioInstructionCount: result.packet.audioInstructions.length,
  }, null, 2) + '\n');
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
