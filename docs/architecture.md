# Piped-child Native Clients — Architecture (M0)

Status: M0 audit complete, revised per owner review (D1, 2026-11-18).
Spec: owner's handoff "Native Android TV, Android Tablet & iOS Clients"
(2026-11-18) plus owner review feedback. This document covers §4 (mandatory
repository investigation), M0 deliverables, and the integration decisions
the milestones depend on.

## 1. What Piped-child actually is

- A Vue 3 + Vite fork of TeamPiped/Piped (the web frontend). pnpm workspace,
  Tailwind 4, shaka-player 5, vue-i18n, reka-ui.
- No backend in this repo. The backend is any Piped API instance
  (TeamPiped/Piped, separate project). `.coderabbit.yaml` already links
  TeamPiped/Piped as the backend reference.
- Default instance config is build-time env (`.env`):
  `VITE_PIPED_API=https://pipedapi.kavin.rocks`,
  `VITE_PIPED_PROXY`, `VITE_PIPED_INSTANCES` (instance list JSON).
  At runtime the user can switch instances (Preferences) or add custom ones.
- Deployment configs present: `vercel.json`, `Dockerfile` + `Dockerfile.ci`
  + `docker/`, `.github/workflows/`: ci.yml (pnpm build + lint on
  push/PR — cheap, keep as-is), codeql.yml, reviewdog.yml, docker-build.yml,
  deploy-azure.yml, ipfs-build.yml, weblate-merge.yml.
- Tests: `test/` holds node security-regression scripts only
  (import/backup/header hardening). No unit test suite for app logic; no
  existing convention to copy. The native clients start their own TDD
  convention per spec §12 (red → green → refactor).
- Latest commit is merged PR #29 (mobile nav a11y). Web frontend must stay
  operational and unchanged (spec §3) — all native work lands in new
  directories.

## 2. How the frontend integrates with the API (what the clients replicate)

- `src/composables/useApi.js`: `apiUrl()` = user-selected instance or env
  default; `authApiUrl()` = optional separate auth instance. Token from
  `/login` is sent as `Authorization` header and stored per instance.
- Endpoint inventory (frontend source): trending, search + nextpage,
  opensearch suggestions, streams, clips, comments + nextpage, channel
  (+/c/, /user/ aliases), channels/tabs, sponsors, dearrow, feed (+RSS,
  unauthenticated variants), subscriptions (auth + unauthenticated), full
  user-playlist CRUD, import, login/register/logout/user/delete, /config.
- Preferences and history are LOCAL: localStorage keys (instance, authToken,
  quality, preferHls, enabledCodecs, bufferGoal, sponsorblock category set,
  dearrow toggle, watchHistory, searchHistory, listen/audio-only, region,
  homepage, etc.). Nothing but subscriptions/playlists/feed syncs through the
  backend. Per spec §9: clients must not claim cross-device sync for
  local-only data — watch history and settings are per-device.
- SponsorBlock and DeArrow go through the instance proxy endpoints
  (`/sponsors/{id}?category=[...]`, `/dearrow?videoIds=`), verified live.
  Clients use these, never sponsor.ajay.app directly — one less endpoint to
  disclose, and instance-side caching applies.
- Playback: shaka-player with the backend `dash`/`hls` URLs when present;
  otherwise the frontend GENERATES a DASH MPD client-side from individual
  streams (`src/utils/DashUtils.js`). `preferHls` preference exists.
  Quality/codec filtering via `enabledCodecs`, `quality`, `listen`.

See docs/api-capability-matrix.md for the endpoint table and live
verification results.

## 3. Platform compatibility risks (verified, ranked)

1. **/streams fragility (high, current).** On 2026-11-18 all reachable public
   instances failed extraction on recent videos (YouTube bot-blocking
   instance IPs; `LOGIN_REQUIRED` / 500). Discovery endpoints still worked.
   Clients must treat this as actionable ("instance blocked — retry / switch
   instance"), never a crash (spec §15.8). M1-A (playback-first) exists to
   pin down exactly how bad this is and what survives it.
2. **iOS format constraint (structural, spec §8).** AVPlayer plays HLS and
   progressive MP4, not DASH. When an instance returns `hls: null` (common
   when degraded), iOS falls back to progressive streams (often only 360p
   under bot-block). The web client's DASH-generation trick is not portable
   to AVPlayer. Fallback order: `hls` → progressive MP4 (muxed,
   `videoOnly: false`) → actionable error. No server-side transcoding (spec).
   Verified in M1-A, not assumed.
3. **Instance variance (medium).** Path layout differs (no `/api/v1`),
   Cloudflare challenges on some instances (leptons.xyz), broken entries in
   the official list (adminforge). Instance connectivity validation is P0 —
   validate with `/trending?region=US` (cheap, cached) and surface
   per-endpoint failures separately.
4. **Chromecast 4K decode (unverified).** ExoPlayer/Media3 should handle
   VP9/H.264/AV1 with hardware decode; must be validated on the actual
   Chromecast with Google TV 4K in M2 — simulator results do not establish
   device compatibility (spec §12). No hardware claims until then.
5. **Auth token semantics (medium).** Tokens are per-instance opaque strings
   with server-side expiry; 401 handling and explicit re-login required.
   Instance switching must never carry a token to a different host (§10).

## 4. Repository structure

Monorepo in THIS repository (spec §3 default; the web app stays at root,
untouched). The suggested `piped-child-native/` tree is adapted to live
under `clients/` so the repo root keeps its current meaning:

```
Piped-child/
├── (existing Vue web app, unchanged)
├── clients/
│   ├── android/
│   │   ├── app-tv/            # Compose for TV (androidx.tv.material3)
│   │   ├── app-tablet/        # Jetpack Compose, adaptive layouts
│   │   ├── core-network/      # Retrofit/OkHttp + contracts models
│   │   ├── core-data/         # repositories, caching, settings store
│   │   ├── core-player/       # Media3 wrapper, SponsorBlock skip engine
│   │   ├── core-model/        # shared data models (from contracts)
│   │   └── feature/{home,search,subscriptions,playlists,player,settings}/
│   ├── ios/
│   │   ├── PipedChild/        # SwiftUI app
│   │   ├── Networking/ Models/ Features/ Player/
│   ├── contracts/
│   │   ├── api/               # endpoint contracts (request/response shapes)
│   │   └── fixtures/          # recorded JSON responses, shared by both tests
│   └── README.md
├── docs/
│   ├── architecture.md        # this file
│   └── api-capability-matrix.md
```

One Gradle build produces both Android APKs (separately installable per
spec §13); iOS is a separate Xcode project consuming the same
`contracts/`. No Kotlin Multiplatform (spec §3: not justified for this
duplication size) — Kotlin and Swift implementations sit behind the shared
API contracts and must pass the same fixture-driven scenario tests (§12).

TV and tablet share core-* modules but not screens (spec §3): d-pad focus
model and touch layouts are different designs. The tablet app is the
touch-first one; Android phones are out of scope unless reuse is free (§2).

## 5. Screen-time integration removed

Owner decision, 2026-11-19: the Piped clients do not connect to ChoreKeeper.
The optional integration proposal is withdrawn. No server connection, child
identity, time-bank operations or playback enforcement is part of this project.

## 6. Privacy and security rules (binding on all clients)

- First-run consent screen lists every endpoint the app will contact BEFORE
  the first request: chosen Piped instance (+ proxy host), and any optional
  integration the user enables. SponsorBlock/DeArrow ride the instance
  proxy. No analytics, no ad SDKs, no Google sign-in (spec §8/§15.10).
- Credentials: Android = EncryptedSharedPrefs/Keystore; iOS = Keychain.
  No token logging. Instance switch never migrates tokens. Fallback
  instances never receive an auth token without explicit user authorization.
- No telemetry. Error reports stay on-device unless the owner asks.

## 7. Implementation plan (playback-first, per owner review)

### M1-A — Playback feasibility (highest priority, precedes broad M1)

- Find a working Piped instance or configure a test instance; retrieve real
  `/streams/{videoId}` responses; record healthy AND degraded sanitized
  fixtures.
- Minimal Android Media3 harness and minimal iOS AVPlayer harness; verify
  both play a verified real video. Determine whether Media3 can
  consume/construct DASH from Piped stream metadata (the web frontend's
  DashUtils approach). iOS: HLS first, then compatible progressive MP4; no
  silent server-side transcoding.
- Test matrix: ordinary video, separate audio+video streams, subtitles,
  unavailable/blocked video, HLS when available, 1080p/4K when available.
- Acceptance: Android plays a verified real video; iOS plays a verified
  real video; deterministic, tested stream selection; actionable errors for
  incompatible formats; extraction failures distinguished from network
  failures; automated tests over recorded fixtures on both platforms;
  hardware limits documented. Simulator-only results do not establish
  Chromecast/iPhone compatibility. If live playback cannot be established,
  report the blocker and alternatives before building further.

### M1-B — Networking and models (after M1-A reports)

- API models, Kotlin + Swift networking, configurable instance, auth with
  secure token storage, per-instance credential isolation, pagination,
  search/metadata, typed errors, shared fixtures, unit + integration tests.
- Failover only if necessary and proven to help: user-configured fallback
  instances, bounded retries, no credential leakage, no silent account
  switching, clear errors. No elaborate routing machinery before evidence.

### M2–M6

- M2 Android TV (Compose for TV, Media3, d-pad, validated on the Chromecast
  with Google TV 4K), M3 Tablet, M4 iPhone (SwiftUI, AVPlayer,
  background audio, PiP, AirPlay), M5 feature parity, M6 release readiness.
- CI (spec §13): separate workflows per platform; light checks on PR,
  expensive builds manual/release-only (owner's GitHub-minutes rule).
  iOS needs a macOS runner only for manual build jobs.
- Each milestone: TDD, incremental reviewable PR, adversarial review, no
  hardware claims without hardware.

## 8. Open questions for the owner

1. Apple Developer account? TestFlight/device signing needs the paid
   program; otherwise 7-day re-sign sideload (documented in M6 either way).
   (Working default: no paid account.)
2. Self-host a Piped backend instance for family use? NOTE: self-hosting
   does NOT by itself fix YouTube IP blocking — a home-hosted instance still
   extracts from YouTube and its IP can be blocked the same way. It helps
   only with instance availability/trust, not extraction reliability.
   (Working default: evaluate during M1-A if no public instance is usable.)
3. Default instance for the clients (kavin.rocks is the web default but was
   unreachable tonight).
4. Minimum Android API level: propose API 26 (covers Chromecast 4K on
   Android 12 and most tablets from ~2017); confirm if a specific older
   tablet matters. (Working default: API 26.)
