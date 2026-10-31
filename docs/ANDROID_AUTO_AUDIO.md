# Android Auto (official audio browser)

This integration uses the official `androidx.media.MediaBrowserServiceCompat`
contract (including `onSearch`) and wraps Ambient TV's existing platform
`PlaybackService`/`MediaSession` without a duplicate decoder. It does **not**
bundle the unofficial OEM SDK from youtube-companion, display video, or use
projection APIs.

## Supported browse roots

- **Radio Browser:** favorites, recent, popular, all stations in paginated
  alphabetical order, genres, countries and languages. Android Auto's official
  `onSearch` displays multiple matching radio stations; voice playback also works.
  Selecting a station uses `RadioPlaybackLauncher` and its existing queue,
  reconnect, dynamic metadata and playback notification handling.
- **Spotify (when enabled and authorized):** home, saved tracks, playlists,
  albums, artists. Track selection is forwarded to the existing
  `SpotifyRepository`; the car browser session mirrors Spotify's state.
  Spotify remains responsible for its own Connect engine and audio focus.
- **IPTV Radio and TVHeadend:** each configured source lists only channels
  explicitly typed RADIO, under All radios and channel groups. Stream URLs,
  headers and channel identity come from the existing `LiveTvRegistry`.
  Video-only/unknown TV channels are never exposed or decoded in the car.
- **DLNA:** saved UPnP media servers, their ContentDirectory containers and
  audio resources, with server-driven 30-item page cursors and WOL support.
- **SMB, local and other file sources:** audio-only folder tree, file favorites
  and recent audio from `FileStore` and `FileAccess`. Existing SMB credentials,
  WireGuard and wake logic stay in the source layer; Android Auto never handles
  them. Directory listings use bounded prefixes (up to 2,000 entries/folder).
- **Plex/Jellyfin/Emby music libraries:** album/artist trees and track entries
  via the existing `RoutingMediaRepository`. Only audio tracks are playable;
  movies, series, episodes and video-only files are excluded. A track must have
  a resolvable audio stream URL to be selected.

No stream URL or login token is used as a media ID. DLNA/file media IDs are
opaque encoded application keys, not encrypted secrets. Only verified trusted
media clients can access the catalog. The car menu uses the host's
standard driver-safe UI; there are no custom on-road touch controls.

## Service lifecycle

A mere Android Auto browse bind does not start a foreground service. When a
playback engine starts, `PlaybackService.onStartCommand()` enters foreground
promptly and publishes the same native MediaSession as the phone app and media
notification. This avoids creating a second radio decoder in the car interface.
Each page has a short-lived stable snapshot so reconnection does not immediately
reorder it while a network request is outstanding.

## Test checklist (phone + Desktop Head Unit)

1. Install a debug APK on an Android phone, connect to DHU or a real Android
   Auto head unit, and open **Ambient TV** from the media launcher.
2. Check that the root contains the currently configured audio systems; disabled
   or unauthorized Spotify should not present an unusable sign-in page.
3. Browse radio favorites, recent, popular, all stations, country, language and
   genre filters. Open a page's "More…" entry; confirm no missing/duplicate
   stations, then use voice search and Android Auto's browsable results.
   Play two stations; confirm Play/Pause, Previous/Next and live title updates from the media widget,
   Bluetooth and steering-wheel transport controls.
4. From Spotify, select a track from Saved/Playlist/Album; confirm the correct
   Connect track, play/pause and seek and no simultaneous radio output.
5. Browse a music library from each configured Plex/Jellyfin/Emby server and
   play a supported audio track. Confirm no video/movie entries are offered.
6. Open IPTV and TVHeadend radio sources/groups, play radio, change channel,
   and ensure ordinary TV programmes are absent and video tracks disabled.
7. Browse DLNA folders containing mixed video/audio, use "More…" across
   server pages, play tracks, and verify WOL and reconnect.
8. Browse SMB shares with subfolders, including audio/video files, play
   FLAC/MP3, test file favorites/recent, WireGuard and invalid credentials.
9. Disconnect/reconnect Android Auto, change WLAN/mobile data, lock the phone,
   and return from the phone UI to the car while playback continues.
10. Verify the app creates no idle foreground notification just from browsing
   and that its media foreground notification appears once playback begins.
11. Test cold start and stop via Bluetooth, audio-focus interruption, headphone
   unplug, and notification dismissal. Check logs for source failures.

## Intentional exclusions and follow-ups

- No video playback, screen casting, parked-video integration or unofficial
  `aauto.aar` SDK.
- DLNA/SMB do not have a portable, universally available recursive full-text
  search endpoint. The Auto search includes already browsed audio plus SMB
  favorites/recent; browse folders for the rest. No unbounded recursive NAS
  crawl is triggered while driving.
- FileAccess lists at most 2,000 entries in one directory; larger SMB folders
  require a later source-specific offset-based adapter.
- `MediaBrowserServiceCompat` exposes official search results while wrapping
  the existing API-29+ platform MediaSession. Migration to Media3
  `MediaLibraryService` can be done later as one carefully tested session
  conversion; a second competing media session would be a regression.
