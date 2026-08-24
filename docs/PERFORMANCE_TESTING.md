# Performance testing

Brooklet's optimized release APK is intentionally non-debuggable. For local
testing only, `local.properties` may opt the release build into the Android
debug signing key:

```properties
brooklet.debugSignRelease=true
```

Build and install it directly with the Android SDK tools:

```sh
./gradlew :app-phone:assembleRelease
adb install -r app/build/outputs/apk/release/app-phone-release.apk
adb shell cmd package compile -m speed-profile -f com.nedrichards.brooklet
```

The final command asks ART to compile the packaged profile immediately. Without
it, a newly installed release can correctly report `status=verify` until the
device's background dex optimiser runs. Android Studio may report
`INSTALL_BASELINE_PROFILE_FAILED` if it checks that status before background
compilation has happened; this does not mean that APK installation failed.

Use the debug variant for ordinary Run/Debug sessions. Do not upload a
debug-signed APK or this local `local.properties` setting to Google Play.

## Baseline Profile and Macrobenchmark

The `baseline-profile` module contains Brooklet-specific startup, inbox-scroll,
reader-open, swipe-and-undo, and image-heavy-reader journeys. Use a spare or
snapshotted API 33+ device: these tasks install the production application ID
and can replace an existing Brooklet installation if its signing key matches.

Generate and copy the profile into the release source set with:

```sh
ANDROID_SERIAL=<device-serial> ./gradlew :app-phone:generateReleaseBaselineProfile
```

Run the cold-start comparison and interaction frame benchmarks with:

```sh
ANDROID_SERIAL=<device-serial> ./gradlew \
  :baseline-profile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nedrichards.brooklet.baselineprofile.StartupBenchmark
```

On a clean device only the startup journey is meaningful. Configure the
benchmark device with a Miniflux account and cached entries before generating
the full profile; the image journey also needs at least one cached read article
containing an image. Keep generated profiles in source control only after
comparing `CompilationMode.None` with the Baseline Profile result on physical
hardware.
