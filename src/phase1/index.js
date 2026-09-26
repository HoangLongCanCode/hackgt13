const { loadPhase1Session } = require('./session');
const { buildSpatialNavigationPacket } = require('./processor');
const { buildRouteSnapshot, sampleRouteTripStates } = require('./capture');
const { createRouteProvider } = require('./providers');

function buildPacketFromSessionDir(sessionDir) {
  const session = loadPhase1Session(sessionDir);
  return buildSpatialNavigationPacket(session);
}

module.exports = {
  loadPhase1Session,
  buildSpatialNavigationPacket,
  buildPacketFromSessionDir,
  buildRouteSnapshot,
  sampleRouteTripStates,
  createRouteProvider,
};
