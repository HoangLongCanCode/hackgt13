const METERS_PER_DEGREE_LAT = 111_320;

function toRadians(value) {
  return (value * Math.PI) / 180;
}

function haversineMeters(a, b) {
  const earthRadiusMeters = 6_371_000;
  const dLat = toRadians(b.lat - a.lat);
  const dLng = toRadians(b.lng - a.lng);
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);

  const sinLat = Math.sin(dLat / 2);
  const sinLng = Math.sin(dLng / 2);
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
  return 2 * earthRadiusMeters * Math.asin(Math.min(1, Math.sqrt(h)));
}

function metersPerDegreeLng(latitude) {
  return METERS_PER_DEGREE_LAT * Math.cos(toRadians(latitude));
}

function projectToLocalMeters(origin, point) {
  const x = (point.lng - origin.lng) * metersPerDegreeLng(origin.lat);
  const y = (point.lat - origin.lat) * METERS_PER_DEGREE_LAT;
  return { x, y };
}

function projectPointToSegmentMeters(point, start, end) {
  const localStart = { x: 0, y: 0 };
  const localEnd = projectToLocalMeters(start, end);
  const localPoint = projectToLocalMeters(start, point);

  const dx = localEnd.x - localStart.x;
  const dy = localEnd.y - localStart.y;
  const segmentLengthSq = dx * dx + dy * dy;

  if (segmentLengthSq === 0) {
    return {
      t: 0,
      projected: start,
      distanceMeters: Math.hypot(localPoint.x - localStart.x, localPoint.y - localStart.y),
    };
  }

  const rawT = ((localPoint.x - localStart.x) * dx + (localPoint.y - localStart.y) * dy) / segmentLengthSq;
  const t = Math.max(0, Math.min(1, rawT));
  const projectedLocal = {
    x: localStart.x + t * dx,
    y: localStart.y + t * dy,
  };

  const distanceMeters = Math.hypot(localPoint.x - projectedLocal.x, localPoint.y - projectedLocal.y);
  const projected = {
    lat: start.lat + projectedLocal.y / METERS_PER_DEGREE_LAT,
    lng: start.lng + projectedLocal.x / metersPerDegreeLng(start.lat),
  };

  return { t, projected, distanceMeters };
}

module.exports = {
  haversineMeters,
  projectPointToSegmentMeters,
};
