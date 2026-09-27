// One real call each to Geocoding and Routes with the key in spatial/.env, to check the key before a demo.
//   node spatial/scripts/check-google-key.js "Piedmont Park, Atlanta" 33.7756,-84.3963
// Prints what came back (never the key). Costs one Geocoding + one Compute Routes request.
const path = require('path');
const { loadEnv } = require('./load-env');

loadEnv(path.join(__dirname, '..', '.env'));
const key = process.env.GOOGLE_MAPS_API_KEY;
if (!key) {
  console.error('GOOGLE_MAPS_API_KEY is not set: put it in spatial/.env (see spatial/.env.example).');
  process.exit(2);
}

const destinationQuery = process.argv[2] || 'Piedmont Park, Atlanta, GA';
const [lat, lng] = String(process.argv[3] || '33.7756,-84.3963').split(',').map(Number);

(async () => {
  const google = require('../phase1/providers/google');
  const destination = await google.resolveDestination(destinationQuery, { apiKey: key });
  console.log(`geocoding  ok: ${destination.label} (${destination.coordinate.lat.toFixed(5)}, ${destination.coordinate.lng.toFixed(5)})`);
  const route = await google.fetchRoute({ lat, lng }, destination, { apiKey: key });
  console.log(`route      ok: ${route.routeApiVersion}, ${route.totalDistanceMeters} m, ${route.totalDurationSeconds} s, ${route.steps.length} steps, polyline ${route.polyline.length} chars`);
  for (const s of route.steps.slice(0, 6)) console.log(`  ${s.maneuver.padEnd(10)} ${String(s.distanceMeters).padStart(5)} m  ${s.instruction}`);
})().catch((e) => {
  console.error(`failed: ${e.message}`);
  console.error('Check: billing is on, the Geocoding API and the Routes API are enabled for the key\'s project, and the key\'s API restrictions allow them.');
  process.exit(1);
});
