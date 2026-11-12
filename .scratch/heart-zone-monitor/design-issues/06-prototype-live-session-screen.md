# Prototype live session screen with dynamic zone color transitions

Type: prototype
Status: resolved
Blocked by: 01, 05

## Question

How should the live session screen look and behave in Jetpack Compose, specifically the giant BPM typography, smooth color transitions between the 5 zones, elapsed/effective timer displays, and pause/resume/stop control layout?

## Answer

The live session screen design has been prototyped and validated through interactive Compose and browser prototypes, converging on the **Athletic Monolith** archetype with vibrant zone illumination and focused telemetry:

### 1. Visual Archetype & Dynamic Background Transitions
- **Full-Bleed Vibrant Illumination**: The full screen background animates smoothly (`animateColorAsState` over 500ms with `FastOutSlowInEasing`) into the saturated, luminous color of the active **Heart Zone**:
  - **Zone 1 (Recovery)**: Cool Slate (`#64748B`)
  - **Zone 2 (Endurance)**: Vibrant Blue (`#3B82F6`)
  - **Zone 3 (Aerobic)**: Emerald Green (`#10B981`)
  - **Zone 4 (Threshold)**: Vivid Amber (`#F59E0B`)
  - **Zone 5 (VO2 Max)**: High-Intensity Crimson (`#EF4444`)
- Implemented with a vertical gradient from the vibrant zone color to an 82% alpha depth, providing intense color fidelity while maintaining outdoor sunlight contrast.

### 2. Giant Typography & Distraction-Free Telemetry
- **BPM Presentation**: Centered, massive 124sp sans-serif typography (`letterSpacing = -4sp`) readable at a glance from handlebar mounts or armbands.
- **Heart Icon Deleted**: Decorative heartbeat icons removed to preserve a clean, professional aesthetic.
- **Zone Badge Format**: Removed all raw percentage numbers (e.g. no "70-80%"). A clean translucent pill directly beneath the BPM displays: `BPM  |  ZONE X`.

### 3. Zone Progress Bar
- A 5-segment horizontal bar positioned below the BPM indicator represents Zones 1 through 5.
- The active zone segment renders in solid opaque white; past zones render in 55% white; future zones render in translucent black (`alpha = 0.25f`). Simple textual labels (`Z1`, `Z2`, `Z3`, `Z4`, `Z5`) mark each zone.

### 4. Single-Metric Focused Duration
- **Pure Timer**: The session duration card displays exclusively the active **Effective Duration** in 52sp bold monospace typography (`HH:mm:ss` or `mm:ss`).
- **No Secondary Timers**: Elapsed duration and paused duration are omitted from the live recording canvas to eliminate cognitive clutter during workouts (both remain tracked in the database and background service).

### 5. Control Layout & Ergonomics
- Large, high-affordance 56dp action buttons at the bottom:
  - `[PAUSE]` / `[RESUME]` toggle button with high-contrast white border.
  - `[STOP & SAVE]` button with translucent dark container and white text.
- Translucent card containers (`Color.Black.copy(alpha = 0.32f)`) ensure razor-sharp typography against dynamic background colors.

### 6. Prototype Artifacts
- **Jetpack Compose Implementation**: [`LiveSessionPrototype.kt`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/app/src/main/java/com/example/heartzonemonitor/prototype/LiveSessionPrototype.kt) (includes interactive preview and simulator).
- **Interactive Web Companion**: [.scratch/heart-zone-monitor/prototypes/live-session-prototype.html](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/.scratch/heart-zone-monitor/prototypes/live-session-prototype.html) (self-contained HTML simulation).
