# 09: Onboarding Step 2: HRM Pairing & "Pair Later"

**What to build:** The second step of the onboarding flow where users discover nearby Heart Rate Monitors, pair their primary sensor, or choose to skip pairing for later.

**Blocked by:** 05 (Root Navigation Gate for Onboarding), 06 (Remembered HRMs Schema & DAO), 08 (BLE Device Discovery & Scan Filtering)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Onboarding Pairing Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#2-onboarding-pairing-screen)
- Visual Mockup: [`docs/design/stitch/02-onboarding-pairing/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/02-onboarding-pairing/screen.png)
- HTML Prototype: [`docs/design/stitch/02-onboarding-pairing/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/02-onboarding-pairing/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Renders Step 2 onboarding screen in Compose, prompting for Bluetooth permissions and starting the 0x180D discovery scan.
- [ ] Displays discovered sensors in a clean list with name, MAC snippet, and RSSI bars.
- [ ] Tapping a discovered sensor pairs it, persists it in `remembered_hrms`, marks it as the Active HRM (`isActive = 1`), and transitions into the main app.
- [ ] Provides a clear "Pair Later" action allowing the user to bypass sensor pairing and land on the main screen.
- [ ] If "Pair Later" was chosen, the live session screen displays a prominent notice and disables the Start Session button until a sensor is paired.
