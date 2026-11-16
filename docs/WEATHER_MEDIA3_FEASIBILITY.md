# Weather animation playback — Media3 implementation

Status: implemented in weather PR #354 (Android TV and phone physical-device verification still required).

The Radar/Satellite map tabs open a separate fullscreen navigation destination; it does not
use the normal video/audio player, session, renderers, config panels or resume history.

The fullscreen player uses Media3 ExoPlayer image `MediaItem` playlists with a fixed
frame duration of 1100 ms. A `ResolvingDataSource` resolves a synthetic frame URI to
a local PNG created on demand through `WeatherMapRepository.playbackFrameFile`.
The repository composites the weather overlay and OSM background at the exact
centred map extent, so the original provider URLs never bypass coverage/caching rules.
Only the requested frame is loaded (Media3 may also preload its next frame); opening
a timeline does not download every available frame. There is no generated video,
no new server endpoint, and no normal playback service or history entry.

`PlayerView` renders the image with Media3's image mode. Compose draws the saved
location marker and attribution above the square map image. The weather-only OSD
offers image time scrubbing, previous/next, pause/play, repeat, zoom, source chips,
and a collapsible info panel with provider/product/time/zoom/width/coverage metadata.
The displayed time comes from the provider's published frame (UTC date for NASA),
not from local download time. A separate manifest refresh timestamp is shown.
Changing zoom keeps the closest matching published image timestamp. A static preview
of the previous map remains visible while the new viewport loads; repeat, play/pause,
info-panel state and TV zoom/source-button focus survive player replacement. Media3
frame errors have a dedicated Retry action instead of being mislabeled as buffering.
Layer availability metadata follows the actual image being decoded, not just the
initial preview frame.

When changing provider, zoom, location, leaving navigation, or hiding the viewer,
the old player is released and its pending resolver requests cancelled. Only
explicitly enabled providers may play. Opening, zooming, and selecting a source do
not force-refresh its manifest after a manual Retry. The visible fullscreen player
checks the manifest every five minutes (hourly for daily satellite imagery), without
boot/background polling. A manifest with unchanged image frames keeps its current
Media3 instance and playback position. Existing map caches are still bounded by
the weather repository's 64 MB disk cache and normal on-demand network policy.

Main follow-up: verify image timing and surface/OSD focus on physical Android TV,
ARMv7, handheld landscape, cache eviction under load, and service outages.
Reference: https://developer.android.com/media/media3/exoplayer/images
