# Ambient TV – Feature compendium

**Languages:** **English** · [Deutsch](DOCUMENTATION.de.md)

**App:** Ambient TV (`com.ambient.tv`) · version `1.0` (see `version.properties` for versionCode) · minSdk 29, target/compileSdk 37 · settings backup schema 76
**License:** [CC0 1.0 Universal](../LICENSE)

This compendium lists what the app does today: every settings page, the player's Video and Audio panels, and the media sources. Labels are the real UI texts from `app/src/main/res/values*/`. Defaults are read from the code (`AppPreferences`, `AudioOptions`, `VideoImageOptions`, `SettingsUiState`). Nothing here is planned work.

**How to read the tables**

| Column | Meaning |
| --- | --- |
| Options / values | Choices the control offers. Ranges are inclusive. |
| Default | Value on a fresh install. "—" means there is no stored value (an action or a field that starts empty). |
| Engines | **M3** = Media3 (ExoPlayer), **MPV**, **VLC**. "All" = all three. |
| Devices | **TV** = Android TV / Leanback UI, **Phone** = phone and tablet (touch UI). "All" = both. |
| Shown when | Conditions under which the control appears. Without a note the control is always shown on its page. |

---

## Table of contents

1. [Overview](#1-overview)
2. [Settings structure and saving](#2-settings-structure-and-saving)
3. [Interface & controls](#3-interface--controls)
4. [Media sources & accounts](#4-media-sources--accounts)
5. [Devices & network](#5-devices--network)
6. [Playback settings](#6-playback-settings)
7. [Player panel (during playback)](#7-player-panel-during-playback)
8. [Performance modes](#8-performance-modes)
9. [App & data](#9-app--data)
10. [Setup](#10-setup)
11. [Navigation and controls](#11-navigation-and-controls)
12. [Phone, tablet and TV together](#12-phone-tablet-and-tv-together)
13. [Backup and restore](#13-backup-and-restore)
14. [Development and build](#14-development-and-build)
15. [Limits and notes](#15-limits-and-notes)

---

## 1. Overview

Ambient TV is one APK with two interfaces: a D-pad UI with a sidebar for Android TV, and a touch UI with a bottom bar for phones and tablets. Android's UI mode decides which one starts.

| System | What it brings | Devices |
| --- | --- | --- |
| Plex | Libraries, Home rows, search, playback, plex.tv/link sign-in, theme music | All |
| Jellyfin | Libraries, Home rows, search, playback, user/password sign-in, theme music | All |
| Emby | Same feature set as Jellyfin | All |
| TVHeadend | Live TV, guide, recordings, timers, own player choice, deinterlacing | All |
| IPTV | M3U playlists, XMLTV guide, channel groups, live player | All |
| SmartTube | YouTube feeds, sign-in by device code, SponsorBlock, "Watch on TV" phone link | All |
| Spotify | Native playback (librespot) and Spotify Connect receiver; Premium needed for playback | All |
| Monogram (optional GPL/MTProto build) | Telegram channel media, full post captions, six Home rows, YouTube links via SmartTube; TV QR or phone code/2FA login | All |
| Trailers | TMDB trailers and a "Now in cinemas" row; needs your own TMDB key | All |
| DLNA | Browse UPnP/DLNA media servers on the LAN | All |
| Files | Local/USB folders, SMB 1/2/3, NFSv3/v4, WebDAV and SFTP sources, favorites, recently opened, continue watching | All |
| Photos | Albums, timeline, folders, slideshow, photo screensaver | All |
| UPnP receiver | Other apps (BubbleUPnP, VLC, …) can send media to this device | All |
| Companion | Pair a phone with a TV: remote control, send playback, transfer accounts | Phone ↔ TV |
| WireGuard | Built-in tunnel, switchable per media system | All |

Shared building blocks: global search across enabled systems, mini-player, background playback, screensaver, notification cards with history, settings backup, UI in English or German.

---

## 2. Settings structure and saving

Settings has five areas. Each area lists its pages; the settings search finds pages and options across areas.

| Area | Pages |
| --- | --- |
| **Interface & controls** | Home rows · When selecting media · Language · Library · Look & feel · Background video · Screensaver · Menu sounds · Clock |
| **Media sources & accounts** | Plex · Jellyfin · Emby · TVHeadend · IPTV · SmartTube · Spotify · Monogram (opt-in build) · Trailers · DLNA · Files (configured systems first, then "add" entries) |
| **Devices & network** | Phone & devices · UPnP receiver · WireGuard / VPN · Servers & Wake-on-LAN |
| **Playback** | Default player · Subtitles · Intros, credits & next up · Seek & resume · Picture profiles · Audio profiles · Audio visualization · Photos |
| **App & data** | Notifications · Transfer accounts and settings · Settings backup · Diagnostics · About this app |

**Saving.** Ordinary values are a draft and apply with **Save**. Leaving with open changes asks to save or discard. These act at once: sign-in, sign-out, server changes, connection tests and import ("Sign-in, server changes, tests and import apply at once; save for everything else."). Pairing and TV availability on *Phone & devices* also apply at once. Export writes the saved values, not the draft.

Every media system page also has:

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Display name | Free text | Empty (provider name) | Replaces the system name in the menu, on Home, in search and in settings. |
| Submenu items | Per-system entries | All on | Which entries the sidebar submenu shows. Includes a "Settings" shortcut at the end. |
| Start page of this system | Continue Watching, Recently Added, Collections, Libraries, Recently Watched, Top Unwatched Picks, Top by genre, Because You Watched …, Server Recommendations | All on except Plex "Server Recommendations" | Rows on the system's own page. Home has its own layout. |
| Network settings | Link | — | Jumps to Servers & Wake-on-LAN and WireGuard. |

---

## 3. Interface & controls

### 3.1 Home rows

| Setting | Options / values | Default | Devices | Description |
| --- | --- | --- | --- | --- |
| Customize Home | Show, reorder, remove rows | Plex: Continue watching, Recently added, Collections; TVHeadend: Live TV | All | Row editor for the app's Home. |
| Add a row | Continue watching · All systems; per system: Continue watching, Recently added, Collections, Libraries; Recently watched TV channels; TVHeadend Live TV / Guide / Recordings / Timers; SmartTube Home / Subscriptions / History / Playlists / Shorts / Music / News / Gaming / Live / Kids; Spotify Home / Liked songs / Playlists / Albums / Artists / Now playing; Monogram Recent media / Channels / Videos / YouTube links / Music & audio / Photos (opt-in); Trailers / Now in cinemas; Recently added photos; Top in genre; shortcuts to system sections | — | All | Content rows or shortcuts to a section of a system. |
| Systems in "Continue watching · All systems" | Plex, Jellyfin, Emby | All on | All | Which servers feed the combined row. Shown when that row is on Home. |
| Show on the home screen | On / Off | On | TV | Watch Next plus the channels "Continue watching" and "Recently added" on the Android TV launcher. Shown when the launcher supports it. |
| Clear TV channel history | Action | — | All | The recent-channels row keeps up to 12 channels on this device; not in backups. |

**Layout on TV:** the hero fills the upper part of the screen; one row rests on the bottom edge beneath it, the others fade out until you move to them (like Plex). Tiles in these rows scale with the screen, independent of the UI scale, so one row fills the space under the hero at 4K too. Down from the last row wraps to the first; Up from the first row reaches the page header or whatever sits above (e.g. the update notice), otherwise the last row. The same applies to library recommendation pages. Titles, context and descriptions use the available hero space and can wrap onto additional lines when height permits, including in Cinematic view. Metadata and playback progress keep their space; text is shortened only at the actual height limit.

**Offline servers on Home:** a server that does not answer keeps one row on Home with its name and **Wake up** (with Wake-on-LAN) or **Reconnect**; its other rows return by themselves once it answers. When only its rows were on Home, Home shows the offline page instead. A failure is reported by the connection notice only; there is no dialog.

**Refresh on entry and after reconnect:** Content views fetch fresh data asynchronously every time they are entered, including a return from a detail page or from the background. A successful server reconnect or network recovery also refreshes the currently open view. This covers Home, libraries, details and submitted searches, plus Live TV channels, guide, recordings and timers; YouTube, Spotify, files/NAS, DLNA, photos and trailers participate too. The source, section, query and existing content remain in place while loading; previously loaded pages and focus are preserved where possible. Successful empty lists remove outdated entries, while a failed partial request retains its previous data. IPTV also requests fresh M3U and XMLTV data. Simultaneous entry and an already pending reconnect are combined. Ongoing changes, such as recording actions or watched-state updates, finish before the pending refresh runs. Renewed Spotify authorization also refreshes open Spotify views.

**Offline labels in the menu:** Plex, Jellyfin, Emby and TVHeadend, as well as individual NAS and DLNA sources, use the same confirmed host status on TV and handheld devices. Entries remain selectable. A login error or stored guide data alone does not mean “Offline” and does not block an otherwise reachable Live TV source. The Offline label for Live TV from a saved server profile uses that profile's own host, even when a different server is currently selected for the library.

### 3.2 When selecting media

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| When selecting media | Show detail page · Play immediately · Show playback options | Show detail page | For Plex, Jellyfin and Emby. SmartTube has its own choice in the SmartTube module (default: play). |

### 3.3 Language

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| App language | Automatic · English · Deutsch | Automatic | Automatic follows the Android locale. Changing it reloads the UI. |
| Metadata language | Automatic + language list | Automatic | Titles and descriptions from Plex, Jellyfin and SmartTube. |

The preferred **audio language** is on *Playback › Audio profiles*; subtitle languages are on *Playback › Subtitles*.

### 3.4 Library

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| High-resolution images | On / Off | Off | Sharper covers and backdrops; more network and storage. |
| Posters per row | 4–12 | 8 | Grid density. |
| List rows on screen | 4–10 | 7 | List density. |
| Filter bar | On / Off (View menu) | On | Filters and sort as a bar directly above the content in Library, person/collection results, photo albums, Live TV/guide, Trailers and SmartTube: each filter opens its values in place, two-value filters are switches, Reset appears only with active filters. Off moves filters and sort back into the View panel. |

**View** is the last action on the right of the page header, including Live TV, guide, DLNA, search, person/collection results and photo albums. Its position stays the same when the filter bar is shown or hidden. It opens a shared panel containing the layouts, filters or sorting options supported by that page. Search offers rows/list, while person/collection results offer grid/list; a layout changes only when selected. Live TV keeps A–Z as a shortcut beside channel navigation and shows the channel count with the title context. Photos keeps timeline/folders as a separate section choice, while date/name and sort direction appear together in the filter bar or panel. The fixed chronological photo timeline has no additional view options. Closing the panel returns focus to the View action.

**Reload** appears before **View** on refreshable browse pages: libraries and Plex/Jellyfin/Emby overviews, person/collection results, SmartTube, the trailer catalog and photos. Spotify offers the same action after its context actions. Live TV, guide, recordings/timers, Files and DLNA retain their existing refresh action. It shows the active request and keeps existing content along with the current section, filters and sort. DLNA also reloads the extent of pages already loaded. The manually activated header button keeps focus; removed content may fall back to a neighboring item on the next content entry. Global search is run again through **Search**.

Changing filters, sort or layout keeps focus on the control being edited while results update. If a filter leaves no results, **Reset filters** returns focus to the last edited filter. With the filter bar hidden, it reopens the View panel at that filter. Normal content entry and returning from a detail page still restore the selected item.

### 3.5 Look & feel

| Setting | Options / values | Default | Devices | Shown when / Description |
| --- | --- | --- | --- | --- |
| Appearance | Dark · OLED black · Dim | Dark | All | Theme. Live preview at the top. |
| Interface size | Slider 50–150 % | 100 %; on TVs 90 % at 1440p and 70 % at 4K | All | Size of text, cards and controls. The TV default is set once from the display resolution. |
| Accent color | Gold, Orange, Blue, Teal, Green, Purple, Pink, Red, Artwork, Custom | Gold | All | Artwork tints detail pages from the cover; elsewhere gold. Custom opens a color picker (default `#E5A00D`). |
| Separate playback color | On / Off | Off | All | Second color for playback status and progress. |
| Swap main and playback colors | Action | — | All | Shown with a separate playback color. |
| Playback color | Color picker | `#1A5FF2` | All | Shown with a separate playback color. |
| HDR interface | Automatic (safe) · Always HDR · Always SDR | Automatic (safe) | All | Shown on Android 15+ (API 35) with an HDR display and wide color gamut. Automatic lifts the UI only while HDR plays. |
| SDR video to HDR | On / Off | Off | All | See [3.5.1](#351-sdr-video-to-hdr). Works only with HDR interface "Automatic". |
| Interface performance | Auto · Balanced · Maximum performance · Low | Auto | All | Animations, shimmer, heavy surfaces. See [§8](#8-performance-modes). |
| Check interface | Action | — | All | Measures UI drawing load and suggests a mode. |
| Start animation | Off · Light field · Daybreak | Light field | All | Plays once on a cold start, in the accent colors. While a server wakes, the light keeps breathing. Any key continues. Low mode shows the settled wordmark. |
| Playback performance | Auto · Balanced · Maximum performance · Low | Auto | All | Motion smoothing, heavy upscalers, double-rate deinterlace. |
| Frame drop test duration | Automatic, 2, 5, 6, 10, 15, 30, 60 s | Automatic (2–6 s) | All | Window of the filter test (MPV, VLC, Media3). |
| Check playback | Action | — | All | Measures filter load off-screen. |
| Light effect above Home | Quiet · Light sweep · Artwork color · Depth motion · Aurora · Cinema light · Prism · Dusk · Ember · Tide · Focus · Moonlight · Mist · Candlelight · Stardust · Projector · Color breath | Depth motion | All | Moving look of the Home hero. Low mode and reduced effects show "Quiet". |
| Light intensity | 0–100 % | 60 % | All | Strength of the hero effect. |
| Light spread | Soft · Focused · Wide | Soft | All | Size of the light. |
| Home background on all screens | On / Off | Off | All | Home effect behind other screens. Off in Low mode. |

#### 3.5.1 SDR video to HDR

Off by default. Shows eligible SDR video as HDR10 on an HDR display. Does not create Dolby Vision.

| Requirement | Value |
| --- | --- |
| Android | 14+ (API 34) |
| Display | HDR10 in the current mode, wide color gamut, HDR/SDR ratio ≥ 1, peak luminance ≥ 300 nits |
| HDR interface | Automatic (safe); "Always SDR" disables it |
| Performance | Not with reduced effects |
| MPV | Vulkan video output must already be selected |
| VLC / Media3 | VLC with Android output or Media3; confirmed BT.709 SDR up to 1920×1080 at up to 60 fps, no extra GPU effect chain, SVP or software filters. VLC Vulkan is not supported. |
| Status line | off · unavailable · requested (awaiting renderer) · renderer confirmed · disabled after an error. "Renderer confirmed" does not verify the TV output. |

### 3.6 Background video

| Setting | Options / values | Default | Engines | Description |
| --- | --- | --- | --- | --- |
| Show video behind the interface | On / Off | Off | All | Keeps the playing video visible while browsing. Off: only audio continues. |
| Background video visibility | 90 %, 75 %, 60 % visible | 75 % | All | Shown when the option above is on. |

### 3.7 Screensaver

| Setting | Options / values | Default | Shown when / Description |
| --- | --- | --- | --- |
| Enable screensaver | On / Off | On | Starts after idle time; never interrupts playback. |
| Start after | Minutes (1–120) | 10 min | Screensaver on. |
| Style | Ambient · Aurora · Fluid · Starfield · Silk · Media · OLED · Photos | Ambient | Screensaver on. |
| Animation quality | Economy · Balanced · High | Balanced | Shader styles (Aurora, Fluid, Starfield, Silk). |
| Photo source | Album / library, or "Recently added photos (all libraries)" | Recently added | Style "Photos". |
| Show clock | On / Off | On | OLED shows a small dimmed moving clock. |
| Show date | On / Off | On | Clock on. Time format and date style follow [Clock](#39-clock). |
| Use media artwork | On / Off | On | Style "Media". Uses artwork already shown in this session. |
| Artwork change | Seconds (10–300) | 30 s | Style "Media" with artwork. |
| Brightness | Percent (20–100) | 70 % | Dims only the screensaver. |
| Test screensaver | Action | — | Preview until the next key press, without saving. |

### 3.8 Menu sounds

| Setting | Options / values | Default | Devices | Description |
| --- | --- | --- | --- | --- |
| Menu sounds | On / Off | Off | All | Soft clicks on focus and activation. Silent during playback. TV menu sounds stay muted with interface performance Low. Phones use media volume. |
| Menu sound volume | Slider 0–100 % | 30 % | All | Loudness of the clicks. |
| Sound editor | Per event: Focus movement, Confirm / open, Back / close, Switch on, Switch off, Change value, Success notification, Error notification | Built-in set | All | Presets (Warm, Soft Glass, Deep Ambient, Soft sine, Soft bell, Ambient pad, Minimal, Wood, Crystal, Sonar, Airy, Tape echo) or Custom: pitch, tone length, attack, release, warmth, detune, reverb (amount, room, tail), echo (amount, delay, feedback), event volume. Export / import as file. |

### 3.9 Clock

Time and date in page headers including detail pages, on Home and in the player, on TV and phone. On TV the Home clock floats above the hero; on phones it has its own row. Color, opacity, font, size and shadow also apply to the player clock. Narrow phone headers shrink the time only when needed and shorten long dates to keep controls reachable. One shared tick jumps to the full minute or second and follows time and time-zone changes at once; the player clock keeps running while the OSD is open. The page has a live preview.

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Show | Off · Home screen only · Everywhere (headers) · Everywhere + player | Everywhere + player | Where the clock appears. |
| Format | 24-hour · 12-hour (AM/PM) · Like the system | 24-hour | Also used by the screensaver. |
| Seconds | On / Off | Off | Updates every second; costs a little performance on slower TVs. |
| Date | Off · Short (Tue, Oct 27) · Long (Tuesday, October 27) · Numeric (10/27) | Short | Also used by the screensaver. |
| Date beside the time | On / Off | Off | One line instead of the date beneath the time. |
| Size | Small · Medium · Large | Medium | |
| Font | Like the interface · Light · Bold · Monospace | Like the interface | |
| Color | White · Accent color · Dimmed | White | |
| Opacity | 40–100 % | 92 % | |
| Shadow | Off · Soft · Strong | Soft | |
| End time in the player | On / Off | On | Shows "Ends at …" next to the remaining time. |

The clock settings are part of the settings backup; older backups take over their former 24-hour switch.

“Everywhere (headers)” shows the clock outside playback; “Everywhere + player” includes playback. The screensaver shares time format and date style but retains its separate brightness and OLED dimming.

---

## 4. Media sources & accounts

### 4.1 Plex

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Enable Plex | On / Off | On | Hides Plex; servers and credentials are kept. |
| Sign in / Sign out | plex.tv/link code with QR | — | Account link. |
| Saved servers | List; remove | — | Active server marked ●; WOL badge when a MAC is set. |
| Search for server | Action | — | LAN discovery ("Found on LAN"). |
| Server IP · Port · Access token (optional) | Text | Port 32400 | Manual connection. |
| Use HTTPS | On / Off | Off | For reverse proxy or TLS. |
| Use secure address | Action | — | Offered after sign-in when the server has a plex.direct HTTPS address. |
| Test connection | Action | — | Checks address and token. |
| Choose server at startup | On / Off | On | Only used with several saved servers. |
| Local network only | On / Off | On | Limits media connections to the LAN. Off shows a cleartext warning if HTTPS is off. |
| Automatic refresh | Off, 30, 60, 120 s | 60 s | Reloads Plex Home and the visible library. |
| Theme music | On / Off | Off | Plays the title theme on detail pages. |
| Theme music volume | 20, 40, 60, 80 % | 40 % | Shared by Plex, Jellyfin and Emby. Shown when theme music is on. |
| Libraries | Show / hide per library | All shown | Library sections in menu and Home. |

### 4.2 Jellyfin and Emby

Both pages have the same controls.

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Enable Jellyfin / Enable Emby | On / Off | Off | Integrates libraries, search, Home and playback. |
| Saved servers / Search for server | List, LAN discovery | — | Switch or remove servers. |
| Server IP · Port | Text | 8096 | Manual connection. |
| Base path (optional) | Text, e.g. `/jellyfin`, `/emby` | Empty | For reverse proxies. |
| Use HTTPS | On / Off | Off | Reverse proxy or TLS. |
| Username · Password | Text | — | Sign in / Sign out. |
| Test connection | Action | — | |
| Theme music | On / Off | Off | Same as Plex. |
| Libraries | Show / hide | All shown | |

### 4.3 TVHeadend

| Setting | Options / values | Default | Engines | Description |
| --- | --- | --- | --- | --- |
| Enable TVHeadend | On / Off | Off | — | Live TV, guide, recordings and timers. |
| Server address · Port | Text | Port 9981 | — | |
| Username · Password | Text | — | — | Account needs rights for guide, live TV and recordings. |
| Stream profile (optional) | Text | Empty | — | TVHeadend stream profile name. |
| Use HTTPS | On / Off | Off | — | |
| Test connection | Action | — | — | |
| Submenu items | Live · Guide · Recordings · Timers | All | — | |
| Show Live TV on the home screen | On / Off | On | — | |
| TVHeadend player | Media3 · VLC · MPV | Same as the global player until changed | — | Default renderer for live TV and recordings; can also be changed in the player. |
| Deinterlacing | Off · Automatic · On | Off | VLC, MPV | Media3 shows a hint only. |
| Method | See [7.5](#75-deinterlacing) | Automatic | VLC, MPV | Separate saved method for VLC and for MPV. |
| Live TV · Audio and video by width | Editor | — | All | Separate audio/video presets for live channels per source width (opened from *Default player*). |

**Recordings and timers** (TVHeadend, Jellyfin, Emby): every row has its own **Info**, **Edit** and **Delete** buttons next to it; the detail dialog offers the same. Deleting a recording or cancelling a timer asks for confirmation (Back is focused first). Failed recordings are deleted, not cancelled. **Edit** sets padding before/after the programme; on TVHeadend also the priority and, for series timers, Active/Paused (TVHeadend: 0 minutes means the DVR profile's padding). Plex timers cannot be edited.

**Artwork:** rows and dialogs show the recording's own image, else its fanart, the guide programme it came from, art found by exact title on TMDb (only with a TMDb key; loaded without server credentials) and last the channel logo. Jellyfin/Emby also use thumb and series images.

### 4.4 SmartTube (YouTube)

| Setting | Options / values | Default | Devices | Description |
| --- | --- | --- | --- | --- |
| Enable SmartTube | On / Off | On | All | Module in navigation and search. |
| Sign in to SmartTube | Device code at yt.be/activate, QR code | — | All | The QR code and the browser link open youtube.com/activate with the code already filled in; the code stays on screen for typing it at yt.be/activate. The TV shows the QR dialog here and on the YouTube page. Phone: the link also copies the code. Works without sign-in as guest. |
| SponsorBlock | On / Off | On | All | Skips identified sponsor segments. |
| Automatically play next video | On / Off | Off | All | Plays the first suggestion when a video ends. |
| Visible sections | Home, Subscriptions, History, Playlists, Shorts, Music, News, Gaming, Live, Kids | All | All | Sections shown in the module. |
| Maximum video resolution (Media3) | Auto, 720p, 1080p, 1440p, 2160p | Auto | All | Quality and codec are chosen in the player from the available tracks. |
| Connection (Media3) | Classic (OkHttp) · Play services (Cronet) · Android (built-in) | Classic | All | Network engine of the Media3 player. Also in the player: Video › Stream; a change there reopens the video at the current position and is kept. |
| Buffer size (Media3) | Small (30 s) · Medium (50 s) · Large (3 min) | Medium | All | Like the original client; also under Video › Stream in the player. |
| Allow linking with the YouTube app | On / Off | Off | TV | "Watch on TV": phones open, pause, seek and stop videos here. Then "Show TV code" / "Unlink all phones". |

Channel directories offer channel-appropriate sorting (provider order or title), with no video filters or duration sort. In mixed results, video filters apply only to videos and keep channel tiles visible. Videos inside a channel retain their usual filters and sorting; saved video preferences are kept when visiting channel directories.

The **New videos** label on a channel card means YouTube/SmartTube reports new content for that subscription. It is not an online or live status for the channel. Running and scheduled live videos have their own labels on the individual video.

### 4.5 Spotify

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Enable Spotify | On / Off | Off | System and Spotify Connect receiver. |
| Spotify account | Sign in / Sign out | — | OAuth with PKCE; your own client ID is required. TV offers QR sign-in. |
| Submenu items | Home, Liked Songs, Playlists, Albums, Artists, Now Playing | All | |
| Connect device name | Text | App name | Name in Spotify's device list. |
| Audio quality | 96, 160, 320 kbps | 320 kbps | Music stream bitrate. |
| Visualization | Ambient cover · Spectrum · projectM · Off | Ambient cover | Audio visualizations need local Spotify playback. |
| Spotify account › Spotify client ID | Text | Empty | Your own client ID from the Spotify developer dashboard; required for sign-in (see the [README guide](../README.md#spotify-setup)). |

Media3, VLC and MPV are not used for Spotify; their audio profiles do not apply.

### 4.6 Trailers

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| TMDB key or read token | Text; Check and save / Remove API key | — | Key from themoviedb.org; no sign-in. |
| Enable trailers | On / Off | On | Trailers on detail pages and trailer rows. |
| Preferred trailer language | Device language · Deutsch · English | Device language | |

### 4.7 DLNA

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Servers | Search and add | — | UPnP/DLNA media servers on the LAN. Optional Wake-on-LAN per server (MAC, auto-wake before browsing, wake now). |

### 4.8 Files

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Show Files in menu | On / Off | Off | Local folders, SMB, NFS, WebDAV and SFTP without a media server. |
| Open sources and files | Add folder · Add NAS | — | Local: Android folder picker with persistent read access. NAS: SMB (share, domain/workgroup, user, password) (SMB 2/3 or, explicitly, legacy SMB1) or NFSv3/NFSv4.0 over TCP (UID/GID). WebDAV over HTTP/HTTPS with Basic or Digest authentication and SFTP with a password or SSH key are also available. Optional Wake-on-LAN per source. |
| Submenu items | Files entries (Browse, Favorites, Recently opened, Continue watching) | All | Shown when Files is on. |

Files play through the shared player (Media3/VLC/MPV) via a local HTTP bridge. Not supported: NFS Kerberos, NFSv4.1+ sessions.

#### Music in Files/NAS
In folders with music the app reads the tags in the background (MP3/ID3v2, FLAC, M4A/ALAC/AAC; up to 300 files per folder) and shows titles with track number and artist. When most files carry a track number, the folder is sorted as an album by disc and track – playback follows that order. A cover (`cover.jpg`, `folder.jpg`, `front.jpg`, `AlbumArt…`) appears with album and artist above the list and in the player. Ogg/Opus, WMA and APE keep showing the file name; embedded covers are not read.

### 4.9 WireGuard / VPN

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Paste a wg-quick config / Load configuration | Text or file | — | Imports one tunnel. Common exports (wg-quick, FRITZ!Box, Mullvad, ProtonVPN, WARP, …) are accepted. IPv4 tunnel. |
| Save config · Test connection · Connect · Disconnect · Remove config | Actions | — | Status: idle, connecting, up, failed. Details show endpoint, address, DNS, allowed IPs, keepalive, MTU. |
| Plex / Jellyfin / Emby / TVHeadend over WireGuard | On / Off each | Off | Shown when a tunnel is imported (on *Servers & Wake-on-LAN* and each system page). Wake-on-LAN for that system also uses the tunnel. |

### 4.10 IPTV (M3U/XMLTV)

Settings › Systems & accounts › **IPTV**: any number of M3U playlists (http/https) with an optional XMLTV guide (`.xml` or `.xml.gz`; empty = the playlist's `url-tvg`) and an optional user agent. Channels, groups, logos, `tvg-chno` and `#EXTVLCOPT` user agent/referrer are used. Each playlist appears in the menu under **IPTV** with Live and Guide and uses the Live TV views and player. The playlist is cached for 6 h, the guide for 12 h (offline, the last loaded playlist is used). Addresses are stored encrypted and are only part of backups that include credentials. No recordings/timers.

---

### 4.11 Monogram (Telegram media; optional native build)

**Availability:** `./build.sh release` ships the pinned GPL-3.0-only native MTProto module. Debug and single-ABI APKs do not, unless built with `MONOGRAM_NATIVE=1`. See the [README setup guide](../README.md#monogram-setup-optional-telegram-media-integration) and [native build notes](monogram-integration.md). Native ABI, renderer and device testing is still in progress.

| Setting | Options / values | Default | Devices | Description |
| --- | --- | --- | --- | --- |
| Enable Monogram (internal system key: Telegram) | On / Off | Off | All | Exposes private channel media only after Telegram authorization. |
| API ID + API hash | Number + masked 32-character hexadecimal hash | Empty | All | Create a Telegram API application at [my.telegram.org/apps](https://my.telegram.org/apps). Both fields are available while Monogram is disabled, and are stored together in encrypted preferences (never compiled into APKs). |
| Save API credentials | Action | — | All | Validates ID/hash and resets the unsigned native client. To change them, sign out first. |
| Monogram account: QR sign-in | Scan and approve from Telegram's Devices page | — | TV | The code is a Telegram `tg://login` token, not a Spotify OAuth web callback. Requires another already signed-in device. |
| Monogram account: phone code | International phone number; request/confirm/resend code | — | TV, Phone | The phone route works without scanning your own screen; an optional two-step verification password is requested when required. |
| Account status / Sign out | Connected account · Sign out | Signed out | All | The account/catalog/menus become active after authorization; sign-out revokes private access and cache. |
| Submenu TV chips and Home rows | Recent media · Channels · Videos · YouTube links · Music & audio · Photos | All six allowed | All | Each category can be selected independently in the Home editor and in the system menu. |
| Layout and cards | Rows · Grid · List · Info list | Rows | All | SmartTube-style 16:9 cards with channel avatar/name, post date and runtime; bounded lazy paging, focus restore. |
| Post details / YouTube | Full post caption and YouTube thumbnails | — | All | Link-only or mixed photo/video posts expose each YouTube URL; linked videos open with SmartTube's normal selection behavior. |
| Player and channel OSD | Media3 · VLC · MPV; channel avatar, join/leave | Global player choice | All | MTProto byte-range streaming and shared OSD. Real device verification still required. |

**Backups:** Ordinary settings backups include Monogram layout, system name, TV chips and Home rows. Only the encrypted *Export with credentials* includes API ID/hash. Native Telegram sessions, login codes, passwords, private media and thumbnails never travel across devices. Monogram GPL and Telegram API license/terms review are required before distributing opt-in binaries.

---

## 5. Devices & network

### 5.1 Phone & devices

| Setting | Options / values | Default | Devices | Description |
| --- | --- | --- | --- | --- |
| Allow connections on this TV | On / Off | Off until paired | TV | Paired devices can connect while the app is open. Optional background availability needs "display over other apps". |
| Pair phone / Connect to TV | Action | — | TV / Phone | QR code or eight-character code. See [§12](#12-phone-tablet-and-tv-together). |
| Remote control · Open playback · Disconnect remote | Actions | — | Phone | Shown while connected. |
| End control | Action | — | TV | Ends the current remote session. |
| Restore previous TV settings | Action | — | TV | Shown after an imported transfer created a recovery backup. |

### 5.2 UPnP receiver

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Offer as player in the network | On / Off | Off | Apps such as BubbleUPnP, Web Video Caster or VLC can send video and music. Needs local network permission. |
| Name visible on the network | Text | Device name | |
| Ask before playing | On / Off | On | Asks on the TV the first time a device plays. Shown when the receiver is on. |
| Forget allowed devices | Action | — | Shown when the receiver is on. |

### 5.3 Servers & Wake-on-LAN

Services on the same computer are grouped by host or MAC address: Plex, Jellyfin, Emby, TVHeadend, NAS and DLNA. A shared MAC address can be applied to the services in a group; connection details remain separate for each service.

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| MAC address for automatic wake-up | Text; Find MAC address / Verify MAC | Empty | |
| Wake automatically on start | On / Off | On | TVHeadend: wakes when unreachable and waits. |
| Test connection · Wake now | Actions | — | |

For all systems together, at the end of the page:

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Wake-on-LAN retries | Off · 1× · 2× · 3× · 5× | 2× | If the server has not answered when the wait time runs out, new packets are sent and the wait starts again, at most this many times. Applies to Plex, Jellyfin, Emby, TVHeadend, NAS sources (SMB, NFS, WebDAV, SFTP; each cycle uses the source's own wait time) and DLNA; the quick check when the app returns is never repeated. |

---

## 6. Playback settings

### 6.1 Default player

| Setting | Options / values | Default | Shown when / Description |
| --- | --- | --- | --- |
| Default player | Media3 · VLC · MPV | Media3 | Default player for Plex, Jellyfin, Emby and SmartTube. A stored "Automatic" (always Media3) reads as Media3. MPV is disabled when unavailable on the device (Media3 is then used). TVHeadend has its own choice. |
| Saved playback settings | Overview | — | Shows saved per-title and per-width audio/video presets. |
| Live TV · Audio and video by width | Editor | — | Live-only presets per source width. |
| Automatic display switching | Off · Always · On start / stop · On start | On start / stop | Shown when the display reports more than one mode. Between two items (next episode, autoplay, playlist, channel change) the display does not return to the menu mode: an equal mode stays, as does one that already shows the next item without judder (same resolution, 1× or allowed 2× its frame rate, e.g. 25 fps on 50 Hz); otherwise one direct switch. |
| Show display changes | On / Off | Off | Short notice on resolution or refresh-rate change. |
| Allow double refresh rates (24→48, 30→60, 50→100, 60→120 Hz) | On / Off | On | Uses 2× when the display lists that mode, including 100 and 120 Hz. 25 fps stays at 50 Hz when both 50 and 100 Hz exist. |
| Prefer double refresh rate (30→60 Hz even with a native 30 Hz mode) | On / Off | On | Smoother menus during playback; off picks the native rate first. 100/120 Hz still need "Allow 100/120 Hz". |
| Allow 3:2 pulldown (24→60 Hz) | On / Off | On | |
| Display mode | Auto · video resolution + Hz · Auto · full display resolution + Hz · System default · fixed modes | System default | Picking an Auto mode turns switching on (start/stop) if it was off. |
| Allowed display modes | Mode list | All supported modes | Limits automatic switching. |

### 6.2 Subtitles

Applies to every player.

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Subtitle mode | Subtitles off · Forced only · Full subtitles | Subtitles off | |
| Subtitle language | Automatic, de, en, fr, es, it, pt, nl, pl, tr, ru, … | Automatic | Automatic follows the preferred audio language, or the app language. |
| Fallback language | None – subtitles off, or a language | None | |
| Full subtitles for foreign-language audio | On / Off | Off | Full subtitles when the audio is not your preferred language. |
| Forced subtitles in app language | On / Off | Off | Forced subtitles in the app language, even with mode Off. |

Per playback: subtitle track and **Subtitle delay** (±100 / ±500 ms steps, remembered per title) in the player.

**Online subtitles (OpenSubtitles.com):** with your own API key (Settings › Playback › Subtitles, optionally an account for more downloads) the subtitle panel offers **Search subtitles online** for movies and episodes in direct play. It searches by IMDb id, title/season/episode and, for files, the file hash (exact matches first), in your subtitle languages or all. The chosen subtitle is loaded and selected right away.

**Subtitles next to the video file** (Files/NAS and DLNA): `.srt`, `.ass`, `.ssa` and `.vtt` files whose names start like the video are offered as subtitle tracks – in the same folder, in a `Subs`/`Subtitles` folder or in `Subs/<video name>/`. Language, “forced” and “SDH” are read from the name (e.g. `Movie.de.forced.srt`, `Movie.German.srt`, `Movie.pt-BR.vtt`). DLNA servers provide subtitles as subtitle resources or `sec:CaptionInfoEx`.

**Look** (Settings › Playback › Subtitles › Look): size, colour, edge (outline, shadow, none), background, bold and position, with a preview. It applies to text subtitles (SRT, WebVTT, text tracks) in every player. ASS/SSA subtitles always keep their own fonts, colours and positions.

**Sync automatically** (subtitle panel): a loaded subtitle file, e.g. from OpenSubtitles, is laid onto a text subtitle the server has for the same title. The cue timelines are matched (shift up to ±90 s and frame rates 23.976/24/25), the file is rewritten with the found timing and loaded again, so it works in Media3 too. Without a clear match nothing changes.

### 6.3 Intros, credits & next up

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Skip TV show intros | Off · Show button · Automatic | Show button | Uses the server intro marker. |
| Skip credits | Off · Show button · Automatic | Show button | Uses the server credits marker. |
| Show Next Up | On / Off | On | Episodes and SmartTube videos; not movies, live TV, live streams or audio. |
| Show before the end | 10, 20, 30, 60 s | 30 s | Shown when Next Up is on. |
| Automatically play series episodes | On / Off | Off | |
| Automatically continue collections | On / Off | Off | Next movie in the collection order. |
| Ask "Still watching?" after | Off, 2, 3, 5 changes | 3 | Shown with an autoplay option on. |

In the Next Up overlay, the autoplay switch sits at the left of the bottom action bar next to Close. Play now / Continue stays at the right and receives the initial focus. Long labels and the optional Skip credits action wrap onto another line when needed; the header contains only the title and countdown/status.

### 6.4 Seek & resume

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Best version for this display | On / Off | On | Picks the version whose resolution fits the TV. |
| Up/Down seek jump | 15, 30, 60, 90, 120 s | 60 s | Seek buttons and Up/Down during playback. |
| Rewind after a pause | Off · 3 · 5 · 10 · 15 · 30 s | Off | Replays the last seconds when continuing after a user pause. |
| Minimum pause duration | Always · 5 · 10 · 30 s · 1 minute | 10 s | Shown when pause rewind is enabled. Shorter pauses continue at the same position. |
| Rewind when resuming a video | Off · 5 · 10 · 15 · 30 · 60 s | Off | Starts a previously watched video before its saved progress, clamped to the beginning. |
| Player controls display time | 3, 5, 8, 15 s | 5 s | How long the OSD stays without a key press (all players, also after a channel change). |
| Back in the player | Stop playback · Minimize (keep playing) | Stop playback | What Back does once the OSD is hidden (all players). Minimize works like the exit-fullscreen button. |
| Network buffer | Small · Medium · Large | Medium | Read-ahead for Media3 (15–30 s / default / 60 s–3 min), MPV (32 MiB·10 s / 64 MiB·20 s / 160 MiB·60 s, 96 MiB on low-RAM devices; only outside the home network) and VLC (0.8 / 1.2 / 3 s). Live timeshift keeps its own values; SmartTube has its own buffer size. Applies to the next playback. |

**Seek & resume** is available under *Settings → Playback* and in Playback settings for seekable videos. Values apply globally to every renderer. The panel summary shows the seek step and only active, available rewind options; pause rewind and saved-progress rewind are independent. A saved point at 24:30 with a 10-second rewind starts at 24:20. Play from the beginning and explicit chapter or time selections retain their chosen start.

Pause rewind runs once when you intentionally continue playback. Seeking during a pause keeps your selected position. Opening the OSD, switching between mini-player and fullscreen, reconnecting, changing renderer, buffering and technical pauses do not trigger another rewind. Music is excluded. Live TV offers pause rewind only when timeshift can actually seek, bounded by the available buffer. Recordings can also resume saved progress with rewind.

Live TV: every channel change shows the OSD with the new channel; while it is only open for that, Up/Down keep changing channels. Started from Live TV or the guide, next/previous channel stays within that view's filtered, sorted list.

### 6.5 Picture profiles

Picture filters (scaling, sharpening, denoise, HDR, color) are set during playback in the player panel ([§7](#7-player-panel-during-playback)). This page holds the renderer output defaults.

| Setting | Options / values | Default | Engines | Shown when / Description |
| --- | --- | --- | --- | --- |
| Profile for player | Media3 · VLC · MPV | Current default player (Media3 when Automatic) | — | Which engine's defaults are edited. Does not change the default player. |
| Motion smoothing (SVP) available | On / Off | Off | MPV, VLC | Off hides SVP in the player. SVP also needs playback performance "Maximum performance". |
| MPV video output | Automatic · Android Surface (direct) · OpenGL (image filters) · Vulkan | Android Surface (direct) | MPV | Direct has no filters; OpenGL and Vulkan allow processing. Vulkan only when the device check passes. |
| MPV video decoder | Automatic (hardware) · MediaCodec (hardware) · Vulkan Video (hardware) · CPU (software) | Automatic (hardware) | MPV | Vulkan Video needs output Automatic or Vulkan and device support. |
| VLC video output | Automatic · Android output (Surface / OpenGL ES) · Vulkan | Automatic | VLC | Vulkan needs Vulkan 1.1+, timeline semaphores and Android presentation. |
| Vulkan checks · selected player | Status + "Recheck device and modules" | — | MPV, VLC | GPU, modules, output, decode profiles, last attempt in this session. |

Media3 uses the normal Android path and has no Vulkan output.

### 6.6 Audio profiles

Audio settings are stored per output profile: **TV**, **HDMI / AVR** and **Headphones**. "Auto (current output)" picks the profile from the active route. Values are defaults for the next playback; the player panel edits the same profile.

| Setting | Options / values | Default | Engines | Shown when / Description |
| --- | --- | --- | --- | --- |
| Passthrough for library & live playback | On / Off | Off | All | Sends Dolby and DTS straight to TV or receiver if the device supports it. This is the "Source default" for Audio output. |
| Profile for player | Media3 · VLC · MPV | Current default player | — | Shows only options the engine supports. |
| Audio profile | Auto (current output) · TV · HDMI / AVR · Headphones | Auto | All | |
| Audio output | Source default · Automatic · PCM · Prefer passthrough | Source default | All | Auto uses passthrough only when no PCM effect is active. Formats the output does not confirm stay on the device (DTS core, local AC3 or PCM). |
| Bitstream format → Allow passthrough | AC3, E-AC3, DTS, DTS-HD, TrueHD: on/off each | All on | M3, MPV | Shown when passthrough is possible and the output confirms a format. DTS-HD on also turns DTS on. VLC switches passthrough for all formats together. |
| Audio conversion | Locally on this device · By the media server | Locally | All | Where unsupported audio is converted. Sources without a transcoder always convert locally. |
| Audio fallback | Automatic · PCM only · Off | Automatic | All | Automatic converts to AC3 (up to 5.1, no Atmos). PCM only avoids AC3; server conversion then requests AAC. |
| Audio delay | −2000 … +2000 ms; Reset to 0 ms | 0 ms | All | Positive: sound later. |
| Channel output | Automatic · Stereo · Mono · Left on both speakers · Right on both speakers · 5.1 · 7.1 | Automatic | M3, MPV | Hidden for VLC. |
| Synthetic upmix | On / Off | Off | M3, MPV | Stereo to a fake 5.1. Shown when the output reports ≥ 6 PCM channels. |
| Keep downmix volume | On / Off | Off | M3, MPV | |
| Night mode | On / Off | Off | All | VLC needs Android DynamicsProcessing; otherwise hidden. |
| Dialogue boost | 0–6 | 0 | All | Lifts the speech band. |
| Volume normalization | On / Off | Off | M3, MPV | Not available through VLC. |
| ReplayGain | Off · Track · Album | Off | MPV | Needs ReplayGain metadata. |
| Equalizer · Apply sound preset | Flat · Speech · TV · Headphones | Flat | All | Preset sets preamp and 10 bands. |
| Preamp | −12 … 0 dB | 0 dB | All | Shown when EQ is on. |
| Frequency band / Band gain | 60 Hz … 16 kHz (10 bands), −12 … +12 dB | 0 dB | All | Shown when EQ is on. |
| Preferred track role | Automatic · Main audio without commentary · Original audio · Commentary · Audio description | Automatic | All | At next stream start; manual choice is kept. |
| Preferred channel count | Automatic · Stereo · Surround | Automatic | All | |
| Reset this audio profile | Action | — | All | |
| Preferred audio language | Automatic + languages | Automatic | All | |

Sound and EQ effects are PCM-only. They stay saved but are suspended during passthrough and DTS core, and active for PCM and local AC3.

**Night mode by the clock** (Settings › Audio): in a time window (also past midnight, default 22:00–07:00) every player turns night mode on and lifts dialogue to at least the chosen level. The profile itself is not changed. Switching night mode off in the player keeps it off until the window ends. It switches live during playback and is part of backups and the companion sync.

### 6.7 Audio visualization

| Setting | Options / values | Default | Shown when / Description |
| --- | --- | --- | --- |
| Audio visualization | MilkDrop / projectM · Spectrum · Off | MilkDrop / projectM | For radio, music and other audio-only playback. Stays on unless audio is bitstreamed. |
| Preset change interval | 15, 30, 60, 120, 300 s | 60 s | projectM. |
| Transition | 0, 1, 2, 5 s | 2 s | projectM. |
| Detail level | Economy · Balanced · High | Balanced | projectM. |
| Render resolution | Auto, 720p, 1080p, 1440p, 2160p | Auto (1080p) | projectM. 4K only on 4K displays; Low mode caps at 720p. |
| Allow 4K HDR (experimental) | On / Off | Off | projectM with HDR support; HDR interface Automatic and a resolution above 1080p. |
| Presets | All presets · Favorites only | All presets | projectM. |

### 6.8 Photos

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Slideshow interval | Seconds (3–60) | 6 s | |
| Transition | Crossfade · Slide · None | Crossfade | |
| Ken Burns effect | On / Off | Off | Slow pan and zoom. |
| Shuffle | On / Off | Off | |
| Include subfolders | On / Off | Off | Slideshow and browsing include sub-albums. |
| Show photo info | On / Off | Off | Date, camera and exposure. |
| Sort order | Newest first · Oldest first · Name | Newest first | |

### 6.9 Live TV: timeshift, direct recording and reminders

In Playback settings, **Live TV** sits directly below **Subtitles**. It brings together teletext, HbbTV, timeshift and recording; available controls depend on the channel, source and permissions. Back returns to the settings panel and focuses Live TV again. Teletext subtitles remain in the shared subtitle selector.

**Timeshift** is off by default. Its switch is available in the TVHeadend/IPTV settings, Playback settings and under **Live TV** in the player; changes apply immediately. When timeshift is enabled, its buffer size is also available there. Changing the size restarts the local buffer. Changes are disabled during channel changes and recording playback. Buffer sizes are 512 MB, 1 GB, 2 GB and 4 GB. The initial size is chosen from free device storage; existing settings are preserved.

**Teletext** is decoded by the app itself, so it works the same with Media3, MPV and VLC. In the live player's settings panel, *Live TV → Teletext* opens the pages. Teletext subtitles appear under *Subtitles* in the same selector as regular subtitle tracks: *Teletext · Automatic* prefers a page in your subtitle language, alongside available pages such as 150 or 777 with their language and hearing-impaired label where announced. The choice is kept and shown in the settings panel summary. Selecting teletext disables regular subtitles, selecting a regular track disables teletext subtitles, and *Off* disables both. While the subtitle selector is open, the app also looks for further subtitle pages. Optional OSD buttons (off by default): *Teletext* and *Teletext subtitles* (on/off).

The teletext controls use the same neutral buttons, icons and focus outline as the player. Available page links have small red, green, yellow or blue markers. The page controls offer previous/next page and a clickable **Page 100** field: selecting it opens a numeric keypad, so a remote without number keys can enter any page from 100 to 899. Subpage controls appear only when several subpages are available. Separate tools provide **Home 100**, the received-page list, **Transparent: On/Off** and **Close**. Groups wrap on smaller screens and the page uses the remaining space.

Within the controls, the D-pad moves focus. In the reading area, ▲/▼ changes pages and OK returns to the controls. Number, colour, channel and page keys keep their direct shortcuts. Page changes and transparency keep focus on the control being used; closing the keypad or page list returns focus to its opener.

With Timeshift the app reads along the stream the player gets (no extra traffic, subtitles follow a paused picture); otherwise it opens its own connection to the channel only while teletext or its subtitles are on (TVHeadend uses the same tuner). Needs an MPEG-TS stream (TVHeadend profile "pass", IPTV TS). Every channel is checked briefly at the start: teletext entries and buttons only appear when the stream carries teletext. IPTV without Timeshift is not checked (providers often allow a single connection). Subtitles without Timeshift can appear up to the player's buffer (about 1–3 s) early.

**Red button (HbbTV, experimental):** When the channel announces an HbbTV application, the *Live TV* submenu shows *Red button (HbbTV)*; the remote's red key opens it too. The additional OSD button can be enabled and is off by default. Detection works independently of teletext and reads complete programme and application tables (PMT/AIT), including tables spread across several MPEG-TS packets. Without a detected application, the entry and button stay hidden. The TEXT key opens teletext.

The HbbTV application opens full-screen over the live picture with an HbbTV browser identity, the colour, media and Back keys and the basic OIPF objects; when the application plays its own video (or stops the broadcast), the live programme pauses and continues when the application returns to it or closes; where the application shows the live picture in a smaller window of its menus, the picture is scaled into it (all players). Channel data is not available, so not every service works exactly as on a TV. The first time a notice explains that HbbTV content and usage tracking come from the broadcaster over the internet.

**HbbTV controls:** press Back briefly to go back within the broadcaster's service; hold it for about 0.7 seconds to open the app's controls. The touch controls button opens the same toolbar. It offers **Back in service**, **Home**, **Reload**, **Return to service** and **Close HbbTV**, plus the colour buttons requested by the current application. Colour buttons use neutral surfaces with small colour markers. D-pad and OK operate the service while it has focus, and the toolbar while it is open. Back from the toolbar returns to the service. Two quick Back presses are two navigation steps, rather than an exit shortcut.

Loading is shown explicitly. A failed page offers **Retry** and **Close HbbTV**. The page preparation uses a user agent captured on the WebView thread, so loading can safely convert HbbTV documents to HTML and inject the compatibility functions before the broadcaster's scripts run. Changing channel closes the old service; changing renderer or a timeshift URL for the same channel does not. Old page callbacks cannot control a replacement session.

When enabled, the app buffers the current channel locally. Pause, rewind, fast-forward and **Back to live** work with Media3, VLC and MPV; remote rewind/fast-forward keys use the configured seek step. The player shows the delay behind live and playback time relative to the current programme. Rewinding is limited to the already buffered period.

This applies to TVHeadend and IPTV with continuous MPEG-TS streams; HLS (`.m3u8`) plays without timeshift. The buffer keeps 512 MB of storage free and is deleted when playback ends. TVHeadend's HTSP timeshift is not used.

**Direct recording:** **Enable direct recording** enables recording actions in the player and is off by default. In the **Live TV** submenu, **Record current programme** starts the recording; an existing recording offers **Stop recording**. The feature switch remains accessible with the required server permissions even when current EPG information is missing. The recording action requires a running programme with an event ID. Scheduled recordings and recordings confirmed as active by the server are shown separately; the active indicator appears only for a confirmed recording status. IPTV has no recordings/timers; the local timeshift buffer is not a permanent recording.

**Reminders:** future guide entries offer **Remind me**, even without recording permissions. At programme start or 1, 2, 5 or 10 minutes before, the app shows a **Switch over** prompt and a system notification. Lead-time changes apply immediately; reminders survive app restarts.

---

## 7. Player panel (during playback)

The panel opens from the player's settings button. Changes are remembered for the current video automatically; "Save / load configuration" stores them for all videos or all videos of the same width. Rows hide when the running engine cannot apply them.

**Compact OSD on television:** Playback starts and the first OK opens the shared title/artwork, timing and seek-bar display with its button bar collapsed. Seeking uses the same display with the brief position preview. The clock and technical information follow their existing settings.

| Input | Behaviour |
| --- | --- |
| OK while the OSD is hidden | Open compact controls without also pausing. |
| OK on the seek bar | Play/pause; first confirm and resume an active seek preview. |
| Left/Right on the seek bar | Seek with preview and acceleration while held. |
| Down from the seek bar or channel information | Expand the button bar and restore its last-used action, initially Play/Pause. |
| Up from the button bar | Return to compact controls. Up on the seek bar keeps focus there. |
| Back | Dismiss the OSD; another press during its animation only finishes dismissal. Once fully hidden, the Stop/Minimize preference applies. |

Closing an expanded bar slides it down for about 250 ms. Information stops at the compact position, remains briefly and then fades; an already compact display fades directly. New input can interrupt dismissal. The configured OSD timeout applies to controls and seek feedback, and paused playback keeps the OSD visible. Closing a settings panel restores the expanded bar and its originating button.

Live TV without a seekable timeline uses channel information as its focus target. Automatic channel information keeps Up/Down available for zapping until OK claims the OSD. Touch controls retain directly accessible buttons; tapping the timeline still seeks to the selected position.

### 7.1 Playback settings menu

| Entry | Options / values | Shown when |
| --- | --- | --- |
| Video | Opens [7.2](#72-video) | Video is playing. |
| Player | Automatic · Media3 · VLC · MPV | Audio-only items (video items choose it under Video). MPV only when available. |
| Quality | Server/stream qualities | More than one quality. |
| Audio | Audio track + [7.6](#76-audio) | |
| Subtitles | Track, Subtitle delay | |
| Seek & resume | Seek step · Pause rewind · Saved-progress rewind | Seekable videos; live TV offers pause rewind with usable timeshift. |
| Save / load configuration | Save for all videos / for this width, load, reset | Video is playing. |
| Skip TV show intros · Skip credits | Off · Show button · Automatic | Episodes with markers. |
| Visualizer | MilkDrop presets | Audio-only playback. |
| Sleep timer | Off · 15–90 min · At the end of this item · after 5–120 min paused · after 30–120 min without input | Stops playback; does not turn off the TV. |
| Tools | A-B repeat · frame step back/forward · bookmarks · screenshot · lyrics · gapless | Video: A-B, frame step, bookmarks, screenshot. Music: A-B, bookmarks, lyrics, gapless. |
| OSD & Info | Controls · Information | The summary lists only the entries currently offered. |

**OSD & Info:** This shared submenu sits directly below *Tools*. **Controls** opens the *OSD buttons* list and sets the controls display time to 3, 5, 8 or 15 seconds (default 5). The duration takes effect immediately and is saved globally for all players. **Information** contains the *Renderer and codec strip* toggle (on by default). *FPS / refresh rate / resolution* and *Stats for Nerds* appear when the current player provides diagnostics; both overlays apply to the current session. Select the playback renderer under *Video*.

**Tools:** The settings panel summary lists only the tools currently offered and uses the same availability check as the submenu. Live TV with video, for example, lists only *Screenshot*; bookmarks appear only for an eligible title. The entry is hidden when no tools are offered. Active A-B repeat is indicated within the list. *A-B repeat* loops between two points for every engine. *Frame step* pauses and shows the previous/next frame. *Bookmarks* are kept per title (up to 30); jump to one or delete one or all. *Screenshot* saves the picture on screen as PNG under Pictures/Ambient TV (not for protected content). *Lyrics* show over the visualizer: timed lyrics follow the song with the sung line highlighted, untimed ones scroll along. They come from Plex lyric streams or Jellyfin; *Search online (LRCLIB)* is off by default and then sends artist, title, album and length to lrclib.net. *Gapless playback* (default on) queues the next song in Media3 when both songs play directly; otherwise songs change as before. Bookmarks show as light ticks in the seek bar, next to the chapter marks.

**Tool buttons:** In the player settings (gear) › OSD & Info › OSD buttons, each player can show the tools directly in the action bar (off by default, like Stats for Nerds and FPS): *Screenshot*, *Bookmark* (sets one at the current position), *A-B* (first press A, second B, third off), *Frame step* (back/forward), *Lyrics* (music) and *Tools* (opens the panel). A button only appears where its tool applies. The list groups the buttons offered for the current player under *Playback*, *Picture & sound*, *Live TV*, *YouTube*, *Tools* and *Information*, omitting empty sections. Each row keeps the button's own icon on the left, shows an eye or crossed-out eye on the right, and states *Shown* or *Hidden*. The whole row toggles visibility.

**Frame drop watchdog:** While picture processing runs (shaders, filters, SVP, Media3 effects, CPU deinterlacing), every engine measures dropped frames after a short warm-up. Above 10% in the test window, playback pauses and asks: *Settings*, *Reduce and continue* (one lighter step, kept like your own picture change for this title: SVP, then sharpening, denoise, deblock, grain, deband, heavy scaler; VLC finally drops software processing or a CPU deinterlacer) or *Keep watching anyway* (remembered for this title and filter set).

**Frame drop overlay:** The video panel keeps the slim, smoothly animated bar. Its label shows the difference from the target frame rate, for example **Δ −2.2 FPS · Target 50**: output is 2.2 frames per second below the intended rate. **Δ 0.0 FPS** means output meets the target; a plus sign indicates output above the target during the sample. The bar is full and green at the target, half full and red at 10% below it. Media3 and VLC measure renderer output over actual elapsed time; MPV estimates the deviation from dropped output frames and marks it with **≈**. The target follows video output, including active frame interpolation, a known deinterlacing cadence, and playback speed. A 24 FPS video therefore still targets 24 FPS on a 60 Hz display. Missing readings, a pause, buffering, or a detected seek show **—** with a neutral bar before measurement restarts. Unknown filter target rates also remain **—**.

### 7.2 Video

| Group | Setting | Options / values | Engines | Shown when / Description |
| --- | --- | --- | --- | --- |
| Player | Choose player | Automatic · Media3 · VLC · MPV | — | Shows why the engine was picked (global default, channel, preset, fallback, Dolby Vision protection). |
| Scaling | Picture fit | Fit to screen · Fill screen · Stretch | All | |
| Scaling | Aspect ratio | Auto · 16:9 · 4:3 · 21:9 | All | |
| Scaling | Zoom level | 100 %, 110 %, 125 %, 150 % and current pinch value | All | Selects the actual zoom value; 100 % resets it. |
| Motion | Playback speed | 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2× | All | Not for live TV. |
| Motion | Motion smoothing (experimental) | Off · Automatic · adaptive protection · Balanced · High quality | MPV, VLC | SVP available on, playback performance Maximum, progressive SDR, not MPV direct output. |
| Output | Display mode | Auto · video resolution + Hz · Auto · full display resolution + Hz · System default · fixed modes | All | More than one display mode. |
| Output | MPV video output / MPV video decoder · VLC video output | As in [6.5](#65-picture-profiles) | MPV, VLC | Applies to this playback (live: this channel); restart keeps position. Native HDR forces the direct surface. |
| Filters / Scaling / Picture / Output | Engine options | See 7.3 and 7.4 | per engine | |

### 7.3 MPV image options

Need a GPU output (OpenGL or Vulkan). With "Android Surface (direct)" the filter rows are hidden. Shaders, CPU filters and picture adjustments wait for confirmed SDR.

| Setting | Options / values | Default | Shown when |
| --- | --- | --- | --- |
| Upscaling | Built-in: Bilinear, Bicubic, Catmull-Rom, Mitchell, Hermite, Robidoux, RobidouxSharp, Spline16/36/64, Lanczos, Sinc, Ginseng, Gaussian, EWA Lanczos/LanczosSharp/Lanczos4Sharpest/LanczosSoft/Robidoux/RobidouxSharp, Nearest, Oversample. GPU shaders: NNEDI3 ladder, RAVU Lite r2/r3/r4, RAVU Zoom r3, FSRCNNX 8/16, ArtCNN C4F16/C4F32 (+DS/DN), Anime4K S/M (+Denoise), CuNNy Fast DS, NVIDIA NIS, FSR (EASU + RCAS) | Lanczos | Video is upscaled (or size unknown). Shaders only for SDR. |
| Chroma scaler | Built-ins except Oversample, KrigBilateral | Lanczos | Upscaling. |
| Downscaler | Built-ins except Oversample / EWA Lanczos4Sharpest, SSimDownscaler | Mitchell | Video is downscaled. |
| Anti-ringing | 0–100 | 0 | Up- or downscaling. |
| Sigmoid upscaling | On / Off | On | Up- or downscaling. |
| Smooth color bands | Off · Light · Medium · Strong | Medium | GPU output. |
| Sharpening | Off · Standard sharpening: Light, Medium · Shader sharpening: AMD CAS, Adaptive Sharpen | Off | SDR; with NIS/FSR the row is replaced by a note on their integrated sharpening. |
| Denoise · HQDN3D | Off · Light · Medium | Off | SDR. |
| Deblock · CPU | Off · Light · Medium | Off | SDR. |
| SSimSuperRes | On / Off | Off | SDR; not with NIS/FSR. |
| Brightness · Contrast · Saturation · Gamma | −20 … +20 | 0 | SDR. |
| HDR tone mapping | Auto · BT.2390 · Reinhard · Hable · Möbius · Clip | Auto | HDR input shown on an SDR output. |
| Dithering depth | Automatic · Off · 8-bit · 10-bit | Automatic | GPU output. |
| Motion smoothing (SVP) | Off · Automatic · Balanced · High quality | Off | See 7.2. |

### 7.4 VLC and Media3 image options

| Engine | Setting | Options / values | Default | Shown when |
| --- | --- | --- | --- | --- |
| VLC | Upscaling | Fast bilinear, Bilinear, Bicubic, Nearest, Area, Bicublin, Gaussian, Sinc, Lanczos, Spline | Bilinear | SDR, upscaling. Used when FFmpeg resizes; Android surface scaling may bypass it. |
| VLC | Image processing | Hardware · standard · Software filters · SDR | Hardware | SDR. Software restarts playback. |
| VLC | Smooth color bands · Denoise · HQDN3D | Off · Light · Medium | Off | Software filters. |
| VLC | Brightness · Contrast · Saturation · Gamma | −20 … +20 | 0 | Software filters. |
| VLC | Motion smoothing (SVP) | Off · Automatic · Balanced · High quality | Off | See 7.2. |
| Media3 | Image processing | On / Off | Off | Confirmed SDR (also H.264 and 8-bit HEVC/VP9/AV1 without colour info); not HDR, Dolby Vision or DRM. Turning on restarts the decoder at the position. |
| Media3 | Upscaling | Bilinear · Bicubic · Catmull-Rom · Lanczos 3 · FSR (EASU + RCAS) · MPV kernels (Mitchell, Hermite, Robidoux, RobidouxSharp, Spline16/36/64, Sinc, Ginseng, Gaussian, EWA Lanczos/Sharp/4Sharpest/Soft, EWA Robidoux/Sharp, Nearest, Oversample) · RAVU Lite r2/r3/r4 · RAVU Zoom r3 · NVIDIA NIS | Bilinear | Image processing on, upscaling. With render resolution Panel/Auto the chain renders at the physical HDMI resolution, so 1080p on a 4K panel is upscaled by the chosen scaler. MPV kernels follow MPV's definitions (blur, windows, sigmoid upscaling); RAVU and NIS are MPV's shaders translated for Media3. Heavy scalers above 4K30 output fall back to Catmull-Rom. |
| Media3 | Sharpening | Off · Light · Medium · Strong · CAS · Adaptive | Off | Unsharp mask, AMD CAS or Adaptive Sharpen. NIS brings its own sharpening. |
| Media3 | Deband · Denoise · Film grain | Off · Light · Medium · Strong | Off | Denoise runs on the source pixels before deband; grain is added after sharpening. |
| Media3 | Brightness · Contrast · Saturation · Gamma | −20 … +20 | 0 | Image processing on. |

**Opening Media3 filters:** **Filters** remains available in the video panel while image processing is off. Choose **Image processing → On** directly in this area to access sharpening, denoise, debanding and film grain. Turning processing off leaves the area open. A device restriction, Low mode or an unsuitable video track instead shows its reason. These restrictions still include the GPU workaround for NVIDIA devices running Android 11 or older, and HDR, Dolby Vision and DRM tracks.

**Media3 filter performance:** Sharpening and film grain are checked against the resolution their shaders actually process. With Bilinear, the display path scales afterwards, so 1080p on a 4K display still counts as 1080p for these filters. An active GPU upscaler makes the output resolution count instead. The existing limit is equivalent to Full HD at 60 FPS; the filter area shows a hint when the configured effects exceed the budget. Matching video and display resolution hides only the upscaler, not sharpening or filter controls.

If the GPU cannot run the chosen Media3 effects (e.g. no highp fragment precision), playback restarts once without them instead of failing.

### 7.5 Deinterlacing

| Setting | Options / values | Default | Engines | Description |
| --- | --- | --- | --- | --- |
| Deinterlacing | Off · Automatic · On | Off | VLC, MPV (GPU output) | Hidden for Media3 and for HDR on VLC. |
| Method (MPV) | Auto · BWDIF · YADIF · W3FDIF · BWDIF 2× · YADIF 2× · W3FDIF 2× · ESTDIF (2×) · NNEDI (2×) · KernelDeint · MCDeint 2× TFF/BFF · IVTC 3:2 | Auto | MPV | CPU filters need copy-back, also with Vulkan. |
| Method (VLC) | Auto · Discard · Mean · Blend · X · YADIF · Bob 2× · Linear 2× · YADIF 2× · Phosphor 2× · IVTC 3:2 | Auto | VLC | |

Methods are grouped by load: automatic, low, balanced, double frame rate, special, film. Low performance mode substitutes a lighter method without changing the saved one.

On NVIDIA SHIELD, interlaced broadcast (1080i H.264, 576i MPEG-2) reopens once with software decoding on MPV's OpenGL and Vulkan outputs: the vendor MediaCodec copy path shows such streams black or with unwoven fields and gives automatic deinterlacing no interlace flag. Renderer and filters stay.

### 7.6 Audio

| Setting | Options / values | Engines | Shown when |
| --- | --- | --- | --- |
| Audio track | Tracks of the item | All | |
| Night mode | On / Off | All | DSP active (not during passthrough or DTS core); VLC only with DynamicsProcessing. |
| Prefer passthrough | On / Off | M3, MPV | Only when the current output confirms an encoded format. Hidden on outputs that confirm none, e.g. a phone speaker. |
| Full profile | All rows from [6.6](#66-audio-profiles) (Output, Sound, Equalizer, Track preferences) | per engine | Same visibility rules as in settings. Live TV shows a flat list. |
| Planned output | passthrough · DTS core · local AC3 · PCM | All | Info line. |

### 7.7 Save / load configuration

| Action | Scope |
| --- | --- |
| Changes are saved automatically for this video | Current title |
| Save for all videos with this width | Source width, any height; also live TV unless it has its own preset |
| Save live TV audio/video for width | Live channels only; takes priority over the width preset |
| Save for all videos | Global defaults |
| Load configuration / Reset this width preset / Reset changes for this video / Reset defaults for all videos | Selected scope |

Live TV, trailers and shared links: changes apply to this playback only unless saved explicitly.

---

## 8. Performance modes

Interface and playback have separate modes (*Look & feel*). Until a side is saved on its own, it reads the shared setting, which is **Auto**.

| Mode | Interface (reduced effects?) | Playback (reduced playback?) |
| --- | --- | --- |
| Auto | Reduced only on a low-end device (low-RAM flag, app heap ≤ 192 MB or < 2 GiB RAM). | Reduced only on a low-end device. |
| Balanced | Full. | Full. No motion smoothing (SVP). |
| Maximum performance | Full. | Full, and motion smoothing (SVP) is allowed. |
| Low | Always reduced. | Always reduced. |

**Reduced interface:** no blur, soft shadows or spring focus; static Home hero; no Home background on other screens; high-resolution images off; smaller image decodes; screensaver at Economy; projectM throttled; TV menu sounds muted.

**Reduced playback:** heavy video paths use lighter overrides (GPU shaders, CPU denoise/deblock, VLC software filters, Media3 effect chain, 2× deinterlace, spectrum). Saved image settings are kept and come back when leaving Low. Audio DSP stays available.

"Check interface" and "Check playback" run a short hidden measurement and save the result for that side.

---

## 9. App & data

| Page | Setting | Options / values | Default | Description |
| --- | --- | --- | --- | --- |
| Notifications | On-screen duration | Off · Short · Normal · Long | Normal | History keeps the last 20 results in this session. |
| Transfer accounts and settings | Sync devices | Accounts / accounts with settings / menu sounds | — | Sends to a paired device; the receiver confirms. |
| Settings backup | Export without credentials · Export with credentials · Import | — | — | Export uses saved values. Encrypted backups ask for a passphrase. |
| Diagnostics | Write error log | On / Off | Off | Up to about 3 MB. "Download log (ZIP)", "Clear log". Secrets are removed. |
| About this app | Players, media services, licenses | — | — | Exit app (TV). |

---

## 10. Setup

| Requirement | Value |
| --- | --- |
| Android | minSdk 29, target/compileSdk 37 |
| Device | Android TV / Leanback and/or phone/tablet |
| Network | LAN to media servers; internet for SmartTube, Spotify, Plex link, TMDB |
| Local network permission | Android may ask for local servers, SMB/NFS, WOL, Spotify Connect, UPnP receiver |
| Camera | Only for "Scan QR code" during phone pairing |

On first launch a setup screen offers Plex, Jellyfin, Emby, TVHeadend, SmartTube (with or without sign-in), Spotify and Files. More systems can be added later under *Media sources & accounts*. Plex, Jellyfin, Emby, TVHeadend and Spotify appear in menu, Home and search only after the server accepted address and sign-in; a later HTTP 401 hides the system until a sign-in succeeds again.

---

## 11. Navigation and controls

### 11.1 Android TV

- Sidebar with system order (configurable), Home, Search, Settings
- Android TV system search and voice search ("search for … in Ambient TV") find movies and shows from Plex, Jellyfin and Emby; a hit connects (and wakes) the server first and opens the title. Servers known to be offline are skipped
- An offline server stays in the menu as "… · Offline"; its overview and libraries show an offline page with Wake up / Reconnect and load by themselves once it answers
- D-Pad: focus groups, scroll positions in settings subpages
- Content focus and scroll positions belong to the current app session. After a cold start, each overview begins at the first item of its first populated row, including SmartTube subscriptions, Home, libraries, Spotify, Live TV, files, DLNA and photos. This also applies when the UI starts anew while a background service has kept the process alive. Rotation, returning from playback and ordinary returns to the still-open app preserve the position. Detail pages use the entry rules described below.
- Vertical wrap in panels, dialogs and long lists: past the last row focus continues at the first, past the first at the last. It only applies when nothing else is reachable that way — where something sits above the list (a header, filter chips), Up still leads there
- Settings Back: subpage → overview/search → area → leave hub
- Mini-player and background playback stay while browsing (optional visible background video)
- Leaving the app (Home, another app, TV off, screen off): a video pauses at once and stops after 30 seconds with its position saved; back earlier, it waits paused. Live TV and timeshift stop at once. Music and audiobooks keep playing on a phone, and on a TV until standby. Closing the app from recents stops everything. File pickers, installation and sign-in opened by the app itself stop nothing.

### 11.1a Android TV home screen

Movies and episodes in progress from Plex, Jellyfin and Emby appear in the launcher's **Continue watching** row (Watch Next) and are updated when playback stops; finished titles disappear. When Home loads, the app reconciles the row with each server's continue-watching list, so titles finished or started on another device follow (only with the Continue watching row enabled and the server reachable; titles just stopped here take precedence for a minute). The app also publishes the channels **Continue watching**, **Recently added** and **Live TV** (recently watched channels, then the live row with the current programme; only from enabled Live TV rows on Home), refreshed when Home loads. The first channel with content is offered automatically, others are added through the launcher's "customize channels". While a server is offline or asleep, or live TV is still loading, the channels keep their last content. Selecting an entry first connects like Home does (VPN, reachability, Wake-on-LAN with the full wait) and then opens the title (Continue watching starts playback) or plays the channel; if the server stays unreachable, the app remains on Home with the connection notice. Channels follow the row settings: enabled rows only, their own title (when there is exactly one such row) and poster or landscape display. Posters are served by the app itself, so no server credentials reach the launcher. Turn it off under Settings › Home › **Show on the home screen**. The launcher decides which rows are visible; Google TV no longer shows other apps' channels.

### 11.1b Receiving from other apps (DLNA renderer)

Settings › Devices & network › UPnP receiver › **Offer as player in the network** (off by default) makes the app announce itself in the home network as a UPnP/DLNA player ("Media Renderer"). Apps such as BubbleUPnP, Web Video Caster, LocalCast or VLC then list it as a target and send videos or music; the app plays them with the configured player (MPV/VLC/Media3) and its picture and audio settings. The sender controls play, pause, seeking, stop and volume and sees position and state live. Direct HTTP(S) media URLs are supported (MP4, MKV, WebM, TS, HLS/m3u8, DASH, MP3, FLAC and more), also without a file extension when the sender names the type; DRM content and images are not.

For HLS and DASH proxy URLs without a matching extension, Media3 receives the format from the sender's metadata. If Media3 fails while initializing a received source, the partially created player is released and the usual playback error handling remains available: Retry/Back, or the existing renderer fallback when Automatic is selected.

Security: the service only answers devices in the same local subnet (not over VPN tunnels or mobile data), events only go back to the subscribing sender (at most four subscriptions per device), and requests are bounded in size and time. Pause, seeking, stop and volume are only accepted from the device that started the playback (or an allowed one), volume only while that playback runs. In the background nothing new starts or switches; the running playback stays controllable. Before the first playback the TV asks ("Play", "Always from this sender", "Decline"; declined after one minute without an answer). A permission belongs to the sending app at its address: if another device later gets the same IP address, the TV asks again. Allowed devices can be forgotten in Settings and are cleared when the feature is switched off. The renderer is reachable while the app is open or plays something it received; in the background it signs off. Received items do not appear in history or in Continue watching.

Chrome's Google Cast button cannot reach this app (Google Cast is tied to certified devices and each website's own receiver app); sender apps with DLNA support are the way to send from a browser or phone.

### 11.2 Phone

- Bottom navigation: Home, Systems, Search, Settings
- Systems → **Manage menu**: the same order and names as on television (single column in portrait)
- Settings: area tabs instead of a left column
- Video: landscape fullscreen; portrait with navigation; optional fullscreen toggle when rotation is locked
- Touch timeline: drag = preview, release = seek
- Pinch video with two fingers: snaps when the picture width or height fills the available player area. Keep spreading or closing your fingers to leave the snap. Available with Media3, VLC and MPV, including SmartTube and TVHeadend.

Pinch zoom ranges from 50 to 800 percent and preserves the original aspect ratio. Recognizing a zoom gesture therefore changes a forced picture format to Auto and converts Stretch/Crop to Fit with the appropriate zoom. Filling one dimension may crop the other dimension at the screen edges. Snaps use the actual player area, including portrait and split-window layouts. Resizing the player area resets gesture-created zoom to Fit. The video panel shows custom percentages too. Music and open settings panels do not respond to pinch zoom; taps and double taps retain their existing actions.

### 11.3 Media selection

When picking items in Plex/Jellyfin/Emby: detail page, play immediately, or chooser. SmartTube has its own setting in view customization.

**Detail navigation with the D-pad:** Down from the hero enters the content row. For shows and seasons, the initial target is the next episode to resume or watch; when a show lists several seasons, the corresponding season is selected. Collections start at the next unwatched movie, with titles already in progress taking priority. Other rows start at their first reachable item (item 0). Each visited row remembers its own item, and that position takes priority over the suggested entry when returning. Positions survive leaving and reopening a detail page until a new app session starts, separated by media source and sign-in. Background loading does not pull focus away from a row already being used. The additional season strip remains reachable with Up from the episode row.

Right from the last item returns focus to the first reachable item in that same row (item 0). This applies to posters, episodes, collections, the season strip, cast, and the movie rows for individual actors. The cast row also starts at actor 0 on first entry, then uses its remembered position. Disabled entries are skipped. The wrap stays within the row even when its beginning is offscreen, without moving to the page's top action bar.

Shows with a single season display its episodes directly. While the final row type is still unknown, a neutral loading state is shown; a temporary season row is not displayed from cached data either. Shows, seasons and collections without their own playable file do not display a misleading “No playback source found” message. An individual movie or episode without a source still shows it.

### 11.3a Picture settings per title

Picture and filter settings (upscaler, sharpening, deband, denoise, SVP, deinterlacing, video output/decoder) are remembered per title when they differ from the defaults and applied immediately the next time that title plays (MPV, VLC, Media3). The last 200 titles are kept; live TV and trailers are not. **Save as default** still applies to every title without its own values.
The **audio delay** and the **subtitle delay** (subtitle panel, MPV and VLC) set in the player are per title too; the default delay lives in Settings › Audio. A line in the video panel shows whether the title has saved values, and **Reset this title to the defaults** removes them.

### 11.4 Sleep timer

Existing sleep timers are a **player session feature**, not a permanent settings page.

When a fixed-duration or "without input" timer runs out, playback **pauses** first and asks: **Keep watching** (timer off), **Restart timer** or **Stop now**. Without an answer playback stops after 60 seconds. Pressing play outside the dialog counts as keep watching. "Paused" and "end of item" timers stop without asking.

---

## 12. Phone, tablet and TV together

On the TV, **Settings → Devices & network → Pair phone → Pair phone** opens a two-minute invitation with a QR code, an eight-character code and the time left; **Create new code** restarts it. On the phone, **Pair a TV** (the **Remote** tab or Settings → Phone & TV) guides through three steps: 1. start pairing on the TV (a discovered TV is shown), 2. scan the QR code – or use **Can’t scan? Enter the code** to pick the discovered TV and type its code; an invitation link is under **More options**, 3. compare the codes on both devices and confirm. The TV then closes its pairing dialog, both devices report “Paired with …”, and the phone offers the remote or a settings transfer. If the comparison is cancelled, the TV creates a new code automatically. Five wrong codes end the pairing window. An old entry of the same TV (same name and address, e.g. after a reinstall) is replaced when pairing again. The phone’s **Phone & TV** overview shows the connection status and one card per paired TV with **Connect**/**Remote** and **Transfer data**; the card opens the detail page with actions, connection (VPN, port, Wake-on-LAN) and **Unpair**. The bottom phone output bar appears only while that TV is paired and connected.

Both apps must remain open on the same IPv4 Wi-Fi/Ethernet subnet. The receiver is disabled until explicitly enabled through pairing. Closed-app wake-up, Internet/VPN companion access, automatic handoff of an existing playback session and automatic local fallback are not implemented.

Playback sends semantic references for Plex/Jellyfin/Emby library items, YouTube/trailers, Live TV, DLNA and SMB/NFS. Configure the corresponding source on the TV; library playback requires the same active media server. The TV selects its media version, uses its own credentials and reports progress to its own source. Library references do not transmit credentials or resolved stream URLs. Local phone files are not portable. Spotify continues to use Spotify Connect.

Android **Share → Ambient TV → Send to [device name]** accepts exactly one YouTube link or direct HTTP(S) media link. Supported extensions: MP4/M4V, MKV, WebM, MOV, AVI, TS/M2TS, MPG/MPEG, OGV, HLS/M3U8, DASH/MPD, MP3, M4A, AAC, FLAC, OGG/OGA, Opus, WAV and WebA. Actual playback depends on reachability, codecs and the renderer. DRM, website extraction, extensionless links and local `content://`/`file://` files are not supported. The explicitly shared URL, including any query parameters, crosses the encrypted companion connection. The TV does not add credentials from its configured media servers. Shared links live in a bounded, process-local store, not a library or persistent history. HTTP media transport itself is unencrypted; prefer HTTPS.

The phone also publishes **Send to [device name]** Android **Direct Share** targets for paired TVs. Selecting one connects that specific stored TV and starts the content without another target picker. Android/the launcher controls suggestion visibility, order and count; the regular app share entry remains the fallback. The app rechecks saved pairing and certificate pin, resolves the current LAN endpoint and sends the start command once. It never falls back to another device or automatically replays failed commands. Leaving the phone app cancels any pending send. Forgetting a TV on the phone removes its shortcut; stale shortcut intents cannot restore a removed pairing. Opening a launcher shortcut without shared content only opens the device panel.

The invitation pins the full TV certificate before TLS 1.2/1.3. The phone proves possession of its Android-Keystore private key by signing a fresh challenge bound to both identities. Both confirmations are required before trust is persisted. Per-session nonces and sequence numbers reject repeated commands. Pairing identities are excluded from settings backups. Forgetting a phone on the TV immediately closes its connections and revokes access.

Connection, disconnection, remote start requests and revocation use the existing non-focusable notification overlay and history, respecting notification duration settings. Rejected attempts are rate-limited. The phone output bar stays hidden unless a TV is paired and currently connected. While it is visible it provides TV play/pause, stop and seek controls. Unsaved TV settings block remote playback starts.

Validation commands: `:app:testDebugUnitTest --tests 'com.ambient.tv.companion.*'`; the Android security audit uses a separate `-PAUDIT_APP_SUFFIX=.companionaudit` debug application, `:app:assembleDebugAndroidTest` and instrumentation argument `-e companion true`. It exercises real Keystore/TLS pairing, two-sided consent, revocation and media-source mapping with two isolated instances on one Android device. Separate phone-to-TV playback still requires a two-device test.

### Remote control and settings transfer

The connected-device panel offers a D-pad, touchpad, text input and account/settings transfer. Remote input is restricted to this app's windows, with one controller at a time and a local TV stop action. Senders cannot operate pairing or import consent dialogs.

Choose accounts only or accounts plus settings, using the current configuration or an existing backup file. Credentials require explicit opt-in. The TV must approve locally: add accounts without overwriting, or explicitly allow replacement. Device-specific display/audio/player settings require separate opt-in. Local files, VPN, pairing identities and history are excluded. Stop playback and save pending settings before applying. An encrypted local recovery backup can restore the previous configuration. Notifications cover transfer, acceptance, rejection and errors. Transfers are limited to 512 KiB, with a two-minute approval window; this is not continuous sync. Update both apps.

---

## 13. Backup and restore

Under **App & data › Backup**:

- **Export without credentials** (default): no Plex tokens, no TVHeadend password; file-source passwords only when exporting with credentials. Before saving, an optional passphrase can be set (entered twice, show/hide toggle); leave it empty for a plain JSON file as before
- **Export with credentials:** only when needed; the file is encrypted with a passphrase (at least 8 characters, AES-256-GCM, PBKDF2) that import asks for. Without the passphrase the backup cannot be restored. Companion transfers stay TLS-protected.
- Encrypted files are a small JSON envelope (`"format":"ambient-tv-encrypted-backup"`, version 1, PBKDF2-HMAC-SHA256 with 310 000 iterations, random 16-byte salt and 12-byte IV, AES-256-GCM). Import detects plain vs. encrypted files automatically and asks for the passphrase; a wrong passphrase or a damaged file shows an error and the dialog stays open for another try.
- Import restores settings and server profiles (schema up to current **76**)
- Monogram system status, six menu/TV chips, Home rows and views are included in ordinary backups; Telegram API ID/hash only in passphrase-encrypted **credentials** exports. Telegram login/2FA, native session and downloaded private media are never portable; sign in again on a new device.
- IPTV playlists and the OpenSubtitles key and account are only part of backups **with credentials** (playlist addresses often carry the provider account); a backup without credentials leaves the device's own unchanged. Timeshift size, timeshift/direct-recording switches (since schema 59) and reminder lead time are always backed up, scheduled reminders are not.
- File sources and WOL are backed up; **local folder permissions (SAF) are not portable** and must be granted again on a new device
- OAuth sessions and device IDs are not meant as a “portable login” — re-sign in accounts if needed

Under **App & data › Diagnostics** (error log for release builds):

- **Write error log** (off by default): app warnings and errors, failed server calls (HTTP ≥ 400 and transport errors with method, host, path, duration — no query string), Media3/VLC/MPV playback errors, error notices and crashes. Rotating files in app storage, at most about 3 MB.
- **Download log (ZIP):** `device.txt` (app/Android version, device), `app.log` and `logcat.txt` (the app's own system log lines, including player output). Without a document picker (common on Android TV) the ZIP is saved to `Android/data/<app>/files/Download/`.
- Tokens, passwords, API keys, Authorization headers, cookies and `user:password@` in URLs are removed before writing; server addresses and paths stay for troubleshooting. **Clear log** deletes all files.

---

## 14. Development and build

### 14.1 Project layout (short)

- `app/` – Android app (Kotlin, Jetpack Compose, Hilt)
- `native/` / `scripts/build_mpv_android.sh` – MPV JNI
- `scripts/build_projectm_android.sh` – projectM
- `scripts/build_spotify_engine.sh` – Spotify native (rusty/librespot)
- `scripts/fetch_smarttube_engine.sh` – YouTube/SmartTube pins
- `smarttube_wrappers/` – host wrappers for SmartTube modules
- `gradle/external-versions.properties` – pinned external engines
- `build.sh` – main entrypoint for APK and native

### 14.2 Build requirements

- JDK **17**
- Android SDK / NDK (for native engines)
- Network access for dependencies and optional engine fetches
- Optional: `keystore.properties` for signed release artifacts (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
- Optional: `spotify.properties` / Gradle properties for `SPOTIFY_REDIRECT_URI`, plus `SPOTIFY_QR_REDIRECT_URI` for TV QR sign-in (default: [relay page](https://seinist.github.io/ambient-tv-spotify-callback/spotify-callback.html) from [seinist/ambient-tv-spotify-callback](https://github.com/seinist/ambient-tv-spotify-callback); build-time; no your own client ID is required — users enter their own in the app; do not put secrets in docs or public issues)

### 14.3 `./build.sh` (selection)

```bash
./build.sh debug          # MPV (if needed) + debug APK
./build.sh release        # release APK
./build.sh universal      # universal release APK (all ABIs)
./build.sh arm64          # arm64-v8a
./build.sh armv7          # armeabi-v7a
./build.sh x86            # x86 (emulator)
./build.sh x86_64         # x86_64 (emulator)
./build.sh all            # all Android artifacts
./build.sh mpv            # native MPV libraries only
./build.sh libs-check     # check updates for pinned engines
./build.sh libs-update    # update pins (no build)
./build.sh gen-keystore   # create keystore/release.jks
```

Native engines are built for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.  
Typical output under `dist/` (e.g. `ambient-tv-arm64-<version>.apk`, `ambient-tv-x86_64-<version>.apk`). Pre-release suffixes such as `beta`, `alpha` or `rc` keep the suffix and insert the build number before it (`ambient-tv-arm64-1.0.270-beta.apk`).

Environment variables include: `SKIP_MPV_NATIVE=1`, `REBUILD_MPV=1`, `MPV_ANDROID_REF`, `ADB_DEVICE` for `*-adb` modes.

A change to the MPV JNI shim (`native/ambientmpv/ambientmpv.c`) triggers a native MPV rebuild on the next build. libmpv logs to Logcat (`AmbientMPV`) at `warn` with credentials redacted; for verbose diagnostics run `adb shell setprop debug.ambienttv.mpv.log v` before starting playback (levels: `no`, `fatal`, `error`, `warn`, `info`, `status`, `v`, `debug`, `trace`). MPV playback state is event-driven: the JNI shim exposes a wakeup pipe and observed properties (pause, buffering, seeking, end, duration, codecs, decoder, track count); a waiter thread wakes the UI thread only when mpv has events.

External libraries: see [`EXTERNAL_LIBRARIES.md`](../EXTERNAL_LIBRARIES.md).

### 14.4 Tests

Unit tests live under `app/src/test/`. Full device acceptance (TV remote, real NAS/AVR/login paths) is separate from source/CI checks.

**Baseline profile:** `scripts/capture_baseline_profile.sh SERIAL` records on an Android 13+ device which app code runs while navigating and adds it to `app/src/main/baseline-prof.txt`. It uses the `profileCapture` build type (release code without obfuscation, not debuggable, installed beside the app). With `audit` instead of manual use, the UI performance audit drives the Home and library layouts. The profile is compiled at install time, in-app updates included.

---

### 14.5 Releases and versioning

Pushing a `v*` tag runs `.github/workflows/release.yml`: it builds MPV natively via `build.sh`, the four signed release APKs (`arm64`, `armv7`, `x86`, `x86_64`) and the debug APKs (`…-debug.apk`), and attaches all of them plus `SHA256SUMS.txt` to one GitHub Release. Tags containing `alpha`, `beta` or `rc` become prereleases.

Required repository secrets: `AMBIENT_TV_KEYSTORE_BASE64` (base64 of the release keystore), `AMBIENT_TV_KEYSTORE_PASSWORD`, `AMBIENT_TV_KEY_ALIAS`, `AMBIENT_TV_KEY_PASSWORD`. Optional: `SPOTIFY_REDIRECT_URI`, `SPOTIFY_QR_REDIRECT_URI` (defaults to the relay page in [ambient-tv-spotify-callback](https://github.com/seinist/ambient-tv-spotify-callback)).

Versioning lives in `version.properties`:
- `VERSION_CODE` goes up by one on every local build that produces an APK/AAB (`assemble*`, `bundle*`, `install*`, `package*` — including Android Studio and `./build.sh`). Sync, tests-only, `clean` and CI (`CI` set) leave it unchanged; `-PversionCode=N` overrides. The file is not committed automatically.
- `VERSION_NAME` is used for local builds. Release builds take it from the tag without the leading `v` (`v1.0.0-beta` → `1.0.0-beta`) via `-PversionName` / `ORG_GRADLE_PROJECT_versionName`. APK file names use that value; a pre-release suffix (`beta`, `alpha`, `rc`, …) stays at the end and the current build number is inserted before it (`1.0-beta` → `1.0.270-beta`).
- On each counted build the version between the `<!-- version:start/end -->` and `<!-- version-badge:start/end -->` markers in both READMEs is rewritten.

## 15. Limits and notes

- **Failure reasons in notices:** connection tests, sign-ins (Plex, Jellyfin, Emby, YouTube, Spotify), server connection notices and partial Home load failures name the concrete cause, e.g. “login rejected (HTTP 401)”, “no connection to 192.168.1.5:32400”, “HTTPS/certificate error”, “address not found”, “access to the local network is not allowed”. Tokens, passwords and URL parameters are stripped from error texts; details are also logged to logcat.

- No guarantee for every codec/bitstream/filter scenario on every device
- Files: no NFS Kerberos, no NFSv4.1+, NAS discovery via mDNS only; music folders support folder artwork, but not embedded covers
- Spotify DSP from the video players does not apply to Spotify Native
- Performance Low changes effective video/visual paths but does not permanently erase saved image profiles
- Third-party logos and upstream components have their own licenses (`third_party_licenses/`, About page)
- Trademarks: Plex, Jellyfin, Emby, YouTube, SmartTube, Spotify, etc. belong to their respective owners; the app display name is in `branding.properties`

---

## See also

- [DOCUMENTATION.de.md](DOCUMENTATION.de.md) – Deutsch
- [docs/README.md](README.md) – index
- [README.md](../README.md) · [README.de.md](../README.de.md) – GitHub overview
