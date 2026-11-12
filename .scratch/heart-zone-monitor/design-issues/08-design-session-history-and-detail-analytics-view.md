# Design session history and detail analytics view

Type: grilling
Status: resolved
Blocked by: 04, 05

## Question

How will historical sessions be listed, what summary metrics will be displayed (date, elapsed duration, effective duration, paused duration, average HR, time-in-zone breakdown), and how will the 1Hz sample graph and zone distribution chart be rendered efficiently in Compose?

## Answer

All decisions regarding the historical session list, detail analytics layout, interactive Compose Canvas chart rendering, downsampling algorithm, and data-gap visualization have been resolved through interactive grilling:

### 1. Session History List & Athletic Card Presentation
- **Query Efficiency**: Reads directly from precalculated summary columns in the `sessions` table (`effectiveDurationMs`, `avgBpm`, `maxBpm`, `zone1DurationMs`..`zone5DurationMs`), requiring $O(1)$ sample-free reads with zero table joins.
- **Card Hierarchy**:
  - **Header**: Formatted date & start time (e.g., `Today, 17:00` or `Nov 11, 2026 • 17:00`). If `status == COMPLETED_UNEXPECTEDLY`, displays a subtle warning badge indicating an ungraceful shutdown/reconciliation.
  - **Key Metrics Row**: **Effective Duration** in large bold typography (e.g. `45:12`), accompanied by **Avg BPM** (e.g. `148 BPM`) and **Max BPM** (e.g. `179 BPM`).
  - **Mini Zone Distribution Bar**: A proportional 5-segment horizontal bar displaying relative time spent in Zones 1 through 5 using official zone colors (Slate, Blue, Green, Amber, Red).

### 2. Session Detail Analytics Layout Hierarchy
A unified, single vertically scrolling screen structured from highest-level summary to granular analytical telemetry:
1. **Header Card**: Session start/end timestamp, status badge, connected HRM device name and MAC address.
2. **Hero Telemetry Row**: 4-column glanceable summary: **Effective Duration**, **Avg BPM**, **Max BPM**, and **Min BPM**.
3. **Zone Distribution Card**:
   - Primary 5-segment horizontal proportional bar with clean percentage labels.
   - Granular breakdown table listing for each zone (Z1–Z5): Zone name, BPM range, accumulated duration (`HH:mm:ss`), and percentage of effective duration.
4. **Interactive 1Hz Heart Rate Time-Series Chart**:
   - Full-width custom Compose `Canvas` plotting BPM against elapsed session time.
   - Faint horizontal gridlines marking the user's specific zone threshold boundaries.
5. **Durations & Physiological Snapshot Card**:
   - Secondary durations: **Elapsed Duration** (wall clock) and **Paused Duration**.
   - Profile snapshot frozen at session start: User Age, Resting HR, calculated HRmax, and Calculation Mode (`KARVONEN` or `PERCENT_HRMAX`).
6. **Bottom Action Bar**:
   - `[Export CSV / JSON]` action (triggers Ticket 07 BiometricPrompt + SAF flow).
   - `[Delete Session]` action with double-confirmation dialog (cascading delete of samples).

### 3. High-Performance Compose Canvas Rendering & Downsampling
- **Custom Compose Canvas**: Implemented natively via `drawPath` in Jetpack Compose Canvas, avoiding external heavy charting dependencies.
- **LTTB Downsampling**:
  - If raw sample count exceeds the visual rendering threshold (e.g. > 1,000 points or screen width in dp), the repository/ViewModel applies an $O(N)$ **Largest-Triangle-Three-Buckets (LTTB)** downsampling algorithm down to ~600–800 visual points.
  - Guarantees 60/120fps fluid scrolling and avoids memory/GPU overhead while preserving true peak maximums and valley drops.

### 4. Interactive Touch Scrubbing & Crosshair Tooltip
- **Drag Interaction**: Users can drag a finger horizontally across the Canvas chart.
- **Scrubber Elements**:
  - A vertical indicator line follows the touch position.
  - A dynamic in-graph tooltip displays: relative elapsed time offset (`MM:SS`), instantaneous heart rate (`XXX BPM`), and the active Heart Zone badge with its corresponding background color.
- **Release**: Releasing touch dismisses the crosshair tooltip without locking the parent scroll container.

### 5. Missing Data & Disconnection Gap Visualization
- **Path Discontinuity**: When the gap between two consecutive `HeartRateSample` timestamps exceeds 3 seconds (indicating a paused interval, BLE disconnection, or sensor contact loss), the chart executes `Path.moveTo()` instead of `Path.lineTo()`.
- **Honest Telemetry**: Paused and disconnected intervals render as visual breaks/gaps in the polyline rather than artificial straight-line interpolations, maintaining physical and analytical fidelity.
