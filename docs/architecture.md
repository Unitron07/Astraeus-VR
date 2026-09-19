# Milestone 1.5.1 architecture

## Evolution from Milestone 1.5

AndroidImuSource, OrientationFusion, CameraTimeMapper and the output scheduler
retain their roles. ArCoreTracker now owns one AnchorReference backend and samples
camera and anchor after the same Session.update. TrackingEngine consumes their
relative pose instead of raw world pose. Its former persistent raw-world transform
is now alignment: anchor space -> Astraeus space. MainActivity detaches the anchor
after pausing the GL renderer and before closing the ARCore session.

Raw camera world coordinates remain diagnostics. **Astraeus never owes a correction
to raw ARCore world coordinates merely because their numerical frame changed.**

## Transform hierarchy and conventions

RigidPose.compose(B) implements A * B: apply B first, then A.
Quaternions are XYZW, Hamilton multiplication, right-handed. Positions are meters;
angles/rates in logs are radians/radians per second unless the UI labels degrees.
The physical camera convention is +X right, +Y up, -Z forward.

```text
ARCore-owned current world
    A = T_world_anchor      C = T_world_camera
                inverse(A) * C
                       |
              R = T_anchor_camera
                       |
              L = T_astraeus_anchor
                       |
           stabilized visual pose L * R
                       |
     measurement-time gyro anchor + gyro propagation
                       |
              U = T_user_astraeus
                       |
                public HMD pose
```

A and C may both change numerically on Session.update. Read both within that
update; never retain a stale anchor pose to combine with a new camera pose.
A shared rigid update J cancels: inverse(J*A)*(J*C) = inverse(A)*C.
This is an invariant of the implementation and deterministic tests, not a promise
that all ARCore map changes will be perfectly shared in physical use.

The local anchor is created at the camera pose after three consecutive camera
TRACKING frames with valid timestamp mapping. Its axes therefore match the camera
at creation, not necessarily gravity. L initially is identity and normally remains
fixed. Only safety/reacquisition/replacement alignment changes L. U changes on
user recenter, which preserves the anchor and L and increments origin revision.
Full recenter includes pitch and roll. No camera-to-eye offset is modeled.

The legacy diagnostic field world remains the effective current transform
L * inverse(A), from raw world to Astraeus. It is derived, not a persistent raw-world
target. New alignment fields expose L explicitly. raw_user remains U*C purely for
legacy raw-world visualization; its axes are not a physically aligned comparison
once the anchor differs from the raw world origin. Numeric camera_anchor fields
are the meaningful visual reference. Raw and anchor world poses remain unmodified.

## Anchor lifecycle

AnchorReference is a platform-independent lifecycle policy around an AnchorHandle.
The ARCore adapter creates a local Session.createAnchor, with no cloud service,
plane detection requirement or networking. Three consecutive healthy frames seed
the reference; an unhealthy frame resets the warmup count.

TRACKING anchors are retained. PAUSED anchors are retained too, their poses are
unusable for fusion, and position is held while gyro orientation continues.
The first transition from tracked anchor to unusable increments anchor_loss_count
and records ANCHOR_LOST. STOPPED anchors are sampled/logged once, detached, and
retired. After three new healthy camera/clock frames a replacement is created with
a monotonically increasing session-local anchor ID. It is never silently replaced
because of a raw-world jump. A permanently PAUSED anchor remains retained until
session restart rather than guessing that it is safe to replace it.

Replacement is explicitly classified ANCHOR_REPLACED. L is solved so the new
relative pose maps to the previous continuous Astraeus pose. Velocity is invalidated,
no old-anchor correction target is carried across, and a 0.5-second recovery settling
window is entered. U/origin revision do not change; anchor ID exposes the new basis.
Before any usable reference output is UNAVAILABLE. A stopped reference after prior
initialization yields a safely held DEGRADED estimate. Creation failures stop the
visual source with an explicit error; Stop/Start begins a new session.

## Event classes and correction policy

- **A: RAW_WORLD_UPDATE_COMPENSATED.** Consecutive valid samples of the same anchor
  show a large raw-camera and anchor-world step while the relative step is small
  and consistent with the fusion safety checks. Tracking continues normally.
  This event does not change L, create residuals, invalidate a valid relative
  velocity or start RECOVERING. Existing unrelated reacquisition correction, if
  already pending, is not erased. All changes are still logged even below the
  classification thresholds.
- **B: ANCHOR_RELATIVE_DISCONTINUITY.** Relative position innovation exceeds 0.15 m
  AND implied speed exceeds 8 m/s, or gyro-aligned orientation innovation exceeds
  35 degrees. These configurable experimental thresholds retain the previous
  safety mechanism but apply it to L*R. Solve L = expected * inverse(R), protecting
  public continuity. Invalidate velocity, record the event, settle for 0.5 seconds.
  Commit this alignment with zero correction debt: a rejected anomaly is not
  gradually paid back.
- **C: TRACKING_REACQUISITION.** The same anchor becomes usable after camera/anchor
  PAUSED, invalid clock, a forced timestamp reset, or a valid visual gap above
  200 ms. Preserve held position and gyro orientation at measurement time by
  solving L. The mismatch may contain actual unseen translation and map correction;
  the system cannot distinguish them. Retain a same-anchor targetAlignment and
  expose the mismatch as residual uncertainty. No instantaneous snap is applied.

Gradual correction is off by default. When explicitly enabled it reconciles only
Class C same-anchor mismatch toward targetAlignment*R, capped at 0.01 m/s and
0.25 degrees/s by default. It never targets raw camera world coordinates.
Recenter commits the current alignment, cancels pending residuals and resets
velocity. No motion-adaptive recovery or translational velocity carry is present.

Event classification is supplemented by an event bitmask so coincident clock/
sensor anomalies are not hidden by a later reference event. Counters separately
track A, B, reacquisition (including replacement) and anchor loss. The legacy
discontinuity counter includes continuity compensation/reacquisition, not Class A.
Event/step values describe the last visual update; heartbeat diagnostics repeat
that update, so deduplicate by frame timestamp and anchor ID when counting events.

## Fusion, timestamps and velocity

OrientationFusion integrates actual gyro timestamps with quaternion exponential
increments and trapezoidal angular rate, retaining two seconds/2048 entries.
Historical lookup aligns delayed visual measurements; future propagation is capped
at 20 ms. A gap above 100 ms resets gyro history; non-monotonic samples are rejected.
Accuracy loss prevents trusted gyro velocity/orientation use until reanchored.

SensorFrameTransform calibrates camera-from-Android-sensor orientation from the
raw camera quaternion and frame.androidSensorPose in the same ARCore frame.
This device-fixed conversion is unchanged by a shared world update. Body-frame
gyro deltas then propagate the anchor-relative visual orientation. Normal visual
orientation reference correction remains bounded at 0.5 degrees/s. Long-term
orientation authority is anchor-relative visual tracking, not an absolute gyro world.

Frame.timestamp is not assumed to use the IMU clock. The paired
Frame.androidCameraTimestamp is accepted only for the selected camera whose
SENSOR_INFO_TIMESTAMP_SOURCE is REALTIME, and after monotonicity/interval/age
validation. SensorEvent.timestamp uses elapsed realtime. Unknown camera clocks
fail closed. The potential difference between camera timestamp convention and
ARCore pose exposure timing still requires device measurement; no exact exposure
alignment is claimed.

Position holds between visual frames and through visual loss. There is no
accelerometer double integration, linear extrapolation or last-velocity carry.
Linear velocity uses consecutive healthy, consistently aligned relative visual
positions. Creation, recenter, loss, reacquisition, genuine discontinuities,
replacement and active alignment corrections invalidate it. Angular velocity
comes from trusted gyro transformed into public/user coordinates.

## Quality states

Priority is: no initialized reference -> UNAVAILABLE; stopped reference or
unusable gyro -> DEGRADED; unhealthy visual input for over two seconds -> DEGRADED;
brief visual loss with trusted gyro -> INERTIAL_ONLY; otherwise settling or active
configured residual reconciliation -> RECOVERING; otherwise FULL_6DOF.

RECOVERING occurs for 0.5 seconds after B, C or replacement, and beyond that while
enabled same-anchor correction has residual above 1 mm or 0.001 rad. A valid Class A
update does not trigger it. With correction disabled, a Class C residual may remain
as diagnostic uncertainty after settling while quality returns FULL_6DOF; the held
alignment is then the accepted reference. Quality describes current observability,
not certified absolute accuracy.

## Threads, transport and diagnostics

The sensor HandlerThread, GL visual source and output scheduler serialize engine
access. Sensor timestamps, not callback intervals, drive integration. Public output
defaults to 120 Hz; 240 Hz remains configurable. Late ticks use current time and
skip catch-up bursts. UDP retains the one-slot freshness-over-completeness queue.

Public packets remain v3/112 bytes. Diagnostics are now v4/488 bytes with an
independent sequence. Every delivered visual update generates a complete snapshot
and logs it before UDP selection; when visual frames stop, a 10 Hz heartbeat
continues. The next public batch carries the latest pending diagnostic. Therefore
UDP can omit diagnostics under load; independent diagnostic sequence gaps and
cumulative event counters expose that. For exact events use the Android binary
log and check its drops. There is no unbounded delivery queue.

The binary log retains all raw ARCore frames, accepted IMU samples, public poses
and per-visual anchor diagnostics with a bounded 512-record writer queue. Disk
writes are buffered off the tracking thread; close drains the queue and can wait
on storage. PC CSV still writes on the receiver thread. Compare logging enabled
and disabled before attributing timing behavior to the tracker.

The viewer accepts legacy v1/v2/v3 public packets and v3/v4 diagnostics. Missing
anchor fields in v3 CSV rows are blank, not fabricated measurements. v4 adds
anchor ID/state/pose, camera-relative pose, alignment, six per-visual step values
with validity flags, event enum/bitmask and separate counters.

## Boundaries and physical validation

One local anchor is appropriate for this focused experiment; large spaces,
permanently paused anchors, anchor-relative map distortion and false detector
thresholds remain limitations. Shared-update invariance alone proves neither
return-to-origin accuracy nor low motion-to-photon latency. Run #5 motivates the
change but is not validation of this implementation. See testing.md and validation.md.
No runtime driver, streaming, controller, compositor, adaptive recovery or
translational prediction work is included.

## Primary references

- [ARCore anchor pose/state and update semantics](https://developers.google.com/ar/reference/java/com/google/ar/core/Anchor)
- [ARCore local anchor creation](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#createAnchor(com.google.ar.core.Pose))
- [Frame timestamps and sensor pose](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- [SensorEvent timestamps and coordinates](https://developer.android.com/reference/android/hardware/SensorEvent)
- [Camera timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)
