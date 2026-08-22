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
