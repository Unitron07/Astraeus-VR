# Validation record — 2026-09-16

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
