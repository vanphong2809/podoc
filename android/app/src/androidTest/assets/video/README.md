These synthetic H.264 fixtures contain only a colored background and a moving square.
Each clip runs for eight seconds at 30 fps without audio:

- portrait.mp4: 180 x 320
- landscape.mp4: 320 x 180

They were generated locally with AVAssetWriter for VideoFeedTest. They contain no
user media and are packaged only in the instrumentation test APK.

The HTTP feed regression tests use `portrait-hd.mp4` (1080×1920) and
`landscape-hd.mp4` (1920×1080), four seconds at 30 fps without audio.
FFmpeg generated four solid quadrants (red/green/blue/yellow); screenshot
assertions verify their position after portrait/landscape switches so a stale,
stretched or scrambled output fails the test. A local HTTP server supports Range
and can hold a response to verify the black loading screen without thumbnails.

`prefetch-long.mp4` is a 20-second synthetic 640×360 test pattern at 30 fps,
H.264 CBR 2.5 Mbps, without audio (about 6.5 MB). The window tests verify that a
far neighbour caches only its opening and downloads less than half this file.
All HTTP fixtures require an Authorization header, expose batch source metadata,
and count Range requests/bytes to detect downloads repeated despite caching.
