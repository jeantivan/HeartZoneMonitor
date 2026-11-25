# 25: Session Detail View: Telemetry & Zone Distribution

**What to build:** The upper analytical sections of the unified vertical Session Detail view, displaying session metadata, hero telemetry cards, a proportional zone bar with granular breakdown table, and frozen profile snapshot metrics.

**Blocked by:** 24 (Session History List & O(1) Athletic Cards)

**Status:** resolved

**Design Specification & Mockups:**
- Screen Spec: [Session Detail & Analytics Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#5-session-detail--analytics-screen)
- Visual Mockup: [`docs/design/stitch/05-session-detail/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/screen.png)
- HTML Prototype: [`docs/design/stitch/05-session-detail/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/05-session-detail/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [x] Displays a top Header Card with formatted start and end timestamps, session completion status badge, and connected sensor metadata (name, MAC address).
- [x] Displays a 4-column Hero Telemetry row: `Effective Duration`, Average BPM, Maximum BPM, and Minimum BPM.
- [x] Displays a Zone Distribution Card featuring:
  - A primary 5-segment horizontal proportional bar with percentage labels.
  - A breakdown table listing each zone (Z1–Z5): zone name, BPM boundary range, duration spent (`HH:mm:ss`), and percentage of Effective Duration.
- [x] Displays a Durations & Physiological Snapshot Card showing: `Elapsed Duration` (wall-clock), `Paused Duration`, and the frozen profile parameters at session start (age, resting HR, calculated HRmax, calculation mode).
- [x] UI tests verify metric formatting and mathematical consistency between total duration and zone breakdowns.

## Answer

Implemented the upper analytical sections of the unified vertical Session Detail screen, ViewModel, and navigation wiring:

- **`SessionDetailUiState.kt`** (`app/src/main/java/com/example/heartzonemonitor/ui/history/SessionDetailUiState.kt`):
  - Added `SessionDetailViewModel(sessionId, sessionDao, rememberedHrmDao, zoneCalculator, zoneId, locale)` observing `SessionDao.observeSessionById(sessionId)` and optional `RememberedHrmDao.observeAllHrms()`.
  - Maps `SessionEntity` into `SessionDetailModel` with formatted date and start/end timestamps (`--` when `endEpochMs == null`), completion status (`COMPLETED` vs `COMPLETED_UNEXPECTEDLY`), reactive Remembered HRM display name + preserved uppercase MAC address (`"Unknown HRM"` fallback when forgotten), `HH:mm:ss` duration formatting (`Effective Duration`, `Elapsed Duration`, `Paused Duration`, and `Z1`–`Z5` durations), frozen Z1–Z5 BPM boundaries via `ZoneCalculator` honoring `calculatedHrmax` and `calculationMode`, and Largest-Remainder zone percentage apportionment (`computeZonePercentages`) so non-zero zones sum cleanly to `100%` without rounding drift.
  - Bundled screen callbacks into `SessionDetailActions(onNavigateBack)`.
- **`SessionDetailScreen.kt`** (`app/src/main/java/com/example/heartzonemonitor/ui/history/SessionDetailScreen.kt`):
  - Separated stateful `SessionDetailScreen` and stateless `SessionDetailContent(uiState, actions, modifier)` using `ui.theme` tokens exclusively and strict `GLOSSARY.md` terminology.
  - Renders Top Bar (`SESSION DETAIL`, formatted date, 56dp Back button), Header Card (start/end timestamps, emerald `COMPLETED` / amber `UNEXPECTED STOP` badge, HRM name + MAC address), 4-column Hero Telemetry Card (`EFFECTIVE`, `AVG HR`, `MAX HR`, `MIN HR`), Zone Distribution Card (dominant zone badge, 5-segment proportional bar with zero-zone omission and all-zero muted bar safety, `Z1`–`Z5` percentage labels, and 5-row Z1–Z5 breakdown table), and Durations & Frozen Profile Snapshot Card (`Elapsed Duration`, `Paused Duration`, `User Age`, `Resting HR`, `Calculation Mode`, `Calculated HRmax`).
- **Navigation & Activity Wiring**:
  - Replaced `SessionDetailPlaceholder` in `SessionHistoryScreen.kt` and `RootNavHost.kt` with `SessionDetailScreen` / `SessionDetailContent` via `sessionDetailViewModelFactory`, wired in `MainActivity.kt`.
- **Verification**:
  - `SessionDetailViewModelTest`: 3 JVM unit tests covering timestamps, reactive HRM fallback, Karvonen custom HRmax override vs `%HRmax` boundaries, Largest-Remainder percentage apportionment, zero-zone sessions, and missing-session state.
  - `SessionDetailScreenTest` & `SessionHistoryScreenTest`: Robolectric Compose UI tests verifying all cards, metric formatting, rendered `HH:mm:ss` mathematical consistency (`sum(Z1..Z5) == Effective Duration`, `Effective Duration + Paused Duration == Elapsed Duration`, `sum(Z1%..Z5%) == 100%`), glossary compliance, and `RootNavHost` History -> Session Detail -> Back navigation.
