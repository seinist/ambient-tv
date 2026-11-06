# Android Auto (official audio browser)

This integration uses AndroidX Media3 `MediaLibraryService` and
`MediaLibrarySession`. `SessionPlaybackPlayer` adapts Ambient TV's existing
`BackgroundPlaybackSession` to Media3's `Player` contract, so Media3, MPV and VLC
keep their existing engine ownership, audio focus and playback policies.
The service exposes both the Media3 and legacy browser actions: Android Auto,
platform media controls and older browser clients reach the same session.
The car interface offers audio browsing and playback through the host UI.

## Supported browse roots

- **Radio Browser:** favorites, recent, popular, all stations in paginated
  alphabetical order, genres, countries and languages. Android Auto's official
  `onSearch` displays multiple matching radio stations; voice playback also works.
  Selecting a station uses `RadioPlaybackLauncher` and its existing queue,
  reconnect, dynamic metadata and playback notification handling.
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
promptly and publishes the Media3 session's platform token in the existing
notification. The service retains its own notification/foreground policy through
`onUpdateNotificationAsync`, including sleep controls and final progress reports.
Spotify's Connect service continues to own Spotify playback and audio focus;
the Auto session mirrors its state and routes transport commands to it.

`PlaybackLibraryCallback` serves asynchronous library and search results, validates
trusted controllers, and resolves only catalog IDs. The existing synthetic
"More…" folders remain stable, while Media3 page requests slice each folder's
snapshot. Prepare-only selections stay silent until Play. Pending source changes
are cancelled when stopped or replaced, and the adapter owns no decoder.

A browser may remain bound after playback stops. The service keeps its polling
and reporting loop available for the next selection instead of abandoning it
after `stopSelfResult`. A retiring instance still cannot release a newer engine.

## Test checklist (phone + Desktop Head Unit)

1. Install a debug APK on an Android phone, connect to DHU or a real Android
   Auto head unit, and open **Ambient TV** from the media launcher.
2. Check that the root contains the currently configured audio systems.
3. Browse radio favorites, recent, popular, all stations, country, language and
   genre filters. Open a page's "More…" entry; confirm no missing/duplicate
   stations, then use voice search and Android Auto's browsable results.
   Play two stations; confirm Play/Pause, Previous/Next and live title updates from the media widget,
   Bluetooth and steering-wheel transport controls.
4. Browse a music library from each configured Plex/Jellyfin/Emby server and
   play a supported audio track. Confirm no video/movie entries are offered.
5. Open IPTV and TVHeadend radio sources/groups, play radio, change channel,
   and ensure ordinary TV programmes are absent and video tracks disabled.
6. Browse DLNA folders containing mixed video/audio, use "More…" across
   server pages, play tracks, and verify WOL and reconnect.
7. Browse SMB shares with subfolders, including audio/video files, play
   FLAC/MP3, test file favorites/recent, WireGuard and invalid credentials.
8. Disconnect/reconnect Android Auto, change WLAN/mobile data, lock the phone,
   and return from the phone UI to the car while playback continues.
9. Verify the app creates no idle foreground notification just from browsing
   and that its media foreground notification appears once playback begins.
10. Test cold start and stop via Bluetooth, audio-focus interruption, headphone
   unplug, and notification dismissal. Check logs for source failures.
11. With a Media3 browser, prepare a different audio item while paused: verify
   silence until Play, then Play/Pause/Seek/Stop. Stop during source loading and
   confirm a late response cannot restart it. Repeat while changing sources.
12. Stop playback while Auto stays connected, then select another item. Verify
   status/position updates and saved progress still work after the service's
   started lifetime has ended once.
13. For playback started in the app, test system controls with Media3, MPV and
   VLC; check paused/buffering/ended/error states, absolute seek offsets, and
   disabled seek for radio or an unseekable live stream.
14. Switch between Spotify, radio and library audio. Check delayed/failed Spotify
   starts, one notification owner, no duplicate audio focus, and next/previous.
15. Dismiss the app from recents and stop an item near completion. Confirm final
   server/local progress reports and correct teardown, including a new renderer
   acquired during the old service's retirement.

## Intentional exclusions and follow-ups

- No video playback, screen casting, parked-video integration or unofficial
  `aauto.aar` SDK.
- DLNA/SMB do not have a portable, universally available recursive full-text
  search endpoint. The Auto search includes already browsed audio plus SMB
  favorites/recent; browse folders for the rest. No unbounded recursive NAS
  crawl is triggered while driving.
- FileAccess lists at most 2,000 entries in one directory; larger SMB folders
  require a later source-specific offset-based adapter.
- Media3 supplies its own backward-compatibility implementation for old clients.
  The app no longer directly uses `MediaBrowserServiceCompat`, `MediaSessionCompat`
  or their browser item types; a transitive compatibility dependency may remain
  inside Media3 itself.
