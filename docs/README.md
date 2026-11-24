# Documentation · Ambient TV

**Languages:** **English** · [Deutsch](README.de.md)

Full user and developer documentation:

- **[DOCUMENTATION.md](DOCUMENTATION.md)** – English (default): feature compendium: every setting, player Video/Audio panels, sources, performance modes, backup, build
- **[DOCUMENTATION.de.md](DOCUMENTATION.de.md)** – Deutsch (vollständig)

Current features in the compendium:

- [IPTV with M3U/XMLTV](DOCUMENTATION.md#410-iptv-m3uxmltv)
- [Radio Browser: internet radio, favorites, categories and search](DOCUMENTATION.md#45-radio-radio-browser)
- [Android Auto audio browsing](DOCUMENTATION.md#54-android-auto-audio)
- [Separate TV interface resolution](DOCUMENTATION.md#35-look--feel)
- [Timeshift, direct recording and reminders](DOCUMENTATION.md#69-live-tv-timeshift-direct-recording-and-reminders)
- [Files/NAS with WebDAV, SFTP and music tags](DOCUMENTATION.md#48-files)
- [Sidecar and online subtitles](DOCUMENTATION.md#62-subtitles)
- [Wake-on-LAN groups](DOCUMENTATION.md#53-servers--wake-on-lan)
- [Clock](DOCUMENTATION.md#39-clock)
- [Weather: locations, display switches and manual map providers](DOCUMENTATION.md#310-weather)
- [Recordings and timers: edit, delete, artwork](DOCUMENTATION.md#43-tvheadend)
- [Media3 GPU filters (MPV kernels, RAVU, NIS, grain, denoise)](DOCUMENTATION.md#74-vlc-and-media3-image-options)
- [Subtitle look and automatic sync](DOCUMENTATION.md#62-subtitles)
- [Night mode by the clock](DOCUMENTATION.md#66-audio-profiles)
- [Player tools: A-B, frame step, bookmarks, screenshots, lyrics, gapless](DOCUMENTATION.md#71-playback-settings-menu)
- [Home layout and offline servers](DOCUMENTATION.md#31-home-rows)
- [Monogram (optional Telegram media): features, six Home rows and sign-in](DOCUMENTATION.md#411-monogram-telegram-media)
- [Monogram API ID/hash, QR login and technical integration](monogram-integration.md)
- [Games (libretro): 31 consoles, Games menu and library, touch controls, shader packs, core rollback](DOCUMENTATION.md#9-app--data)
- [Menu, playback and game controls: key actions, controllers, layouts per console, emulator and game](DOCUMENTATION.md#9-app--data)
- [RomM game server: library, firmware, saves, states, memory cards and favorites on the server](DOCUMENTATION.md#412-romm-game-server)
- [Watched and resume thresholds, D-pad seeking](DOCUMENTATION.md#64-seek--resume)
- [Minimalist Next Up dialog](DOCUMENTATION.md#63-intros-credits--next-up)
- [Retro game library: folders, catalog, Home rows](RETRO_GAME_LIBRARY.md)
- [Game metadata, artwork, private-build keys and individual ROM folders](game-metadata.md)
- [Game player OSD, controllers, phone menus and rewind](game-player-osd.md)
- [System and voice search](DOCUMENTATION.md#111-android-tv)

## Other project notes (repo root)

Technical notes in the repository:

| File | Topic |
| --- | --- |
| [`EXTERNAL_LIBRARIES.md`](../EXTERNAL_LIBRARIES.md) | Pinned engines and update workflow |
| [`MPV_NATIVE_INTEGRATION.md`](../MPV_NATIVE_INTEGRATION.md) | MPV native build and JNI integration |
| [`BRANDING.md`](../BRANDING.md) | Display name and artwork |

**Spotify:** disabled in regular builds because Spotify no longer supplies usable audio keys to the app's playback backend. See the compendium's [overview](DOCUMENTATION.md#1-overview).

Authoritative user-facing behaviour is the app strings plus [`DOCUMENTATION.md`](DOCUMENTATION.md) / [`DOCUMENTATION.de.md`](DOCUMENTATION.de.md).
