# Phase 1 Provider Setup

Phase 1 uses a provider abstraction so the demo and production path stay as close as possible.

## 1. Provider selection

Use this environment variable:

```text
PHASE1_ROUTE_PROVIDER=mock|google
```

Default:
- `mock`

If `GOOGLE_MAPS_API_KEY` is set and `PHASE1_ROUTE_PROVIDER` is not set, the demo will prefer `google` automatically.

## 2. Google configuration

If `PHASE1_ROUTE_PROVIDER=google`, set:

```text
GOOGLE_MAPS_API_KEY=your_key_here
```

Google provider behavior:
- Geocodes the destination query
- Fetches driving directions
- Normalizes the response into the same `route.json` shape as the mock provider

## 3. Output contract

Both providers emit the same normalized route snapshot:

```typescript
type RouteSnapshot = {
  routeId: string;
  provider: string;
  origin: { lat: number; lng: number };
  destination: {
    label: string;
    placeId?: string;
    coordinate: { lat: number; lng: number };
  };
  polyline: string;
  geometry: Array<{ lat: number; lng: number }>;
  steps: Array<{
    stepId: string;
    instruction: string;
    maneuver: string;
    distanceMeters: number;
    durationSeconds: number;
    roadName?: string;
    exitNumber?: string;
    requiredLane?: string;
    polyline?: string;
  }>;
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  highwayName?: string | null;
  createdAtMs: number;
};
```

## 4. Demo flow

The demo script does this:

1. Resolve destination
2. Fetch route
3. Generate local `session_manifest.json`, `route.json`, and `trip_state.jsonl`
4. Replay the session through the Phase 1 processor
