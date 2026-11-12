# Design System & UI Architecture: Athletic Monolith

This document defines the visual archetype, design tokens, ergonomic requirements, motion specifications, and Jetpack Compose translation patterns for **HeartZoneMonitor**.

---

## 1. Visual Archetype: The Athletic Monolith

HeartZoneMonitor is designed for athletes operating at maximum cardiovascular exertion outdoors under harsh, direct sunlight. 

### Core Tenets:
1. **Glanceable Over Decorative**: No pulsing heart icons, decorative waveform animations, or secondary statistical noise during active exercise. At 175 BPM in a sprint, cognitive bandwidth is near zero.
2. **Atmospheric Zone Immersion**: The active Heart Zone dictates the physical ambiance of the screen. Color changes are ambient, saturated, and impossible to mistake in peripheral vision.
3. **Monolithic Typography**: Primary telemetry (BPM) commands the visual hierarchy with massive, heavyweight numerals (124sp). Secondary timer telemetry (52sp) uses fixed-pitch tabular numbers to eliminate jitter.
4. **Sweaty Hands / Glove Affordance**: All interactive touch targets on active workout surfaces maintain a minimum height/width of **56dp** with high-contrast tactile feedback borders.

---

## 2. Design Tokens & Color Palette

### 2.1 Heart Zone Colors (Physiological Identity)
The app uses a 5-zone chromatic progression grounded in physiological intensity:

| Zone | Label | Physiological Meaning | Hex Code | Compose Color |
|---|---|---|---|---|
| **Zone 1** | Recovery | Active Recovery / Warmup (50–60%) | `#64748B` | `Color(0xFF64748B)` *(Slate 500)* |
| **Zone 2** | Aerobic Base | FatMax / Aerobic Threshold LT1 (60–70%) | `#3B82F6` | `Color(0xFF3B82F6)` *(Blue 500)* |
| **Zone 3** | Tempo | Aerobic Endurance / Steady State (70–80%) | `#10B981` | `Color(0xFF10B981)` *(Emerald 500)* |
| **Zone 4** | Threshold | Lactate Threshold LT2 / MLSS (80–90%) | `#F59E0B` | `Color(0xFFF59E0B)` *(Amber 500)* |
| **Zone 5** | Anaerobic | VO2max / Peak Effort (90–100%) | `#EF4444` | `Color(0xFFEF4444)` *(Crimson 500)* |

### 2.2 System & Surface Palette (OLED High-Contrast Dark)
All screens adhere to an OLED-optimized dark theme:

| Token Name | Hex Code | Role |
|---|---|---|
| `VoidBlack` | `#0B0F19` | Default window background, deep negative space |
| `DarkSlate` | `#1E293B` | Card surfaces, container backgrounds (`Slate 800`) |
| `BorderSlate` | `#334155` | 1dp card borders, high-contrast outlines (`Slate 700`) |
| `SurfaceGlass` | `rgba(15, 23, 42, 0.72)` | Translucent backdrop blur / bottom bar surface |
| `TextPrimary` | `#FFFFFF` | 100% pure white for primary numbers and active labels |
| `TextSecondary` | `#94A3B8` | Subdued metadata, inactive tab items (`Slate 400`) |
| `TextDisabled` | `#64748B` | Disabled action states, future progress segments |
| `WarningAmber` | `#F59E0B` | Disconnection / sensor contact loss warning banners |
| `DestructiveRed`| `#EF4444` | Session deletion, ungraceful crash recovery badges |

### 2.3 Brand & Action Accent Tokens (Stitch Prototype Mapping)
The application uses athletic accent colors for onboarding, call-to-action buttons, and telemetry units:

| Token Name | Hex Code | Compose Color | Role / Mapping from Stitch Prototype |
|---|---|---|---|
| `AthleticLime` | `#BDCF73` | `Color(0xFFBDCF73)` | Primary interactive buttons, step progress active bars (`primary` in Stitch) |
| `TertiaryCyan` | `#00DAF3` | `Color(0xFF00DAF3)` | Telemetry units (`BPM`), mode badges (`KARVONEN HRR`), setup headers (`tertiary` in Stitch) |
| `RequiredOrange` | `#FD5921` | `Color(0xFFFD5921)` | Mandatory input field badges (`REQ`) (`secondary-container` in Stitch) |

---

## 3. Typography Hierarchy

| Role | Font Family | Size | Weight | Letter Spacing | Use Case |
|---|---|---|---|---|---|
| **Display Giant** | System Sans-Serif | `124sp` | Bold (`700`) | `-4sp` | Live Session BPM Numerals |
| **Display Large** | Monospace (Tabular) | `52sp` | Bold (`700`) | `0sp` | Effective Duration Timer (`HH:mm:ss`) |
| **Title Large** | System Sans-Serif | `24sp` | Bold (`700`) | `-0.5sp` | Screen Headers, Card Hero Metrics |
| **Title Medium** | System Sans-Serif | `18sp` | SemiBold (`600`)| `0sp` | Card Titles, Settings Group Labels |
| **Body Large** | System Sans-Serif | `16sp` | Regular (`400`) | `0sp` | Body descriptions, input form fields |
| **Badge / Stat** | System Sans-Serif | `14sp` | Bold (`700`) | `1.5sp` (All Caps) | `BPM \| ZONE X`, status badges |
| **Caption Mono** | Monospace | `12sp` | Medium (`500`) | `0.5sp` | MAC Snippets, timestamps, RSSI |

---

## 4. Component & Ergonomic Specifications

### 4.1 Live Session Monolith Screen
- **Full-Bleed Animated Background**: The background color transitions smoothly to the active zone color using `animateColorAsState` over **500ms** with `FastOutSlowInEasing`. The color is blended with `VoidBlack` at an 82% depth to preserve high contrast for text.
- **5-Segment Horizontal Progress Bar**:
  - 5 equal-width segments separated by 4dp gaps.
  - Active zone segment: solid white (`#FFFFFF`).
  - Past zones (lower intensity): 55% white (`rgba(255, 255, 255, 0.55)`).
  - Future zones (higher intensity): 25% black/translucent (`rgba(0, 0, 0, 0.25)`).
- **Workout Controls**:
  - Large bottom pill buttons with a minimum height of **56dp**.
  - Dual action layout: Pause/Resume (left) and Stop & Save (right).
  - Outlined with `BorderSlate` and translucent dark fill (`rgba(0, 0, 0, 0.32)`).

### 4.2 Compose Canvas 1 Hz Time-Series Chart
- **Sampling & Performance**: Sessions with > 1,000 raw samples apply the Largest-Triangle-Three-Buckets (LTTB) downsampling algorithm to 600–800 rendered points to guarantee fluid 60/120fps scrubbing.
- **Horizontal Zone Guidelines**: Faint dotted/semi-transparent lines at calculated zone boundaries (Z1–Z5 thresholds) with muted zone labels on the Y-axis.
- **Honest Telemetry Gaps**: If consecutive samples have a timestamp delta > 3 seconds, the canvas breaks the line using `Path.moveTo()` instead of `Path.lineTo()`, preventing fake linear interpolations during pauses or dropouts.
- **Interactive Touch Scrubber**: Dragging across the chart displays a vertical crosshair line and a floating pill tooltip containing:
  - Relative elapsed time (`MM:SS`)
  - Instantaneous BPM
  - Color-coded Heart Zone badge

### 4.3 History Cards & Mini-Zone Bar
- **O(1) Summary Card**: Displays formatted date, start time, effective duration, average BPM, and maximum BPM.
- **Proportional Mini-Bar**: A 6dp tall horizontal bar partitioned by the percentage of effective duration spent in each of the 5 zones, visually conveying session distribution at a glance.

---

## 5. Navigation & Shell Behavior

### 5.1 Root Navigation Guard
- App entry inspects `user_profile` table via `UserProfileDao.getProfile()`.
- If `null`, start destination is locked to `OnboardingGraph`. System back exits the app.
- When profile is saved, the app unlocks and transitions to the main navigation shell.

### 5.2 3-Tab Bottom Navigation Bar
- **Tab 1: Live Session (`/session/live`)** — Instant access to sensor state and workout start.
- **Tab 2: History (`/history`)** — Chronological list of recorded workouts.
- **Tab 3: Settings (`/settings`)** — User profile editor, device management, and app details.

### 5.3 Immersion Mode Invariant
- During active workout recording (`Recording` or `Paused` states), the **Bottom Navigation Bar is completely hidden**.
- Prevents accidental tab switches during high-intensity training.
- Returning to History or Settings is permitted only after ending or discarding the workout.

---

## 6. Translation Guide: From Stitch HTML/CSS to Jetpack Compose

When translating Google Stitch exported code into Android Compose Composables:

| Stitch / Tailwind Pattern | Jetpack Compose Implementation |
|---|---|
| `<div class="bg-[#0B0F19] text-white">` | `Surface(modifier = Modifier.fillMaxSize(), color = VoidBlack) { ... }` |
| `animate-colors duration-500` | `val bg by animateColorAsState(targetValue = zoneColor, animationSpec = tween(500, easing = FastOutSlowInEasing))` |
| `text-[124px] font-bold tracking-tighter` | `Text(text = "$bpm", fontSize = 124.sp, fontWeight = FontWeight.Bold, letterSpacing = (-4).sp)` |
| `font-mono text-5xl` | `Text(text = durationText, fontFamily = FontFamily.Monospace, fontSize = 52.sp, fontWeight = FontWeight.Bold)` |
| `min-h-[56px] rounded-full` | `Button(modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp))` |
| Flex column / row with gap | `Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { ... }` |
| Glassmorphism card (`backdrop-blur-md`) | `Card(colors = CardDefaults.cardColors(containerColor = DarkSlate), border = BorderStroke(1.dp, BorderSlate))` |
| Canvas SVG Chart | Custom `androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(220.dp))` |
