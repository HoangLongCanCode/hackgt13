# AI Spatial Driving Copilot — Production Plan

## 1. Product Overview

**AI Spatial Driving Copilot** is an augmented-reality driving assistant that transforms navigation, road perception, vehicle awareness, and eventually connected-vehicle communication into spatial visual and audio cues in the driver's field of view.

The product is not simply "Google Maps on glasses."

It combines:

- Navigation
- Computer vision
- Depth estimation
- Lane and road understanding
- Spatial AR
- Voice/audio guidance
- Driver-awareness features
- Connected-vehicle communication
- Eventually, Meta VR Glasses hardware

### Core product question

The system continuously answers:

> **Where am I going? + What is around me? + What is happening right now?**

and converts the answer into:

> **What should the driver see, hear, and eventually communicate to nearby vehicles?**

---

# 2. High-Level Architecture

```text
                         ┌──────────────────────┐
                         │       INPUTS         │
                         │                      │
                         │ POV Driving Video    │
                         │ GPS / Location       │
                         │ Destination          │
                         │ Map Data             │
                         │ Vehicle Data*        │
                         └──────────┬───────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
          ┌──────────────────┐             ┌──────────────────┐
          │  PERCEPTION      │             │   NAVIGATION     │
          │  ENGINE          │             │   ENGINE         │
          │                  │             │                  │
          │ Vehicles         │             │ Route            │
          │ Lanes            │             │ Turns            │
          │ Traffic lights   │             │ Exits            │
          │ Signs            │             │ Distance         │
          │ Road             │             │ ETA              │
          │ Depth            │             │ Lane guidance    │
          └────────┬─────────┘             └────────┬─────────┘
                   │                                │
                   └───────────────┬────────────────┘
                                   ▼
                         ┌─────────────────────┐
                         │  DRIVING CONTEXT    │
                         │      ENGINE         │
                         │                     │
                         │ "What is happening  │
                         │  right now?"        │
                         └──────────┬──────────┘
                                    │
                     ┌──────────────┼──────────────┐
                     ▼                             ▼
           ┌──────────────────┐         ┌──────────────────┐
           │ SPATIAL AR       │         │ AUDIO ENGINE     │
           │ ENGINE           │         │                  │
           │                  │         │ ElevenLabs       │
           │ Road anchors     │         │ Alerts           │
           │ Arrows           │         │ Instructions     │
           │ Lane highlights  │         │ Social voice     │
           │ Distance labels  │         └────────┬─────────┘
           └────────┬─────────┘                  │
                    │                            │
                    └──────────────┬─────────────┘
                                   ▼
                         ┌─────────────────────┐
                         │   GLASSES OUTPUT    │
                         │                     │
                         │ AR                  │
                         │ Audio               │
                         │ Future haptics      │
                         └─────────────────────┘

                       * future connected vehicle
```

---

# 3. Input System

The system should use an abstraction around all input sources.

## 3.1 POV Driving Video

The initial product uses recorded driving videos as the simulated camera input.

Examples:

- Highway driving
- City driving
- Intersections
- Lane changes
- Highway exits
- Merging
- Night driving
- Heavy traffic
- Complex intersections

The video represents:

> **The visual field of the glasses.**

## 3.2 GPS / Location

Eventually the system receives:

- Latitude
- Longitude
- Heading
- Speed
- Timestamp

For simulation, these values can be generated from a scenario timeline.

## 3.3 Destination

The user selects a destination.

```text
Current Location
       ↓
Destination
       ↓
Navigation Engine
```

## 3.4 Future Hardware Input

Eventually:

```text
Meta VR Glasses
       ↓
Camera / Tracking / Location
       ↓
Driving Copilot
```

The rest of the system should not care whether frames came from a video file or real glasses.

Recommended abstraction:

```typescript
interface VisionInput {
    getFrame(): Frame;
}
```

Possible implementations:

```text
VideoFileInput
LiveCameraInput
MetaGlassesCameraInput
```

---

# 4. Navigation Engine

## Primary technology

### Google Maps Platform

Potential services:

- Google Routes API
- Google Geocoding API
- Google Places APIs
- Google Maps Platform
- Google Navigation SDK for future navigation integration

Google's Routes API can provide route steps, maneuvers, distances, durations, and polylines.

### Architectural rule

Google Maps provides **routing data**, not the final AR user interface.

```text
Google Routes API
        ↓
Navigation Engine
        ↓
Our navigation representation
        ↓
Spatial AR / Audio
```

Example internal representation:

```json
{
  "event": "TURN_RIGHT",
  "distanceMeters": 243,
  "street": "University Blvd",
  "requiredLane": "right"
}
```

---

# 5. Navigation Functions

## Basic navigation

- Turn left
- Turn right
- Continue straight
- U-turn
- Roundabout
- Destination arrival

## Highway navigation

- Enter highway
- Exit highway
- Exit number
- Distance to exit
- Highway name
- Upcoming interchange
- Lane required for exit

Example:

```text
EXIT 23B
0.4 mi
```

## Lane guidance

```text
┌───┬───┬───┐
│ ← │ ↑ │ → │
│   │   │ ★ │
└───┴───┴───┘
        RIGHT
```

## Merge guidance

```text
MERGE LEFT
800 ft
```

## Re-routing

If the vehicle deviates:

```text
Route deviation detected
        ↓
Recalculate
        ↓
New route
```

---

# 6. Computer Vision / Perception Engine

The Perception Engine is the "eyes" of the system.

## Primary model candidates

### Ultralytics YOLO

Potential uses:

- Object detection
- Object tracking
- Segmentation
- Depth estimation
- Classification

The initial system should favor pretrained models rather than training everything from zero.

---

# 7. Vehicle Detection

Detect:

- Cars
- Trucks
- Buses
- Motorcycles
- Bicycles
- Pedestrians

Example output:

```json
{
  "id": 17,
  "class": "car",
  "bbox": [x1, y1, x2, y2],
  "confidence": 0.94
}
```

---

# 8. Object Tracking

Detection alone is insufficient.

The system must recognize that the same vehicle across multiple frames is one object.

Candidate technologies:

- ByteTrack
- BoT-SORT
- YOLO tracking

Example:

```text
Frame 1 → Car #17
Frame 2 → Car #17
Frame 3 → Car #17
```

This enables:

- Vehicle trajectory
- Relative movement
- Approaching vehicle detection
- Vehicle leaving a lane
- Relative velocity estimation

---

# 9. Depth Estimation

Depth enables features such as:

> "The car ahead is 18 meters away."

Candidate technologies:

- Ultralytics depth models
- Depth Anything
- MiDaS
- Other monocular depth models

Pipeline:

```text
Video Frame
     │
     ├──────────→ YOLO → Vehicles
     │
     ▼
Depth Model
     │
     ▼
Depth Map
     │
     ▼
Vehicle Distance
```

Example:

```json
{
  "vehicleId": 17,
  "distanceMeters": 18.4
}
```

---

# 10. Lane Detection

The system needs to understand:

```text
LEFT LANE
CURRENT LANE
RIGHT LANE
```

Candidate technologies:

- Lane detection models
- Semantic segmentation
- OpenCV
- Perspective transforms

Example output:

```json
{
  "currentLane": 2,
  "laneCount": 3,
  "laneBoundaries": []
}
```

Navigation can then determine:

```text
Exit requires lane 3
```

and the AR system can highlight the correct lane.

---

# 11. Traffic-Light Detection

Detect:

- Red
- Yellow
- Green

Potential output:

```text
RED
120m
```

The system can use this for contextual visual and audio alerts.

---

# 12. Traffic-Sign Detection

Potential classes:

- Stop
- Yield
- Speed limit
- Do not enter
- Exit
- Merge
- Construction
- Road work

These become part of the Road Understanding Engine.

---

# 13. Road Segmentation

The system should understand:

- Road
- Lane
- Sidewalk
- Grass
- Buildings
- Vehicles
- Pedestrians

Candidate technologies:

- YOLO segmentation
- SAM-family models
- Specialized road segmentation models

The purpose is not only object recognition.

Road geometry helps determine:

> **Where should the AR content be anchored?**

---

# 14. Autonomous-Driving Research Ecosystem

The project should leverage open autonomous-driving research rather than attempting to download proprietary production systems from companies such as Tesla or Waymo.

Relevant ecosystems and datasets include:

- KITTI
- nuScenes
- Waymo Open Dataset
- BDD100K
- Argoverse

Potential applications:

- 3D detection
- Vehicle tracking
- Lane perception
- Depth
- Road understanding
- Driving-scene benchmarks

The goal is to reuse pretrained research models and datasets where appropriate and focus engineering effort on perception + navigation + spatial fusion.

---

# 15. Driving Context Engine

This is the central intelligence layer.

It combines:

```text
Navigation
     +
Perception
     +
Depth
     +
Lane understanding
     +
Vehicle state
```

and answers:

> **What is happening right now, and what should the driver be shown/heard?**

Example:

```text
Upcoming right turn
+
Current vehicle is in left lane
+
Right lane available
+
Distance = 400m
```

Output:

```text
PREPARE TO MOVE RIGHT
```

---

# 16. Deterministic Driving Logic

Core driving instructions should **not depend on an LLM**.

Bad:

```text
Video
 ↓
LLM
 ↓
"Maybe turn right?"
```

Preferred:

```text
Map
 +
Vision
 +
Rules
 ↓
Driving Context Engine
 ↓
Deterministic event
```

Possible driving events:

```text
KEEP_LANE
CHANGE_LANE
MERGE_LEFT
MERGE_RIGHT
TURN_LEFT
TURN_RIGHT
EXIT
ENTER_HIGHWAY
FOLLOW_ROAD
STOP
ARRIVE
```

---

# 17. Driving-Assistance Functions

The product can provide:

## Navigation assistance

- Next turn
- Turn distance
- Lane required
- Exit distance
- Highway entrance
- Highway exit
- ETA
- Route deviation
- Re-routing

## Road awareness

- Vehicle ahead
- Vehicle distance
- Relative movement
- Lane occupancy
- Traffic lights
- Road signs
- Pedestrians
- Road geometry

## Contextual assistance

- Prepare lane change
- Merge guidance
- Exit-lane guidance
- Following-distance display
- Vehicle proximity warning
- Upcoming intersection
- Upcoming traffic signal

---

# 18. Following-Distance System

Example:

```text
YOU
 ↓
12.4m
 ↓
🚗
```

Possible internal state:

```typescript
enum FollowingState {
    NORMAL,
    CLOSE,
    CRITICAL
}
```

The system should initially display measurable information rather than making unsupported claims such as "safe distance."

Example:

```text
DISTANCE
4.2m
```

Safety-critical behavior should remain a separate, validated layer if this ever moves beyond a prototype.

---

# 19. Spatial AR Engine

This is one of the most important technical systems.

The system converts:

```text
World / Road Position
        ↓
Camera Coordinate
        ↓
Headset / View Coordinate
        ↓
Display Coordinate
```

The simulation can approximate this with:

- OpenCV
- Homography
- Perspective transformation
- Camera calibration
- Spatial anchors
- Road geometry
- 3D transforms

The future hardware implementation can use Meta's spatial APIs and environment understanding.

---

# 20. AR Objects

The renderer should support reusable spatial primitives.

## Navigation arrow

```text
        ↗
      ↗
    ↗
  ↗
```

## Lane arrow

```text
────────────
     ↗
    ↗
────────────
```

## Vehicle marker

```text
CAR
18m
```

## Exit marker

```text
EXIT 23B
0.4 mi
```

## Traffic-light marker

```text
RED
120m
```

## Warning

```text
VEHICLE
TOO CLOSE
```

---

# 21. Spatial Instruction API

The AR renderer should receive generic spatial instructions instead of knowing about Google Maps.

Example:

```typescript
type SpatialInstruction = {
    type:
        | "TURN_ARROW"
        | "LANE_ARROW"
        | "EXIT_MARKER"
        | "VEHICLE_MARKER"
        | "DISTANCE_LABEL"
        | "WARNING";

    anchor: SpatialAnchor;

    content: string;

    lifetime: number;
};
```

Flow:

```text
Driving Context
      ↓
Spatial Instruction
      ↓
AR Renderer
      ↓
Simulation / Meta Glasses
```

---

# 22. Audio Engine

Audio is a first-class output alongside AR.

```text
Driving Event
      │
      ├────────→ AR Renderer
      │
      └────────→ Audio Engine
```

Example event:

```json
{
  "event": "MERGE_LEFT",
  "distanceMeters": 243
}
```

Visual:

```text
MERGE LEFT
800 FT
```

Audio:

> "Merge left in 800 feet."

---

# 23. ElevenLabs Integration

Use ElevenLabs as the initial voice provider.

Recommended abstraction:

```typescript
interface VoiceProvider {
    speak(text: string): Promise<void>;
    stop(): void;
}
```

Possible implementations:

```text
VoiceProvider
├── ElevenLabs
├── Local TTS
└── Native TTS
```

The navigation engine generates deterministic text.

ElevenLabs only handles natural voice generation.

---

# 24. Voice Priority System

Multiple events can happen simultaneously, so audio requires prioritization.

Recommended priority:

```text
CRITICAL SAFETY
      ↓
TRAFFIC ALERT
      ↓
IMMEDIATE NAVIGATION
      ↓
UPCOMING NAVIGATION
      ↓
GENERAL INFORMATION
      ↓
SOCIAL
```

The system should avoid speaking five messages at the same time.

---

# 25. Optional LLM / VLM Layer

LLMs and VLMs should be optional supporting systems.

Potential uses:

## Explanation

> "Why do I need to change lanes?"

## Road-sign explanation

> "What does that sign mean?"

## Natural-language social intent

> "Can I get over?"

↓

```text
MERGE_REQUEST
```

## Conversational interaction

The LLM should **not** determine core navigation or safety-critical driving actions.

---

# 26. Social / Connected Driving

This is an advanced subsystem that sits beside the Driving Context Engine.

Concept:

```text
                CLOUD
             /    |    \
            /     |     \
          Car A  Car B  Car C
```

Each connected vehicle can have:

```typescript
interface VehicleState {
    id: string;
    location: GeoCoordinate;
    heading: number;
    speed: number;
    lane?: number;
    intent?: VehicleIntent;
}
```

---

# 27. Vehicle Intent

Potential intents:

```typescript
enum VehicleIntent {
    KEEP_LANE,
    TURN_LEFT,
    TURN_RIGHT,
    MERGE_LEFT,
    MERGE_RIGHT,
    OVERTAKE,
    STOP,
    PARK
}
```

Instead of only exchanging voice, vehicles can exchange structured driving intent.

Example:

```text
Vehicle A
    │
    │ OVERTAKE_REQUEST
    ▼
Vehicle B
```

B sees:

```text
Vehicle behind you
requests to overtake
```

Possible responses:

```text
ALLOW
IGNORE
DECLINE
```

---

# 28. Natural-Language Social Interaction

A driver could say:

> "Can I go over?"

Speech pipeline:

```text
Speech
  ↓
Speech-to-Text
  ↓
Intent Classification
  ↓
OVERTAKE_REQUEST
```

Other possible phrases:

```text
"Can I pass?"
"Can you let me merge?"
"Mind if I get over?"
```

can map to:

```text
MERGE_REQUEST
```

The important distinction is:

> Human language is converted into a constrained structured intent.

---

# 29. Real-Time Vehicle Communication

For an early prototype:

- WebSockets
- REST for non-realtime data
- WebRTC for realtime voice if required

Potential production infrastructure:

- WebSocket gateway
- Redis
- PostgreSQL
- PostGIS
- Cloud Pub/Sub or equivalent

The backend can determine:

- Which vehicles are nearby
- Which vehicles are ahead
- Which are behind
- Which are traveling in the same direction
- Potentially which are in the same lane

---

# 30. Vehicle Privacy

Nearby drivers should not necessarily see personal identity.

Prefer:

```text
Vehicle #A7F2
```

instead of:

```text
John Smith
```

The system should minimize personally identifying information shared between vehicles.

---

# 31. Simulation System

The simulation environment is a first-class development environment, not a throwaway workaround.

```text
Simulation Engine
├── Video
├── Timeline
├── GPS
├── Navigation
├── Perception
├── AR
└── Audio
```

Example scenarios:

### Highway Exit

```text
Cruise
 ↓
Exit approaching
 ↓
Move right
 ↓
Exit 23B
 ↓
Take exit
```

### City Intersection

```text
Traffic light
 ↓
Intersection
 ↓
Turn right
```

### Lane Change

```text
Current lane
 ↓
Navigation requires right lane
 ↓
Detect gap
 ↓
Guidance
```

### Following Distance

```text
Car ahead
 ↓
Distance decreases
 ↓
Contextual alert
```

### Connected Driving

```text
Vehicle behind
 ↓
Overtake request
 ↓
Driver accepts
 ↓
Both systems update
```

---

# 32. Scenario Timeline

Every simulation should have structured metadata.

Example:

```json
{
  "scenario": "highway_exit_01",
  "video": "highway.mp4",
  "events": [
    {
      "time": 8,
      "type": "NAVIGATION",
      "action": "MERGE_LEFT",
      "distance": 500
    },
    {
      "time": 15,
      "type": "VEHICLE_DETECTED",
      "distance": 18
    },
    {
      "time": 28,
      "type": "EXIT",
      "exit": "23B",
      "distance": 250
    }
  ]
}
```

This keeps demonstrations deterministic and makes system behavior reproducible.

---

# 33. User Experience Modes

## Glass Mode

Full-screen simulation designed to feel like looking through the glasses.

Shows:

- Road
- AR arrows
- Lane guidance
- Vehicle markers
- Exit information
- Minimal HUD

## Debug Mode

Shows:

- Video
- Detection boxes
- Tracking IDs
- Depth
- Lane boundaries
- Navigation state
- Spatial anchors
- Current simulation time
- Event queue

## Scenario Mode

User selects:

- Highway Exit
- City Intersection
- Lane Change
- Night Driving
- Following Distance
- Connected Driving

Then starts the simulation.

---

# 34. Hardware Abstraction

Hardware should be replaceable.

```text
                    Product
                       │
                Hardware Interface
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      Simulation   Meta VR      Future HW
                     Glasses
```

Similarly:

```text
VoiceProvider
├── ElevenLabs
└── LocalTTS

VisionInput
├── Video
└── MetaCamera

LocationProvider
├── SimulatedGPS
└── RealGPS
```

This allows real hardware to replace simulation inputs without rewriting the core product.

---

# 35. Meta VR Glasses Target

The final target platform is Meta VR Glasses.

The current Meta developer ecosystem provides Horizon OS development resources and SDK support for VR Glasses, including Unity and other development paths. Meta also provides simulator/development workflows before hardware is available.

The architecture should therefore isolate Meta-specific functionality behind a hardware/spatial interface.

Potential future components:

- Meta Horizon OS
- Meta SDK
- Unity
- Android/Kotlin where required
- Meta spatial/environment APIs
- Camera input
- Tracking
- Passthrough
- Environment depth
- Eye/hand interaction

---

# 36. Technology Stack

| System | Primary Technology |
|---|---|
| Frontend / Simulation | React + TypeScript |
| Video | HTML5 Video |
| 2D AR simulation | Canvas |
| 3D spatial simulation | Three.js |
| Computer Vision | Python |
| Object Detection | Ultralytics YOLO |
| Tracking | ByteTrack / BoT-SORT |
| Depth | YOLO depth / Depth Anything |
| Lane Understanding | Lane models + segmentation |
| Image Processing | OpenCV |
| Navigation | Google Routes API |
| Geocoding | Google Geocoding API |
| Places | Google Places APIs |
| Maps | Google Maps Platform |
| Voice | ElevenLabs |
| Backend | FastAPI / Python |
| Realtime | WebSocket |
| Database | PostgreSQL |
| Geospatial Database | PostGIS |
| Cache / Realtime State | Redis |
| ML Framework | PyTorch |
| Model Export | ONNX |
| Edge Optimization | TensorRT where appropriate |
| LLM/VLM | Optional |
| Hardware Target | Meta VR Glasses |
| Meta Development | Horizon OS / Meta SDK / Unity |
| Containers | Docker |
| Cloud | AWS / GCP / Azure |
| Monitoring | OpenTelemetry + cloud monitoring |

---

# 37. Model Strategy

The project should follow a three-level strategy.

## Level 1 — Pretrained Models

Use existing models for:

- Object detection
- Tracking
- Depth
- Segmentation
- Lane perception

## Level 2 — Fine-Tuning

Fine-tune only where generic models are insufficient.

Potential targets:

- Lane detection
- Traffic signs
- Driving-specific classes
- Specialized road environments

## Level 3 — Custom Models

Build custom models only when there is a clear product requirement.

The goal is not to train an autonomous-driving stack from scratch.

The goal is to build the **system that fuses existing perception and navigation capabilities into a spatial driving experience**.

---

# 38. Safety Boundary

This project is a driving-assistance visualization prototype.

It should not:

- Control steering
- Control braking
- Control acceleration
- Claim autonomous driving capability
- Make unsupported safety guarantees
- Depend on an LLM for safety-critical decisions

The system should initially present information and alerts to the driver.

Any future real-world deployment would require substantially more validation, testing, safety engineering, hardware integration, and regulatory consideration.

---

# 39. Product Roadmap

## Phase 1 — Spatial Navigation

Core:

- POV driving video
- Google route
- Turn guidance
- Lane arrows
- Exit guidance
- Distance
- AR overlay
- ElevenLabs audio

Goal:

> Prove that navigation can feel native to the driver's visual field.

---

## Phase 2 — Road Perception

Add:

- YOLO
- Object tracking
- Depth
- Lane detection
- Traffic lights
- Traffic signs
- Vehicle distance
- Road segmentation

Goal:

> The system doesn't just know where the driver is going; it understands the road.

---

## Phase 3 — Intelligent Driving Context

Fuse:

```text
MAP
+
VISION
+
DEPTH
+
LANE
```

into:

```text
DRIVING CONTEXT
```

Add:

- Prepare lane change
- Maintain lane
- Exit-lane guidance
- Merge awareness
- Vehicle proximity
- Contextual alerts

Goal:

> Turn raw perception + navigation into useful driver assistance.

---

## Phase 4 — Connected Driving

Add:

- Real-time vehicle presence
- Vehicle identity
- Vehicle intent
- Merge requests
- Overtaking requests
- Driver-to-driver communication
- Structured social events
- Realtime voice

Goal:

> Turn the product from a single-driver assistant into a connected driving network.

---

## Phase 5 — Meta VR Glasses

Replace:

```text
Recorded Video
```

with:

```text
Live Glasses Input
```

while preserving:

```text
Navigation
Perception
Context
Spatial
Audio
Social
```

Goal:

> Move the complete spatial driving copilot from simulation to actual glasses.

---

# 40. Final Product Architecture

```text
                         AI SPATIAL
                      DRIVING COPILOT
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
         ▼                    ▼                    ▼
    NAVIGATION            PERCEPTION           CONNECTED
      ENGINE                ENGINE              DRIVING
         │                    │                    │
         │              ┌─────┼─────┐              │
         │              │     │     │              │
      Google          Object Depth Lane          Vehicle
      Routes          Detection       Detection   Identity
         │              │     │     │              │
         │              └─────┼─────┘              │
         │                    │                    │
         └────────────────────┼────────────────────┘
                              ▼
                    DRIVING CONTEXT
                         ENGINE
                              │
                 ┌────────────┴────────────┐
                 ▼                         ▼
           SPATIAL ENGINE             AUDIO ENGINE
                 │                         │
            AR Renderer               ElevenLabs
                 │                         │
                 └────────────┬────────────┘
                              ▼
                         USER / DRIVER
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
                 VISUAL               AUDIO
                GLASSES              GLASSES


        ─────────────────────────────────────────────

                    SIMULATION LAYER
                         │
               ┌─────────┴─────────┐
               ▼                   ▼
          Driving Video        Scenario Timeline
               │                   │
               └─────────┬─────────┘
                         ▼
                    SAME CORE


        ─────────────────────────────────────────────

                    FUTURE HARDWARE
                         │
                    Meta VR Glasses
                         │
                         ▼
                    Real Camera
                    Real Location
                    Real Spatial Data
```

---

# 41. Core Product Principle

The product's differentiation is **not** any individual technology.

Google Maps is not the product.

YOLO is not the product.

ElevenLabs is not the product.

Meta VR Glasses are not the product.

The core product is:

```text
                 WHERE AM I GOING?
                         +
                 WHAT DO I SEE?
                         +
               WHAT IS HAPPENING?
                         ↓
                DRIVING CONTEXT
                         ↓
             ┌───────────┴───────────┐
             ↓                       ↓
       SPATIAL VISUAL            AUDIO
             ↓                       ↓
          GLASSES                DRIVER
```

That **Driving Context → Spatial Experience** layer is where the system becomes a genuine spatial driving copilot rather than simply another navigation application.
