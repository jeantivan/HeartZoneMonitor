# HeartZoneMonitor

Heart rate monitoring and zone tracking application for Android using Bluetooth Low Energy sensors.

## Language

**Heart Rate Monitor (HRM)**:
An external Bluetooth Low Energy sensor complying with the Bluetooth SIG Heart Rate Service (UUID `0x180D`) and Heart Rate Measurement characteristic (UUID `0x2A37`).
_Avoid_: Smartwatch, fitness tracker, band, peripheral

**Session**:
A single continuous recording interval of heart rate data with explicit start, pause, resume, and completion lifecycle states.
_Avoid_: Workout, activity, training, run

**Elapsed Duration**:
The total wall-clock time elapsed between the start timestamp and the end timestamp of a session.
_Avoid_: Total time, overall duration

**Effective Duration**:
The accumulated active time during a session during which heart rate was actively measured and not paused.
_Avoid_: Active time, training time, net time

**Paused Duration**:
The accumulated time during a session where recording was manually or automatically paused.
_Avoid_: Idle time, rest time

**Heart Zone**:
One of five standardized cardiovascular intensity ranges (Zones 1 through 5) defined by specific percentage boundaries of Maximum Heart Rate.
_Avoid_: Heart bracket, cardio zone, pulse range

**Maximum Heart Rate (HRmax)**:
The theoretical or manually overridden upper limit of heart rate (in beats per minute) used to compute the boundaries of Heart Zones.
_Avoid_: Max BPM, peak heart rate, top pulse

**Heart Rate Sample**:
A single timestamped heart rate measurement (in beats per minute) recorded at 1 Hz during active session recording.
_Avoid_: Tick, data point, pulse reading

**Remembered HRM**:
A previously paired Heart Rate Monitor whose MAC address and metadata are persisted locally for automatic reconnection.
_Avoid_: Paired device, saved sensor, trusted peripheral

**Active HRM**:
The single designated Remembered HRM that the application prioritizes for connection and data streaming during a Session.
_Avoid_: Selected device, current sensor, primary HRM

**User Profile**:
The user's physiological parameters (birth year, resting heart rate, calculation mode, and optional manual HRmax override) used to calculate Heart Zone boundaries.
_Avoid_: User settings, user account, personal info
