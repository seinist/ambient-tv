# Radio Browser integration

## Decision

Ambient TV uses **Radio Browser** as its only built-in internet-radio catalog.
SHOUTcast and TuneIn are intentionally out of scope.

The user-facing system is simply **Radio**. Provider-specific implementation details stay
inside the data layer.

## First implementation slice

The initial data layer provides:

- stable station identity via \`stationuuid\`;
- paged server-side station search;
- filters for country code, language and tag;
- popular stations;
- broken-station filtering;
- stream URL selection via \`url_resolved\` with \`url\` fallback;
- the official \`/json/url/{stationuuid}\` call when playback starts, so Radio Browser can
  count the click and return a fresh stream URL;
- a descriptive Ambient TV User-Agent.

The API client deliberately uses a separate public-internet OkHttp client instead of the
LAN-scoped media-server clients.

## Next slices

1. Add the Radio system to setup/navigation/settings.
2. Add browse screens: Popular, Genres/Tags, Countries, Languages, Favorites, Recent.
3. Add global search integration and paging.
4. Connect a station to the shared audio playback session.
5. Propagate ICY metadata to player, miniplayer, notification, MediaSession and companion.
6. Add persistent favorites/history keyed by \`stationuuid\`.
7. Add previous/next station semantics and radio-appropriate transport controls.
8. Add Android Auto browsing through the media library service.
9. Add resilient Radio Browser server discovery/failover instead of relying on one API host.

## API behavior relied upon

Radio Browser documents:

- \`/json/stations/search\` with \`offset\`, \`limit\`, sorting and \`hidebroken\`;
- \`/json/stations/byuuid/{stationuuid}\`;
- \`/json/stations/topclick/{limit}\`;
- \`/json/url/{stationuuid}\` for playback starts;
- \`url_resolved\` as the already-resolved stream URL;
- a request for clients to send a descriptive User-Agent.

The catalog is external and may be temporarily unavailable. UI work must therefore keep
cached favorites/history usable and show catalog failures without breaking other systems.
