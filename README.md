# Brooklet

Brooklet is a phone-first, offline-first Android client for a single Miniflux account. Room is the UI's only source of truth: sync writes remote data into Room, and read/star/Karakeep actions update Room and a durable mutation queue together.

Brooklet is primarily maintained for its author's own use. It is free software:
fork it, adapt it, and maintain your own version if it is useful to you. There
is no promise of user support, compatibility, feature work, or response times.
Please read [SECURITY.md](SECURITY.md) before reporting a vulnerability, and
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a non-security issue or pull
request.

Brooklet is licensed under the GNU General Public License, version 3 or later;
see [COPYING](COPYING). The privacy policy is in [PRIVACY.md](PRIVACY.md), and
the release checklist for the Google Play Data Safety form is in
[PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md).
See [PERFORMANCE_TESTING.md](PERFORMANCE_TESTING.md) for the local optimized
release deployment path.

## Current foundation

- Dense Inbox triage with bidirectional swipe, an accessible Mark read action, and Undo.
- Reader that marks on open, exposes **Keep unread**, star, Karakeep, share, original, previous/next, and cached native text blocks.
- Pull to refresh with an unobtrusive new-articles control, plus Android share-sheet support to send a URL to the subscription flow.
- Saved and searchable Library workspaces, dynamic colour, edge-to-edge layout, and navigation rail adaptation.
- HTTPS/API-token setup with Miniflux 2.3.2+ validation and Android Keystore encryption.
- Incremental pull with a 60-second overlap, conflict-aware merges, batched/coalesced offline mutations, exponential WorkManager retry, and protected pruning.
- Miniflux-mediated or direct Karakeep delivery with durable queue state and canonical-URL deduplication.

## Build

The phone application is the `app-phone` Gradle module (stored in `app/` because it is based on the Android CLI template).

```sh
JAVA_HOME=/path/to/jdk-17 \
GRADLE_USER_HOME="$PWD/.gradle-local" \
./gradlew :core-model:test :app-phone:assembleDebug
```

Set `JAVA_HOME` to a local JDK 17 installation; the path above is only an
example. Never commit `local.properties`, signing keys, emulator captures,
logs, or generated `build/` output. These are ignored by the repository and
may contain machine-specific paths or private test data.

The first release compiles against API 37.1, targets API 36, and has a minimum API of 28. Article images remain network-only; cached text never waits for them.

For local performance testing, an optimized release APK can be signed with the
debug certificate by adding this ignored setting to `local.properties`:

```properties
brooklet.debugSignRelease=true
```

Build it with `./gradlew :app-phone:assembleRelease`. This artifact is for local
testing only and must not be uploaded to Play.

## Security and privacy model

Brooklet requires HTTPS for configured Miniflux and Karakeep services. Their
credentials are encrypted by Android Keystore; cached article text and metadata
remain in the app's local database for offline use. Credential-bearing API
requests do not follow redirects. Article images are transient, HTTPS-only,
redirect-free, and cannot resolve to local/private network addresses.

See [PRIVACY.md](PRIVACY.md) for the full data-handling description. This model
does not protect data from someone who can unlock or compromise the device, or
from a Miniflux/Karakeep server or article site chosen by the user.

## Development checks

Use JDK 17 and a repository-local Gradle cache:

```sh
JAVA_HOME=/path/to/jdk-17 \
GRADLE_USER_HOME="$PWD/.gradle-local" \
./gradlew :core-model:test :core-network:test :core-sync:test \
  :app-phone:testDebugUnitTest lintDebug :app-phone:assembleDebug
```

For the release rehearsal, follow [docs/release.md](docs/release.md). Do not
commit generated artifacts, local configuration, signing keys, captures, or
logs.
