# Repeatable S24 tracking test

## Before the test

Build/install using the [README](../README.md). Keep the phone's rear camera fully
unobstructed, including when mounted. Use a well-lit room with textured environmental
features. Record phone model/Android version, Google Play Services for AR version,
camera orientation/mount, room lighting, Wi-Fi band, router, distance, PC build and
commit ID. Do not evaluate in a featureless dark room as the baseline.

1. Connect phone and PC to the same trusted LAN (avoid guest/client isolation).
2. Run `AstraeusPoseViewer.exe` (UDP 4242). Find the PC's LAN IPv4 using `ipconfig`.
3. In the Android app enter that IPv4 and `4242`, then **Connect to PC**.
4. Press **Start Tracking**; grant camera permission/install Google Play Services
   for AR if prompted. Press Start again after installation. Slowly move the phone
   to initialize tracking. Wait for `TRACKING` and increasing PC packet counts.
5. Press **Reset stream** in the viewer whenever restarting the Android tracker.
6. Once ready, enable **Toggle CSV logging** on the PC. Its status gives the CSV
   path (current working directory). Logging is off by default. Optional phone
   diagnostics use its checkbox plus `adb logcat -s AstraeusPose:I '*:S'`.

## Physical sequence

Mark a repeatable camera position and orientation on a stable support. Place a
measuring tape along forward, horizontal and vertical directions. Note each phase's
PC monotonic timestamp or sequence in a separate notes file. Do not recenter again
during the movement sequence; that would hide accumulated error.

1. Place the phone in the well-lit, visibly featured room.
2. Start ARCore and wait for TRACKING.
3. At the marked position press **Recenter / Set Origin**; verify revision changes
   and near-zero XYZ / identity XYZW. Hold the phone level if you want gravity-up.
4. Leave it stationary for **30 seconds**. Record position and orientation drift.
5. Move approximately **0.5 m forward** (-Z after recenter), then return to the mark.
6. Move approximately **0.5 m left/right**, returning to the mark.
7. Move approximately **0.5 m vertically**, returning to the mark.
8. Rotate approximately **90 degrees**, then return to the original orientation.
9. Walk a small loop; return to the starting camera position and orientation.
10. Hold still for a further 30 seconds and record return-to-origin error.

Repeat at least three times with a new log per run. Then separately test slow and
rapid headset-like turns, mild temporary camera occlusion, lower light, and a
10-minute warm run. Restore camera visibility and record relocalization behavior.
Do not mix those stress conditions into the baseline without labeling them.
Toggle CSV off to flush/close it before copying it. Stop Tracking when finished.

## Measurements and interpretation

Use only `accepted=1` rows with tracking_state=2. Split by session and origin
revision. Use the first settled valid pose as baseline. Keep invalid periods in
the analysis of tracking availability; do not silently discard them there.

| Measurement | Method |
|---|---|
| Stationary position drift | Norm of position minus baseline; report RMS, maximum and end displacement in mm |
| Stationary rotational drift | `2*acos(clamp(abs(dot(q,q0)),0,1))`, convert radians to degrees |
| Return error | Position norm and quaternion angular difference at the marked return pose |
| Tracking loss | Count PAUSED/STOPPED spans and duration; report missing packet spans separately |
| Relocalization | Time to regain TRACKING and magnitude of any pose jump on recovery |
| Sample rate | Differences between phone timestamps within the same session |
| Packet rate | Accepted packet count per elapsed PC receive time |
| Jitter | Distribution of receive intervals minus sample intervals; viewer reports EWMA absolute variation |
| Missing/old packets | Sequence gaps, duplicates and old samples; gaps include sender queue drops |
| Heat/battery | Record before/after level and observed throttling/update-rate changes |

This test has no trustworthy absolute one-way latency measurement: the two clocks
are not synchronized. Do not subtract raw PC receive time and phone timestamp.
Use a later clock-sync protocol or external motion-to-display measurement to
quantify latency and its uncertainty. Phone finite-difference velocities may spike
on relocalization and should not be treated as ground truth.

Choose acceptance thresholds based on the intended experience before reviewing
results. This repository does not certify comfort, accuracy or headset suitability.
If baseline results are promising, the next step is repeated mounted tests and
latency instrumentation before implementing the virtual HMD bridge.

## Automated checks

For a visual desktop smoke test without a phone, start the viewer, Reset stream,
then run `./scripts/send-synthetic.ps1` from repository root. A generated object
moves and rotates for 10 seconds on loopback, then becomes stale. This uses
synthetic poses and is **not tracking evidence**. Reset before connecting the phone.

Android: from `android/AstraeusTracker`, run
`./gradlew.bat assembleDebug testDebugUnitTest lintDebug`.
Tests cover rotated recenter translation/orientation, velocity resets, quaternion
sign equivalence and exact byte agreement with the independent golden fixture.

Windows: build with CMake then `ctest --test-dir build/pc -C Release --output-on-failure`,
or use `pc/AstraeusPoseViewer/build-mingw.ps1`. Tests reject truncated/malformed
packets, check fixture fields, wraparound, duplicates, gaps, timestamp ordering,
session isolation, actual UDP loopback, origin revision and optional CSV output.

These tests do **not** exercise ARCore on a real camera, Samsung lifecycle behavior,
camera frame rate, radio/Wi-Fi delivery, drift, comfort or physical latency.
