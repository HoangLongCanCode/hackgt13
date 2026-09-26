const { haversineMeters, projectPointToSegmentMeters } = require('./geo');
const { getLatestTripState } = require('./session');

function validateRoute(route) {
  if (!route || typeof route !== 'object') {
    throw new Error('route must be an object');
  }
  if (!route.routeId) {
    throw new Error('route.routeId is required');
  }
  if (!Array.isArray(route.geometry) || route.geometry.length < 2) {
    throw new Error('route.geometry must contain at least two coordinates');
  }
  if (!Array.isArray(route.steps)) {
    throw new Error('route.steps must be an array');
  }
}

function computeRouteGeometry(routeGeometry) {
  const segments = [];
  let cumulativeMeters = 0;

  for (let index = 0; index < routeGeometry.length - 1; index += 1) {
    const start = routeGeometry[index];
    const end = routeGeometry[index + 1];
    const lengthMeters = haversineMeters(start, end);

    segments.push({
      segmentIndex: index,
      start,
      end,
      lengthMeters,
      cumulativeStartMeters: cumulativeMeters,
      cumulativeEndMeters: cumulativeMeters + lengthMeters,
    });

    cumulativeMeters += lengthMeters;
  }

  return {
    segments,
    totalMeters: cumulativeMeters,
  };
}

function projectLocationOntoRoute(location, routeGeometry) {
  const geometry = computeRouteGeometry(routeGeometry);

  let best = null;

  for (const segment of geometry.segments) {
    const result = projectPointToSegmentMeters(location, segment.start, segment.end);
    const alongMeters = segment.cumulativeStartMeters + result.t * segment.lengthMeters;

    if (!best || result.distanceMeters < best.distanceMeters) {
      best = {
        segmentIndex: segment.segmentIndex,
        alongMeters,
        distanceMeters: result.distanceMeters,
        projectedPoint: result.projected,
      };
    }
  }

  return {
    routeIndex: best ? best.segmentIndex : 0,
    distanceAlongRouteMeters: best ? best.alongMeters : 0,
    distanceFromRouteMeters: best ? best.distanceMeters : null,
    projectedPoint: best ? best.projectedPoint : location,
    geometry,
  };
}

function computeRouteProgress(tripState, routeGeometry, totalRouteDistanceMeters) {
  const projection = projectLocationOntoRoute(tripState.location, routeGeometry);
  const remainingDistanceMeters = Math.max(0, (totalRouteDistanceMeters || projection.geometry.totalMeters) - projection.distanceAlongRouteMeters);
  const speedMps = tripState.speedMps || 0;
  const etaSeconds = speedMps > 0.5 ? Math.round(remainingDistanceMeters / speedMps) : null;

  return {
    currentLocation: tripState.location,
    heading: tripState.heading,
    speedMps,
    routeIndex: projection.routeIndex,
    distanceTraveledMeters: projection.distanceAlongRouteMeters,
    remainingDistanceMeters,
    etaSeconds,
    offRoute: projection.distanceFromRouteMeters != null ? projection.distanceFromRouteMeters > 35 : false,
    projectedPoint: projection.projectedPoint,
    distanceFromRouteMeters: projection.distanceFromRouteMeters,
  };
}

function buildNavigationEvents(routeSteps) {
  let cumulativeMeters = 0;

  return routeSteps.map((step, index) => {
    const event = {
      eventId: step.stepId || `step_${index + 1}`,
      type: maneuverToEventType(step.maneuver),
      distanceMeters: cumulativeMeters,
      roadName: step.roadName,
      exitNumber: step.exitNumber,
      requiredLane: step.requiredLane,
      instruction: step.instruction,
    };

    cumulativeMeters += step.distanceMeters || 0;
    return event;
  });
}

function maneuverToEventType(maneuver) {
  switch (maneuver) {
    case 'left':
      return 'TURN_LEFT';
    case 'right':
      return 'TURN_RIGHT';
    case 'keep_left':
      return 'KEEP_LEFT';
    case 'keep_right':
      return 'KEEP_RIGHT';
    case 'merge':
      return 'MERGE';
    case 'exit':
      return 'EXIT_HIGHWAY';
    case 'arrive':
      return 'ARRIVE';
    case 'straight':
    case 'start':
    default:
      return 'GO_STRAIGHT';
  }
}

function getUpcomingManeuver(progress, navigationEvents) {
  const upcoming = navigationEvents.find((event) => event.distanceMeters >= progress.distanceTraveledMeters);
  return upcoming || navigationEvents[navigationEvents.length - 1] || null;
}

function computeInstructionWindow(distanceToManeuver) {
  if (distanceToManeuver <= 50) {
    return 'immediate';
  }
  if (distanceToManeuver <= 200) {
    return 'near';
  }
  return 'upcoming';
}

function prioritizeInstructions(events) {
  return events
    .slice()
    .sort((a, b) => a.distanceMeters - b.distanceMeters)
    .map((event, index) => ({
      ...event,
      priority: index + 1,
    }));
}

function buildSpatialInstruction(event, progress) {
  const distanceMeters = Math.max(0, Math.round(event.distanceMeters - progress.distanceTraveledMeters));
  const window = computeInstructionWindow(distanceMeters);
  const content = formatInstructionContent(event, distanceMeters, window);

  return {
    id: `spatial_${event.eventId}`,
    type: instructionTypeForEvent(event.type),
    anchor: {
      kind: 'ROUTE_POINT',
      routeDistanceMeters: event.distanceMeters,
    },
    content,
    priority: distanceMeters <= 50 ? 1 : distanceMeters <= 200 ? 2 : 3,
    lifetimeMs: window === 'immediate' ? 4000 : 8000,
  };
}

function buildAudioPrompt(event, progress) {
  const distanceMeters = Math.max(0, Math.round(event.distanceMeters - progress.distanceTraveledMeters));
  const direction = event.type
    .replace('TURN_', '')
    .replace('KEEP_', 'keep ')
    .replace('EXIT_HIGHWAY', 'exit')
    .replace('GO_STRAIGHT', 'continue straight')
    .toLowerCase();

  let text;
  if (event.type === 'ARRIVE') {
    text = 'You have arrived at your destination.';
  } else if (event.type === 'GO_STRAIGHT') {
    text = distanceMeters > 0 ? `Continue straight in ${formatDistance(distanceMeters)}.` : 'Continue straight.';
  } else if (event.type === 'EXIT_HIGHWAY') {
    text = `Take the exit in ${formatDistance(distanceMeters)}.`;
  } else if (event.type === 'MERGE') {
    text = `Merge ${direction} in ${formatDistance(distanceMeters)}.`;
  } else {
    text = `${humanizeEventType(event.type)} in ${formatDistance(distanceMeters)}.`;
  }

  return {
    id: `audio_${event.eventId}`,
    content: text,
    priority: distanceMeters <= 50 ? 1 : distanceMeters <= 200 ? 2 : 3,
    speakAtDistanceMeters: event.distanceMeters,
  };
}

function formatInstructionContent(event, distanceMeters, window) {
  const base = humanizeEventType(event.type);
  const distance = formatDistance(distanceMeters);
  if (event.type === 'ARRIVE') {
    return 'ARRIVE';
  }
  if (window === 'immediate') {
    return `${base}\n${distance}`;
  }
  return `${base}\n${distance}`;
}

function instructionTypeForEvent(type) {
  if (type === 'TURN_LEFT' || type === 'TURN_RIGHT') {
    return 'TURN_ARROW';
  }
  if (type === 'KEEP_LEFT' || type === 'KEEP_RIGHT' || type === 'MERGE') {
    return 'LANE_ARROW';
  }
  if (type === 'EXIT_HIGHWAY') {
    return 'EXIT_MARKER';
  }
  if (type === 'ARRIVE') {
    return 'WARNING';
  }
  return 'DISTANCE_LABEL';
}

function humanizeEventType(type) {
  switch (type) {
    case 'TURN_LEFT':
      return 'TURN LEFT';
    case 'TURN_RIGHT':
      return 'TURN RIGHT';
    case 'KEEP_LEFT':
      return 'KEEP LEFT';
    case 'KEEP_RIGHT':
      return 'KEEP RIGHT';
    case 'MERGE':
      return 'MERGE';
    case 'EXIT_HIGHWAY':
      return 'EXIT';
    case 'ARRIVE':
      return 'ARRIVE';
    case 'GO_STRAIGHT':
    default:
      return 'CONTINUE STRAIGHT';
  }
}

function formatDistance(distanceMeters) {
  if (distanceMeters >= 1609.34) {
    return `${(distanceMeters / 1609.34).toFixed(1)} mi`;
  }
  return `${Math.max(0, Math.round(distanceMeters))} m`;
}

function buildSpatialNavigationPacket({ manifest, route, tripStates }) {
  validateRoute(route);

  const currentTripState = getLatestTripState(tripStates);
  const progress = computeRouteProgress(currentTripState, route.geometry, route.totalDistanceMeters);
  const navigationEvents = buildNavigationEvents(route.steps);
  const upcomingEvents = navigationEvents.filter((event) => event.distanceMeters >= progress.distanceTraveledMeters);
  const upcomingManeuver = getUpcomingManeuver(progress, navigationEvents);
  const spatialInstructions = upcomingManeuver ? [buildSpatialInstruction(upcomingManeuver, progress)] : [];
  const audioInstructions = upcomingManeuver ? [buildAudioPrompt(upcomingManeuver, progress)] : [];

  return {
    packetType: 'SPATIAL_NAVIGATION_PACKET',
    tripId: manifest.sessionId,
    routeId: route.routeId,
    generatedAtMs: currentTripState.timestampMs,
    source: {
      provider: route.provider || 'google',
      routeApiVersion: route.routeApiVersion,
    },
    destination: route.destination,
    progress: {
      currentLocation: currentTripState.location,
      heading: currentTripState.heading,
      speedMps: currentTripState.speedMps,
      routeIndex: progress.routeIndex,
      distanceTraveledMeters: Math.round(progress.distanceTraveledMeters),
      remainingDistanceMeters: Math.round(progress.remainingDistanceMeters),
      etaSeconds: progress.etaSeconds,
      offRoute: progress.offRoute,
    },
    route: {
      polyline: route.polyline,
      steps: route.steps,
      totalDistanceMeters: route.totalDistanceMeters,
      totalDurationSeconds: route.totalDurationSeconds,
    },
    activeManeuver: upcomingManeuver || undefined,
    upcomingManeuvers: prioritizeInstructions(upcomingEvents),
    spatialInstructions,
    audioInstructions,
    routeSemantics: {
      roadName: upcomingManeuver?.roadName,
      highwayName: route.highwayName,
      exitNumber: upcomingManeuver?.exitNumber,
      requiredLane: upcomingManeuver?.requiredLane,
      turnDirection: semanticsTurnDirection(upcomingManeuver?.type),
    },
    handoffHints: {
      confidence: progress.offRoute ? 0.5 : 0.95,
      displayPriority: spatialInstructions.length > 0 ? spatialInstructions[0].priority : 3,
      staleAfterMs: 5000,
    },
  };
}

function semanticsTurnDirection(type) {
  switch (type) {
    case 'TURN_LEFT':
      return 'left';
    case 'TURN_RIGHT':
      return 'right';
    case 'KEEP_LEFT':
      return 'left';
    case 'KEEP_RIGHT':
      return 'right';
    case 'MERGE':
      return 'merge';
    case 'EXIT_HIGHWAY':
      return 'exit';
    default:
      return 'straight';
  }
}

module.exports = {
  validateRoute,
  computeRouteGeometry,
  projectLocationOntoRoute,
  computeRouteProgress,
  buildNavigationEvents,
  getUpcomingManeuver,
  computeInstructionWindow,
  prioritizeInstructions,
  buildSpatialInstruction,
  buildAudioPrompt,
  buildSpatialNavigationPacket,
};
