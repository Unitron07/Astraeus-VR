# Astraeus

Experimental standalone phone-based PCVR research. Milestone 1.5.1 combines camera-relative-to-anchor visual tracking with timestamped Android gyro propagation. Headset-quality tracking has not been demonstrated.

## Current status

Milestone 1.5.1 implementation: persistent local ARCore anchor, high-rate rotational fusion, position hold during
visual loss, world-pose discontinuity compensation, separate user recenter,
raw/fused viewer comparison and buffered diagnostic logging. No streaming, VR
runtime driver or controllers are implemented. The new code requires physical
S24 testing. Shared camera/anchor world changes create no recovery debt. Genuine
relative anomalies and reacquisition remain separate safety paths. Earlier user-reported measurements are recorded separately in
[observations](docs/milestone-1.5-observations.md). See [validation](docs/validation.md)
and the [Milestone 1.5 test procedure](docs/testing.md).

The viewer and CSV include `tracking_failure_reason`: NONE, BAD_STATE,
INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES or CAMERA_UNAVAILABLE.
Update both apps for v3 public poses and v4 anchor diagnostics. The viewer also accepts v1/v2 trackers (v1
reports UNKNOWN for the missing failure reason). NONE during PAUSED can mean
normal initialization. Failure reason and fused tracking quality are separate.

## Architecture

`inverse(anchor world) * camera world -> Astraeus alignment -> gyro fusion -> user origin U -> public pose`

- [Android](android/AstraeusTracker): tracking, pure Kotlin pose math, encoder and transport modules.
- [PC](pc/AstraeusPoseViewer): independent protocol decoder, stream diagnostics, Winsock receiver and Win32/GDI visualization.
- [Protocol](protocol/pose_protocol.md): 112-byte public pose, 488-byte anchor diagnostics, legacy layouts and coordinate conventions.
- [Architecture](docs/architecture.md): threading, recenter math, velocity derivation and future boundaries.
- [Roadmap](docs/roadmap.md): runtime bridge, stereo streaming, optics, latency, reprojection and controllers.

## Requirements

- Samsung Galaxy S24 with Google Play Services for AR, rear camera access and USB
  debugging for installation. App minimum Android 9/API 28; primary target is S24.
- Windows 10/11 x64 PC and trusted LAN/Wi-Fi shared with the phone.
- Android build: JDK 17 or 21, Android SDK Platform 35, Build Tools 35.0.0,
  Platform Tools and Internet access for pinned Gradle/Google Maven dependencies.
- PC build: Visual Studio 2022 Build Tools with **Desktop development with C++**,
  Windows SDK and CMake 3.20+, or the MinGW-w64 alternative below.

## Fresh clone and Android build

```powershell
git clone https://github.com/Unitron07/astraeus-vr.git
cd astraeus-vr
```

Install [Android Studio](https://developer.android.com/studio). In SDK Manager,
install Android SDK Platform 35, Build Tools 35.0.0, Android SDK Command-line Tools
and Android SDK Platform-Tools. Set `JAVA_HOME` to your JDK 17/21 installation and
`ANDROID_HOME` to the SDK directory shown by SDK Manager. If SDK Manager requests
license acceptance, review and accept it to complete installation. Android Studio
can alternatively open `android/AstraeusTracker` and use its configured SDK/JDK.

In PowerShell, with your SDK/JDK paths configured:

```powershell
cd android/AstraeusTracker
./gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

The committed Gradle wrapper downloads Gradle 8.11.1. Dependencies are pinned to
Android Gradle Plugin 8.9.2, Kotlin 2.1.20 and ARCore 1.48.0. The debug APK is
`android/AstraeusTracker/app/build/outputs/apk/debug/app-debug.apk` from repository
root. A local `local.properties` with `sdk.dir=...` can replace `ANDROID_HOME`;
it is machine-specific and ignored by Git.

### Install on the Galaxy S24

1. On phone: Settings -> About phone -> Software information -> tap Build number
   seven times. Enable USB debugging in Developer options.
2. Connect a data-capable USB cable and accept this PC's debugging prompt.
3. From repository root, run:

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" devices
& "$env:ANDROID_HOME/platform-tools/adb.exe" install -r android/AstraeusTracker/app/build/outputs/apk/debug/app-debug.apk
```

The device must say `device`, not `unauthorized`. Launch **Astraeus Tracker**.
Allow camera access. If Google Play Services for AR requires installation/update,
complete it and press Start again. No ARCore cloud API key or cloud project is
needed for local motion tracking. Disconnect USB if measuring wireless behavior.

## Windows build

From repository root, use an **x64 Native Tools Command Prompt for VS 2022** with
CMake available, or a PowerShell session with those build tools configured:

```powershell
cmake -S pc/AstraeusPoseViewer -B build/pc -G "Visual Studio 17 2022" -A x64
cmake --build build/pc --config Release
ctest --test-dir build/pc -C Release --output-on-failure
./build/pc/Release/AstraeusPoseViewer.exe
```

The application only links Windows system libraries. CMake also builds decoder
and real UDP loopback tests. The portable decoder test can be built on other
platforms; the viewer and socket integration test require Windows.

### Alternative tested MinGW-w64 build

Extract the official [w64devkit](https://github.com/skeeto/w64devkit/releases)
Windows x64 distribution. Run from repository root, replacing the compiler path:

```powershell
./pc/AstraeusPoseViewer/build-mingw.ps1 -Compiler C:/tools/w64devkit/bin/g++.exe
./pc/AstraeusPoseViewer/build/AstraeusPoseViewer.exe
```

The script builds a statically linked executable and runs all three C++ tests. The
executable is in `pc/AstraeusPoseViewer/build/`. To select another UDP port, pass
it as the sole argument, for example `AstraeusPoseViewer.exe 5000`.
If a running viewer locks that output file, build with
`-OutputDirectory build/tracking-reasons` and close the old viewer before starting
the new executable on the same port.

## First tracking run

1. Start the viewer. Use `ipconfig` to find the PC's LAN IPv4 address.
2. If Windows Firewall prompts, allow the viewer on your trusted **Private**
   network. If packets remain blocked, add an inbound UDP rule for the chosen
   port scoped to your phone/local subnet using Windows Defender Firewall with
   Advanced Security. Do not disable the firewall.
3. On the phone, enter the PC IPv4 and `4242`; press **Connect to PC**, then
   **Start Tracking**. Keep the rear camera unobstructed.
4. Wait for TRACKING and PC packet counts. Press **Recenter / Set Origin** while
   at a known, level position. PC XYZ should be near zero; quaternion near identity.
5. Enable PC CSV logging and follow the [30-second drift and movement test](docs/testing.md).
6. Stop CSV logging to flush it. Stop Tracking when done. Each Android restart
   creates a new stream: press **Reset stream** in the PC viewer to accept it.

If the PC stays WAITING, verify IPv4/port, firewall, Wi-Fi client isolation and
matching LAN. Android's UDP status cannot confirm receipt. ARCore errors appear
in the app; tracking loss reports the ARCore failure reason. A STALE PC feed means
no accepted packet arrived in the last second. CSV writes only when enabled;
files named `astraeus-<time>.csv` are created in the viewer's working directory.

## Known limitations

- No claim of headset-quality accuracy, comfort or latency; Milestone 1.5.1 S24 tests pending.
- Camera physical pose is a headset proxy; no camera-to-eye/mount calibration.
- ARCore bounds position updates; gyro propagation supplies intermediate orientation.
  Requested output is 120 Hz by default, configurable to 240 Hz; actual rates must be measured.
- Full recenter resets pitch and roll too. Local +Y may cease to be gravity-up.
- Linear velocity uses valid visual finite differences; angular velocity uses transformed gyro.
- No clock synchronization or absolute one-way latency estimate. Jitter and receive
  age are provided instead. Android fusion requires a compatible camera timestamp source.
  Discontinuity thresholds are experimental and can miss slow corrections or reject real motion.
- UDP is unauthenticated and unencrypted; one selected stream at a time. Logging
  can affect timing. Gaps include sender omissions, not only network loss.
- Viewer uses a fixed orthographic view; no interactive orbit/zoom. It hides the
  object when invalid/stale. Android intentionally shows no camera preview.
- App stops in background; manual Start and PC Reset are required on resume.

MIT license applies to Astraeus code. Third-party build tools, Gradle wrapper and
ARCore retain their own licenses; see [third-party notices](docs/third-party.md).
