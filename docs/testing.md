# Repeatable S24 tracking test

## Milestone 1.5: next physical test

Install the rebuilt 0.1.5 APK and run the new Windows viewer. Close any older
viewer first. The local tested executable is
`pc/AstraeusPoseViewer/build/milestone-1.5/AstraeusPoseViewer.exe`.

1. Record commit, S24/Android/AR services versions, room, mount, network and
   battery/thermal conditions. Use a well-lit textured room and mark a repeatable
   camera position and orientation on a stable support.
2. Start the PC viewer on UDP 4242. Enter its LAN IPv4 in Android, connect, and
   expand tracking settings. Begin with **120 Hz**, calibrated gyro, default
   thresholds and gradual correction **off**. Settings apply on the next Start.
3. Start tracking, then Reset stream on PC if replacing an earlier session.
   Wait for ARCore TRACKING, compatible clock and Astraeus FULL_6DOF. Record actual
   gyro/accelerometer/ARCore/output rates, accuracy and selected sensor details.
   If clock compatibility is false, save diagnostics; do not treat the held pose
   as a successful fusion test. Requested rates are not measured rates.
4. Enable Android binary logging and PC CSV. Recenter once at the marked pose.
   Choose **View: Both**; cyan is raw ARCore in user coordinates, purple is public
   Astraeus. Cycle the button to inspect each separately. Do not recenter again
   within a trial. Note phase times or public sequence numbers.
5. **A — Stationary:** hold on the support for 60 seconds. Compare raw and public
   position/orientation drift, output intervals, quality and failure reason.
6. **B — Walk and return:** move 0.5 m along each axis, return to the exact mark,
   then walk a short loop and return. Hold 15 seconds after each return. Measure
   both return errors; continuity alone does not establish absolute accuracy.
7. **C — Brief visual loss:** while safely stationary, obscure the rear camera
   for roughly 1 second and rotate gently. Confirm PAUSED/failure reason,
   INERTIAL_ONLY orientation response, held position, and continuous recovery.
   Repeat with a longer loss to observe DEGRADED.
8. **D — Fast rotation:** with camera clear, perform controlled yaw, pitch, roll
   and combined turns, then a short rapid translation. Check for gyro tracking
   between visual frames, false discontinuities, frame mapping errors and spikes.
9. Stop logging to flush files, then stop tracking. Repeat A–D at **240 Hz** in a
   new session; compare requested and actual rates, sample gaps, CPU/heap and drops.
   Repeat a representative run with logging disabled to estimate its overhead.
   Finally repeat a recovery trial with gradual correction enabled if desired;
   record configured limits and verify residual convergence at the bounded rate.

For each trial report duration, rate distributions, maximum raw/public pose step,
discontinuity count, tracking-state/quality time, failure-reason counts, recovery
time, return error, gyro accuracy/gaps, camera age, clock anomalies, packet gaps
and log drops. Compare matched phone timestamps within one session/origin revision.
Use shortest quaternion angle with sign equivalence. Raw position is invalid
during PAUSED; identity placeholders must not become drift measurements. Do not
mix Android and PC clocks to claim one-way latency. End-to-end latency and
camera-to-eye calibration remain separate work.

## Log extraction and CSV conversion

PC logging creates a public-pose CSV and a `-diagnostics.csv` companion in the
viewer working directory. Diagnostics are sampled at approximately 10 Hz and
contain raw/user/world/public poses, innovations, rates, clocks and quality.
They can miss a transient raw frame: use the Android raw log for per-frame maxima.

Android logging creates `astraeus-<session>-<time>.bin` in external app files.
After disabling logging, pull files using the configured SDK (USB debugging):

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" pull /sdcard/Android/data/org.astraeus.tracker/files/ ./s24-logs
python scripts/decode_android_log.py ./s24-logs/astraeus-SESSION-TIME.bin --prefix ./s24-logs/trial-a
```

Replace SESSION-TIME with the actual filename. Python 3.8+ needs no packages.
The converter creates `.poses.csv`, `.diagnostics.csv`, `.imu.csv`, and `.raw.csv`
for record types present. Raw IMU retains original sensor timestamps, values,
bias and accuracy; raw ARCore retains every delivered unique frame, its paired
camera timestamp, arrival time, state/reason and sensor orientation. File records
have a big-endian four-byte length envelope followed by a little-endian payload.
The bounded background writer reports dropped records; reject incomplete trials
when drops compromise the measurement. Logs consume storage and can change timing.
Save them locally for analysis; they are ignored by Git.

## Historical Milestone 1 procedure

The procedure below describes the earlier raw-pose baseline. Milestone 1.5 uses
the binary logging and expanded A–D procedure above instead of logcat diagnostics.

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

To check a paused diagnostic and CSV value, run
`./scripts/send-synthetic.ps1 -TrackingFailureReason INSUFFICIENT_LIGHT`.
All six documented names are accepted; use `-Paused -TrackingFailureReason NONE`
to simulate normal initialization. Confirm the viewer's `tracking_failure_reason`
and the CSV column match. The headset object is hidden while PAUSED. Older v1
senders produce UNKNOWN because they never transmitted the reason.

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
