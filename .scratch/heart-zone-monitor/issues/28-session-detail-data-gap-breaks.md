# 28: Session Detail: Data Gap Visualizer & Discontinuous Path Breaks

**What to build:** The time-series chart path generation logic that renders honest visual line breaks across pauses, radio disconnections, or skin contact loss intervals rather than artificial straight-line interpolations.

**Blocked by:** 27 (Session Detail: Interactive Compose Canvas Chart & Touch Scrubbing)

**Status:** ready-for-agent

- [ ] Inspects timestamps between consecutive samples during path generation.
- [ ] When the time delta between two consecutive samples exceeds 3 seconds ($\Delta t > 3000\text{ ms}$), executes `Path.moveTo()` instead of `Path.lineTo()`.
- [ ] Paused workout periods, BLE disconnect intervals, and contact loss gaps render as clear, distinct physical breaks in the polyline.
- [ ] Verifies that touch scrubbing correctly indicates gaps or snaps to the nearest valid sample without crashing.
- [ ] Unit and UI tests verify that multi-gap sessions generate disconnected path contours accurately.
