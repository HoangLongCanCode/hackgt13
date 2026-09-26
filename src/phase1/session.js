const fs = require('fs');
const path = require('path');

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function readJsonLines(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  return content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      try {
        return JSON.parse(line);
      } catch (error) {
        const message = `Invalid JSONL line ${index + 1} in ${filePath}`;
        throw new Error(message);
      }
    });
}

function loadPhase1Session(sessionDir) {
  const manifestPath = path.join(sessionDir, 'session_manifest.json');
  const manifest = readJson(manifestPath);

  const routePath = path.join(sessionDir, manifest.routeFile || 'route.json');
  const tripStatePath = path.join(sessionDir, manifest.tripStateFile || 'trip_state.jsonl');

  const route = readJson(routePath);
  const tripStates = readJsonLines(tripStatePath);

  return {
    sessionDir,
    manifest,
    route,
    tripStates,
  };
}

function getLatestTripState(tripStates) {
  if (!Array.isArray(tripStates) || tripStates.length === 0) {
    throw new Error('tripStates must contain at least one sample');
  }

  return tripStates.reduce((latest, sample) => {
    if (!latest || sample.timestampMs > latest.timestampMs) {
      return sample;
    }
    return latest;
  }, null);
}

module.exports = {
  readJson,
  readJsonLines,
  loadPhase1Session,
  getLatestTripState,
};
