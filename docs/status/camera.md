# Camera: presence + profiles (agent H, `:feature:camera`)

_Draft, measurements being filled in._

## Capture gate (step 1): PASSED, no camera patch needed

Measured Nov 22, 2026 ~04:00 on the Echo (`--es screen camera-probe`, tools/probe.sh), shutter open
(`dumpsys input` SwitchValues 0), **room dark: light sensor 0.0 lux**.

| API | Result | Size | fps (asked 15) | first frame | app CPU (all frames delivered) |
|---|---|---|---|---|---|
| Camera2 (LIMITED, level 0) | frames OK | 176×144 YUV_420_888 | 9.5 | ~620 ms | 8.3–9.3 % of a core |
| Camera1 | frames OK | 176×144 NV21 | 9.5 | ~600 ms | 1.8–2.4 % of a core |

- Both APIs deliver frames. Offered sizes: 1600×1200 … 640×480, 320×240, 176×144; fps ranges (15,15) (20,20) (5,30) (30,30).
- Frames were near-black (mean luma 2–3) because the room was dark. **Light test**: turning the screen
  white at full brightness for 4 s doubled the mean luma on both APIs (2.8 → 5.6 → 3.0), so the sensor
  is live and responds to light (not a dead pipeline). Image quality in daylight is still to be seen.
- **Camera1 is the cheaper path** (≈4× less app CPU: one reused NV21 buffer vs ImageReader planes);
  the pipeline uses Camera1.
- One Camera2 failure seen only when two clients opened the camera at once (`createDefaultRequest … -38`).
