# Phase 1 Spatial Navigation Spec

This document defines the first hackathon slice only: **spatial navigation**.

Scope:
- No perception pipeline
- No lane detection
- No object detection
- No depth estimation
- No connected-vehicle logic

The goal is to answer three questions:
1. What data do we take in, in what form, and from where?
2. How do we process that data into useful navigation information?
3. What output should Phase 1 hand to later phases?

---

## 1. Phase 1 Inputs

Phase 1 takes in **route intent + live trip state + map geometry**.

### 1.1 Input types

#### A. Destination intent
Represents where the user wants to go.

Form:
- Free-text destination query, such as a place name, address, or POI
- Optional place metadata if the destination is selected from search results

Example:
```json
{
  "destinationQuery": "Georgia Tech Student Center"
}
```

Source:
- Google Places API or Google Geocoding API for destination resolution
- User input from the app UI

#### B. Current trip state
Represents where the vehicle is right now.

Form:
- Latitude
- Longitude
- Heading
- Speed
- Timestamp
- Optional accuracy fields

Example:
```json
{
  "lat": 33.7768,
  "lng": -84.3982,
  "heading": 92.4,
  "speedMps": 13.2,
  "timestampMs": 1730000000000,
  "accuracyMeters": 4.1
}
```

Source:
- Simulated GPS in the hackathon demo
- Later: real device GPS / vehicle telemetry

#### C. Route data
Represents the path from origin to destination.

Form:
- Polyline geometry
- Route legs
- Route steps
- Maneuver type per step
- Distance and duration per step
- Street names
- Exit / turn metadata
- Optional lane guidance

Source:
- Google Routes API

#### D. Map context
Provides semantic route context needed for instructions.

Form:
- Road names
- Intersections
- Exit numbers
- Place names
- Travel time and distance

Source:
- Google Routes API
- Google Geocoding API
- Google Places API

### 1.2 Recommended source split

Use Google services like this:

- `Google Geocoding API` for resolving a free-text destination into coordinates
- `Google Places API` for destination search and place metadata
- `Google Routes API` for route geometry, steps, maneuvers, and travel estimates

### 1.3 Input abstraction

The code should not depend directly on Google in the rest of the system. It should normalize external data into project-owned structures.

Suggested input interfaces:

```typescript
type GeoCoordinate = {
  lat: number;
  lng: number;
};

type TripState = {
  location: GeoCoordinate;
  heading: number;
  speedMps: number;
  timestampMs: number;
  accuracyMeters?: number;
};

type DestinationRequest = {
  query: string;
};
```

---

## 2. Phase 1 Processing

Phase 1 converts raw route data into **deterministic navigation events** and **spatial cues**.

### 2.1 Core processing steps

#### Step 1: Resolve destination
Turn the user destination query into a place or coordinate.

Functions needed:
- `resolveDestination(query)`
- `normalizePlaceResult(placeResult)`

Output:
- Destination coordinate
- Display label
- Optional place id

#### Step 2: Request route
Fetch route options from the routing provider.

Functions needed:
- `fetchRoute(origin, destination)`
- `selectBestRoute(routes)`
- `normalizeRoute(routeResponse)`

Output:
- One canonical route
- Route id
- Ordered route steps
- Route polyline

#### Step 3: Decode route geometry
Convert route polyline into usable path geometry.

Functions needed:
- `decodePolyline(polyline)`
- `buildRouteGeometry(routePoints)`

Output:
- Point list
- Segments
- Curvature / heading transitions if needed

#### Step 4: Convert steps into navigation events
Transform route steps into a compact event timeline.

Functions needed:
- `classifyManeuver(step)`
- `buildNavigationEvents(routeSteps)`
- `attachDistanceThresholds(events)`

Typical event types:
- `START_ROUTE`
- `GO_STRAIGHT`
- `TURN_LEFT`
- `TURN_RIGHT`
- `KEEP_LEFT`
- `KEEP_RIGHT`
- `MERGE`
- `EXIT_HIGHWAY`
- `ARRIVE`

#### Step 5: Match live trip state to route progress
Determine where the driver is on the route.

Functions needed:
- `projectLocationOntoRoute(location, routeGeometry)`
- `computeRouteProgress(tripState, routeGeometry)`
- `estimateRemainingDistance(progress, routeGeometry)`
- `estimateETA(progress, routeMetadata)`

Output:
- Current route index
- Distance traveled
- Remaining distance
- ETA

#### Step 6: Detect upcoming intent windows
Figure out what should be shown or spoken next.

Functions needed:
- `getUpcomingManeuver(progress, events)`
- `computeInstructionWindow(distanceToManeuver)`
- `prioritizeInstructions(events)`

Output:
- Next maneuver
- Distance to maneuver
- Instruction priority

#### Step 7: Generate spatial instructions
Convert navigation state into display-ready instructions.

Functions needed:
- `buildSpatialInstruction(event, routeProgress)`
- `buildAudioPrompt(event)`
- `buildOverlayAnchor(routeProgress, event)`

Output:
- AR arrow instructions
- Distance labels
- Exit markers
- Lane guidance markers when available from maps

### 2.2 What Phase 1 must compute

Phase 1 should be able to answer these navigation questions deterministically:

- Where am I on the route?
- What is the next maneuver?
- How far away is it?
- What should be shown in the driver’s field of view?
- What should be spoken aloud?
- Am I off route?
- What route event is active right now?

### 2.3 Minimal function set for the hackathon

If the team wants the smallest useful implementation, these functions are the core:

```typescript
resolveDestination()
fetchRoute()
normalizeRoute()
decodePolyline()
buildNavigationEvents()
computeRouteProgress()
getUpcomingManeuver()
buildSpatialInstruction()
buildAudioPrompt()
```

### 2.4 What not to do in Phase 1

Do not try to infer anything from video yet.

Do not create probabilistic driving judgments.

Do not depend on LLM output for the core navigation path.

The navigation layer should be deterministic and explainable.

---

## 3. Phase 1 Output Handoff

Phase 1 should hand off a **route-aware spatial context packet** to later phases.

The next phase will likely add perception. That means the handoff must preserve both:
- the route intention
- the exact navigation state the system currently believes

### 3.1 Output goal

The output should represent the system’s full navigation knowledge at a given moment:

- route identity
- trip progress
- next action
- upcoming action queue
- remaining distance and ETA
- lane / exit expectations if known from maps
- spatial cue definitions for AR and audio

### 3.2 Recommended output type

Use one top-level object, such as:

```typescript
type SpatialNavigationPacket = {
  packetType: "SPATIAL_NAVIGATION_PACKET";
  tripId: string;
  routeId: string;
  generatedAtMs: number;
  source: {
    provider: "google";
    routeApiVersion?: string;
  };
  destination: {
    label: string;
    placeId?: string;
    coordinate: GeoCoordinate;
  };
  progress: {
    currentLocation: GeoCoordinate;
    heading: number;
    speedMps: number;
    routeIndex: number;
    distanceTraveledMeters: number;
    remainingDistanceMeters: number;
    etaSeconds: number;
    offRoute: boolean;
  };
  route: {
    polyline: string;
    steps: NormalizedRouteStep[];
    totalDistanceMeters: number;
    totalDurationSeconds: number;
  };
  activeManeuver?: NavigationEvent;
  upcomingManeuvers: NavigationEvent[];
  spatialInstructions: SpatialInstruction[];
  audioInstructions: AudioInstruction[];
  routeSemantics: {
    roadName?: string;
    highwayName?: string;
    exitNumber?: string;
    requiredLane?: string;
    turnDirection?: "left" | "right" | "straight" | "merge" | "exit";
  };
  handoffHints: {
    confidence: number;
    displayPriority: number;
    staleAfterMs: number;
  };
};
```

### 3.3 Supporting schema pieces

```typescript
type NavigationEvent = {
  eventId: string;
  type:
    | "START_ROUTE"
    | "GO_STRAIGHT"
    | "TURN_LEFT"
    | "TURN_RIGHT"
    | "KEEP_LEFT"
    | "KEEP_RIGHT"
    | "MERGE"
    | "EXIT_HIGHWAY"
    | "ARRIVE";
  distanceMeters: number;
  roadName?: string;
  exitNumber?: string;
  requiredLane?: string;
};

type NormalizedRouteStep = {
  stepId: string;
  instruction: string;
  maneuver:
    | "start"
    | "straight"
    | "left"
    | "right"
    | "keep_left"
    | "keep_right"
    | "merge"
    | "exit"
    | "arrive";
  distanceMeters: number;
  durationSeconds: number;
  roadName?: string;
  exitNumber?: string;
  requiredLane?: string;
  polyline?: string;
};

type SpatialInstruction = {
  id: string;
  type: "TURN_ARROW" | "LANE_ARROW" | "EXIT_MARKER" | "DISTANCE_LABEL" | "WARNING";
  anchor: {
    kind: "ROAD" | "ROUTE_POINT" | "SCREEN";
    x?: number;
    y?: number;
    routeDistanceMeters?: number;
  };
  content: string;
  priority: number;
  lifetimeMs: number;
};

type AudioInstruction = {
  id: string;
  content: string;
  priority: number;
  speakAtDistanceMeters?: number;
};
```

### 3.4 Why this handoff is the right boundary

This schema does not just say "turn right".

It captures the navigation state as a structured object that later phases can fuse with perception and spatial rendering.

That means Phase 2 can add things like:
- lane detection confidence
- vehicle proximity
- traffic light context
- road anchors

without changing the basic route logic.

---

## 4. Summary

Phase 1 should do three things well:

1. Take in destination, current location, and route data from Google services or simulated equivalents.
2. Convert that raw route data into deterministic navigation events and spatial/audio instructions.
3. Emit a structured `SpatialNavigationPacket` that later phases can enrich with perception and spatial reasoning.

The main product value of Phase 1 is:

> turning route guidance into a structured spatial driving context, not just a list of map directions.
