const { createRouteProvider } = require('./providers');

async function buildRouteSnapshot({ origin, originQuery, destinationQuery, providerName, providerOptions = {} }) {
  const provider = createRouteProvider({ providerName });
  const resolvedOrigin = originQuery
    ? await provider.resolveDestination(originQuery, {
        ...providerOptions,
      })
    : { label: 'Origin', coordinate: origin };
  const destination = await provider.resolveDestination(destinationQuery, {
    ...providerOptions,
  });
  const route = await provider.fetchRoute(resolvedOrigin.coordinate, destination, providerOptions);

  return {
    origin: resolvedOrigin,
    destination,
    route,
  };
}

function sampleRouteTripStates(route, sampleCount = 4) {
  const geometry = route.geometry || [];
  if (geometry.length === 0) {
    return [];
  }

  const positions = [];
  const lastIndex = geometry.length - 1;

  for (let index = 0; index < sampleCount; index += 1) {
    const ratio = sampleCount === 1 ? 1 : index / (sampleCount - 1);
    const exact = ratio * lastIndex;
    const lowerIndex = Math.floor(exact);
    const upperIndex = Math.min(lastIndex, Math.ceil(exact));
    const t = exact - lowerIndex;
    const start = geometry[lowerIndex];
    const end = geometry[upperIndex];

    positions.push({
      timestampMs: Date.now() + index * 8000,
      location: {
        lat: start.lat + (end.lat - start.lat) * t,
        lng: start.lng + (end.lng - start.lng) * t,
      },
      heading: 45,
      speedMps: 11 + index * 0.3,
      accuracyMeters: 3,
    });
  }

  return positions;
}

module.exports = {
  buildRouteSnapshot,
  sampleRouteTripStates,
};
