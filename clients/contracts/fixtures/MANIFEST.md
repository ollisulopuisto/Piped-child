# Fixtures manifest

Real responses recorded 2026-11-18 (~19:15–19:21 EET) from live public
instances. Synthetic fixtures, when added, are named `*_SYNTHETIC.json` and
listed here with their derivation source.

| File | Endpoint | Source instance | HTTP | State of instance |
| --- | --- | --- | --- | --- |
| trending_OK.json | GET /trending?region=US | pipedapi.ducks.party | 200 | healthy for discovery |
| search_videos_OK.json | GET /search?q=lofi&filter=videos | pipedapi.ducks.party | 200 | shape: `{"items":[...]}` |
| search_suggestions_OK.json | GET /opensearch/suggestions?query=lofi | pipedapi.ducks.party | 200 | OpenSearch array |
| comments_OK.json | GET /comments/dQw4w9WgXcQ | pipedapi.ducks.party | 200 | includes `nextpage`, `disabled` |
| dearrow_OK.json | GET /dearrow?videoIds=dQw4w9WgXcQ | pipedapi.ducks.party | 200 | real replacement titles |
| sponsors_empty_OK.json | GET /sponsors/{id}?category=["sponsor"] | api.piped.private.coffee | 200 | `{}` when no segments |
| streams_degraded_360p_OK.json | GET /streams/dQw4w9WgXcQ | pipedapi.ducks.party | 200 | DEGRADED: dash/hls null, 1 progressive 360p, 0 audioStreams, subs+previewFrames present |
| streams_degraded_lbry_OK.json | GET /streams/dQw4w9WgXcQ | api.piped.private.coffee | 200 | DEGRADED: LBRY MP4 + LBRY HLS + 360p progressive |
| streams_error_login_required.json | GET /streams/{trending video} | api.piped.private.coffee | 500 | YouTube bot-block body (`error`, `message`) |
| lbry_hls_master.m3u8 | GET {LBRY HLS url from streams_degraded_lbry_OK.json} | proxy.piped.private.coffee | 200 | HLS v6 master playlist, 4 AVC variants 1080p/720p/360p/144p; the only real adaptive manifest recordable on 2026-11-18 |

Healthy `/streams` responses (dash/hls URLs, full adaptive videoStreams and
audioStreams) could not be recorded on 2026-11-18: all reachable instances
were extraction-blocked. Add one as `streams_healthy_OK.json` on the first
day an instance recovers; the error-taxonomy and fallback tests must keep
passing against the degraded and error fixtures meanwhile.

## Synthetic fixtures (added for M1-B, 2026-11-18)

These endpoints were NOT exercised live on 2026-11-18 (all reachable
instances failed extraction, and account endpoints need a real account).
Shapes are derived from the Piped-child frontend source (field usage in
src/components/ChannelPage.vue, PlaylistPage.vue, SubscriptionsPage.vue,
LoginPage.vue, FooterComponent.vue) and the contracts in
clients/contracts/api/README.md. Replace with recorded responses when a
healthy instance is available.

| File | Endpoint | Derivation source |
| --- | --- | --- |
| channel_OK_SYNTHETIC.json | GET /channel/{id} | ChannelPage.vue fields: id, name, avatarUrl, bannerUrl, description, subscriberCount, verified, nextpage, relatedStreams |
| playlist_OK_SYNTHETIC.json | GET /playlists/{id} | PlaylistPage.vue fields: name, description, uploader*, videos, nextpage, relatedStreams |
| user_playlists_OK_SYNTHETIC.json | GET /user/playlists | contracts/api/README.md: id, name, shortDescription, thumbnail, videos |
| subscriptions_OK_SYNTHETIC.json | GET /subscriptions | SubscriptionsPage.vue fields: url, name, avatar (+ verified from upstream API) |
| feed_OK_SYNTHETIC.json | GET /feed | Array of StreamItem (same shape as trending_OK.json) |
| login_OK_SYNTHETIC.json | POST /login | LoginPage.vue: `{token}` on success, `{error}` on failure |
| subscribed_OK_SYNTHETIC.json | GET /subscribed?channelId= | useSubscriptions.js: `response?.subscribed` |
| config_OK_SYNTHETIC.json | GET /config | FooterComponent.vue: donationUrl, statusPageUrl, privacyPolicyUrl |
| message_OK_SYNTHETIC.json | POST /logout, /subscribe, /unsubscribe, /user/playlists/* | OpenAPI `{"message": ...}` success bodies |
