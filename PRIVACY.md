# Brooklet privacy policy

Last updated: 30 August 2026

Brooklet is an offline-first Miniflux client. Brooklet has no Brooklet-hosted
backend, advertising, analytics, crash-reporting, recommendation service, or
account. The app does not sell personal information.

## Information Brooklet handles

Brooklet handles the following information on the device when you configure an
account:

- the Miniflux HTTPS address, username, server version, API token, feeds,
  categories, and entries returned by that Miniflux server;
- local reading state, starred state, queued reading actions, article text,
  parsed article blocks, and reading positions; and
- if configured, a Karakeep HTTPS endpoint, API key, and queued bookmark
  requests; and
- a URL deliberately shared to Brooklet from another Android app, only while
  Brooklet shows its subscribe confirmation.

API tokens and the optional Karakeep key are encrypted with Android Keystore.
Article text and metadata are stored in the local Room database for offline
reading. Article images are fetched only when needed over HTTPS and are not
persistently cached by Brooklet. Android backup and device-transfer rules
exclude Brooklet's local database and preferences.

If the Wear OS application is configured, the phone sends the selected watch a
one-time, nonce-bound message containing the Miniflux HTTPS address and API
token only after confirmation in phone Settings. The message is not stored in
the Wear Data Layer. The watch validates Miniflux, encrypts the credential with
that watch's Android Keystore, and maintains its own Room cache and durable
action queue. Article bodies and reading actions are not copied between phone
and watch.

## Where information goes

Brooklet sends Miniflux credentials and synchronisation requests only to the
Miniflux address you enter. It sends bookmark requests only to the configured
Miniflux integration or Karakeep endpoint. A URL shared from another Android
app is shown for confirmation and is sent to Miniflux only if you choose
**Subscribe**. When you open an article or request an original link, the
relevant URL is contacted directly; article image hosts may therefore see the
device's network address as part of normal HTTPS delivery. Brooklet does not
send this information to Brooklet maintainers.

## Retention and deletion

Unread, starred, recently opened, and queued articles are retained according to
the app's offline-storage settings. Ordinary read articles are pruned according
to that setting. Use **Settings → Disconnect account and delete local data** to
cancel sync, remove the account, cached articles, reading state, queued
mutations, Karakeep configuration, and locally stored credentials. This does
not delete articles, stars, or bookmarks from your Miniflux or Karakeep server;
those services have their own controls. Uninstalling Brooklet or clearing its
Android app data also removes its local data.

Phone and watch deletion are per-device. **Disconnect and delete watch data**
removes the watch credential, cached text, sync state, and queued watch actions
without deleting phone data or remote Miniflux/Karakeep data. Disconnecting the
phone does not remotely erase a previously configured watch; disconnect both
devices when retiring the account.

## Your choices and responsibilities

Brooklet requires a valid HTTPS Miniflux address and API token. You choose
which Miniflux and Karakeep services receive requests and which article images
are loaded. Review those services' privacy policies and revoke tokens there
when they are no longer needed. Brooklet cannot control retention or logging on
those services or on linked article websites.

Android Keystore protects credentials at rest, but cached article content is
not separately encrypted; someone able to unlock or compromise the device may
access local data.

For a privacy question, use the public project issue tracker if one is enabled.
For a vulnerability, follow [SECURITY.md](SECURITY.md); do not include sensitive
details in a public issue.
