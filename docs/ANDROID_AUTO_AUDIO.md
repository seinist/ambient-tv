# Android Auto (official audio browser)

This integration uses the platform `android.media.browse.MediaBrowserService` contract
and the existing Ambient TV `PlaybackService`/`MediaSession`. It does **not**
bundle the unofficial OEM SDK from youtube-companion, display video, or use
projection APIs.

## Supported browse roots

- **Radio Browser:** favorites, recently played stations, popular stations.
  Selecting a station uses `RadioPlaybackLauncher` and its existing queue,
  reconnect, dynamic metadata and playback notification handling.
- **Spotify (when enabled and authorized):** home, saved tracks, playlists,
  albums, artists. Track selection is forwarded to the existing
  `SpotifyRepository`; the car browser session mirrors Spotify's state.
  Spotify remains responsible for its own Connect engine and audio focus.
- **Plex/Jellyfin/Emby music libraries:** album/artist trees and track entries
  via the existing `RoutingMediaRepository`. Only audio tracks are playable;
  movies, series, episodes and video-only files are excluded. A track must have
  a resolvable audio stream URL to be selected.

No URL or login token is used as a media ID. The car menu uses the host's
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
3. Browse radio favorites, recent and popular; play two stations; confirm
   Play/Pause, Previous/Next and live title updates from the media widget,
   Bluetooth and steering-wheel transport controls.
4. From Spotify, select a track from Saved/Playlist/Album; confirm the correct
   Connect track, play/pause and seek and no simultaneous radio output.
5. Browse a music library from each configured Plex/Jellyfin/Emby server and
   play a supported audio track. Confirm no video/movie entries are offered.
6. Disconnect/reconnect Android Auto, change WLAN/mobile data, lock the phone,
   and return from the phone UI to the car while playback continues.
7. Verify the app creates no idle foreground notification just from browsing
   and that its media foreground notification appears once playback begins.
8. Test cold start and stop via Bluetooth, audio-focus interruption, headphone
   unplug, and notification dismissal. Check logs for source failures.

## Intentional exclusions and follow-ups

- No video playback, screen casting, parked-video integration or unofficial
  `aauto.aar` SDK.
- IPTV Radio, TVHeadend audio-only channels, DLNA and SMB file-directory music
  need a separate audio source adapter and are **not** exposed by this initial
  browser. They should not be presented as playable until a headless service
  route exists that can provide a valid audio stream.
- The platform browser covers Android Auto through the existing API-29+ phone
  app. The future Media3 `MediaLibraryService` migration can be done once the
  project's platform MediaSession is migrated in one place; introducing a second
  active media session for the same playback would be a regression.
