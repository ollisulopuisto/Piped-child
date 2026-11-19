# Android TV development build

Requires JDK 17, Gradle 8.10.2, and the Android SDK with platform 35 and build tools 34.0.0. Set `ANDROID_HOME` to the SDK directory.

From `clients/android`:

```sh
gradle :core-model:test :core-network:test :core-player:testDebugUnitTest :app-tv:testDebugUnitTest :app-tv:assembleDebug --configure-on-demand --max-workers=1
```

On a memory-limited machine, add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx450m`. Run core tests and app tests/build in separate invocations if necessary.

The debug-signed APK is `app-tv/build/outputs/apk/debug/app-tv-debug.apk`. With the target TV connected through ADB:

```sh
adb install -r app-tv/build/outputs/apk/debug/app-tv-debug.apk
adb shell am start -n fi.pipedchild.tv/.MainActivity
```

This is a development APK, not a release-signed build. Installing or launching it on a physical TV is a separate check, not something the JVM tests establish.

## Current flow

1. Approve the API instance before the first request. This version defaults to `https://pipedapi.ducks.party`. Instance settings is available before approval and above the home grid.
2. Optionally approve the listed HTTPS thumbnail image origin before any images load, or choose Titles only. New image origins are shown individually. Approval lasts for the home ViewModel session, not across app restarts; image cache is memory-only. Move around Trending with the remote. The focused card has a white border. Select a card to request its playback metadata.
3. Approve the media proxy shown for that selection before any video bytes are loaded. Approval is per selection. Only HTTPS proxy origins are supported in this increment.
4. Media3 supplies playback controls. Back returns to the selected card and its grid position. Leaving/backgrounding the player releases it; foregrounding resumes the in-session position.

Media requests to undisclosed origins are blocked. The data source rewrites Google video URLs through the approved proxy, converts `/videoplayback` byte ranges to the Piped query format, and rejects HTTP redirects. Direct LBRY/CDN entries are excluded rather than silently contacting them. Some videos may therefore have no supported source even if the web frontend can play them.

SponsorBlock playback integration, and the optional isolated ChoreKeeper module are not included yet.

## Verification boundaries

`TvScreenRenderTest` uses Robolectric native graphics to render the real Compose layouts and generate screenshots under `app-tv/build/tv-screens/`. It sends directional and center key events through the grid, scrolls beyond the first visible rows, and checks selection and focus restoration after a Back dispatch. Other tests cover metadata cancellation, proxy approval, shared source selection, URL filtering and the Media3 byte-range bridge.

These are JVM checks. They do not verify Android TV launcher behavior, physical remote behavior, hardware decoding, or end-to-end playback on a Chromecast. Run those on an Android TV emulator and a physical Chromecast with Google TV 4K before calling M2 hardware acceptance complete.

### Thumbnail safety checks

Thumbnail HTTP uses no cookies or credentials and does not follow redirects. Responses must be images and at most 2 MiB, including chunked bodies. Decoding rejects dimensions over 4096 in either direction and samples to at most 640 by 360; there are at most three in-flight loads and a 12 MiB memory cache. Malformed, blocked, oversized or failed images leave the title and placeholder usable. Session approval is checked before every cache hit as well as before fetching.

Loopback HTTP tests verify redirect refusal, MIME checks, the chunked download limit, and absence of Cookie/Authorization headers. Native-graphics JVM tests check the optional consent gate, remote decline, zero fetches before approval, actual image rendering, caching and cancellation. These tests are not physical-TV or real-network image validation.

### Switching the API instance

Open Instance settings before first-run approval or press Up from the grid's first row. Enter an HTTPS API host root, optionally with a port, then Save instance. The form does not probe the URL or claim it is healthy. A changed URL is saved locally, API approval is removed, and a fresh MainActivity session replaces the old clients, memory caches and image approvals. The new instance cannot receive requests until the first-run screen is accepted. Saving an equivalent normalized URL leaves the existing session unchanged. Cancel and invalid input leave the existing setting and approval unchanged.

This TV settings increment supports HTTPS only, including private HTTPS hosts with valid certificates. It does not expose the networking module's private-network plaintext HTTP allowance, because the Android app does not yet have a verified per-domain cleartext configuration. No silent failover or token migration is performed.
