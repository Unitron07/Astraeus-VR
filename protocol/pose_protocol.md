# Astraeus protocols: v3 fusion, legacy v1/v2

## Milestone 1.5: v3

Android-only log records also include `ARAW` (80 bytes, version 1, type 1).
Offsets: magic 0, version/type 4/5, u16 size 6; u64 frame/camera/arrival timestamps
8/16/24; u8 state/reason/clock-valid/reserved 32/33/34/35; camera XYZ+XYZW at 36;
Android sensor XYZW at 64. Poses are invalid when state is not TRACKING. These
records retain every delivered unique ARCore frame and are not sent over UDP.

The new tracker emits v3. The viewer still accepts v1/v2 without changing their
meaning or golden fixture. Old viewers reject v3; update both apps.
Little-endian fixed-width integers and IEEE-754 binary32 remain unchanged.
UDP port, endpoint/session locking and the one-slot freshness-first sender remain.

### Public pose: type 1, 112 bytes

Bytes 0–91 retain the field offsets in the legacy table below, with version=3 and
length=112. Position/quaternion now mean **public Astraeus fused pose**, not raw
ARCore. Timestamp at 24 is Android elapsedRealtimeNanos at output evaluation.
This is the time of an estimate, not a fabricated camera/IMU measurement timestamp.
Position is held between valid visual measurements. Orientation integrates actual
gyro samples and extrapolates at most 20 ms using the latest angular velocity.
After that, orientation holds and quality becomes DEGRADED. No accelerometer
position integration is used. Angular velocity is in recentered Astraeus world
coordinates. Linear velocity is a visual finite difference, valid only across
ordinary consecutive visual samples; corrections/loss/recenter invalidate it.

| Offset | Type | v3 meaning |
|---:|---|---|
| 92 | u8 | ARCore failure reason, same explicit IDs as v2 |
| 93 | u8 | Astraeus quality: 0 UNAVAILABLE, 1 FULL_6DOF, 2 INERTIAL_ONLY, 3 RECOVERING, 4 DEGRADED |
| 94 | u16 | reserved zero |
| 96 | u64 | latest integrated gyro measurement timestamp, Android boot-time ns; zero if absent |
| 104 | u64 | latest source Android camera timestamp, not necessarily a valid tracking pose |

ARCore source state at 38 is independent of Astraeus quality. PAUSED packets can
carry useful orientation and held position. Do not hide a v3 pose merely because
ARCore is paused. UNAVAILABLE means no valid world anchor exists; DEGRADED means
the held/partially tracked estimate must not be mistaken for healthy 6DoF.
Source clock validity is in diagnostics. Unknown quality/flags are rejected.

### Layer diagnostics: type 2, 352 bytes, at most 10 Hz

Common header 0–39 has version=3, type=2, length=352 and the **same sequence,
session, output timestamp and origin revision** as its accompanying public packet.
It does not consume another pose sequence number. It includes its own output
pose, so log analysis does not depend on both datagrams arriving together.
The two datagrams are offered as one item to the existing single-slot queue.
Diagnostic loss is allowed; counters/residuals remain in subsequent snapshots.

| Offset | Type | Meaning |
|---:|---|---|
| 40 | u64 | original ARCore Frame.timestamp (undefined ARCore clock domain) |
| 48 | u64 | paired Android camera timestamp |
| 56 | u64 | latest gyro timestamp |
| 64 | u64 | latest accelerometer timestamp |
| 72 | f32[7] | raw ARCore camera XYZ + XYZW; invalid placeholder unless source state=TRACKING |
| 100 | f32[7] | raw camera transformed by user recenter ONLY, for the comparison drawing |
| 128 | f32[7] | persistent world transform W: Astraeus-from-ARCore |
| 156 | f32[7] | user recenter transform U: user-from-Astraeus |
| 184 | f32[3] | raw gyro sensor XYZ rad/s |
| 196 | f32[3] | reported gyro bias XYZ rad/s, zero if calibrated source |
| 208 | f32[3] | accelerometer XYZ m/s² including gravity, sensor space |
| 220 | f32[4] | measured gyro, accel, ARCore and output Hz |
| 236 | f32 | position innovation meters |
| 240 | f32 | orientation innovation radians vs timestamp-aligned gyro estimate |
| 244 | f32 | implied raw mapped translation speed m/s |
| 248 | f32[2] | remaining correction: position meters, angle radians |
| 256 | f32[2] | last compensated jump: position meters, angle radians |
| 264 | u32 | count of compensated discontinuities/reacquisitions |
| 268 | i32 | latest gyro accuracy (-1 absent, Android 0–3 otherwise) |
| 272 | i32 | latest accel accuracy |
| 276 | u8 | camera clock compatibility/checks valid (0/1) |
| 277 | u8 | gyro is uncalibrated; bias is subtracted for fusion (0/1) |
| 278 | u8 | discontinuity since previous diagnostic emission (0/1) |
| 279 | u8 | quality (same as 349) |
| 280 | i64 | paired camera timestamp minus ARCore frame timestamp, ns |
| 288 | i64 | camera timestamp age at source callback arrival, ns |
| 296 | u32 | gyro timestamp/value anomaly count |
| 300 | u32 | tracking timestamp anomaly count |
| 304 | u32 | clock-check anomaly count |
| 308 | u32 | Android binary logging drops in current enabled log |
| 312 | f32 | Java heap in use, MiB (not process RSS) |
| 316 | f32 | process CPU seconds / elapsed seconds; 1.0 = one core |
| 320 | f32[7] | public output XYZ + XYZW at header timestamp |
| 348 | u8 | ARCore failure reason |
| 349 | u8 | Astraeus quality |
| 350 | u16 | reserved zero |

All pose quaternions must be unit length within 0.01, and all floats finite.
Rate/clock/IMU fields are snapshots, not guarantees about sustained device behavior.
The receiver validates exact lengths, IDs and finite values, selects the already
locked public stream, and rejects old/duplicate diagnostics separately. Diagnostic
packets never inflate public packet counts/gaps. Source IMU timestamps preserve
their original ns values; raw gyro data is not resampled to display cadence.

Fixtures `golden_fused.hex` and `golden_diagnostics.hex` are independently assembled
reference bytes consumed by Kotlin, C++ and Python tests. `golden_pose.hex` remains
the original v1 fixture.

## Legacy v1/v2 contract

One UDP datagram = exactly 96 bytes. Default destination port 4242. All integers
are unsigned little-endian; floats are IEEE-754 binary32 little-endian. No native
struct packing. Unknown versions/types/lengths must be rejected. No authentication,
encryption, retransmission or clock synchronization: use a trusted local network.

| Offset | Type | Meaning |
|---:|---|---|
| 0 | byte[4] | ASCII `ASTR` |
| 4 | u8 | version = 2; receiver also accepts legacy 1 |
| 5 | u8 | packet type = 1 (pose) |
| 6 | u16 | length = 96 |
| 8 | u32 | sequence; increments per sample, wraps modulo 2^32 |
| 12 | u32 | device_id = 0 for initial headset |
| 16 | u64 | session_id: random positive value on each Start |
| 24 | u64 | ARCore frame timestamp in nanoseconds, phone monotonic time domain |
| 32 | u32 | origin revision, starts at 0, increments on recenter |
| 36 | u8 | device_type: 1 HEADSET, 2 LEFT_CONTROLLER, 3 RIGHT_CONTROLLER, 4 TRACKER |
| 37 | u8 | tracking_source: 1 ARCORE; other values reserved |
| 38 | u8 | tracking: 0 STOPPED, 1 PAUSED, 2 TRACKING |
| 39 | u8 | flags: bit0 linear velocity valid, bit1 angular velocity valid |
| 40 | f32[3] | position XYZ, meters |
| 52 | f32[4] | unit quaternion XYZW, local device to origin space |
| 68 | f32[3] | linear velocity XYZ, meters/sec, origin space |
| 80 | f32[3] | angular velocity XYZ, radians/sec, origin space |
| 92 | u8 | tracking_failure_reason (v2); reserved zero in v1 |
| 93 | byte[3] | reserved, zero |

Failure reason IDs are explicitly mapped, not ARCore enum ordinals:

| ID | tracking_failure_reason |
|---:|---|
| 0 | NONE |
| 1 | BAD_STATE |
| 2 | INSUFFICIENT_LIGHT |
| 3 | EXCESSIVE_MOTION |
| 4 | INSUFFICIENT_FEATURES |
| 5 | CAMERA_UNAVAILABLE |
| 255 | UNKNOWN (unrecognized source reason or unavailable in legacy v1) |

IDs 6–254 are reserved and rejected in v2. The viewer and CSV use the names above.
`NONE` while PAUSED can indicate normal initialization; it does not imply TRACKING.
See [ARCore TrackingFailureReason](https://developers.google.com/ar/reference/java/com/google/ar/core/TrackingFailureReason).
New Android builds send v2. Update the PC viewer too: old v1 viewers reject v2.
New viewers accept v1 but display/log `UNKNOWN`, never assume its reserved zero is
an actual `NONE` measurement. CSV appends `tracking_failure_reason` after `accepted`
to preserve existing column positions. This field reports the camera reason from
each available ARCore frame; no frame means no new reason sample. Fatal camera
exceptions still stop the tracker and surface as Android errors/PC stream staleness.

Unavailable velocities are zero with validity bits clear. Pose fields on PAUSED
or STOPPED are identity placeholders, not measurements. Receivers must not move
the visualized device using invalid poses. Float fields must be finite; quaternion
norm must be within 0.01 of one. Reserved fields/bits must be zero in both versions.

The sample timestamp belongs to the camera frame, not send time. Sequence gaps
include local sender queue drops. Session IDs distinguish restarts. Compare
sequence numbers modulo 2^32; forward distances below 2^31 are newer. Duplicate
or older packets must not update the display. A viewer locks to the first endpoint,
session and device until Reset; this avoids old datagrams rolling state backward.

No ACK exists. Android's status means a local UDP destination is configured and
send calls succeed, **not** that a PC received anything. PC receipt is authoritative.
One-way latency cannot be computed by subtracting unsynchronized clock readings.
The viewer reports interarrival jitter (RFC-style EWMA of absolute changes in
receive interval minus sample interval) and age since local receipt instead.

Coordinates: right-handed, +X right, +Y up, -Z forward, meters, quaternion XYZW.
At startup the ARCore world is used. Recenter stores p0,q0 and maps
`p' = inverse(q0) * (p - p0)` and `q' = inverse(q0) * q`.
Full orientation is reset, including pitch/roll: +Y is then recentered up and may
no longer align with gravity. This is intentional, not yaw-only recentering.
Physical camera pose is used, independent of display rotation. Camera-to-eye and
phone mount calibration remain future work.

See `golden_pose.hex` for a legacy v1 cross-language fixture: seq=42, device=0, session=7,
timestamp=1000000000, origin=0, headset/ARCore/TRACKING, no velocities,
position=(1,2,-3), quaternion=(0,0,0,1).
