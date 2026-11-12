# Google Stitch Generation Prompts

Use these prompts on [stitch.withgoogle.com](https://stitch.withgoogle.com) to generate the high-fidelity UI mockups for each of the 7 core screens of HeartZoneMonitor.

> [!TIP]
> Stitch creates a project containing all exported screens. When you export, download the project containing `DESIGN.md`, each screen's `.png` image, and `code.html`. Save the files into `docs/design/stitch/`.

---

## 1. Onboarding Profile Screen (`01-onboarding-profile`)

**Copy & paste into Stitch:**

```text
Design a mobile screen for an offline-first Android fitness app called "HeartZoneMonitor".
Screen: First-Run Onboarding Profile Setup.
Style: Athletic Monolith dark theme, OLED black background (#0B0F19), card surface (#1E293B), border (#334155), white typography (#F3F6F4)

Layout (Top to Bottom):
1. Progress indicator at top showing "Step 1 of 2: Physiological Profile".
2. Header: Title "Calibrate Your Heart Zones" in bold 24px, with subtitle explaining: "Accurate zones using the peer-reviewed Tanaka HRmax formula. Your data stays 100% encrypted on this device."
3. Form Card (#1E293B, 1px border #334155, rounded-2xl p-5):
   - Input Field: "Birth Year" (numeric input, placeholder "1988", helper text "Ages 10–110").
   - Input Field: "Resting Heart Rate (BPM)" (optional, placeholder "60", helper text "Leave blank for %HRmax, or enter resting pulse for Karvonen HRR").
   - Toggle switch: "Custom Measured HRmax Override" (unchecked by default).
4. Live Dynamic Preview Card (#1E293B, rounded-2xl p-5):
   - Calculated ceiling: "Estimated HRmax: 182 BPM (Tanaka Formula: 208 - 0.7 × Age)"
   - Active Mode badge pill: "KARVONEN HEART RATE RESERVE" in #3B82F6 pill.
   - 5-Zone breakdown mini-table with color dots:
     * Zone 1 (Recovery): 50-60% | 121 - 133 BPM [Pill #64748B]
     * Zone 2 (Aerobic Base): 60-70% | 134 - 146 BPM [Pill #3B82F6]
     * Zone 3 (Tempo): 70-80% | 147 - 159 BPM [Pill #10B981]
     * Zone 4 (Threshold): 80-90% | 160 - 171 BPM [Pill #F59E0B]
     * Zone 5 (Anaerobic): 90-100% | 172 - 182 BPM [Pill #EF4444]
5. Bottom Action: Large 56px height high-contrast pill button: "Continue to Sensor Pairing" in bold white text with subtle glowing blue/emerald gradient.
```

---

## 2. Onboarding Pairing Screen (`02-onboarding-pairing`)

**Copy & paste into Stitch:**

```text
Design a mobile screen for an Android fitness app called "HeartZoneMonitor".
Screen: First-Run Onboarding BLE Sensor Pairing.
Style: Athletic Monolith dark theme, OLED black background (#0B0F19), card surface (#1E293B), border (#334155), white text (#F3F6F4).

Layout (Top to Bottom):
1. Progress indicator at top showing "Step 2 of 2: Connect Chest Strap".
2. Header: Title "Pair Heart Rate Monitor" in bold 24px, with subtitle: "Scanning for standard Bluetooth SIG Heart Rate sensors (0x180D). Moisten strap contacts and wear strap to awaken."
3. Bluetooth Permission Card: Subdued dark pill banner with Bluetooth icon: "Bluetooth scan permissions enabled (neverForLocation - GPS location is never accessed)".
4. Scanning Radar/Beacon Card:
   - Subtle animated radar rings with text "Scanning for nearby BLE sensors..."
5. Discovered Peripherals List (Card stack):
   - Item 1 (Highlighted/Recommended):
     * Name: "COOSPO H808S" in bold 18px
     * Subtitle: "MAC: D4:36:39:...:9B · RSSI: -54 dBm (Strong Signal)"
     * Action button: "Connect & Pair" in primary color
   - Item 2:
     * Name: "Polar H10 92F4"
     * Subtitle: "MAC: A0:9E:1A:...:11 · RSSI: -78 dBm"
     * Action button: "Connect" in outline border
6. Bottom Actions:
   - Primary: Disabled button "Continue" (activates once paired).
   - Secondary text button: "Pair Later (Explore app without sensor)" in subdued slate (#94A3B8).
```

---

## 3. Live Session Screen (`03-live-session`) — The Athletic Monolith

**Copy & paste into Stitch:**

```text
Design an intense, ultra-glanceable outdoor live workout screen for an Android app called "HeartZoneMonitor".
Theme: "Athletic Monolith" - optimized for direct sunlight, high speed, and zero distraction.
Current State: Actively RECORDING in Zone 3 (Tempo).
Background: Atmospheric dark accent luminous gradient (#FF5A22 at top blending into OLED deep void #0B0F19 at 82% depth). Absolutely NO pulsing heart icons or decorative sound waves.

Layout (Top to Bottom):
1. Top Status Bar:
   - Left: Connected sensor pill: "● COOSPO H808S · 95% BATTERY" (solid green dot).
   - Right: Recording badge with blinking red dot: "● RECORDING".
2. Center Telemetry (Hero Focus):
   - Massive 124px bold tabular number: "154" in pure crisp white (#F3F6F4) with tight letter-spacing.
   - Distinct badge directly beneath the number: "BPM  |  ZONE 3 (TEMPO)" in uppercase bold white text on translucent dark emerald background pill.
3. 5-Segment Horizontal Zone Progress Bar:
   - Full width horizontal bar divided into 5 equal rounded segments separated by 4px gaps:
     * Segment 1 (Z1): 55% white opacity
     * Segment 2 (Z2): 55% white opacity
     * Segment 3 (Z3 - ACTIVE): 100% solid white (#F3F6F4) with subtle bright glow
     * Segment 4 (Z4): 25% translucent dark
     * Segment 5 (Z5): 25% translucent dark
4. Single-Metric Duration Card (#1E293B with 1px border #334155, rounded-2xl py-4 px-6 text-center):
   - Small all-caps label: "EFFECTIVE DURATION" (#94A3B8)
   - Giant 52px bold monospace clock: "00:38:42" in white. (No secondary elapsed or paused clutter).
5. High-Affordance Workout Controls (Bottom 56px height):
   - Left Button (Flex 1): "PAUSE" pill button with pause icon, dark glass container (rgba(0,0,0,0.4)), 1.5px border #334155, white text.
   - Right Button (Flex 1): "STOP & SAVE" pill button with square stop icon, solid crimson/amber border, vibrant white text.
6. Bottom Navigation Bar: HIDDEN (full screen immersion).
```

---

## 4. Session History Screen (`04-session-history`)

**Copy & paste into Stitch:**

```text
Design a mobile workout history list screen for an Android fitness app called "HeartZoneMonitor".
Style: Athletic Monolith dark theme, OLED black background (#0B0F19), cards #1E293B with 1px border #334155, white text (#F3F6F4).

Layout (Top to Bottom):
1. Top App Bar:
   - Title: "Workout History" in bold 24px.
   - Subtitle: "18 Sessions Completed · 14.2 Total Hours"
2. Chronological Session Cards List:
   - Card 1 (Latest Workout):
     * Header row: "Monday, Nov 11, 2026 · 07:15 AM" | Sensor: "Chest Strap" | Green pill "SAVED"
     * Metric columns (3-column hero):
       - "DURATION": 54:20 (52px monospace font style, 20px size)
       - "AVG HR": 146 BPM
       - "MAX HR": 174 BPM
     * Proportional 5-Zone mini bar (6px height horizontal bar showing color proportions):
       - 10% Slate (#64748B), 35% Blue (#3B82F6), 40% Green (#10B981), 15% Amber (#F59E0B), 0% Red (#EF4444).
   - Card 2:
     * Header row: "Saturday, Nov 9, 2026 · 06:40 AM" | Amber badge "RECONCILED" (recovered after power loss)
     * Metric columns: 1:12:05 duration | 138 Avg BPM | 162 Max BPM
     * 5-Zone mini bar with proportional colors.
   - Card 3:
     * Header row: "Thursday, Nov 7, 2026 · 05:30 PM"
     * Metric columns: 32:15 duration | 158 Avg BPM | 181 Max BPM (High Zone 4/5 session).
3. Bottom Navigation Bar (Persistent 3-Tab Bar, glassmorphic surface rgba(15,23,42,0.85)):
   - Tab 1: "Live Session" (Heart Rate icon)
   - Tab 2: "History" (Active, bright blue #3B82F6 icon & label)
   - Tab 3: "Settings" (Gear icon)
```

---

## 5. Session Detail & Analytics Screen (`05-session-detail`)

**Copy & paste into Stitch:**

```text
Design a detailed workout analytics screen for an Android fitness app called "HeartZoneMonitor".
Style: Athletic Monolith dark theme, OLED black background (#0B0F19), cards #1E293B, border #334155, white text (#F3F6F4).

Layout (Top to Bottom):
1. Top Bar:
   - Left: Back arrow icon.
   - Center: "Workout Analytics · Nov 11, 2026".
   - Right: Trash icon in red (#EF4444) for delete.
2. Hero Telemetry Row (Card #1E293B, 4-column metric grid):
   - "EFFECTIVE": 54:20 (monospace)
   - "AVG BPM": 146
   - "MAX BPM": 174
   - "MIN BPM": 98
   Sub-row: "Elapsed: 56:10 · Paused: 01:50 · Sensor: COOSPO H808S"
3. Zone Distribution Card:
   - Full-width stacked horizontal bar showing % time in each zone.
   - Table rows for each zone:
     * Zone 1 (Recovery): 05:20 (10%) [Color #64748B]
     * Zone 2 (Aerobic Base): 18:45 (35%) [Color #3B82F6]
     * Zone 3 (Tempo): 21:30 (40%) [Color #10B981]
     * Zone 4 (Threshold): 08:45 (15%) [Color #F59E0B]
     * Zone 5 (Anaerobic): 00:00 (0%) [Color #EF4444]
4. Time-Series Heart Rate Line Chart (Custom Canvas, 220px height):
   - Smooth cyan/green continuous line showing 1 Hz heart rate curve over 54 minutes.
   - Faint horizontal dashed guideline thresholds at Z1(121), Z2(134), Z3(147), Z4(160), Z5(172).
   - Clear visual gap break in line at 25-minute mark where user paused for 1:50.
   - Vertical crosshair scrubber line at 34:10 with interactive floating tooltip pill:
     "34:10 · 158 BPM · [ZONE 4]"
5. Physiological Snapshot Card (Frozen profile parameters):
   - "Frozen Profile: Age 38 · Resting HR 58 · Karvonen HRR · HRmax 181 BPM"
6. Export Actions (Bottom sticky bar):
   - Left Button: "Export CSV (Biometric Gated)" with fingerprint icon in #3B82F6 outline.
   - Right Button: "Export JSON" with fingerprint icon in #10B981 outline.
```

---

## 6. Device Management Screen (`06-device-management`)

**Copy & paste into Stitch:**

```text
Design a Bluetooth sensor management screen for an Android fitness app called "HeartZoneMonitor".
Style: Athletic Monolith dark theme, OLED black background (#0B0F19), cards #1E293B, border #334155, white text (#F3F6F4).

Layout (Top to Bottom):
1. Top Bar:
   - Back arrow icon, Title "Heart Rate Monitors" in bold 20px.
2. Active Sensor Card (#1E293B with 2px bright blue #3B82F6 border, rounded-2xl p-5):
   - Header: Green pulsing indicator pill: "ACTIVE WORKOUT SENSOR"
   - Sensor Name: "Chest Strap (COOSPO H808S)" in bold 20px
   - Details: "MAC: D4:36:39:88:21:9B · Battery: 95% (Healthy)"
   - Subtitle: "Will automatically connect when starting a workout."
3. Remembered Sensors Section:
   - Section header: "REMEMBERED SENSORS (2)"
   - Device Card 1:
     * Name: "Armband HRM (Polar OH1)"
     * MAC: "A0:9E:1A:33:12:44 · Battery: 80%"
     * Actions: "Set as Active" pill button, Pencil edit nickname icon, Trash forget icon.
4. Scan & Add New Sensor:
   - Large 52px height dashed border container or button:
     "+ Scan for New Heart Rate Monitor (0x180D)" with Bluetooth icon.
5. Notice Banner at bottom:
   - Slate card with lock icon: "Active sensor switching is locked out during active workout recording to maintain data integrity."
```

---

## 7. Settings & Profile Screen (`07-settings-profile`)

**Copy & paste into Stitch:**

```text
Design a settings and profile editor screen for an Android fitness app called "HeartZoneMonitor".
Style: Athletic Monolith dark theme, OLED black background (#0B0F19), card surface #1E293B, border #334155, white text (#F3F6F4).

Layout (Top to Bottom):
1. Top App Bar: Title "Settings & Profile" in bold 24px.
2. Physiological Profile Section (Card #1E293B, rounded-2xl p-5):
   - Section title: "PHYSIOLOGICAL PROFILE" in bold 14px slate (#94A3B8)
   - Form fields:
     * "Birth Year": 1988 (Calculated Age: 38)
     * "Resting Heart Rate": 58 BPM
     * "Custom HRmax Override": [Off / Empty]
   - Zone Preview pill row showing calculated boundaries:
     "Z1: 120-132 · Z2: 133-145 · Z3: 146-158 · Z4: 159-170 · Z5: 171-181"
   - Invariant Notice Box:
     "Profile changes update future workouts. Past historical session records retain their frozen physiological snapshot."
   - Action: "Save Profile Changes" button.
3. Hardware & Devices Section:
   - List item card with chevron:
     "Manage Bluetooth Sensors" -> "1 Active Strap (COOSPO H808S), 1 Remembered"
4. Security & Privacy Status (Card #1E293B):
   - Shield check icon: "Hardware Keystore Encryption: Active (AES-256-GCM)"
   - Fingerprint icon: "Biometric Data Export: Enabled"
   - Cloud icon with slash: "100% Offline: Zero servers, zero telemetry"
5. About & Version:
   - "HeartZoneMonitor v1.0.0 · Open Source & Sovereign Health"
6. Persistent Bottom Navigation Bar (Tab 3 "Settings" active).
```
