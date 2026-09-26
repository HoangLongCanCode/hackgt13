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

Put the key in `spatial/.env` (gitignored; template: `spatial/.env.example`). It is read on the laptop only, by the
demo scripts and by the relay the perception server starts; it never goes into the tablet app.

```text
GOOGLE_MAPS_API_KEY=your_key_here
GOOGLE_ROUTES_API=routes        # optional: routes (default) | directions
```

In Google Cloud Console (billing on): enable the **Geocoding API** and the **Routes API** for the key's project, and
restrict the key to those two APIs. Check the key with one real request each:

```text
node spatial/scripts/check-google-key.js "Piedmont Park, Atlanta" 33.7756,-84.3963
```

Google provider behavior:
- Geocodes the destination query (Geocoding API)
- Fetches the driving route with the Routes API (`computeRoutes`, key in the `X-Goog-Api-Key` header). Google closed
  the legacy Directions API to new projects in March 2025, so it is only the fallback: a 403 / 404 from the Routes
  API (not enabled) retries with the Directions API; `GOOGLE_ROUTES_API=directions` uses it directly.
- Normalizes the response into the same `route.json` shape as the mock provider: maneuvers (`TURN_LEFT`, `RAMP_RIGHT`,
  `FORK_LEFT`... -> left / keep_right / keep_left...), the street after "onto" / "on" as `roadName`, `exitNumber` from
  "exit 250", the encoded overview polyline (the tablet's route map draws it)
- Errors never carry the key (URLs are redacted as `key=REDACTED`)

Offline test (fake responses, no key): `node spatial/scripts/test-google-provider.js`.

Live navigation from the tablet: start the perception server with `--nav-live --nav-provider google` and type the
destination in the tablet's settings (LIVE); it sends `client.destination`, the route is built from the tablet's GPS.

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
