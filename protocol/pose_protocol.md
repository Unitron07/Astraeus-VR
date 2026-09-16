# Astraeus pose protocol v1

One UDP datagram = exactly 96 bytes. Default destination port 4242. All integers
are unsigned little-endian; floats are IEEE-754 binary32 little-endian. No native
struct packing. Unknown versions/types/lengths must be rejected. No authentication,
encryption, retransmission or clock synchronization: use a trusted local network.

| Offset | Type | Meaning |
|---:|---|---|
| 0 | byte[4] | ASCII `ASTR` |
| 4 | u8 | version = 1 |
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
| 92 | u32 | reserved, zero |

Unavailable velocities are zero with validity bits clear. Pose fields on PAUSED
or STOPPED are identity placeholders, not measurements. Receivers must not move
the visualized device using invalid poses. Float fields must be finite; quaternion
norm must be within 0.01 of one. Reserved fields/bits must be zero in v1.

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

See `golden_pose.hex` for a cross-language fixture: seq=42, device=0, session=7,
timestamp=1000000000, origin=0, headset/ARCore/TRACKING, no velocities,
position=(1,2,-3), quaternion=(0,0,0,1).
