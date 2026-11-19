# 11: GATT 0x2A37 Parser & Sequential Command Queue

**What to build:** The GATT protocol engine that safely unpacks Heart Rate Measurement characteristic (`0x2A37`) byte streams and serializes all GATT operations through a sequential queue to prevent Android GATT Error 133 collisions.

**Blocked by:** 07 (Hardware Seam `BleTransport` & Bluetooth Permissions)

**Status:** resolved

- [x] Unpacks Little-Endian byte array from `0x2A37`: parses Byte 0 flags (bit 0: 8-bit vs 16-bit BPM; bits 1–2: sensor contact status; bit 3: energy expended; bit 4: RR-intervals).
- [x] Extracts instantaneous BPM (0–255) and physical skin contact boolean status accurately.
- [x] Enforces a Coroutine Mutex/Channel sequential command queue for all GATT operations (`discoverServices`, subscribing to CCCD `0x2902`, reading Battery Level `0x2A19`).
- [x] Subscribes to CCCD `0x2902` using API-level descriptor write overload on Android 13+.
- [x] Reads Battery Level from Battery Service (`0x180F` / `0x2A19`) upon connection and schedules a periodic refresh every 10 minutes.
- [x] Unit tests verify unpacking of 8-bit, 16-bit, contact-present, contact-lost, and RR-interval frames.
