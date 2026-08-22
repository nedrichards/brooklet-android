# Release verification

This document is a release rehearsal, not a promise to publish a binary.

## Before tagging

1. Start from a clean clone. Check tracked paths with `git ls-files` and scan
   the full history with a secret scanner such as Gitleaks. Do not publish if
   `local.properties`, signing material, generated APK/AAB files, logs,
   captures, or machine paths are tracked.
2. Review dependency and licence changes. Export an SPDX SBOM from GitHub's
   dependency graph for the release record.
3. Confirm [ASSET_PROVENANCE.md](../ASSET_PROVENANCE.md) covers all artwork and
   other non-code assets and that each licence is compatible with GPLv3. Do not
   infer provenance from a filename.
4. Review `PRIVACY.md` and `PLAY_DATA_SAFETY.md` against the exact build.
5. Resolve all CI, Dependabot, secret-scanning, and code-scanning alerts, or
   document an explicit risk acceptance outside the repository.

## Build and test

Use JDK 17 and a private local Gradle cache:

```sh
JAVA_HOME=/path/to/jdk-17 \
GRADLE_USER_HOME="$PWD/.gradle-local" \
./gradlew :core-model:test :core-network:test :core-sync:test \
  :app-phone:testDebugUnitTest lintDebug :app-phone:assembleDebug :app-phone:assembleRelease
```

Exercise setup, offline reading, pull to refresh, share-to-subscribe, Keep
unread, Karakeep, disconnect/delete-local-data, and a failed network request on
a physical device. Test the minified release build, not only debug.

## Signing and publication

1. Keep the upload key outside the repository and CI logs. Use Play App Signing
   for distribution. `brooklet.debugSignRelease` is solely a local test path.
2. Build a signed AAB using a local/CI secret-injection process. Verify it with
   `apksigner verify --verbose --print-certs` and retain the certificate
   fingerprint in private release records.
3. Create and verify a signed Git tag, attach checksums and the SBOM to the
   release, then archive the exact source revision and release notes.
4. Confirm rollback: retain the previous signed artifact and document whether
   a regression requires a new Play release or unpublishing a GitHub asset.

## GitHub controls to enable

After creating the repository, enable private vulnerability reporting,
dependency graph, Dependabot alerts and security updates, secret scanning with
push protection, and CodeQL/code scanning. Protect `main` with required CI and
review; prevent force pushes and direct pushes. These are repository settings,
so they cannot be enabled from this source tree.
