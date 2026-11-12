# 10: Remembered HRMs Management Screen

**What to build:** A dedicated management interface accessible from Settings and the session screen to review remembered Heart Rate Monitors, edit custom nicknames, promote a device to Active HRM, and remove devices.

**Blocked by:** 06 (Remembered HRMs Schema & DAO)

**Status:** ready-for-agent

**Design Specification & Mockups:**
- Screen Spec: [Device Management Screen](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/screens.md#6-device-management-screen)
- Visual Mockup: [`docs/design/stitch/06-device-management/screen.png`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/06-device-management/screen.png)
- HTML Prototype: [`docs/design/stitch/06-device-management/code.html`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch/06-device-management/code.html)
- Design System: [Athletic Monolith Design System](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/DESIGN_SYSTEM.md)

- [ ] Displays a list of all Remembered HRMs with advertised name, custom nickname, MAC address, battery level (if known), and Active HRM indicator.
- [ ] Tapping an inactive Remembered HRM immediately promotes it to the Active HRM in an atomic transaction.
- [ ] Allows editing the custom nickname (e.g. "Chest Strap", "Armband") for any remembered sensor.
- [ ] Provides a "Forget Device" action with confirmation to remove a sensor from the database.
- [ ] If the Active HRM is deleted, its active status is cleared, GATT connection is closed, and the user is prompted to designate another sensor or scan for a new one.
