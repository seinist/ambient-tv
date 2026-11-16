# Weather animation playback — Media3 feasibility

Status: feasibility review only; no change to the video player.

The weather animation currently consists of separately downloaded composited Bitmap frames
(base map, weather overlay and coverage). The location marker and map attribution
are Compose overlays. WeatherMapViewModel already prefetches two upcoming frames,
caches completed loops, displays one frame about every 1.1 seconds, and cancels playback
on pause, hiding the view or switching provider/location. The frames are not video streams.

Media3 ExoPlayer supports PNG/JPEG images in a playlist: use MediaItem.Builder with
setImageDurationMs(duration). A future full-screen weather player could expose composited
frames through stable local content URIs or a bounded temporary cache and pass the ordered
images to Media3, rendering the location marker and attribution over the image output.

Caveats:
- Ambient TV's shared player is currently built for media URIs, video/audio tracks and
  metadata, not the repository's in-memory Bitmaps. Passing upstream WMS/RainViewer
  image URLs directly bypasses compositing, centre/zoom, and cache/error handling.
- Loading and decoding an entire map timeline can increase latency, disk I/O and memory;
  image playlists are not equivalent to hardware-decoded H.264/HEVC video.
- The image output used by Ambient TV's player needs TV and phone tests. Preserve
  weather timestamps, provider credits, repeat/pause, zoom, offline/stale indications,
  cancellation on leave, and avoid adding weather to playback history.
- VLC/MPV are not direct substitutes for an in-memory bitmap playlist without encoding.

Recommendation: retain the existing in-view animation. If full-screen playback is
desired, prototype a separate Media3 image-playlist mode with bounded cached frames,
then measure first-frame latency, loop smoothness and memory before enabling it.

Reference: https://developer.android.com/media/media3/exoplayer/images
