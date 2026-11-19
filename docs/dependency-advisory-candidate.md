# Development dependency advisory candidate

Status: prepared for review, not approved to merge. The overrides cross major
versions and need the owner's word before merging.

## Scope

The current pnpm audit (2026-11-19) reports two moderate development-tool issues:

- glob 11.1.0, reached through vite-plugin-pwa/workbox-build, is affected by
  [GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4).
  An aborted traversal can continue reading directories. The published fix is
  14.0.2; no patched 11.x release was available when checked.
- minimatch 10.2.6, reached through ESLint, typescript-estree and glob, is affected
  by [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249).
  Pattern preprocessing can amplify work. The published fix is 11.0.1;
  no patched 10.x release was available when checked.

The candidate pins those patched releases using narrow pnpm overrides, including
minimatch under glob 14.0.2. It leaves minimatch 5.x, direct application package
versions and Android/iOS dependencies unchanged. glob's path-scurry dependency
also changes from 2.x to 3.x. These are compatibility risks despite being
build-time dependencies. Remove the overrides when the parent dependencies adopt
compatible patched requirements.

The old GitHub push banner said one high and one moderate issue. Its private alert
records could not be verified, so these two current audit results are not claimed
to be an exact reconciliation of that banner.

## Reproducible checks

Run from the repository root with Node 22.23.5 and pnpm 12.21.0:

```sh
pnpm install --frozen-lockfile
node --test test/glob-dependencies-security.mjs
for f in test/*.mjs; do node "$f" || exit; done
pnpm audit
NODE_OPTIONS=--max-old-space-size=768 pnpm build
pnpm lint --no-fix
```

The new regressions failed against master before the overrides: the cancelled
walk issued 364 reads instead of one, and both tested minimatch consumers exceeded
an explicit 32-pattern budget. They pass after the overrides. A separate smoke
check exercises the synchronous glob API, nested assets and ignore patterns used
by Workbox. Existing security scripts pass, audit reports zero vulnerabilities,
and the production web build generates the service worker with 47 precache
entries. The uncapped build was killed for memory; the bounded-heap build passed.

Master already had six quote-format lint errors in the DASH golden-fixture
generator, confirmed in a clean master checkout. This PR formats those lines
without changing behavior. The candidate passes full lint with local generated
Android build reports excluded. Those reports are untracked build output and are
absent in a clean checkout. No lint configuration is changed.

## Not established

No browser playback, live Piped service, offline service-worker runtime, physical
Android TV or iOS validation was performed for this dependency candidate. No
new CI workflow was added. Passing a build and regression tests is not proof of
all upstream major-version compatibility. Owner approval and completed review
remain required before merge.
