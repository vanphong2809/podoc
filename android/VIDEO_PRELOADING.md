# Video feed cache — PoDoc 1.6.38

Video playback now uses Media3 ExoPlayer 1.11.1. Audio playback retains the
existing service/session/notification controls through the same Media3 player.
The selected player is reused across swipes rather than released every time.

## Startup and moving window

The feed list must finish loading before the first playback request. The initial
window contains the selected entry and up to five entries on either side.
`VideoFeedPreloader` waits for three playable seconds of the selected source and
one playable second of every available neighbour. A shorter clip is cached in
full. The user sees preparation progress and cannot swipe until this gate opens.

After startup, cache targets are 6 seconds at distance 1, 4 seconds at distance 2,
and 2 seconds at distances 3–5. Rapid swiping or measured throughput below
1.5 Mbps reduces these targets to 2/2/1 seconds. After two seconds without a move,
normal targets are restored. During a drag, the predicted target gets priority.
Every settled page shifts the window and resolves the newly exposed neighbours
without delaying playback of a source already known in the previous window.

Media3 `PreCacheHelper` computes MP4 byte ranges from the actual seek map;
targets include keyframe/container overhead rather than assuming a fixed number
of bytes per second. Both metadata reads and body downloads use authenticated
HTTP. A single download executor limits pre-cache writes. The same disk cache
feeds playback and pre-cache reads. At most two native players exist: the active
player and one muted, paused standby decoder for the predicted adjacent video.
The standby prepares its first frame on that article's own surface; promotion
preserves that surface and never transfers an old frame between articles.
Its loading stops after roughly 750 ms of encoded samples (one source read may
overshoot). Devices that cannot open the standby decoder fall back to cached
playback without failing the current video.

Foreground playback has priority: neighbourhood prefetch pauses below a
three-second buffer and resumes at eight seconds, or when a short clip is fully
buffered. When the window is warm and foreground buffering is healthy, files up
to 64 MiB can finish caching in the background in cancellable 512 KiB ranges.

## Storage and identity

One application-wide `SimpleCache` lives in internal `cacheDir/video_stream_cache`.
Its LRU budget is at most 512 MiB, reduced on devices with little free storage;
cache initialization requires at least 64 MiB of free space. Active-player
compressed buffering targets 16 MiB per player; this is not a cap on total
app/decoder RAM.

Removing a source from the moving window cancels its downloads and releases its
readiness state but **keeps its cached byte spans**. Readiness markers are discarded
so returning sources verify cache coverage again, including after LRU eviction.
Leaving the reader does not delete cached bytes. Logout stops playback/preload
and removes cached media.

Cache keys include a hash of server/account plus article ID, variant and file
revision. Tokens are passed in Authorization headers and never enter cache keys.
`POST /api/articles/playback-sources` resolves up to 11 files with one DB query and
filesystem stats, without ffprobe. Playback URLs pin original/optimized and a
32-character revision. Range reads reject changed revisions with HTTP 412.
Metadata is refreshed after 30 seconds, and retry resolves fresh sources.
An original and its later derivative cannot share
the same cache entry.

Startup HTTP metadata is bounded to 15 seconds and media preparation to 60 seconds.
Failures show retry UI rather than silently opening an unprepared window.

## Verification

`VideoPreloadPolicyTest` covers boundaries, overlap and video twelve entering the
window at position seven. `VideoFeedTest` uses authenticated HTTP/Range fixtures,
holds the eleventh opening to verify the startup gate, measures bytes downloaded
for a 6.5 MB far clip, checks prefetch through sixteen before selection, and
checks cache reuse on return. Existing aspect-ratio, cancellation, loading and
audio tests remain. The long fixture is packaged only in the test APK.

`VIDEO FIRST FRAME` Android logs measure selection-to-presented-frame latency;
startup includes its deliberate preparation wait. Emulator figures do not imply
the same latency on every physical device or network.
