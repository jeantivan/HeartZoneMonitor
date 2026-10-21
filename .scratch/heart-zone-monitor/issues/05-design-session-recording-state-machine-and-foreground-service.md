# Design session recording state machine and foreground service

Type: grilling
Status: open
Blocked by: none

## Question

How will the state machine manage transitions (`Disconnected` -> `Connected` -> `Recording` -> `Paused` [manual or auto-BLE-loss] -> `Resumed` -> `Completed`), how will effective duration and paused duration be accumulated accurately without clock drift, and how will the Android Foreground Service coordinate with the ViewModel and persistent notification?
