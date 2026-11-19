# Optional ChoreKeeper boundary

This is a foundation, not a working ChoreKeeper integration. The Android TV app
and playback engine do not depend on this module. There is no HTTP transport,
login, credential storage, session request/stop action, enforcement, or UI here.
The app remains usable without ChoreKeeper. No backend changes are included.

`ChoreKeeperConnection` is an in-memory opt-in gate for a future read-only
status source. Configuration alone makes no request. Contact is allowed only
after explicit approval of that exact normalized HTTPS host. Switching endpoints
or disabling the connection clears approval; stale in-flight results are
discarded. The caller must cancel its coroutine to stop in-flight work: revocation
cannot undo a request already sent.

The source interface accepts only the ChoreKeeper endpoint, never a Piped token,
account, watch history, or video selection. Its outcomes separate unreachable,
authentication-required and API error states. Available is connection status,
not permission to watch. No playback decision can be derived from it.

Before wiring a source into TV settings, settle child identity and sign-in or
pairing, approve the endpoint disclosure UI, and implement credential storage
using the platform security policy. Any session request, charge/refund behavior,
parent approval or playback enforcement needs a separate product decision. Do
not use a privileged server/automation key as a shortcut for child sign-in.

A future transport must refuse redirects and unexpected destinations, map
network/auth/API failures separately, propagate cancellation, and undergo HTTP
contract tests. None of those transport checks are claimed by these unit tests.

Run `gradle :feature:chorekeeper:test` from `clients/android`.
