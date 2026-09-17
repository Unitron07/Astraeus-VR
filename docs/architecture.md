# Milestone 1 architecture

`ARCore Session -> ArCoreTracker -> OriginTransform / VelocityEstimator -> PoseSample`

`PoseSample -> PosePacket -> UdpTransport -> UDP -> decode -> Stream -> numerical / 3D viewer`

Android owns the camera and uses a foreground Activity with a GL context. A tiny
GL surface supplies ARCore's required external camera texture; camera frames are
not displayed or transmitted. Blocking session updates avoid spinning. Duplicate
frame timestamps are suppressed. A supported 60 fps camera configuration with the
smallest CPU image size is preferred; the default configuration is the fallback.
Measured rate, rather than display refresh, is reported. Plane detection and
lighting estimation are disabled. Tracking stops on backgrounding, camera errors,
or Stop; Start creates a new random session ID and fresh world origin.

ARCore access and recenter changes run on the GL thread. Numerical UI refresh is
limited to 4 Hz. UDP uses a separate worker and a one-sample queue; the oldest queued
sample is discarded when full. Every produced sample has a sequence number even
if no destination exists or a queued sample is dropped. Thus sequence gaps are
end-to-end omissions, not a pure network-loss measurement. No growing backlog.

`PoseMath.kt` is independent of Android and ARCore. `PosePacket.kt` is independent
of networking. `UdpTransport.kt` accepts encoded packets without understanding poses.
ARCore physical camera coordinates enter through `ArCoreTracker` once. No display
rotation conversion is applied. The local camera is a proxy for a headset, not a
calibrated eye position. See the [wire contract](../protocol/pose_protocol.md).

## Recenter and velocities

For origin `(p0,q0)` and raw camera `(p,q)`, the local position is
`rotate(conjugate(q0), p-p0)` and local orientation is `conjugate(q0) * q`.
Unit quaternions represent active device-to-world rotations, ordered XYZW.
At the selected pose the result is zero position and identity rotation. This full
orientation reset includes pitch and roll; it can tilt the local ground plane.
The revision increments so offline analysis can split runs. Recenter is consumed
only on TRACKING samples; a paused sample cancels a pending recenter request.

ARCore's public camera pose interface supplies no direct velocity here. Both
velocities are finite differences of successive valid origin-space poses:
`v=(p2-p1)/dt`; `dq=q2*inverse(q1)` is sign-adjusted so `dq.w >= 0`, and
`omega=axis(dq)*angle(dq)/dt`. Angular velocity is in **origin space**, not body
space. Validity resets on tracking loss, recenter, restart or intervals outside
1–200 ms. These are noisy diagnostic estimates, not independently verified IMU
velocities. Relocalization discontinuities may produce spikes even while tracking
is reported valid. No prediction, filtering or artificial smoothing is applied.

## Windows modules

C++17 with Winsock and Win32/GDI requires no third-party runtime framework.
The 3D scene uses an orthographic projection of the actual quaternion-transformed
headset wireframe, grid and axes. This keeps the prototype small; a future runtime
adapter can use pose data without any GDI dependency. View scale is fixed; a device
outside the field may leave the drawing area. Invalid/stale tracking hides the
object. Numerical diagnostics remain visible and explicitly show state.

The receiver thread parses and validates packets, maintains counters and optionally
writes buffered CSV. The UI snapshots state under a mutex at approximately 30 Hz.
CSV disk stalls can affect receipt: measure baseline with logging both off and on.
Packets from other endpoint/session/device combinations are counted but excluded
until Reset. Reset permits an intentional phone restart without accepting delayed
packets from prior sessions automatically. No network data controls file paths.

No transport ACK means the Android status cannot prove delivery. The viewer's
local receive age detects a stale feed after one second. Its jitter metric uses
changes in receive and phone sample intervals and needs no absolute clock offset.
No one-way latency claim is made. CSV receive timestamps use the PC monotonic
clock; phone sample timestamps use the ARCore timestamp domain. Neither is UTC.

## Extension boundaries

Future tracking sources can emit the same pose model; the protocol already carries
device ID/type/source. The current viewer deliberately selects one stream. Runtime,
video transport, decoder and compositor interfaces belong to later milestones.
There is currently no SteamVR, OpenXR, ALVR, Sunshine or Moonlight dependency.
Possible future integration into Asteria should preserve these module boundaries.

## Primary references

- [ARCore Camera](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera)
- [ARCore Session](https://developers.google.com/ar/reference/java/com/google/ar/core/Session)
- [ARCore Frame](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- [Android Gradle Plugin 8.9 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes)
