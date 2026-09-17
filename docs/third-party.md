# Third-party components

Astraeus source is MIT-licensed. This does not relicense dependencies or tools.

- Gradle wrapper (generated scripts/JAR), Gradle 8.11.1: Apache License 2.0.
  [Gradle license](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE).
- ARCore SDK for Android 1.48.0: distributed by Google; consult its package and
  [ARCore SDK repository](https://github.com/google-ar/arcore-android-sdk) notices.
  Google Play Services for AR is separately installed on the device.
- Kotlin, Android Gradle Plugin, Android SDK and JUnit: their respective upstream
  licenses/terms apply. They are downloaded by the build, not vendored here.
- w64devkit/MinGW-w64/GCC or Microsoft build tools are local build dependencies;
  no compiler distributions are committed.

The ignored `.tools` directory used for local validation is not part of the
repository or required folder layout of a fresh clone.
