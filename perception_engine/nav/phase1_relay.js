#!/usr/bin/env node
'use strict';
// phase1 navigation relay: a long-running child process of the perception server
// (perception/realtime/nav_relay.py). It runs the phase1 route engine and answers
// JSON-lines requests on stdin with JSON-lines replies on stdout; logs go to stderr.
//
//   node nav/phase1_relay.js [--phase1-dir <dir>]
//
// phase1 dir: --phase1-dir > env PHASE1_DIR > <repo>/spatial (main) > <repo> with a legacy src/phase1 >
// ../hackgt13-phase1. Both layouts work: <dir>/phase1/index.js (spatial/) or <dir>/src/phase1/index.js.
// GOOGLE_MAPS_API_KEY is read from <dir>/.env (e.g. spatial/.env) if present (never printed).
//
// Requests (one JSON object per line):
//   {"id":1,"op":"start_sim","sessionDir":"<phase1 session folder>"}
//   {"id":2,"op":"start_live","routeJson":"<route.json>"}                      (or "route": {...})
//   {"id":2,"op":"start_live","destination":"<query>","origin":"<query|lat,lng>","provider":"mock|google"}
//   {"id":2,"op":"start_live","destinationPlace":{"label":..,"placeId":..,"coordinate":{"lat":..,"lng":..}},"provider":..}
//   {"id":3,"op":"at_pts","ptsSeconds":12.3}
//   {"id":4,"op":"trip_state","sample":{"timestampMs":..,"location":{"lat":..,"lng":..},"heading":..,"speedMps":..}}
//   {"id":5,"op":"search","query":"coffee","near":{"lat":..,"lng":..}|null,"provider":"mock|google"}
//   {"id":6,"op":"status"} | {"id":7,"op":"ping"} | {"id":8,"op":"close"}
// Replies: {"id":N,"ok":true,"message":{navigation.packet}} for at_pts / trip_state,
//          {"id":N,"ok":true,"info":{...}[,"route":{...}]} for start_* / status / ping,
//          {"id":N,"ok":true,"places":[{placeId,label,address,location}],"provider":".."} for search,
//          {"id":N,"ok":false,"error":"..."} on failure.
// On startup the relay prints {"id":null,"ok":true,"event":"ready","info":{...}} (or
// {"id":null,"ok":false,"event":"fatal","error":"..."} and exits with code 2).
// It exits when stdin closes, so it never outlives the server.

const util = require('util');
const readline = require('readline');

// stdout carries the protocol only: anything that console.log()s (phase1 included) goes to stderr.
const toStderr = (...args) => process.stderr.write(`${util.format(...args)}\n`);
console.log = toStderr;
console.info = toStderr;
console.debug = toStderr;

const core = require('./relay_core');

function log(message) {
  process.stderr.write(`[phase1_relay] ${message}\n`);
}

function parseArgs(argv) {
  const args = { phase1Dir: null };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--phase1-dir') {
      args.phase1Dir = argv[index + 1];
      index += 1;
    } else if (arg.startsWith('--phase1-dir=')) {
      args.phase1Dir = arg.slice('--phase1-dir='.length);
    } else if (arg === '-h' || arg === '--help') {
      args.help = true;
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  return args;
}

function send(obj, callback) {
  process.stdout.write(`${JSON.stringify(obj)}\n`, callback);
}

process.stdout.on('error', () => process.exit(0)); // parent went away

function main() {
  let args;
  let relay;
  let startupInfo;
  try {
    args = parseArgs(process.argv.slice(2));
    if (args.help) {
      process.stderr.write('usage: node nav/phase1_relay.js [--phase1-dir <dir>]  (JSON lines on stdin/stdout)\n');
      return;
    }
    const found = core.resolvePhase1(args.phase1Dir);
    const phase1Dir = found.root;
    const env = core.loadPhase1Env(phase1Dir);
    relay = core.createNavRelay({ phase1Dir, log });
    startupInfo = {
      phase1Dir,
      phase1Layout: found.layout,
      phase1From: found.from,
      envFile: env.envFile,
      googleKeyConfigured: env.googleKey,
      node: process.version,
      pid: process.pid,
    };
    log(`ready: phase1 at ${phase1Dir} (${found.layout} layout, ${found.from}); .env ${env.envFile ? 'loaded' : 'absent'}; ` +
        `GOOGLE_MAPS_API_KEY ${env.googleKey ? 'configured' : 'not configured'}`);
  } catch (error) {
    log(`fatal: ${error.message}`);
    send({ id: null, ok: false, event: 'fatal', error: error.message }, () => process.exit(2));
    return;
  }
  send({ id: null, ok: true, event: 'ready', info: startupInfo });

  async function handle(line) {
    let request;
    try {
      request = JSON.parse(line);
    } catch (error) {
      send({ id: null, ok: false, error: `bad JSON request: ${error.message}` });
      return;
    }
    const id = request && request.id !== undefined ? request.id : null;
    const op = request && request.op;
    try {
      switch (op) {
        case 'start_sim': {
          const result = relay.startSim(request.sessionDir);
          send({ id, ok: true, info: result.info });
          break;
        }
        case 'start_live': {
          const result = await relay.startLive({
            routeJson: request.routeJson,
            route: request.route,
            origin: request.origin,
            destination: request.destination,
            destinationPlace: request.destinationPlace,
            provider: request.provider,
          });
          send({ id, ok: true, info: result.info, route: result.route || undefined });
          break;
        }
        case 'search': {
          const result = await relay.searchPlaces({
            query: request.query,
            near: request.near,
            provider: request.provider,
          });
          send({ id, ok: true, places: result.places, provider: result.provider });
          break;
        }
        case 'at_pts': {
          const result = relay.atPts(request.ptsSeconds);
          send({ id, ok: true, message: result.message });
          break;
        }
        case 'trip_state': {
          const result = await relay.tripState(request.sample);
          send({ id, ok: true, message: result.message, route: result.route || undefined });
          break;
        }
        case 'status':
          send({ id, ok: true, info: { ...startupInfo, ...relay.status() } });
          break;
        case 'ping':
          send({ id, ok: true, info: startupInfo });
          break;
        case 'close':
          log('close requested');
          send({ id, ok: true, message: 'bye' }, () => process.exit(0));
          break;
        case '_crash':
          // Test hook (tests/test_nav_relay.py): die without replying, like a crashed child.
          process.exit(3);
          break;
        default:
          send({ id, ok: false, error: `unknown op: ${JSON.stringify(op)}` });
      }
    } catch (error) {
      send({ id, ok: false, error: error.message || String(error) });
    }
  }

  // Requests are handled strictly in order (start_live / trip_state may await the route provider).
  let chain = Promise.resolve();
  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  rl.on('line', (raw) => {
    const line = raw.trim();
    if (!line) return;
    chain = chain.then(() => handle(line));
  });
  rl.on('close', () => {
    chain.then(() => process.exit(0));
  });
}

main();
