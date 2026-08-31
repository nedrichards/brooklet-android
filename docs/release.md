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
  :app-phone:testDebugUnitTest :app-wear:testDebugUnitTest lintDebug \
  :app-phone:assembleDebug :app-wear:assembleDebug \
  :app-phone:assembleRelease :app-wear:assembleRelease
```

Exercise setup, offline reading, pull to refresh, share-to-subscribe, Keep
unread, Karakeep, disconnect/delete-local-data, and a failed network request on
a physical device. Test the minified release build, not only debug.

For Wear, use a 41 mm round profile and a physical Pixel Watch 2 before release.
Verify phone-confirmed setup, crown scrolling, swipe-to-reveal versus swipe-back,
TalkBack actions, Tile deep links, offline body reading, Keep unread, cache
limits, and independent watch deletion. With Bluetooth disabled, confirm direct
Wi-Fi or LTE sync and action delivery. Capture WorkManager/battery evidence that
an unchanged six-hour periodic sync uses neither a foreground service nor a
long wake lock and that Tile rendering starts no network activity. These
physical-device claims cannot be replaced by emulator results.

## Signing and publication

1. Keep the upload key outside the repository and CI logs. Use Play App Signing
   for distribution. `brooklet.debugSignRelease` is solely a local test path.
2. Build separately signed phone and Wear AABs using the same release signing
   identity. The Wear module uses its own version-code sequence. Verify both with
   `apksigner verify --verbose --print-certs` and retain the certificate
   fingerprint in private release records.
3. Create and verify a signed Git tag, attach checksums and the SBOM to the
   release, then archive the exact source revision and release notes.
4. Confirm rollback: retain the previous signed artifact and document whether
   a regression requires a new Play release or unpublishing a GitHub asset.

## Automation plan

The pull-request and `main` workflow deliberately stops at an installable
debug-signed APK. Release automation should be a separate, manually dispatched
workflow so untrusted pull-request code never runs with signing or publishing
credentials. Add it only when there is a real distribution target and an
agreed key-management process.

Recommended stages for that workflow are:

1. Accept an existing signed tag as input, check out that exact commit, and
   require approval through a protected GitHub `release` environment.
2. Run the same tests and lint as the Android checks workflow. Derive and
   validate `versionCode` and `versionName` from the tag rather than editing
   them during an opaque CI step.
3. Retrieve the upload key from an external secret manager through GitHub OIDC
   where possible. If encrypted GitHub secrets are used instead, store the
   base64-encoded keystore, alias, and passwords as environment secrets, write
   the keystore only to the runner's temporary directory, mask values, and
   delete it in an `always()` cleanup step.
4. Build both signed AABs, run `bundletool validate`, verify their signatures and
   certificate fingerprint, generate SHA-256 checksums and an SBOM, and attest
   the artifacts to the immutable commit.
5. First upload to a non-production Play track or create a draft GitHub
   release. Make production promotion a second approved job rather than an
   automatic consequence of a tag or merge.

Prefer Play App Signing: CI then holds only the replaceable upload key, not the
app-signing key. Pin third-party actions to full commit hashes, grant each job
only the permissions it needs, prevent concurrent releases, retain the prior
artifact for rollback, and rehearse key rotation before enabling publication.

## GitHub controls to enable

After creating the repository, enable private vulnerability reporting,
dependency graph, Dependabot alerts and security updates, secret scanning with
push protection, and CodeQL/code scanning. Protect `main` with required CI and
review; prevent force pushes and direct pushes. These are repository settings,
so they cannot be enabled from this source tree.
