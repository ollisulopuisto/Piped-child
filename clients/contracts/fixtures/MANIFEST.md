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
