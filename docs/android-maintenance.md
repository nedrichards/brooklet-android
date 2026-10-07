# Android maintenance

Weekly Dependabot pull requests report obsolete Gradle dependencies and GitHub Actions. Kotlin/KSP and AndroidX updates are grouped for coherent review; upgrades still require passing checks. A dedicated main-branch workflow submits resolved dependencies to GitHub for vulnerability alerts. Dependency review blocks high-severity runtime vulnerabilities. CodeQL checks Kotlin weekly and on changes.

Run the same verification locally with the project wrapper:

```sh
./gradlew --no-daemon :core-model:test :core-network:test :core-sync:test :app-phone:testDebugUnitTest :app-wear:testDebugUnitTest lintDebug lintRelease :app-phone:assembleDebug :app-wear:assembleDebug :app-phone:assembleRelease :app-wear:assembleRelease
```

Pull requests, main pushes and manual runs retain test/lint reports and debug/release APKs for 14 days. Debug APKs are installable. Release APKs exercise the release configuration and shrinking; they may be unsigned or debug-signed, as determined by the app's existing signing configuration. They are not production distribution artifacts.

Pushing a `v*` tag runs verification and creates a **draft development release** with explicitly labelled debug APKs and SHA-256 checksums. CI uses a disposable debug key, so installing over another build may require uninstalling it first. Review the draft before publishing. Production releases require the existing app-specific version code/name and production keystore procedure; never publish debug APKs as production-signed builds. No keystore or service credentials are needed by these workflows.

Reports upload even when verification fails. Brooklet emulator journeys remain advisory with retained reports; deterministic verification blocks failures.

Both hosted emulator configurations exclude `KeyboardJourneyTest#heldReadKeyDoesNotDismissMoreThanOneArticle`. Native `Instrumentation.sendKeySync` delivery intermittently fails to mark the first article read on the hosted API 36 emulator (runs 37144297922 and 37671329521), even with list focus explicitly asserted. The test remains enabled for local emulators and devices; other journey tests still run in CI. Run it locally with:

```sh
./gradlew :app-phone:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nedrichards.brooklet.KeyboardJourneyTest#heldReadKeyDoesNotDismissMoreThanOneArticle
```

GitHub dependency review and CodeQL run on public repositories. Private repositories require GitHub Code Security; after enabling it, set repository variable `GH_CODE_SECURITY_ENABLED=true`. Otherwise CI explicitly reports the unavailable checks while Dependabot version updates, vulnerability alerts, dependency submission, tests and lint remain active.
