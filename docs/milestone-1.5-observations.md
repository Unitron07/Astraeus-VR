# Why Milestone 1.5 exists

## User-reported physical observations (three Galaxy S24 runs)

These are supplied experimental results, not measurements of this new code. The
source CSV files were not supplied in this task, so these summaries have not been
independently recomputed here.

- UDP: zero sequence gaps in controlled runs, no meaningful packet loss or
  out-of-order effect, stable binary transport/receiver.
- ARCore: approximately 30.0 samples/s, typically 33.33 ms, despite requesting
  supported 60 fps camera configurations.
- During TRACKING, multi-second stationary windows in run 3 had approximately
  sub-millimeter short-term positional variation.
- Run 1 included long PAUSED intervals with the camera facing down against a table.
- Run 3 included approximately 0.23 s PAUSED / INSUFFICIENT_FEATURES while walking.
- World-pose changes included approximately 1.5 m in 33 ms and approximately
  0.64 m around recovery, creating derived speeds around 45–50 m/s.
- Run 3 returned to a physically marked starting position/orientation, yet raw
  ARCore differed by approximately 29.3 cm (-14.0, -21.8, -13.7 cm XYZ) and 23.1°.

## Engineering interpretation

Transport is not the demonstrated bottleneck. Camera-rate poses are not a
high-rate orientation source. Gross frame-to-frame jumps are inconsistent with
the controlled headset motion and should not directly drive a public HMD pose.
The reported stationary behavior gives no reason to add heavy low-pass smoothing.

## Unverified hypotheses

Possible relocalization or world-map correction may contribute to the large
discontinuities and return error. The API does not label these events as such;
the detector therefore reports **ARCore world-pose discontinuities**, not proven
relocalizations. Return error must not be called pure continuous drift when the
same run contains tracking interruptions/world changes.

The new gyro/world-stabilized pipeline is a hypothesis to test. It has deterministic
software tests, but its comfort, true accuracy, timestamp alignment and sustained
rates on the S24 have not yet been measured.
