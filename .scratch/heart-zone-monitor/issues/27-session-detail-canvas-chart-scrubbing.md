# 27: Session Detail: Interactive Compose Canvas Chart & Touch Scrubbing

**What to build:** The interactive time-series heart rate chart in the Session Detail view, built natively with Jetpack Compose Canvas, featuring faint horizontal zone threshold lines and touch drag scrubbing with crosshair indicators and tooltips.

**Blocked by:** 25 (Session Detail View: Telemetry & Zone Distribution), 26 (LTTB Downsampling Algorithm for Time-Series Samples)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Session Detail & Analytics Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#5-session-detail--analytics-screen)
- Visual Mockup: [`docs/design/stitch/05-session-detail/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/screen.png)
- HTML Prototype: [`docs/design/stitch/05-session-detail/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/code.html)

- [x] Renders a native full-width Compose `Canvas` drawing the 1 Hz heart rate polyline across elapsed session time using LTTB downsampling when sample count exceeds 1,000 points.
- [x] Draws faint horizontal gridlines indicating the session's specific Heart Zone threshold boundaries (50%, 60%, 70%, 80%, 90% of HRmax).
- [x] Implements touch scrubbing: dragging a finger horizontally draws a vertical crosshair line at the touch position.
- [x] Displays a dynamic floating tooltip showing: relative elapsed time offset (`MM:SS`), instantaneous heart rate (`XXX BPM`), and the active Heart Zone badge with its corresponding background color.
- [x] Lifting touch dismisses the crosshair indicator and tooltip smoothly.
- [x] UI tests verify scrubbing touch coordinate mapping and tooltip content formatting.

## Answer

Implemented the native Compose `Canvas` heart rate time-series chart and interactive touch scrubbing in the Session Detail screen:

- **`SessionHeartRateChartModel.kt`** (`app/src/main/java/com/example/heartzonemonitor/ui/history/SessionHeartRateChartModel.kt`):
  - Added `ZoneThresholdLine`, `SessionChartModel`, `ChartPlotBounds`, `ScrubbedSampleReadout`, `buildSessionChartModel`, `formatElapsedMmSs`, and `ChartProjection`.
  - Applies `downsampleLttb(samples, 800)` automatically when raw sample count exceeds `1,000`, computes Y-axis BPM bounds covering all 5 frozen zone thresholds and sample extrema with padding, projects X/Y coordinates and threshold-aligned vertical gradient stops, and maps horizontal touch X via binary search to the nearest sample readout.
- **`SessionDetailUiState.kt` & `MainActivity.kt`**:
  - Extended `SessionDetailViewModel` with optional `sampleDao: HeartRateSampleDao? = null` (wired to `database.heartRateSampleDao()` in `MainActivity`) observing `observeSamplesForSession(sessionId)` and exposing `SessionDetailModel.chart` with frozen Karvonen / `%HRmax` threshold lines.
- **`SessionHeartRateChartCard.kt` & `SessionDetailScreen.kt`**:
  - Built Athletic Monolith `SessionHeartRateChartCard` rendering a full-width native Compose `Canvas` with threshold-aligned zone gradient polyline + subtle gradient fill, faint dashed horizontal Z1–Z5 threshold gridlines and Y-axis labels, X-axis elapsed `MM:SS` labels, empty-sample state, and horizontal-drag scrubbing (`detectHorizontalDragGestures`) with vertical dashed crosshair + highlighted point marker and a horizontally-clamped floating tooltip pill (`MM:SS · XXX BPM · ZONE X` with official zone background color) that fades out smoothly on touch release without locking vertical scroll.
- **Verification**:
  - Verified pure projection, LTTB threshold, gradient stop alignment, and scrubber coordinate mapping in `SessionHeartRateChartGeometryTest`; reactive sample observation and frozen thresholds in `SessionDetailViewModelTest`; and Canvas rendering, threshold labels, horizontal drag scrubbing, tooltip formatting/zone colors, dismissal, and parent scroll freedom in `SessionHeartRateChartTest` plus full `SessionDetailScreenTest` and `:app:lintDebug`.
