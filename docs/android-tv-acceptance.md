# Android TV acceptance record

This is a test procedure and a record of what is still unverified, not an M2
completion claim. APK install/build instructions are in `clients/android/README.md`.
All PRs are owner-reviewed and owner-merged. Merge in dependency order, verify the
next PR base/diff after each merge, and rerun the gates on combined master.

## Current evidence (2026-11-19)

- JVM tests exercise models, networking, proxy media-data-source rules, Player
  seek calls through a recording double, actual Compose rendering/remote events,
  and MainActivity settings-result/recreation behavior.
- Android debug APK assembles. TV lint passes with zero errors and existing
  warnings left visible. This is not a release-signed build.
- No ADB device is connected to the build environment. No emulator/system image
  or KVM is available. No physical TV launcher, remote, keyboard, decoder, audio,
  buffering, real seek or end-to-end video playback has been verified here.
- macOS `swift test` has not run here. The build environment is Linux without a
  Swift toolchain. A passing Kotlin suite does not establish Swift compilation.

### Backend availability, separate from proxy policy

At approximately 10:17 EET, the selected default instance returned 15 entries
from `https://pipedapi.ducks.party/trending?region=US`. Streams metadata for the
first five entries all returned HTTP errors:

- `DxgDeW-gIcU`
- `H-4PyJlhI9g`
- `BShFAk9vEMk`
- `qSyo5d-dVzA`
- `8OZQzCJS52w`

The first was checked separately at
`https://pipedapi.ducks.party/streams/DxgDeW-gIcU`: HTTP 500 with JSON error/message
indicating YouTube bot/sign-in blocking. Do not assume the other four share that
classification without inspecting their responses. No media bytes were fetched.

This sample cannot estimate proxy-policy rejection: metadata extraction failed
before source selection. Trending availability is not proof of working playback.
The instance is not recorded as a working playback instance on this evidence.
Do not silently switch instances or approve additional origins to hide failures.

For a useful future compatibility sample, record each attempt separately as:
metadata failure (HTTP class and sanitized cause), metadata succeeds but no source
passes policy, or permitted source selected. Then, with explicit endpoint consent,
record whether the chosen source actually played. Preserve the date, instance,
video ID, source type and tested device, never signed URLs or credentials. Count
policy exclusions only among metadata-successful samples; parser selection is
not proof that an excluded source would actually have played.

## Device smoke procedure

Use a TV emulator and Chromecast with Google TV 4K as separate runs. Record APK
commit/hash, Android version, device model, display/audio output and date. On an
emulator mark physical-remote/hardware results unverified, even if keys work.
Use a fresh app data directory for the first run. Clearing data removes settings
and consent; do not clear an existing installation without the owner's agreement.

1. Install the debug APK and launch from the TV launcher. Verify readable banner,
   focus indication, overscan, real remote direction/center/Back and keyboard.
2. Before Continue, move Up to Instance settings. Cancel and return. Reopen,
   enter an invalid URL and check the actionable validation error. Save a chosen
   HTTPS API root and verify first-run approval is required before any request.
   Use a controlled log/capture to verify no premature request; UI alone does not
   prove absence of traffic. Redact signed URLs, tokens and cookies from evidence.
3. Approve the API. Confirm real Trending. On an unreachable API confirm a useful
   failure and reachable Retry/settings, with no focus trap or silent failover.
   A backend extraction error and a network routing error are separate results.
4. For thumbnail consent choose Titles only. Navigate a long grid in all
   directions, beyond the first visible rows, and return to Settings from row one.
   Repeat with image approval, checking only the disclosed image origin is used.
   Check focus and selected-card position after returning from other screens.
5. Select a video. Before Play, verify the exact HTTPS proxy is disclosed and no
   media bytes are loaded. Back without approving and verify grid focus returns.
   Select again and Play. Record a real VOD with audible, synchronized audio.
6. Test pause/resume, multiple seeks forward/back, buffering recovery, Back and
   restoration of the exact selected grid card/scroll. Background/resume during
   playback, then re-enter. Check for stuck audio, duplicate players and crashes.
   Report position/live-edge behavior rather than assuming it from UI controls.
7. Test known examples for generated/server DASH, HLS (including a live stream)
   and proxied muxed MP4 fallback when available. Record the source actually
   selected, not just formats listed by metadata. If a format has no reachable
   fixture that day, leave its hardware row unverified rather than checking it off.
8. Test a policy-blocked outside CDN and a controlled redirect. They must show a
   usable failure without contacting an unapproved destination. Metadata-only
   simulation is not a substitute for inspecting actual device requests.
9. For SponsorBlock, test the remote opt-out before Play and a known segment in
   VOD. Verify seek accuracy, audible continuity, pause, deliberate rewind and
   behavior on a slow/failed segment request. Live/DVR must not seek from these
   timestamps. No working segment that day means hardware skipping unverified.
10. Change API instance after browsing/playback. Verify fresh API approval,
    cleared image/media approvals and no old client/account requests. Restart the
    app and rotate/recreate where supported, checking selection, consent and focus.

## Swift and merge gates

On an approved macOS environment, from `clients/ios/PipedChildCore`, run
`swift test`. Record macOS, Xcode/Swift version, commit and output. Do not add or
trigger a paid runner without approval. Review shared fixture and error behavior
against Kotlin, including sparse payloads, cancellation, redirect refusal,
per-instance token boundaries and API URL handling. Add regression tests for
confirmed gaps; source review alone is not a passing Swift result.

After each owner merge, inspect the next PR's base and file diff. After the whole
stack is merged, rerun model/network/player/optional-boundary/TV JVM tests,
`:app-tv:lintDebug` and `:app-tv:assembleDebug` on master. Repeat emulator and
Chromecast smoke checks on that exact APK. Keep backend limits, confirmed bugs
(with reproducible steps), and unverified hardware risks in separate sections.

ChoreKeeper remains an isolated boundary. Its next scope must separately decide
server URL/reachability, child identity/auth, credential storage, read-only test,
endpoint consent, error behavior and integration tests. No spending, refunds,
parent approval or playback enforcement follows from this acceptance procedure.
