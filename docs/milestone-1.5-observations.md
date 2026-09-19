# Why Milestone 1.5 exists

## Run #5: motivation for Milestone 1.5.1

The user reports a completed Milestone 1.5 physical run, beginning and ending at
the same marked physical pose. Around 18.85 seconds, while ARCore continuously
reported TRACKING, raw camera pose changed by approximately 31.9 cm and 55.7 degrees
within one visual update. Physical movement did not account for that step.
Immediate compensation held the public pose, but a corresponding correction
residual was subsequently paid back, moving the public pose while the phone was
stationary. Near-240 Hz output also had isolated sequence gaps, unlike the earlier
approximately 120 Hz run. These are supplied observations, not independently
recomputed statistics; the source Run #5 logs were not provided in this request.

Interpretation: a rejected raw coordinate change must not automatically become
public correction debt. A shared camera/anchor frame change is one hypothesis
that anchor-relative diagnostics can test. Run #5 did not log the new anchor data,
so it does not prove that a particular anchor would have moved consistently.
Milestone 1.5.1 uses the relative reference and returns the default to 120 Hz
(already the code default), preserving 240 Hz as an option. Its physical success
remains to be measured.

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
