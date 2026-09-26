const { decodePolyline } = require('../polylines');
const { getJson } = require('./http');

function stripHtml(value) {
  return String(value || '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
}

function normalizeManeuver(maneuver) {
  const value = String(maneuver || '').toLowerCase();
  if (value.includes('turn-left') || value.includes('left')) return 'left';
  if (value.includes('turn-right') || value.includes('right')) return 'right';
  if (value.includes('merge')) return 'merge';
  if (value.includes('ferry')) return 'straight';
  if (value.includes('keep-left')) return 'keep_left';
  if (value.includes('keep-right')) return 'keep_right';
  if (value.includes('roundabout')) return 'straight';
  return 'straight';
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
    throw new Error(`Unable to geocode destination: ${query}`);
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

async function fetchRoute(origin, destination, config = {}) {
  if (!config.apiKey) {
    throw new Error('GOOGLE_MAPS_API_KEY is required for Google route lookup');
  }

  const url = new URL('https://maps.googleapis.com/maps/api/directions/json');
  url.searchParams.set('origin', `${origin.lat},${origin.lng}`);
  url.searchParams.set('destination', `${destination.coordinate.lat},${destination.coordinate.lng}`);
  url.searchParams.set('mode', 'driving');
  url.searchParams.set('alternatives', 'false');
  url.searchParams.set('key', config.apiKey);

  const response = await getJson(url.toString());
  if (response.status !== 'OK' || !response.routes || !response.routes[0]) {
    throw new Error('Unable to fetch route from Google Directions API');
  }

  const route = response.routes[0];
  const leg = route.legs && route.legs[0] ? route.legs[0] : null;
  const decodedGeometry = decodePolyline(route.overview_polyline && route.overview_polyline.points);
  const geometry = decodedGeometry.length > 0 ? decodedGeometry : [origin, destination.coordinate];
  const steps = normalizeSteps(leg ? leg.steps || [] : [], destination.label);

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

function normalizeSteps(steps, destinationLabel) {
  const normalized = steps.map((step, index) => ({
    stepId: `step_${index + 1}`,
    instruction: stripHtml(step.html_instructions || step.instructions || ''),
    maneuver: normalizeManeuver(step.maneuver),
    distanceMeters: step.distance ? step.distance.value : 0,
    durationSeconds: step.duration ? step.duration.value : 0,
    roadName: step.html_instructions ? stripHtml(step.html_instructions) : undefined,
    polyline: step.polyline && step.polyline.points ? step.polyline.points : undefined,
  }));

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
  fetchRoute,
};
