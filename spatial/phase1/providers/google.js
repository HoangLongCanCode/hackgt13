const { decodePolyline } = require('../polylines');
const { getJson, postJson } = require('./http');

// Google Routes API (computeRoutes). The legacy Directions API is the fallback: Google closed it to new projects
// in March 2025, so a key created today usually only has the Routes API. Select with GOOGLE_ROUTES_API=routes|directions
// (default routes; routes falls back to directions by itself when the Routes API is not enabled for the key).
const ROUTES_URL = 'https://routes.googleapis.com/directions/v2:computeRoutes';
const ROUTES_FIELD_MASK = [
  'routes.description',
  'routes.distanceMeters',
  'routes.duration',
  'routes.polyline.encodedPolyline',
  'routes.legs.distanceMeters',
  'routes.legs.duration',
  'routes.legs.steps.distanceMeters',
  'routes.legs.steps.staticDuration',
  'routes.legs.steps.polyline.encodedPolyline',
  'routes.legs.steps.navigationInstruction',
].join(',');

// Places API (New) Text Search for the tablet's destination search ("coffee", "Piedmont Park", an address). The
// Geocoding API is the fallback when Places is not enabled for the key (it finds addresses, not "coffee").
const PLACES_URL = 'https://places.googleapis.com/v1/places:searchText';
const PLACES_FIELD_MASK = 'places.id,places.displayName,places.formattedAddress,places.location';
const PLACES_MAX_RESULTS = 8;
const PLACES_BIAS_RADIUS_METERS = 20000;

function stripHtml(value) {
  return String(value || '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
}

function normalizeManeuver(maneuver) {
  // Directions uses 'turn-left', Routes 'TURN_LEFT': compare in one spelling.
  const value = String(maneuver || '').toLowerCase().replace(/_/g, '-');
  // Specific maneuvers first: 'keep-left', 'fork-right', 'ramp-left' also contain 'left' / 'right'.
  if (value.includes('keep-left') || value.includes('fork-left') || value.includes('ramp-left')) return 'keep_left';
  if (value.includes('keep-right') || value.includes('fork-right') || value.includes('ramp-right')) return 'keep_right';
  if (value.includes('merge')) return 'merge';
  if (value.includes('ferry') || value.includes('roundabout')) return 'straight';
  if (value.includes('left')) return 'left';
  if (value.includes('right')) return 'right';
  return 'straight';
}

/** "Turn left onto Peachtree St NE" -> "Peachtree St NE"; "Head north on 10th St" -> "10th St"; else undefined. */
function roadNameOf(instruction) {
  const text = stripHtml(instruction);
  const onto = /\bonto\s+(.+?)(?:\s+toward\b.*|\s*\/.*)?$/i.exec(text);
  if (onto) return onto[1].trim();
  const on = /\b(?:on|along)\s+(.+?)(?:\s+toward\b.*|\s*\/.*)?$/i.exec(text);
  return on ? on[1].trim() : undefined;
}

function exitNumberOf(instruction) {
  const m = /\bexit\s+(\d+[A-Z]?)\b/i.exec(stripHtml(instruction));
  return m ? m[1] : undefined;
}

function seconds(duration) {
  if (typeof duration === 'number') return duration;
  const n = parseFloat(String(duration || '0').replace(/s$/, ''));
  return Number.isFinite(n) ? Math.round(n) : 0;
}

function normalizeDestination(placeOrResult) {
  return {
    label: placeOrResult.label,
    placeId: placeOrResult.placeId,
    coordinate: placeOrResult.coordinate,
  };
}

async function resolveDestination(query, config = {}) {
  if (!config.apiKey) {
    throw new Error('GOOGLE_MAPS_API_KEY is required for Google destination lookup');
  }

  const url = new URL('https://maps.googleapis.com/maps/api/geocode/json');
  url.searchParams.set('address', query);
  url.searchParams.set('key', config.apiKey);

  const response = await getJson(url.toString());
  if (response.status !== 'OK' || !response.results || !response.results[0]) {
    const why = [response.status, response.error_message].filter(Boolean).join(': ');
    throw new Error(`Unable to geocode destination: ${query}${why ? ` (${why})` : ''}`);
  }

  const result = response.results[0];
  return normalizeDestination({
    label: result.formatted_address || query,
    placeId: result.place_id,
    coordinate: {
      lat: result.geometry.location.lat,
      lng: result.geometry.location.lng,
    },
  });
}

/** Places matching free text, best first: [{placeId, label, address, location: {lat, lng}}]. config.near biases. */
async function searchPlaces(query, config = {}) {
  if (!config.apiKey) {
    throw new Error('GOOGLE_MAPS_API_KEY is required for Google place search');
  }
  try {
    return await searchPlacesText(query, config);
  } catch (error) {
    // 403 / 404: the Places API (New) is not enabled for this key's project; the Geocoding API may still be.
    if (/HTTP (403|404)\b/.test(String(error && error.message))) {
      process.stderr.write(`[phase1] Places API unavailable (${String(error.message).slice(0, 160)}); trying the Geocoding API\n`);
      return searchPlacesGeocoding(query, config);
    }
    throw error;
  }
}

async function searchPlacesText(query, config) {
  const body = { textQuery: query, maxResultCount: PLACES_MAX_RESULTS };
  if (config.near) {
    body.locationBias = {
      circle: {
        center: { latitude: config.near.lat, longitude: config.near.lng },
        radius: PLACES_BIAS_RADIUS_METERS,
      },
    };
  }
  const response = await postJson(PLACES_URL, body, {
    'X-Goog-Api-Key': config.apiKey,
    'X-Goog-FieldMask': PLACES_FIELD_MASK,
  });
  // No match = an empty object (no "places" array).
  return ((response && response.places) || [])
    .filter((place) => place.location)
    .slice(0, PLACES_MAX_RESULTS)
    .map((place) => ({
      placeId: place.id || null,
      label: (place.displayName && place.displayName.text) || place.formattedAddress || query,
      address: place.formattedAddress || null,
      location: { lat: place.location.latitude, lng: place.location.longitude },
    }));
}

async function searchPlacesGeocoding(query, config) {
  const url = new URL('https://maps.googleapis.com/maps/api/geocode/json');
  url.searchParams.set('address', query);
  url.searchParams.set('key', config.apiKey);

  const response = await getJson(url.toString());
  if (response.status === 'ZERO_RESULTS') return [];
  if (response.status !== 'OK' || !response.results) {
    const why = [response.status, response.error_message].filter(Boolean).join(': ');
    throw new Error(`Unable to search places for: ${query}${why ? ` (${why})` : ''}`);
  }
  return response.results.slice(0, PLACES_MAX_RESULTS).map((result) => {
    // "Piedmont Park" -> its name; an address starts with the street number -> the whole formatted address.
    const first = (result.address_components || [])[0];
    const named = first && first.long_name && !(first.types || []).includes('street_number');
    return {
      placeId: result.place_id || null,
      label: named ? first.long_name : result.formatted_address || query,
      address: result.formatted_address || null,
      location: { lat: result.geometry.location.lat, lng: result.geometry.location.lng },
    };
  });
}

async function fetchRoute(origin, destination, config = {}) {
  if (!config.apiKey) {
    throw new Error('GOOGLE_MAPS_API_KEY is required for Google route lookup');
  }
  const api = String(config.routesApi || process.env.GOOGLE_ROUTES_API || 'routes').toLowerCase();
  if (api === 'directions') return fetchRouteDirections(origin, destination, config);
  try {
    return await fetchRouteRoutesApi(origin, destination, config);
  } catch (error) {
    // 403 / 404: the Routes API is not enabled for this key's project; the legacy Directions API may still be.
    if (/HTTP (403|404)\b/.test(String(error && error.message))) {
      process.stderr.write(`[phase1] Routes API unavailable (${String(error.message).slice(0, 160)}); trying the Directions API\n`);
      return fetchRouteDirections(origin, destination, config);
    }
    throw error;
  }
}

async function fetchRouteRoutesApi(origin, destination, config) {
  const body = {
    origin: { location: { latLng: { latitude: origin.lat, longitude: origin.lng } } },
    destination: { location: { latLng: { latitude: destination.coordinate.lat, longitude: destination.coordinate.lng } } },
    travelMode: 'DRIVE',
    routingPreference: 'TRAFFIC_UNAWARE',
    computeAlternativeRoutes: false,
    languageCode: 'en-US',
    units: 'METRIC',
  };
  const response = await postJson(ROUTES_URL, body, {
    'X-Goog-Api-Key': config.apiKey,
    'X-Goog-FieldMask': ROUTES_FIELD_MASK,
  });
  const route = response && response.routes && response.routes[0];
  if (!route) {
    throw new Error('Unable to fetch route from the Google Routes API (no route returned)');
  }
  const leg = route.legs && route.legs[0] ? route.legs[0] : null;
  const encoded = route.polyline && route.polyline.encodedPolyline ? route.polyline.encodedPolyline : '';
  const decodedGeometry = decodePolyline(encoded);
  const geometry = decodedGeometry.length > 0 ? decodedGeometry : [origin, destination.coordinate];
  const steps = normalizeSteps((leg && leg.steps) || [], destination.label, (step) => {
    const ni = step.navigationInstruction || {};
    return {
      instruction: ni.instructions || '',
      maneuver: ni.maneuver,
      distanceMeters: step.distanceMeters || 0,
      durationSeconds: seconds(step.staticDuration),
      polyline: step.polyline && step.polyline.encodedPolyline ? step.polyline.encodedPolyline : undefined,
    };
  });

  return {
    routeId: route.description ? `google_${route.description}` : 'google_route',
    provider: 'google',
    routeApiVersion: 'routes-v2',
    origin,
    destination: normalizeDestination(destination),
    polyline: encoded,
    geometry,
    steps,
    totalDistanceMeters: route.distanceMeters || (leg && leg.distanceMeters) || 0,
    totalDurationSeconds: seconds(route.duration || (leg && leg.duration)),
    highwayName: route.description || null,
    createdAtMs: Date.now(),
  };
}

async function fetchRouteDirections(origin, destination, config) {
  const url = new URL('https://maps.googleapis.com/maps/api/directions/json');
  url.searchParams.set('origin', `${origin.lat},${origin.lng}`);
  url.searchParams.set('destination', `${destination.coordinate.lat},${destination.coordinate.lng}`);
  url.searchParams.set('mode', 'driving');
  url.searchParams.set('alternatives', 'false');
  url.searchParams.set('key', config.apiKey);

  const response = await getJson(url.toString());
  if (response.status !== 'OK' || !response.routes || !response.routes[0]) {
    const why = [response.status, response.error_message].filter(Boolean).join(': ');
    throw new Error(`Unable to fetch route from Google Directions API${why ? ` (${why})` : ''}`);
  }

  const route = response.routes[0];
  const leg = route.legs && route.legs[0] ? route.legs[0] : null;
  const decodedGeometry = decodePolyline(route.overview_polyline && route.overview_polyline.points);
  const geometry = decodedGeometry.length > 0 ? decodedGeometry : [origin, destination.coordinate];
  const steps = normalizeSteps(leg ? leg.steps || [] : [], destination.label, (step) => ({
    instruction: step.html_instructions || step.instructions || '',
    maneuver: step.maneuver,
    distanceMeters: step.distance ? step.distance.value : 0,
    durationSeconds: step.duration ? step.duration.value : 0,
    polyline: step.polyline && step.polyline.points ? step.polyline.points : undefined,
  }));

  return {
    routeId: route.summary ? `google_${route.summary}` : 'google_route',
    provider: 'google',
    routeApiVersion: 'directions-v1',
    origin,
    destination: normalizeDestination(destination),
    polyline: route.overview_polyline ? route.overview_polyline.points : '',
    geometry,
    steps,
    totalDistanceMeters: leg && leg.distance ? leg.distance.value : 0,
    totalDurationSeconds: leg && leg.duration ? leg.duration.value : 0,
    highwayName: route.summary || null,
    createdAtMs: Date.now(),
  };
}

/** Provider steps -> the project-owned step shape (plus the final arrive step). [read] maps one raw step. */
function normalizeSteps(steps, destinationLabel, read) {
  const normalized = steps.map((raw, index) => {
    const step = read(raw);
    const instruction = stripHtml(step.instruction);
    return {
      stepId: `step_${index + 1}`,
      instruction,
      maneuver: normalizeManeuver(step.maneuver),
      distanceMeters: step.distanceMeters,
      durationSeconds: step.durationSeconds,
      roadName: roadNameOf(instruction),
      exitNumber: exitNumberOf(instruction),
      polyline: step.polyline,
    };
  });

  normalized.push({
    stepId: `step_${normalized.length + 1}`,
    instruction: `Arrive at ${destinationLabel}`,
    maneuver: 'arrive',
    distanceMeters: 0,
    durationSeconds: 0,
    roadName: destinationLabel,
  });

  return normalized;
}

module.exports = {
  resolveDestination,
  searchPlaces,
  fetchRoute,
  // exported for tests
  normalizeManeuver,
  roadNameOf,
  exitNumberOf,
};
