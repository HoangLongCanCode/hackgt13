// Offline test of the Google provider (no key, no network): global fetch is replaced by canned responses.
//   node spatial/scripts/test-google-provider.js
const assert = require('assert');
const { encodePolyline } = require('../phase1/polylines');

const calls = [];
let routesStatus = 200;

const origin = { lat: 33.7756, lng: -84.3963 };
const dest = { lat: 33.7853, lng: -84.3733 };
const overview = encodePolyline([origin, { lat: 33.7800, lng: -84.3900 }, dest]);

global.fetch = async (url, init = {}) => {
  calls.push({ url: String(url), init });
  const u = new URL(String(url));
  const reply = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body) });
  if (u.pathname.endsWith('/geocode/json')) {
    return reply(200, { status: 'OK', results: [{ formatted_address: 'Piedmont Park, Atlanta, GA, USA', place_id: 'pp1', geometry: { location: dest } }] });
  }
  if (u.hostname === 'routes.googleapis.com') {
    if (routesStatus !== 200) return reply(routesStatus, { error: { code: routesStatus, message: 'Routes API has not been used in project 123 before or it is disabled.', status: 'PERMISSION_DENIED' } });
    return reply(200, { routes: [{
      description: 'Piedmont Ave NE',
      distanceMeters: 2310,
      duration: '412s',
      polyline: { encodedPolyline: overview },
      legs: [{ distanceMeters: 2310, duration: '412s', steps: [
        { distanceMeters: 400, staticDuration: '60s', navigationInstruction: { maneuver: 'DEPART', instructions: 'Head north on Techwood Dr NW toward North Ave NW' } },
        { distanceMeters: 900, staticDuration: '150s', navigationInstruction: { maneuver: 'TURN_RIGHT', instructions: 'Turn right onto 10th St NE' } },
        { distanceMeters: 700, staticDuration: '120s', navigationInstruction: { maneuver: 'RAMP_LEFT', instructions: 'Take exit 250 toward Piedmont Ave' } },
        { distanceMeters: 310, staticDuration: '82s', navigationInstruction: { maneuver: 'TURN_SLIGHT_LEFT', instructions: 'Slight left onto Piedmont Ave NE' } },
      ] }],
    }] });
  }
  if (u.pathname.endsWith('/directions/json')) {
    return reply(200, { status: 'OK', routes: [{ summary: 'Legacy Rd', overview_polyline: { points: overview }, legs: [{
      distance: { value: 2000 }, duration: { value: 400 },
      steps: [{ html_instructions: 'Turn <b>left</b> onto <b>Juniper St</b>', maneuver: 'turn-left', distance: { value: 2000 }, duration: { value: 400 } }],
    }] }] });
  }
  return reply(404, {});
};

(async () => {
  const google = require('../phase1/providers/google');
  const config = { apiKey: 'TEST_KEY_123' };

  // Geocoding: the key goes in the query string of this one call only.
  const d = await google.resolveDestination('Piedmont Park', config);
  assert.strictEqual(d.label, 'Piedmont Park, Atlanta, GA, USA');
  assert.deepStrictEqual(d.coordinate, dest);

  // Routes API: key in a header (never in the URL), field mask set, steps normalized.
  const r = await google.fetchRoute(origin, d, config);
  const rc = calls.find((c) => c.url.includes('routes.googleapis.com'));
  assert.ok(rc, 'Routes API called');
  assert.ok(!rc.url.includes('TEST_KEY_123'), 'no key in the Routes URL');
  assert.strictEqual(rc.init.headers['X-Goog-Api-Key'], 'TEST_KEY_123');
  assert.ok(rc.init.headers['X-Goog-FieldMask'].includes('routes.legs.steps.navigationInstruction'));
  assert.strictEqual(r.routeApiVersion, 'routes-v2');
  assert.strictEqual(r.totalDistanceMeters, 2310);
  assert.strictEqual(r.totalDurationSeconds, 412);
  assert.strictEqual(r.polyline, overview);
  assert.strictEqual(r.geometry.length, 3);
  assert.deepStrictEqual(r.steps.map((s) => s.maneuver), ['straight', 'right', 'keep_left', 'left', 'arrive']);
  assert.deepStrictEqual(r.steps.map((s) => s.roadName), ['Techwood Dr NW', '10th St NE', undefined, 'Piedmont Ave NE', 'Piedmont Park, Atlanta, GA, USA']);
  assert.strictEqual(r.steps[2].exitNumber, '250');

  // Routes API not enabled for the project (403): falls back to the legacy Directions API.
  routesStatus = 403;
  const legacy = await google.fetchRoute(origin, d, config);
  assert.strictEqual(legacy.routeApiVersion, 'directions-v1');
  assert.strictEqual(legacy.steps[0].maneuver, 'left');
  assert.strictEqual(legacy.steps[0].roadName, 'Juniper St');

  // Errors never carry the key.
  const http = require('../phase1/providers/http');
  assert.strictEqual(http.redact('https://maps.googleapis.com/x?address=a&key=TEST_KEY_123'), 'https://maps.googleapis.com/x?address=a&key=REDACTED');

  console.log('google provider: 4/4 checks passed (geocode, Routes API, Directions fallback, key redaction)');
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
