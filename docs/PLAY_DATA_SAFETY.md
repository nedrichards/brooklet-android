# Google Play Data Safety declaration

This is the source-of-truth checklist for completing Brooklet's Google Play
Data safety form. Re-check it against the exact release build and any newly
added dependency before submission.

## Brooklet's handling

- **Data collected by Brooklet for the developer:** No. Brooklet has no
  developer-operated backend, analytics, advertising, crash reporting, or
  tracking SDK.
- **Data shared by Brooklet with the developer:** No.
- **Data sent to services selected by the user:** Yes. The app sends the
  configured Miniflux API token and synchronisation requests to the user's
  Miniflux server. It may send a bookmark URL, title, and the configured
  Karakeep credential to the user's selected Miniflux integration or Karakeep
  endpoint. These are user-directed service requests, not Brooklet telemetry.
- **URLs shared from another Android app:** A shared URL is displayed for
  confirmation and is sent to the user's Miniflux server only if they choose
  to subscribe. It is not sent to Brooklet or used for analytics.
- **Data stored on the device:** Miniflux account details, encrypted
  credentials, cached article content and metadata, reading/star state,
  reading positions, and queued offline actions.

## Likely Play form answers

Use the Play Console's current category names when entering the form:

- **Personal info / Authentication information:** Miniflux username and API
  token; optional Karakeep API key. Purpose: account access and app
  functionality. Handling: collected on-device and transmitted only to the
  user-configured endpoint. The token/key is encrypted at rest and the user can
  delete it with account disconnect.
- **App activity / Other user-generated content:** reading state, starred state,
  cached article text, and reading position. Purpose: app functionality and
  offline reading. This is kept on-device and synchronised only with the
  user's configured Miniflux/Karakeep services as required by their actions.
- **App activity / App interactions:** sync and reading actions, only as local
  state needed to provide the feature; not sent to Brooklet.

For each applicable item, declare that data is encrypted in transit. The app
does not offer a developer account, does not use data for advertising, and does
not sell data. The in-app deletion path removes the local account and cached
data; remote Miniflux/Karakeep deletion remains controlled by those services.
