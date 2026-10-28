# Ambient TV – Feature compendium

**Languages:** **English** · [Deutsch](DOCUMENTATION.de.md)

**App:** Ambient TV (`com.ambient.tv`) · version `1.0` (see `version.properties` for versionCode) · minSdk 29, target/compileSdk 37 · settings backup schema 72
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
| **Media sources & accounts** | Plex · Jellyfin · Emby · TVHeadend · IPTV · SmartTube · Spotify · Trailers · DLNA · Files (configured systems first, then "add" entries) |
| **Devices & network** | Phone & devices · UPnP receiver · WireGuard / VPN · Servers & Wake-on-LAN |
| **Playback** | Default player · Subtitles · Intros, credits & next up · Seek & playback · Picture profiles · Audio profiles · Audio visualization · Photos |
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
| Add a row | Continue watching · All systems; per system: Continue watching, Recently added, Collections, Libraries; Recently watched TV channels; TVHeadend Live TV / Guide / Recordings / Timers; SmartTube Home / Subscriptions / History / Playlists / Shorts / Music / News / Gaming / Live / Kids; Spotify Home / Liked songs / Playlists / Albums / Artists / Now playing; Trailers / Now in cinemas; Recently added photos; Top in genre; shortcuts to system sections | — | All | Content rows or shortcuts to a section of a system. |
| Systems in "Continue watching · All systems" | Plex, Jellyfin, Emby | All on | All | Which servers feed the combined row. Shown when that row is on Home. |
| Show on the home screen | On / Off | On | TV | Watch Next plus the channels "Continue watching" and "Recently added" on the Android TV launcher. Shown when the launcher supports it. |
| Clear TV channel history | Action | — | All | The recent-channels row keeps up to 12 channels on this device; not in backups. |

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
| Filter bar | On / Off (View menu) | On | Filters and sort as a bar directly above the content in Library, Live TV guide, Trailers and SmartTube: each filter opens its values in place, two-value filters are switches, Reset appears only with active filters. Off moves filters and sort back into the View panel. |

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
| Frame drop test duration | Automatic, 2, 5, 6, 10, 15, 30, 60 s | Automatic (2–6 s) | All | Window of the MPV/VLC filter test. |
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

Time and date in the page headers (top right, TV), floating above the Home hero and in the player. One shared tick jumps to the full minute or second and follows time and time-zone changes at once; the player clock keeps running while the OSD is open. The page has a live preview.

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
| Sign in to SmartTube | Device code at yt.be/activate | — | All | Phone: link copies the code. Works without sign-in as guest. |
| SponsorBlock | On / Off | On | All | Skips identified sponsor segments. |
| Automatically play next video | On / Off | Off | All | Plays the first suggestion when a video ends. |
| Visible sections | Home, Subscriptions, History, Playlists, Shorts, Music, News, Gaming, Live, Kids | All | All | Sections shown in the module. |
| Maximum video resolution (Media3) | Auto, 720p, 1080p, 1440p, 2160p | Auto | All | Quality and codec are chosen in the player from the available tracks. |
| Allow linking with the YouTube app | On / Off | Off | TV | "Watch on TV": phones open, pause, seek and stop videos here. Then "Show TV code" / "Unlink all phones". |

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
| Automatic display switching | Off · Always · On start / stop · On start | On start / stop | Shown when the display reports more than one mode. |
| Show display changes | On / Off | Off | Short notice on resolution or refresh-rate change. |
| Allow double refresh rates (24→48, 30→60, 50→100, 60→120 Hz) | On / Off | On | Uses 2× when the display lists that mode, including 100 and 120 Hz. 25 fps stays at 50 Hz when both 50 and 100 Hz exist. |
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

### 6.4 Seek & playback

| Setting | Options / values | Default | Description |
| --- | --- | --- | --- |
| Best version for this display | On / Off | On | Picks the version whose resolution fits the TV. |
| Up/Down seek jump | 15, 30, 60, 90, 120 s | 60 s | Seek buttons and Up/Down during playback. |
| Player controls display time | 3, 5, 8, 15 s | 5 s | How long the OSD stays without a key press (all players, also after a channel change). |

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

**Timeshift** is off by default. Its switch is available in the TVHeadend/IPTV settings, Playback settings and the shared live-player panel; changes apply immediately. Buffer sizes are 512 MB, 1 GB, 2 GB and 4 GB. The initial size is chosen from free device storage; existing settings are preserved.

When enabled, the app buffers the current channel locally. Pause, rewind, fast-forward and **Back to live** work with Media3, VLC and MPV; remote rewind/fast-forward keys use the configured seek step. The player shows the delay behind live and playback time relative to the current programme. Rewinding is limited to the already buffered period.

This applies to TVHeadend and IPTV with continuous MPEG-TS streams; HLS (`.m3u8`) plays without timeshift. The buffer keeps 512 MB of storage free and is deleted when playback ends. TVHeadend's HTSP timeshift is not used.

**Direct recording:** the separate **Record** switch is off by default and shows a recording action in the live player for supported sources. It records the current EPG programme on the server and requires timer permissions and a current programme with an event ID. Existing scheduled recordings are detected. IPTV has no recordings/timers; the local timeshift buffer is not a permanent recording.

**Reminders:** future guide entries offer **Remind me**, even without recording permissions. At programme start or 1, 2, 5 or 10 minutes before, the app shows a **Switch over** prompt and a system notification. Lead-time changes apply immediately; reminders survive app restarts.

---

## 7. Player panel (during playback)

The panel opens from the player's settings button. Changes are remembered for the current video automatically; "Save / load configuration" stores them for all videos or all videos of the same width. Rows hide when the running engine cannot apply them.

### 7.1 Playback settings menu

| Entry | Options / values | Shown when |
| --- | --- | --- |
| Video | Opens [7.2](#72-video) | Video is playing. |
| Player | Automatic · Media3 · VLC · MPV | Audio-only items (video items choose it under Video). MPV only when available. |
| Quality | Server/stream qualities | More than one quality. |
| Audio | Audio track + [7.6](#76-audio) | |
| Subtitles | Track, Subtitle delay | |
| Save / load configuration | Save for all videos / for this width, load, reset | Video is playing. |
| Skip TV show intros · Skip credits | Off · Show button · Automatic | Episodes with markers. |
| Visualizer | MilkDrop presets | Audio-only playback. |
| Renderer and codec | On / Off (default On) | Badges in the info bar. |
| Stats for Nerds | On / Off | Session only. |
| FPS / refresh rate / resolution | On / Off | Overlay. |
| Sleep timer | Off · 15–90 min · At the end of this item · after 5–120 min paused · after 30–120 min without input | Stops playback; does not turn off the TV. |
| Tools | A-B repeat · frame step back/forward · bookmarks · screenshot · lyrics · gapless | Video: A-B, frame step, bookmarks, screenshot. Music: A-B, bookmarks, lyrics, gapless. |

**Tools:** *A-B repeat* loops between two points for every engine. *Frame step* pauses and shows the previous/next frame. *Bookmarks* are kept per title (up to 30); jump to one or delete one or all. *Screenshot* saves the picture on screen as PNG under Pictures/Ambient TV (not for protected content). *Lyrics* show over the visualizer: timed lyrics follow the song with the sung line highlighted, untimed ones scroll along. They come from Plex lyric streams or Jellyfin; *Search online (LRCLIB)* is off by default and then sends artist, title, album and length to lrclib.net. *Gapless playback* (default on) queues the next song in Media3 when both songs play directly; otherwise songs change as before.

### 7.2 Video

| Group | Setting | Options / values | Engines | Shown when / Description |
| --- | --- | --- | --- | --- |
| Player | Choose player | Automatic · Media3 · VLC · MPV | — | Shows why the engine was picked (global default, channel, preset, fallback, Dolby Vision protection). |
| Scaling | Picture fit | Fit to screen · Fill screen · Stretch | All | |
| Scaling | Aspect ratio | Auto · 16:9 · 4:3 · 21:9 | All | |
| Scaling | Zoom level | 100 %, 110 %, 125 %, 150 % | All | |
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
| Media3 | Image processing | On / Off | Off | Confirmed SDR; not HDR, Dolby Vision or DRM. Turning on restarts the decoder at the position. |
| Media3 | Upscaling | Bilinear · Bicubic · Catmull-Rom · Lanczos 3 · FSR (EASU + RCAS) · MPV kernels (Mitchell, Hermite, Robidoux, RobidouxSharp, Spline16/36/64, Sinc, Ginseng, Gaussian, EWA Lanczos/Sharp/4Sharpest/Soft, EWA Robidoux/Sharp, Nearest, Oversample) · RAVU Lite r2/r3/r4 · RAVU Zoom r3 · NVIDIA NIS | Bilinear | Image processing on, upscaling. With render resolution Panel/Auto the chain renders at the physical HDMI resolution, so 1080p on a 4K panel is upscaled by the chosen scaler. MPV kernels follow MPV's definitions (blur, windows, sigmoid upscaling); RAVU and NIS are MPV's shaders translated for Media3. Heavy scalers above 4K30 output fall back to Catmull-Rom. |
| Media3 | Sharpening | Off · Light · Medium · Strong · CAS · Adaptive | Off | Unsharp mask, AMD CAS or Adaptive Sharpen. NIS brings its own sharpening. |
| Media3 | Deband · Denoise · Film grain | Off · Light · Medium · Strong | Off | Denoise runs on the source pixels before deband; grain is added after sharpening. |
| Media3 | Brightness · Contrast · Saturation · Gamma | −20 … +20 | 0 | Image processing on. |

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
- Vertical wrap in panels, dialogs and long lists: past the last row focus continues at the first, past the first at the last. It only applies when nothing else is reachable that way — where something sits above the list (a header, filter chips), Up still leads there
- Settings Back: subpage → overview/search → area → leave hub
- Mini-player and background playback stay while browsing (optional visible background video)
- Leaving the app (Home, another app, TV off, screen off): a video pauses at once and stops after 30 seconds with its position saved; back earlier, it waits paused. Live TV and timeshift stop at once. Music and audiobooks keep playing on a phone, and on a TV until standby. Closing the app from recents stops everything. File pickers, installation and sign-in opened by the app itself stop nothing.

### 11.1a Android TV home screen

Movies and episodes in progress from Plex, Jellyfin and Emby appear in the launcher's **Continue watching** row (Watch Next) and are updated when playback stops; finished titles disappear. When Home loads, the app reconciles the row with each server's continue-watching list, so titles finished or started on another device follow (only with the Continue watching row enabled and the server reachable; titles just stopped here take precedence for a minute). The app also publishes the channels **Continue watching**, **Recently added** and **Live TV** (recently watched channels, then the live row with the current programme; only from enabled Live TV rows on Home), refreshed when Home loads. The first channel with content is offered automatically, others are added through the launcher's "customize channels". While a server is offline or asleep, or live TV is still loading, the channels keep their last content. Selecting an entry first connects like Home does (VPN, reachability, Wake-on-LAN with the full wait) and then opens the title (Continue watching starts playback) or plays the channel; if the server stays unreachable, the app remains on Home with the connection notice. Channels follow the row settings: enabled rows only, their own title (when there is exactly one such row) and poster or landscape display; servers hidden as unreachable are left out. Posters are served by the app itself, so no server credentials reach the launcher. Turn it off under Settings › Home › **Show on the home screen**. The launcher decides which rows are visible; Google TV no longer shows other apps' channels.

### 11.1b Receiving from other apps (DLNA renderer)

Settings › Devices & network › UPnP receiver › **Offer as player in the network** (off by default) makes the app announce itself in the home network as a UPnP/DLNA player ("Media Renderer"). Apps such as BubbleUPnP, Web Video Caster, LocalCast or VLC then list it as a target and send videos or music; the app plays them with the configured player (MPV/VLC/Media3) and its picture and audio settings. The sender controls play, pause, seeking, stop and volume and sees position and state live. Direct HTTP(S) media URLs are supported (MP4, MKV, WebM, TS, HLS/m3u8, DASH, MP3, FLAC and more), also without a file extension when the sender names the type; DRM content and images are not.

Security: the service only answers devices in the same local subnet (not over VPN tunnels or mobile data), events only go back to the subscribing sender (at most four subscriptions per device), and requests are bounded in size and time. Pause, seeking, stop and volume are only accepted from the device that started the playback (or an allowed one), volume only while that playback runs. In the background nothing new starts or switches; the running playback stays controllable. Before the first playback the TV asks ("Play", "Always from this sender", "Decline"; declined after one minute without an answer). A permission belongs to the sending app at its address: if another device later gets the same IP address, the TV asks again. Allowed devices can be forgotten in Settings and are cleared when the feature is switched off. The renderer is reachable while the app is open or plays something it received; in the background it signs off. Received items do not appear in history or in Continue watching.

Chrome's Google Cast button cannot reach this app (Google Cast is tied to certified devices and each website's own receiver app); sender apps with DLNA support are the way to send from a browser or phone.

### 11.2 Phone

- Bottom navigation: Home, Systems, Search, Settings
- Systems → **Manage menu**: the same order and names as on television (single column in portrait)
- Settings: area tabs instead of a left column
- Video: landscape fullscreen; portrait with navigation; optional fullscreen toggle when rotation is locked
- Touch timeline: drag = preview, release = seek

### 11.3 Media selection

When picking items in Plex/Jellyfin/Emby: detail page, play immediately, or chooser. SmartTube has its own setting in view customization.

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
- Import restores settings and server profiles (schema up to current **72**)
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
