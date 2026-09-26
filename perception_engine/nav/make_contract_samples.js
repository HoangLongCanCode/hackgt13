#!/usr/bin/env node
'use strict';
// Regenerate the navigation golden samples in contracts/samples/v2/ from the real relay code
// (relay_core + phase1) and the committed demo sessions. Deterministic: fixed server clock.
//
//   node nav/make_contract_samples.js [--phase1-dir <dir>]
//
// Writes: client.trip_state.json, navigation.packet.sim_city.json,
//         navigation.packet.sim_highway.json, navigation.packet.live.json

const fs = require('fs');
const path = require('path');
const core = require('./relay_core');

const SERVER_TIME_MS = 1790000000000; // same epoch the PROTOCOL_v2.md examples use
const LIVE_TIMESTAMP_MS = 1790000000123;
const SESSIONS = path.join(__dirname, 'demo_sessions');
const OUT_DIR = path.resolve(__dirname, '..', 'contracts', 'samples', 'v2');   // perception_engine/contracts/samples/v2

function write(name, obj) {
  fs.writeFileSync(path.join(OUT_DIR, name), `${JSON.stringify(obj, null, 2)}\n`);
  process.stderr.write(`wrote contracts/samples/v2/${name}\n`);
}

async function main() {
  const argv = process.argv.slice(2);
  const flag = argv.indexOf('--phase1-dir');
  const phase1Dir = core.resolvePhase1Dir(flag >= 0 ? argv[flag + 1] : null);
  core.loadPhase1Env(phase1Dir);
  const relay = core.createNavRelay({ phase1Dir, clock: () => SERVER_TIME_MS });
  fs.mkdirSync(OUT_DIR, { recursive: true });

  relay.startSim(path.join(SESSIONS, 'b1ff4656-0435391e'));
  write('navigation.packet.sim_city.json', relay.atPts(12.3).message);

  relay.startSim(path.join(SESSIONS, 'b1f4491b-cf446195'));
  write('navigation.packet.sim_highway.json', relay.atPts(20.0).message);

  // live: the city demo route, one GPS fix a third of the way along it.
  const cityDir = path.join(SESSIONS, 'b1ff4656-0435391e');
  const lines = fs.readFileSync(path.join(cityDir, 'trip_state.jsonl'), 'utf8').trim().split(/\r?\n/);
  const fix = JSON.parse(lines[Math.floor(lines.length / 3)]);
  const tripState = {
    type: 'client.trip_state',
    timestampMs: LIVE_TIMESTAMP_MS,
    location: fix.location,
    heading: fix.heading,
    speedMps: fix.speedMps,
    accuracyMeters: 4.1,
  };
  write('client.trip_state.json', tripState);
  await relay.startLive({ routeJson: path.join(cityDir, 'route.json') });
  write('navigation.packet.live.json', (await relay.tripState(tripState)).message);
}

main().catch((error) => {
  process.stderr.write(`make_contract_samples: ${error.stack || error.message}\n`);
  process.exitCode = 1;
});
