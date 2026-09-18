# Milestone 1.5 tracking architecture

`ArCoreTracker + AndroidImuSource -> TrackingEngine -> AstraeusPose`

`AstraeusPose -> public UDP / buffered log / Android UI / future runtime adapter`

ARCore is a visual/world anchor. Astraeus owns a persistent world, user origin and
high-rate orientation. No SteamVR, OpenXR, video or controller implementation is
included. [Observed motivation](milestone-1.5-observations.md) distinguishes the
user's physical results from interpretations and unverified hypotheses.

## Coordinate chain and ownership

All rigid transforms map local coordinates to their parent; quaternions are
right-handed, active XYZW, multiplication `parent * local`. Camera space is +X
right, +Y up, -Z forward. Public units are meters and radians.

1. Raw physical camera pose C in ARCore world (independent of screen orientation).
2. Persistent W = Astraeus-from-ARCore, initially identity.
3. User U = user-from-Astraeus, initially identity.
4. Public position is `(U * W * C).position`; public orientation replaces the
   visual orientation with the timestamped gyro estimate in that same world.

Gyro vectors use Android's native sensor coordinates. `SensorFrameTransform`
calibrates the **rotation** q_camera_from_sensor as
`inverse(q_raw_camera) * q_android_sensor` from paired TRACKING poses using
ARCore's `Frame.getAndroidSensorPose()`. This is a device-fixed extrinsic, latched
once per session; no screen-rotation swaps, hard-coded Samsung axis mappings or
assumed gyro-to-camera alignment are scattered through the code. Camera position
remains the positional authority; there is no camera-to-eye lever-arm calibration.

The diagnostic raw comparison drawing uses U*C **without W**. After a compensated
world change, raw and public drawings need not coincide, even after recenter.
This intentionally exposes what a naive raw-pose client would see. The CSV also
preserves C itself, W, U, and the actual public output.

## Sensor choice and actual sampling

`AndroidImuSource` registers gyro and accelerometer on a dedicated HandlerThread,
requests SENSOR_DELAY_FASTEST and zero batching latency, and declares
HIGH_SAMPLING_RATE_SENSORS. The default is calibrated gyro: no new online bias
estimator is invented. The optional uncalibrated mode subtracts the three reported
bias components before integration and logs both original measurement and bias.
The other gyro type is a fallback if the selected type is missing. Accuracy is
retained; accuracy <= 0 degrades/holds orientation until a trustworthy source and
fresh visual re-anchor are available.

Accelerometer data (including gravity) is diagnostic only. It is never integrated
into position. Requested rates are not measured rates: the UI calculates rates
from original increasing sensor timestamps, reports sensor name/minDelay and
registration success, and retains timestamps in logs. Privacy toggles and device
policy may cap sampling. Only an S24 run can establish sustained gyro/accel Hz.

## Time domains: verified API contract and remaining uncertainty

- SensorEvent.timestamp shares Android elapsedRealtimeNanos' boot-time domain.
- ARCore Frame.timestamp explicitly has an **unspecified time base**. It is retained
  for provenance and source cadence, never directly subtracted from gyro time.
- Frame.getAndroidCameraTimestamp gives the paired Android camera image timestamp.
  The selected camera ID's SENSOR_INFO_TIMESTAMP_SOURCE must be REALTIME to use
  that timestamp with gyro/elapsedRealtimeNanos.
- CameraTimeMapper is the single boundary: record camera-minus-frame offset; reject
  zero, old, duplicate, future, >500 ms age, and >5 ms inter-domain interval mismatch.
  No arrival-time offset estimation quietly substitutes for measurement time.
- UNKNOWN camera timestamp source disables fusion anchoring (UNAVAILABLE), while
  raw diagnostics remain available. Clock-check failure after an anchor degrades
  to held position/inertial behavior, visibly; fix/measure the source before use.
- Public packet time is actual elapsedRealtimeNanos at output evaluation. It denotes
  a **pose estimate**. The latest gyro and visual measurement times remain separate.
- PC receive time is a different machine's monotonic clock. Neither side claims
  absolute one-way latency or motion-to-photon latency.

Clock compatibility does not prove exact optical/IMU alignment. Camera exposure
start versus ARCore's effective camera-pose exposure/readout timing can introduce
a small temporal offset; this needs the rapid-rotation experiment and potentially
camera exposure/readout metadata instrumentation. Logged camera callback age is
delivery age on the phone, not network latency or complete pose latency.

## Orientation propagation

OrientationFusion stores a bounded history (2 s / 2048 entries) of integrated
sensor-frame rotations. Consecutive gyro angular velocities are trapezoid-averaged;
the quaternion exponential of omega*dt is **right-multiplied**, then normalized.
History interpolation is only between integrated gyro states, never a substitute
for gyro using old camera frames.

At a delayed visual measurement time, the engine looks up the integrated gyro
orientation at that measurement. Future camera orientation is
`q_anchor * q_camera_from_sensor * inverse(g_anchor) * g_now * inverse(q_camera_from_sensor)`.
Thus camera arrival delay is not mistaken for head motion. Constant-rate
extrapolation is limited to 20 ms after the last gyro sample. Later queries hold
the last public orientation and mark DEGRADED; they do not integrate an unlimited
stale velocity. Duplicate/old/non-finite samples are discarded, >100 ms gyro gaps
reset history/generation, and sensor restarts create a new history on Start.
Accuracy transitions invalidate the old anchor/history.

Healthy visual orientation disagreement is corrected at a bounded default
0.5 degree/s. This is an explicit bias/anchor correction, not heavy pose smoothing.
It is configurable (including zero). Angular velocity is preferably gyro-derived,
rotated through camera and fused public orientation into **user/Astraeus world**.

## World discontinuities, continuity and correction

The detector compares W*C to the prior stable position and timestamp-aligned gyro
orientation. Defaults: translation >0.15 m **and** implied speed >8 m/s, or orientation
innovation >35 degrees with a valid gyro prediction. Thresholds are HMD-oriented,
configurable and unvalidated comfort settings. Ordinary 2 m/s walking and fast
gyro-consistent rotations are explicitly covered by tests.

PAUSED retains position and continues trustworthy gyro orientation. Visual silence
>200 ms also loses positional authority. Recovery after PAUSED, a visual gap, clock
failure or detected ARCore timestamp restart is handled as a continuity event.
For expected continuous pose E at the recovered measurement time, assign:
`W_new = E * inverse(C_recovered)`. This absorbs both translation and rotation.
No finite-difference velocity crosses that event. The diagnostic counter includes
compensated reacquisitions even when the innovation is small.

The old target world is retained as a potential correction destination. Optional
gradual correction moves the **current mapped camera position** toward that target
at <=0.01 m/s and orientation at <=0.25 degree/s, updating W around the current
head pose. This avoids a distant world-origin rotation causing an unbounded
positional arc. Rates are independent, configurable and disabled by default.
Residuals are computed at the current camera pose; movement itself can change
their magnitudes. Correction invalidates visual finite-difference velocity.

Continuity is not objective accuracy. Holding compensation can retain drift;
bleeding correction can produce perceivable world motion. Rotation correction
requires particular caution in physical evaluation. There is no asserted optimal
rate or comfort certification. Raw data is always kept for comparison.

## Quality and recenter

UNAVAILABLE: no valid clock-aligned world anchor.
FULL_6DOF: fresh visual source and gyro propagation.
INERTIAL_ONLY: visual loss, trustworthy gyro, within 2 s of the last valid visual pose.
RECOVERING: 500 ms after compensation, or optional residual correction underway.
DEGRADED: gyro/history stale/unreliable, or longer visual loss.

These describe availability/continuity, not accuracy guarantees. With gradual
correction off, FULL_6DOF can resume after the settling interval while a nonzero
world residual remains; that residual stays visible. Fatal ARCore update errors
preserve inertial orientation/held position, show an explicit restart error, and
require Stop/Start to restart the camera. After 2 s of visual loss quality degrades.

Recenter uses the current continuous pose P and sets U=inverse(P). It works during
short visual loss after an anchor exists. W and gyro integration history remain
intact; velocity is invalidated/reseeded, origin revision increments, and the
current W becomes the new correction target. Pending correction is canceled.
Recenter is not counted as a world discontinuity. Full pitch/roll are reset as
before; local up need not remain gravity-up. Start creates a new session/world;
the PC must Reset stream to intentionally select it.

## Threading, output and logging

ARCore lives on its GL thread, IMU on a sensor HandlerThread, output on an independent
scheduled worker. Short synchronized snapshots serialize state. UI refresh is 4 Hz.
Output defaults to 120 Hz, configurable 30–240 (test 120/240). A late timer evaluates
the actual current time and skips catch-up bursts. It is not clocked by the camera
or display. Public data is 112 bytes; 352-byte diagnostics are <=10 Hz, paired in
the existing one-slot UDP queue. No growing network backlog or transport redesign.

PC public CSV records every selected-stream pose including validity, quality,
source state/reason and measurement timestamps. A sibling `-diagnostics.csv`
contains the complete decimated source/transform/IMU/innovation snapshot and its
own fused pose. Diagnostic packet ordering is independent of public pose counters.

Android's optional binary log has a bounded 512-record queue, buffered storage and
a writer thread; the tracker never waits for individual writes. It records all
public poses, diagnostic snapshots, every unique raw ARCore frame and raw gyro/accel samples. Dropped logging
records are counted. A slow close flushes/drains the log; stop logging before
backgrounding/copying it. Normal tracking creates no file. Decoder and exact record
envelopes are documented in [testing](testing.md).

Heap in use and process CPU time/elapsed time are available in diagnostics. These
are practical instrumented observations, not yet measured S24 results. PC CSV
writes still occur on the receiver thread; compare logging on/off before making
performance claims. No accelerometer dead reckoning, heavy low-pass smoothing,
controllers, streaming or runtime integration is hidden in this milestone.

## Primary references

- [ARCore Frame timestamp and sensor pose](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- [ARCore physical camera pose](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera)
- [SensorEvent timestamps and coordinates](https://developer.android.com/reference/android/hardware/SensorEvent)
- [Camera timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)
- [High-rate Android sensor permission](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
