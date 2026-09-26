const { encodePolyline } = require('../polylines');

function hashString(value) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }
  return hash;
}

function resolveDestination(query, config = {}) {
  const origin = config.origin || { lat: 33.7756, lng: -84.3963 };
  const hash = hashString(query);
  const latOffset = ((hash % 1200) / 100000) + 0.0015;
  const lngOffset = (((hash >> 8) % 1200) / 100000) + 0.0015;

  return Promise.resolve({
    label: query,
    placeId: `mock_${hash}`,
    coordinate: {
      lat: origin.lat + latOffset,
      lng: origin.lng + lngOffset,
    },
  });
}

function fetchRoute(origin, destination, config = {}) {
  const waypoint1 = {
    lat: origin.lat + (destination.coordinate.lat - origin.lat) * 0.33,
    lng: origin.lng + (destination.coordinate.lng - origin.lng) * 0.26,
  };
  const waypoint2 = {
    lat: origin.lat + (destination.coordinate.lat - origin.lat) * 0.68,
    lng: origin.lng + (destination.coordinate.lng - origin.lng) * 0.62,
  };

  const geometry = [origin, waypoint1, waypoint2, destination.coordinate];

  return Promise.resolve({
    routeId: 'mock_route',
    provider: 'mock',
    routeApiVersion: 'mock-v1',
    origin,
    destination,
    // Encoded like a real provider's route, so displays (the tablet's route map) need no special case.
    polyline: encodePolyline(geometry),
    geometry,
    steps: [
      {
        stepId: 'step_1',
        instruction: 'Continue on the current road',
        maneuver: 'straight',
        distanceMeters: 120,
        durationSeconds: 20,
        roadName: 'Mock Avenue',
      },
      {
        stepId: 'step_2',
        instruction: 'Turn right onto the next road',
        maneuver: 'right',
        distanceMeters: 180,
        durationSeconds: 30,
        roadName: 'Mock Street',
      },
      {
        stepId: 'step_3',
        instruction: `Arrive at ${destination.label}`,
        maneuver: 'arrive',
        distanceMeters: 0,
        durationSeconds: 0,
        roadName: destination.label,
      },
    ],
    totalDistanceMeters: 300,
    totalDurationSeconds: 50,
    highwayName: config.highwayName || null,
    createdAtMs: Date.now(),
  });
}

module.exports = {
  resolveDestination,
  fetchRoute,
};
