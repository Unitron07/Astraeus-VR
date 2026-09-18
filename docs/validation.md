# Validation record — 2026-09-16

## Milestone 1.5 — 2026-09-18

Android 0.1.5 debug APK assembled successfully with the pinned toolchain. All
**28 JVM tests** passed (17 fusion/state-machine, 4 packet/log, 7 existing pose/
transport tests). Lint passed with **zero errors, 22 warnings**; remaining warnings
cover diagnostic UI strings/layout, SDK/library updates and application metadata.
No Android compiler warnings appeared in the final build.

Windows x64 GCC 16.2 build passed with `-Wall -Wextra -Wpedantic` and no compiler
warnings. All three test executables passed: 139 legacy protocol checks, real UDP
receiver/CSV integration including v3 diagnostics, and v3 independent fixtures,
malformed packet rejection, stream ordering and diagnostics CSV. Python binary
log conversion tests passed (2 tests, including raw-frame/IMU conversion and
truncation rejection). Git whitespace checks passed.

Fusion tests cover signed yaw/pitch/roll, quaternion normalization/sign equivalence,
combined and sequential rotations, sensor-frame conversion, timestamp anomalies,
delayed visual measurement alignment, brief loss with continuing rotation, held
position, recovery/jump compensation, fast legitimate motion, recenter, gradual
translation/rotation limits, gyro accuracy loss and recovery quality transitions.
These are deterministic synthetic tests, not measurements of the S24.

Windows GUI was opened with synthetic UDP fixtures. Numerical diagnostics and
the raw cyan/public purple objects were visible, and the view-mode button changed
its selected mode. Fixture rate values are synthetic and prove no sensor rate.
Android UI and sensor/lifecycle behavior still require on-device testing.

Artifacts: `android/AstraeusTracker/app/build/outputs/apk/debug/app-debug.apk` and
`pc/AstraeusPoseViewer/build/milestone-1.5/AstraeusPoseViewer.exe`.
See [testing](testing.md) for installation and the exact A–D physical sequence.
Earlier user-reported raw-tracker measurements are in
[observations](milestone-1.5-observations.md), separate from this validation.

Ready for Milestone 1.5 physical testing, not physical acceptance. Actual gyro
rates/accuracy, camera/IMU clock compatibility and exposure alignment, mount axes,
false-positive/negative thresholds, drift, latency, thermal behavior and logging
overhead remain unmeasured for this implementation. There is no acceleration
position integration. Continuity compensation intentionally trades absolute
world alignment for a continuous output; slow map corrections may pass through.
No SteamVR/OpenXR driver or video work has begun. Historical results follow.

## Tracking failure reasons update — 2026-09-17

Android debug build, seven JVM tests and lint passed (zero lint errors; existing
14 warnings remain). The SDK enum fallback also emits a non-blocking redundant
`else` compiler warning. Windows build passed 139 protocol checks and real UDP/CSV
integration tests covering all six failure names, plus legacy v1 UNKNOWN behavior.
The Windows output for this run is `pc/AstraeusPoseViewer/build/tracking-reasons/`
because the previous viewer executable was running. Close the old viewer before
launching the new one on the same port. Reinstall the rebuilt APK as well.
Physical ARCore failure conditions have not been tested on the S24.

## Local builds

- Windows x64: w64devkit 2.10.0 / GCC 16.2.0, C++17, `-Wall -Wextra -Wpedantic`.
  Viewer and both test executables built successfully, with no compiler warnings
  on the final build. The viewer is statically linked to its compiler runtime.
- Android: JDK 21.0.10, Gradle 8.11.1 (official SHA-256 pinned), AGP 8.9.2,
  Kotlin 2.1.20, SDK 35 / Build Tools 35.0.0, ARCore 1.48.0. Debug APK assembled.
- Android lint: zero errors. Fourteen non-blocking warnings cover a newer ARCore
  release being available, fixed landscape orientation, backup configuration,
  missing custom application icon and English diagnostic strings. They do not
  establish hardware compatibility or tracking quality.

## Automated checks

- C++ protocol/stream test: 116 checks passed, including fixture fields, all short
  packet lengths, version/reserved/NaN/quaternion validation, modular wraparound,
  duplicates, gaps, timestamp regressions and session isolation.
- Windows real UDP loopback: passed. Exercises receive/decode, duplicates,
  sequence gaps, malformed version, session lock/reset, recenter revision and
  opt-in CSV creation/content. The test removes only its own generated CSV.
- Kotlin JVM tests: all six passed. Pose recenter math, finite-difference reset behavior,
  quaternion sign equivalence, angular velocity magnitude/direction, shared
  binary golden fixture and actual Kotlin UDP sender loopback.
- Git whitespace check: passed.

## Hardware / visual limitations

No Galaxy S24 physical test has been performed in this run. No measured drift,
tracking-loss rate, return-to-origin accuracy, radio jitter, battery consumption,
thermal behavior or absolute tracking latency is available. Android camera,
permissions/install flow, app lifecycle and mount alignment require on-device
validation. Windows GUI interaction and visual alignment need the documented
synthetic smoke test and physical run; automated tests exercise the receiver and
protocol, not screenshots.

The independent GitHub Windows/MSVC build and both CTest cases passed on commit
`55aa4c6`. The initial Android CI setup attempted to install the obsolete SDK
`tools` package; the workflow now explicitly requests `platform-tools`. Consult
GitHub Actions for the corrected workflow's separately reported outcome and
downloadable build artifacts.
