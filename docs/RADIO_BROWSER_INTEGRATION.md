# Radio Browser integration

## Decision

Ambient TV uses **Radio Browser** as its only built-in internet-radio catalog.
SHOUTcast and TuneIn are intentionally out of scope. The user-facing system is simply
**Radio**; Radio Browser remains an implementation/source label.

## Implemented on `feat/radio-browser-foundation`

### Catalog and network

- stable station identity via `stationuuid`;
- paged server-side search with country-code, language and tag filters;
- popular stations and broken-station filtering;
- `url_resolved` with catalog URL fallback;
- `/json/url/{stationuuid}` on playback start for click accounting and a fresh URL;
- descriptive Ambient TV User-Agent;
- DNS discovery through `all.api.radio-browser.info`, randomized mirrors and GET failover;
- a dedicated public-internet client; debug logs strip URL queries.

### Ambient TV system

- `Radio` is an independent `MediaSystem` with its own icon;
- it can be enabled without an account, server or API key;
- initial setup, start routing, sidebar, system naming and Settings are wired;
- Radio can be the only configured media system.

### Browse and playback

- Radio page with Popular, Favorites and Recently played;
- explicit station search and server-side paging;
- station artwork/name/country/codec/bitrate;
- favorites keyed by stable station UUID;
- recent listening history stored locally;
- a station starts through the shared Media3/BackgroundPlaybackSession path, not a second player;
- playback is marked live + audio-only and uses the existing audio-focus/background service;
- Media3 in-stream metadata updates the active Radio session without restarting the stream;
- stale metadata from a previous station is rejected by UUID;
- metadata changes force MediaSession and notification refresh;
- global Ambient TV search can query Radio Browser and start a station directly;
- the active station list drives previous/next controls in MediaSession and the notification;
- radio playback deliberately exposes no seek action.

### Backup

- Radio enabled state and Favorites are included in settings backup schema 75;
- Recently played intentionally stays device-local, matching the existing treatment of live-TV watch history.

### Browse facets

- Genres use Radio Browser tags ordered by station count;
- Countries use stable ISO country codes and localized display names;
- Languages use the Radio Browser language index;
- selecting a facet performs a server-side filtered station query with paging;
- Back returns from a selected facet to its facet list.

### Branding and Home

- menu, system badges, Settings and Home use a RadioBrowser-derived monochrome system glyph;
- Add System uses the colored RadioBrowser logo;
- an optional Radio Home shortcut can be added through the existing Home row editor;
- RadioBrowser logo source/license is documented in SYSTEM_LOGO_RADIO_BROWSER_LICENSE.txt.

## Still to implement

1. Device playback tests with representative MP3/AAC/HLS and ICY stations.

## Radio Browser contract relied upon

Radio Browser documents server-side paging, `hidebroken`, stable station UUIDs,
`url_resolved`, the station-click endpoint and mirror discovery. The client must send a
descriptive User-Agent. Individual mirrors may disappear, so no single mirror is treated as
permanent.

The catalog is external and has no availability guarantee. Persisted Favorites and Recent
therefore retain enough station data to render while the catalog is unavailable; playback
still asks Radio Browser for the freshest URL and falls back to the stored catalog URL.
