// Offline test of the Google provider (no key, no network): global fetch is replaced by canned responses.
//   node spatial/scripts/test-google-provider.js
const assert = require('assert');
const { encodePolyline } = require('../phase1/polylines');

const calls = [];
let routesStatus = 200;
let placesStatus = 200;
// When set, the Routes / Directions reply carries these steps instead of the default ones.
let routesSteps = null;
let directionsSteps = null;

// Two steps of the recorded drive's Routes reply: a "Keep left" sent with a slight-left maneuver, and the last turn
// with the destination side on a second line.
const RECORDED_ROUTES_STEPS = [
  { distanceMeters: 832, staticDuration: '143s', navigationInstruction: { maneuver: 'TURN_LEFT', instructions: 'Turn left onto W Peachtree St NE' } },
  { distanceMeters: 82, staticDuration: '6s', navigationInstruction: { maneuver: 'TURN_SLIGHT_LEFT', instructions: 'Keep left' } },
  { distanceMeters: 194, staticDuration: '12s', navigationInstruction: { maneuver: 'TURN_SLIGHT_RIGHT', instructions: 'Keep right to stay on GA-13 N' } },
  { distanceMeters: 178, staticDuration: '50s', navigationInstruction: { maneuver: 'TURN_RIGHT', instructions: 'Turn right onto Lenox Pkwy NE\nDestination will be on the right' } },
  { distanceMeters: 25, staticDuration: '7s', navigationInstruction: { maneuver: 'TURN_LEFT', instructions: 'Turn left\nDestination will be on the left' } },
];
// The same shapes as legacy Directions html_instructions (the second line is a <div>).
const RECORDED_DIRECTIONS_STEPS = [
  { html_instructions: '<b>Keep left</b>', maneuver: 'turn-slight-left', distance: { value: 82 }, duration: { value: 6 } },
  { html_instructions: 'Keep <b>right</b> to stay on <b>GA-13 N</b>', maneuver: 'turn-slight-right', distance: { value: 194 }, duration: { value: 12 } },
  { html_instructions: 'Turn <b>left</b><div style="font-size:0.9em">Destination will be on the left</div>', maneuver: 'turn-left', distance: { value: 25 }, duration: { value: 7 } },
];
// Other second-line notices Google sends (toll road, restricted road): the road name is on the first line only.
const NOTICE_ROUTES_STEPS = [
  { distanceMeters: 900, staticDuration: '40s', navigationInstruction: { maneuver: 'TURN_RIGHT', instructions: 'Turn right onto GA-400 N\nToll road' } },
];
const NOTICE_DIRECTIONS_STEPS = [
  { html_instructions: 'Turn <b>left</b> onto <b>X Rd</b><div style="font-size:0.9em">Restricted usage road</div>', maneuver: 'turn-left', distance: { value: 300 }, duration: { value: 30 } },
];

const origin = { lat: 33.7756, lng: -84.3963 };
const dest = { lat: 33.7853, lng: -84.3733 };
const overview = encodePolyline([origin, { lat: 33.7800, lng: -84.3900 }, dest]);

global.fetch = async (url, init = {}) => {
  calls.push({ url: String(url), init });
  const u = new URL(String(url));
  const reply = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body) });
  if (u.pathname.endsWith('/geocode/json')) {
    return reply(200, { status: 'OK', results: [{ formatted_address: 'Piedmont Park, Atlanta, GA, USA', place_id: 'pp1', geometry: { location: dest },
      address_components: [{ long_name: 'Piedmont Park', types: ['park', 'point_of_interest'] }] }] });
  }
  if (u.hostname === 'places.googleapis.com') {
    if (placesStatus !== 200) return reply(placesStatus, { error: { code: placesStatus, message: 'Places API (New) has not been used in project 123 before or it is disabled.', status: 'PERMISSION_DENIED' } });
    return reply(200, { places: [
      { id: 'ChIJfox', displayName: { text: 'Foxtail Coffee', languageCode: 'en' }, formattedAddress: '811 Peachtree St NE, Atlanta, GA 30308, USA', location: { latitude: 33.7766, longitude: -84.3838 } },
      { id: 'ChIJurb', displayName: { text: 'Urban Grind' }, formattedAddress: '962 Marietta St NW, Atlanta, GA 30318, USA', location: { latitude: 33.7801, longitude: -84.4104 } },
      { id: 'ChIJnoloc', displayName: { text: 'No location' } },
    ] });
  }
  if (u.hostname === 'routes.googleapis.com') {
    if (routesStatus !== 200) return reply(routesStatus, { error: { code: routesStatus, message: 'Routes API has not been used in project 123 before or it is disabled.', status: 'PERMISSION_DENIED' } });
    return reply(200, { routes: [{
      description: 'Piedmont Ave NE',
      distanceMeters: 2310,
      duration: '412s',
      polyline: { encodedPolyline: overview },
      legs: [{ distanceMeters: 2310, duration: '412s', steps: routesSteps || [
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
      steps: directionsSteps || [{ html_instructions: 'Turn <b>left</b> onto <b>Juniper St</b>', maneuver: 'turn-left', distance: { value: 2000 }, duration: { value: 400 } }],
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

  // "Keep left" / "Keep right" in the instruction is a keep, whatever slight maneuver Google sends; a destination
  // side ("Destination will be on the left") is not a road name.
  assert.strictEqual(google.normalizeManeuver('TURN_SLIGHT_LEFT', 'Keep left'), 'keep_left');
  assert.strictEqual(google.normalizeManeuver('turn-slight-right', 'KEEP RIGHT at the fork'), 'keep_right');
  assert.strictEqual(google.normalizeManeuver('TURN_SLIGHT_LEFT'), 'left', 'no instruction: the maneuver decides');
  assert.strictEqual(google.normalizeManeuver('TURN_LEFT', 'Turn left to keep left'), 'left', 'only a leading Keep counts');
  assert.strictEqual(google.roadNameOf('Turn left\nDestination will be on the left'), undefined);
  assert.strictEqual(google.roadNameOf('Turn right onto Lenox Pkwy NE\nDestination will be on the right'), 'Lenox Pkwy NE');
  assert.strictEqual(google.roadNameOf('Take the exit on the left onto I-75 N'), 'I-75 N');
  assert.strictEqual(google.roadNameOf('Keep left'), undefined);
  // Only the first line names the road: a notice after '\n' (Routes) or in a <div> (Directions) is not part of it.
  assert.strictEqual(google.roadNameOf('Turn right onto GA-400 N\nToll road'), 'GA-400 N');
  assert.strictEqual(google.roadNameOf('Keep right to stay on I-285 E\nToll road'), 'I-285 E');
  assert.strictEqual(google.roadNameOf('Continue onto I-75 N\nEntering Georgia'), 'I-75 N');
  assert.strictEqual(google.roadNameOf('Turn left onto Peachtree St NE\nPass by Chick-fil-A (on the right in 0.2 mi)'), 'Peachtree St NE');
  assert.strictEqual(google.roadNameOf('Turn <b>left</b> onto <b>X Rd</b><div style="font-size:0.9em">Restricted usage road</div>'), 'X Rd');
  assert.strictEqual(google.roadNameOf('<div>Toll road</div>'), undefined);
  routesSteps = NOTICE_ROUTES_STEPS;
  const notice = await google.fetchRoute(origin, d, config);
  routesSteps = null;
  assert.strictEqual(notice.steps[0].roadName, 'GA-400 N');
  assert.strictEqual(notice.steps[0].instruction, 'Turn right onto GA-400 N Toll road', 'the shown instruction keeps the notice');
  routesSteps = RECORDED_ROUTES_STEPS;
  const recorded = await google.fetchRoute(origin, d, config);
  routesSteps = null;
  assert.deepStrictEqual(recorded.steps.map((s) => s.maneuver), ['left', 'keep_left', 'keep_right', 'right', 'left', 'arrive']);
  assert.deepStrictEqual(recorded.steps.map((s) => s.roadName),
    ['W Peachtree St NE', undefined, 'GA-13 N', 'Lenox Pkwy NE', undefined, 'Piedmont Park, Atlanta, GA, USA']);

  // Routes API not enabled for the project (403): falls back to the legacy Directions API.
  routesStatus = 403;
  const legacy = await google.fetchRoute(origin, d, config);
  assert.strictEqual(legacy.routeApiVersion, 'directions-v1');
  assert.strictEqual(legacy.steps[0].maneuver, 'left');
  assert.strictEqual(legacy.steps[0].roadName, 'Juniper St');
  directionsSteps = RECORDED_DIRECTIONS_STEPS;
  const legacyRecorded = await google.fetchRoute(origin, d, config);
  directionsSteps = null;
  assert.strictEqual(legacyRecorded.routeApiVersion, 'directions-v1');
  assert.deepStrictEqual(legacyRecorded.steps.map((s) => s.maneuver), ['keep_left', 'keep_right', 'left', 'arrive']);
  assert.deepStrictEqual(legacyRecorded.steps.map((s) => s.roadName), [undefined, 'GA-13 N', undefined, 'Piedmont Park, Atlanta, GA, USA']);
  assert.strictEqual(legacyRecorded.steps[2].instruction, 'Turn left Destination will be on the left');
  directionsSteps = NOTICE_DIRECTIONS_STEPS;
  const legacyNotice = await google.fetchRoute(origin, d, config);
  directionsSteps = null;
  assert.strictEqual(legacyNotice.steps[0].roadName, 'X Rd');

  // Places Text Search: key + field mask in headers, biased around `near`, places without a location dropped.
  const near = { lat: 33.7756, lng: -84.3963 };
  const found = await google.searchPlaces('coffee', { ...config, near });
  const pc = calls.filter((c) => c.url.includes('places.googleapis.com')).pop();
  assert.ok(pc, 'Places API called');
  assert.ok(!pc.url.includes('TEST_KEY_123'), 'no key in the Places URL');
  assert.strictEqual(pc.init.headers['X-Goog-Api-Key'], 'TEST_KEY_123');
  assert.strictEqual(pc.init.headers['X-Goog-FieldMask'], 'places.id,places.displayName,places.formattedAddress,places.location');
  const sent = JSON.parse(pc.init.body);
  assert.strictEqual(sent.textQuery, 'coffee');
  assert.strictEqual(sent.maxResultCount, 8);
  assert.deepStrictEqual(sent.locationBias, { circle: { center: { latitude: near.lat, longitude: near.lng }, radius: 20000 } });
  assert.deepStrictEqual(found, [
    { placeId: 'ChIJfox', label: 'Foxtail Coffee', address: '811 Peachtree St NE, Atlanta, GA 30308, USA', location: { lat: 33.7766, lng: -84.3838 } },
    { placeId: 'ChIJurb', label: 'Urban Grind', address: '962 Marietta St NW, Atlanta, GA 30318, USA', location: { lat: 33.7801, lng: -84.4104 } },
  ]);
  await google.searchPlaces('coffee', config);
  assert.ok(!('locationBias' in JSON.parse(calls.filter((c) => c.url.includes('places.googleapis.com')).pop().init.body)), 'no bias without near');

  // Places API not enabled for the project (403): falls back to the Geocoding API.
  placesStatus = 403;
  const geocoded = await google.searchPlaces('Piedmont Park', { ...config, near });
  assert.deepStrictEqual(geocoded, [
    { placeId: 'pp1', label: 'Piedmont Park', address: 'Piedmont Park, Atlanta, GA, USA', location: dest },
  ]);

  // Errors never carry the key.
  const http = require('../phase1/providers/http');
  assert.strictEqual(http.redact('https://maps.googleapis.com/x?address=a&key=TEST_KEY_123'), 'https://maps.googleapis.com/x?address=a&key=REDACTED');

  console.log('google provider: 8/8 checks passed (geocode, Routes API, Directions fallback, Places search, Places -> Geocoding fallback, key redaction, ' +
    'Keep left / right from the instruction, destination side and second-line notices are not a road)');
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
