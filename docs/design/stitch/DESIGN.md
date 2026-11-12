---
name: Kinetic Telemetry
colors:
  surface: '#051424'
  surface-dim: '#051424'
  surface-bright: '#2c3a4c'
  surface-container-lowest: '#010f1f'
  surface-container-low: '#0d1c2d'
  surface-container: '#122131'
  surface-container-high: '#1c2b3c'
  surface-container-highest: '#273647'
  on-surface: '#d4e4fa'
  on-surface-variant: '#c7c8b5'
  inverse-surface: '#d4e4fa'
  inverse-on-surface: '#233143'
  outline: '#909281'
  outline-variant: '#46483a'
  surface-tint: '#bdcf73'
  primary: '#bdcf73'
  on-primary: '#2b3400'
  primary-container: '#889843'
  on-primary-container: '#252d00'
  inverse-primary: '#566513'
  secondary: '#ffb59f'
  on-secondary: '#5e1600'
  secondary-container: '#fd5921'
  on-secondary-container: '#531300'
  tertiary: '#00daf3'
  on-tertiary: '#00363d'
  tertiary-container: '#009fb2'
  on-tertiary-container: '#002f35'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d9eb8c'
  primary-fixed-dim: '#bdcf73'
  on-primary-fixed: '#181e00'
  on-primary-fixed-variant: '#3f4c00'
  secondary-fixed: '#ffdbd0'
  secondary-fixed-dim: '#ffb59f'
  on-secondary-fixed: '#3a0a00'
  on-secondary-fixed-variant: '#852300'
  tertiary-fixed: '#9cf0ff'
  tertiary-fixed-dim: '#00daf3'
  on-tertiary-fixed: '#001f24'
  on-tertiary-fixed-variant: '#004f58'
  background: '#051424'
  on-background: '#d4e4fa'
  surface-variant: '#273647'
typography:
  display-xl:
    fontFamily: JetBrains Mono
    fontSize: 72px
    fontWeight: '800'
    lineHeight: 72px
    letterSpacing: -0.04em
  display-xl-mobile:
    fontFamily: JetBrains Mono
    fontSize: 56px
    fontWeight: '800'
    lineHeight: 56px
    letterSpacing: -0.03em
  headline-lg:
    fontFamily: Chivo
    fontSize: 40px
    fontWeight: '800'
    lineHeight: 44px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Chivo
    fontSize: 32px
    fontWeight: '800'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Chivo
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Chivo
    fontSize: 22px
    fontWeight: '700'
    lineHeight: 26px
  title-md:
    fontFamily: Chivo
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Chivo
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 24px
  body-md:
    fontFamily: Chivo
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '700'
    lineHeight: 18px
    letterSpacing: 0.06em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.08em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.1em
  telemetry-unit:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.04em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-sm: 0.5rem
  margin: 1rem
  margin-lg: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.875rem
  space-lg: 1.25rem
  space-xl: 2rem
---

## Brand & Style

This design system is engineered for extreme physical output, mid-stride glanceability, and high-cadence biometric monitoring. Built specifically for endurance athletes, track cyclists, and hybrid competitors operating at maximal heart rates (180+ BPM), the interface prioritizes immediate optical legibility, split-second tactile comprehension, and zero cognitive drag under duress.

The visual direction merges **High-Performance Brutalism** with **Aerospace Instrument HUDs**:
- **Pure Functional Contrast:** Pure and near-black OLED backdrops eliminate peripheral battery and cognitive drain, allowing high-visibility metrics to vibrate forward with precision.
- **Physical Affordance & Glove Reliability:** Large-footprint hit targets, pronounced structural frames, and intentional tactile borders allow operation while wearing cycling gloves, chalked hands, or through sweat-covered displays.
- **Glanceable Hierarchy:** Biometric telemetries occupy dominant, non-shifting tabular layouts so athletes can digest speed, wattage, and zone transitions in under 200 milliseconds.

## Colors

The palette operates on a true dark canvas optimized for outdoor OLED sunlight rejection and night-training legibility.

### Core Architecture
- **Primary (`#7D8D39` - Endurance Olive):** Structural grounding, pacing baselines, sustained aerobic benchmarks, active indicators, and verified system states.
- **Secondary (`#FF5A22` - Blaze Orange):** High-cadence interventions, peak zone triggers, critical thresholds, primary action drivers, and anaerobic warnings.
- **Tertiary (`#00E5FF` - Kinetic Cyan):** Live telemetry feeds, GPS tracking locks, external Bluetooth/ANT+ sensor statuses, and elevation deltas.
- **Canvas & Surface Layers:** Base OLED `#080C14`, Elevated HUD Container `#111827`, Sub-container/Border `#1E293B`.
- **Text & Telemetry:** Static foreground `#F8FAFC`, Readout Neutral `#94A3B8`, Muted Dim `#475569`.

### 5-Zone Cardiovascular Progression
Harmonized with the core olive and blaze primaries to maintain cohesive chromatic tension:
- **Zone 1 (Active Recovery):** `#5A6E7C` (Muted Steel Slate) — Low-intensity baseline.
- **Zone 2 (Aerobic Endurance):** `#7D8D39` (Endurance Olive) — Sustained steady-state engine.
- **Zone 3 (Tempo / Aerobic Power):** `#D4AF37` (Dynamic Ochre) — Threshold ramp.
- **Zone 4 (Threshold / Lactate):** `#FF5A22` (Blaze Orange) — High-cadence critical limit.
- **Zone 5 (Neuromuscular / VO2 Max):** `#FF1E56` (Infrared Crimson) — Supra-maximal exertion.

## Typography

The type system is strictly split between high-velocity numeric rendering and modern athletic structural titling.

- **Telemetry Readouts & Metric Arrays (`JetBrains Mono`):** All real-time values (Heart Rate, Watts, Pace, Cadence, Distance) utilize monospaced, tabular numerals to prevent layout vibration or micro-jitter when numbers fluctuate at high speed.
- **Interface Structure & Wayfinding (`Chivo`):** Sharp, angular, high-legibility geometric cuts that retain visual presence under bright outdoor glare and extreme view angles.
- **Micro-Labels & Unit Identifiers:** Always set in uppercase with increased tracking (`letter-spacing: 0.06em` to `0.1em`) using `JetBrains Mono` to cleanly separate raw values from qualitative units (e.g., `182` / `BPM`, `340` / `WATTS`).

## Layout & Spacing

Layout geometry follows an unyielding, modular cockpit grid designed to fit phone mounts, bike computers, and handheld grips.

- **Primary Rhythm:** An 8pt grid with dense, compact sub-units (4pt/8pt/14pt/20pt/32pt). Structural margins compress slightly on handheld mobile layouts (`1rem`) to maximize real estate for high-contrast numeric blocks.
- **Split Telemetry Grids:** Dashboard screens feature equal-split 2-column, 4-quadrant, or 6-tile layouts with rigid `gutter-sm` (8px) interior gutters. This maximizes sensor density without border collision.
- **Safe Interaction Pockets:** The lower 35% of the viewport is designated as the Primary Action Zone, housing large mechanical hit areas easily reached by one hand during movement.
- **Breakpoints:**
  - **Mobile (< 600px):** Single-column HUD stacks or rigid 2x2 telemetry matrices.
  - **Tablet / Cockpit Mount (600px - 1024px):** Split left HUD console (live map, elevation profile) and right telemetry cluster (4x2 data matrix).
  - **Desktop / Post-Workout Analysis (> 1024px):** 12-column fixed grid with persistent left-hand workout summary stream and expansive timeline telemetry graphs.

## Elevation & Depth

To maximize outdoor contrast and prevent display wash-out, this design system discards standard diffuse shadows in favor of **structural containment, surface stepped luminance, and laser-precise tactical outlines**.

- **Level 0 (Canvas Base):** Deep OLED Black (`#080C14`). Zero light emission, absolute optical contrast.
- **Level 1 (Data Pod / HUD Cell):** Solid Charcoal Blue (`#111827`) enclosed by a crisp, 1px low-contrast outline (`#1E293B`).
- **Level 2 (Active Telemetry Block / Floating Overlay):** Surface `#1A2234` paired with a directional 1px top highlight (`#334155`) and an intense 1px active edge matching the metric state (e.g., `#FF5A22` for Zone 4 alerts).
- **Tactile Feedback Outlines:** On press or active selection, components avoid soft drop shadows. Instead, they trigger an immediate 2px inset accent stroke (`#7D8D39` or `#FF5A22`) paired with an instantaneous +10% surface luminance bump, ensuring clear confirmation under vibration and motion.

## Shapes

The shape architecture relies on industrial, clipped-corner discipline (`roundedness: 1` — Soft, 4px default radius).

- **4px Corner Softening:** Provides enough curvature to differentiate interactive items from background layout lines while retaining a chiseled, precision-instrument aesthetic.
- **Aggressive Uniformity:** Metric HUD containers, buttons, alerts, and progress bars all adhere to the identical 4px corner profile. Pill shapes and circular components are explicitly avoided, except for status indicator LEDs, circular progress gauges, and user profile badges.
- **Structural Inset Dividers:** Telemetry tiles within the same module are separated by straight 1px hairpins rather than nested borders, maintaining maximum structural density.

## Components

### 56dp Athletic Buttons
- **Touch Target:** Absolute minimum 56x56dp footprint across all primary interactive surfaces, engineered for thumb engagement while running or wearing full-finger gloves.
- **Primary Control (Start / Lap / Split):** Solid `#FF5A22` surface, `#080C14` high-contrast bold typography (`Chivo` 16px Bold, Uppercase), 4px border radius. On active press, visual state shifts to `#E04815` with an instant 2px white keyline.
- **Secondary Control (Pause / Resume / Calibrate):** `#111827` surface with a 1.5px `#7D8D39` border and `#F8FAFC` label.
- **Hold-to-Confirm Actions:** Workout termination requires a 2000ms continuous press accompanied by an inward-filling progress stroke around the border to eliminate accidental sweat-induced cancellation.

### Modular Telemetry HUD Pods
- **Construction:** `#111827` background, 1px `#1E293B` outer frame, 4px radius, padded with `0.875rem`.
- **Anatomy:** 
  1. Top-left: Micro-label (`JetBrains Mono` 10px uppercase `#94A3B8`).
  2. Center: Large tabular value (`JetBrains Mono` 40px to 56px bold `#F8FAFC`).
  3. Bottom-right: Unit indicator (`JetBrains Mono` 12px `#94A3B8`).
  4. Top-right: Miniature trend icon or zone color-pill (e.g., a 4x12px vertical bar colored to Zone 4 Blaze).

### Cardiovascular Zone Bar
- Segmented linear progress strip dividing current output across the 5 heart rate zones.
- Height of 8px with 2px channel gaps between zones. Active zone displays full chroma saturation, while inactive zones stay muted at 25% opacity.
- The active indicator pulses subtly at the current measured BPM cadence.

### Status Chips & Sensor Tags
- Height of 28px, 4px corner radius.
- Background of `#1E293B`, 1px border.
- Features a 6px glowing status dot indicating external sensor locks (ANT+, Power Meter, GPS) using Kinetic Cyan (`#00E5FF`).

### Tactical Inputs & Steppers
- Segmented minus/plus incrementers with distinct 56dp square touch zones on either side of a central monospaced display.
- Text input forms use an active `#7D8D39` border highlight and high-contrast tabular numeric entry keys.

### High-Visibility Alert Banners
- Full-width HUD interruptions for critical biometric thresholds (e.g., Zone 5 Ceiling, Cadence Drop, Dehydration Interval).
- Bordered on the left edge with a thick 4px `#FF5A22` or `#FF1E56` strip, using high-urgency monospaced warning tags.