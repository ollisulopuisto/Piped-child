# Piped-child Native Clients — Architecture (M0)

Status: M0 audit complete. Date: 2026-11-18.
Spec: owner's handoff "Native Android TV, Android Tablet & iOS Clients"
(2026-11-18). This document covers §4 (mandatory repository investigation),
M0 deliverables, and the integration decisions the milestones depend on.

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

1. **/streams fragility (high, current).** Tonight all reachable public
   instances fail extraction on recent videos (YouTube bot-blocking instance
   IPs; `LOGIN_REQUIRED` / 500). Discovery endpoints still work. Clients must
   treat this as actionable ("instance blocked — retry / switch instance"),
   never a crash (spec §15.8). Playback-only failover to a user-configured
   fallback list is recommended in M1, token-safe per spec §10.
2. **iOS format constraint (structural, spec §8).** AVPlayer plays HLS and
   progressive MP4, not DASH. When an instance returns `hls: null` (common
   when degraded), iOS falls back to progressive streams (often only 360p
   under bot-block). The web client's DASH-generation trick is not portable.
   Fallback order: `hls` → progressive MP4 (muxed, `videoOnly: false`) →
   actionable error. No server-side transcoding (spec). This is a documented
   limitation, not a defect.
3. **Instance variance (medium).** Path layout differs (no `/api/v1`),
   Cloudflare challenges on some instances (leptons.xyz), broken entries in
   the official list (adminforge). Instance connectivity validation is P0 —
   validate with `/trending?region=US` (cheap, cached) and surface
   per-endpoint failures separately.
4. **Chromecast 4K decode (low).** ExoPlayer/Media3 handles VP9/H.264/AV1
   with hardware decode; verify 4K VP9 profile support on-device in M2
   validation. Do not claim hardware playback from simulator tests (§12).
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
│   │   └── feature/{home,search,subscriptions,playlists,player,settings,gate}/
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

## 5. ChoreKeeper screen-time gate (owner requirement for the TV client)

Owner's standing condition (2026-11-18): the TV client ships with the
ChoreKeeper screen-time hook. ChoreKeeper (family server, private repo)
already provides the full API: screen targets (priced per activity,
per-child, `requires_approval`, grace), sessions
(`POST /api/screen/sessions`, poll `/api/screen/sessions/current`, stop with
refund), parent approval via signed ntfy links, server-kept clock with
`ends_at`/`locks_at`.

Design (defaults accepted by owner 2026-11-18):
- TV app signs in to ChoreKeeper as the child; QR device-pairing link
  generated in the ChoreKeeper admin UI (small backend delta, its only one).
- Profile picker at launch when more than one child exists.
- Play press → affordable-minutes check → session request (or approval wait
  screen) → playback locked to `locks_at` locally (server remains the clock;
  a mid-session network drop still locks on time — fail-closed) → stop
  refunds unused minutes.
- Gate is a self-contained module (`feature/gate` on Android, one Swift
  package on iOS) wired only into the playback entry point. Browsing stays
  free. The gate is TV-first per owner requirement; tablet/iOS get it when
  the owner asks — it is out of the handoff's P0.
- ChoreKeeper server URL is an app setting; the HA API key is never used
  (read-only by design there).

## 6. Privacy and security rules (binding on all clients)

- First-run consent screen lists every endpoint the app will contact BEFORE
  the first request: chosen Piped instance (+ proxy host), ChoreKeeper
  server, and nothing else. SponsorBlock/DeArrow ride the instance proxy.
  No analytics, no ad SDKs, no Google sign-in (spec §8/§15.10).
- Credentials: Android = EncryptedSharedPrefs/Keystore; iOS = Keychain.
  No token logging. Instance switch never migrates tokens.
- No telemetry. Error reports stay on-device unless the owner asks.

## 7. Implementation plan (mapped to spec milestones)

- M0 (this): docs/architecture.md + docs/api-capability-matrix.md. ✔
- M1 contracts + networking: `contracts/api` shapes from the OpenAPI spec,
  fixtures recorded from a healthy instance (need one reachable day — the
  matrix notes which), Kotlin + Swift clients, instance validation,
  auth token store, playback-only failover design. Failing-first tests per
  repo rules.
- M2 Android TV slice: home/search/subscriptions/player per the approved
  sketches (2026-11-18), d-pad only, LEANBACK_LAUNCHER, gate module
  included. Validate on his Chromecast with Google TV 4K.
- M3 tablet (adaptive, PiP), M4 iPhone (SwiftUI, AVPlayer, HLS constraint
  documented), M5 parity (P0 completion + prioritized P1: SponsorBlock
  categories, DeArrow, speed, background audio, PiP, queue), M6 release
  readiness (CI per platform, install docs, limitations).
- CI (spec §13): separate workflows per platform, manual/tag triggers for
  builds (owner's GitHub-minutes rule), PR-time unit tests + lint only.
  iOS needs a macOS runner only for manual build jobs.

## 8. Open questions for the owner

1. Apple Developer account? TestFlight/device signing needs the paid
   program; otherwise 7-day re-sign sideload (documented in M6 either way).
2. Self-host a Piped backend instance for family use? It removes the biggest
   risk (risk #1) and keeps tokens in-house. Backend is upstream-supported,
   not in this repo's scope either way.
3. Default instance for the clients until then (kavin.rocks is the web
   default but was unreachable tonight).
4. Confirm repo write path: PAT previously lacked PR write on Piped-child
   (2026-11-10); needs re-test before M1's first PR.
5. Minimum Android API level: propose API 26 (covers Chromecast 4K on
   Android 12 and most tablets from ~2017); confirm older-tablet target if
   one specific device matters.
