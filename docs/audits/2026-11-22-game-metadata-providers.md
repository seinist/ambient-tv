# Game metadata providers, credentials and artwork — feature audit

Date: 2026-11-22

## Implemented scope

The game catalog now has a keyless Libretro artwork provider, optional ScreenScraper metadata/artwork and an optional TheGamesDB alternative. The provider layer does not read or hash ROM payloads. It accepts a checksum/size or known game ID from the catalog and otherwise performs an explicitly system-scoped name search. Existing matched IDs remain independent of editable display titles.

`GameMetadataProviders` exposes `availableProviders`, `search`, `artworkChoices` and `downloadArtwork`. The coordinator owns scans, positive/negative lookup state, user match/field locks, preview ownership and publication of validated artwork. A provider writes only to the caller's temporary destination. It returns `false` only for a missing image response; service, credential, quota and invalid-image failures remain errors.

The settings panel is available from the library and game details. It uses the existing AppDialog, TV text fields and action/chip components, with wrapping choices on both TV and handheld. The panel offers provider/off, automatic scan matching, preferred region/language, ScreenScraper application credentials and optional account, and TheGamesDB application key. Passwords and keys are masked. Draft secrets are not placed in saved instance state. The off option disables online folder enrichment; explicit matching/editing remains available.

## Provider identity and API audit

### Libretro

- Provider namespaces are explicit and use Ambient's stable system IDs, rather than localized labels or guessed icon names.
- Combined systems are resolved by file extension: Game Boy/Color, Mega Drive/Master System/Game Gear/SG-1000, NES/FDS, PC Engine/SuperGrafx, Neo Geo Pocket/Color and WonderSwan/Color. An ambiguous archive probes only the relevant related systems.
- C128 and CBM-II deliberately have no invented Libretro/ScreenScraper mapping. Unknown systems do not fall back to a different console.
- Thumbnail paths use the upstream system directories and `Named_Boxarts`, `Named_Snaps`, `Named_Titles`. Each name is encoded as one URL path segment; upstream forbidden filename characters are replaced before encoding.
- A saved Libretro match stores the canonical system/name identity. Display renames do not change its image lookup. Name alternatives are bounded to three, with at most twelve related system/name candidates.
- The coordinator confirms a static thumbnail candidate by downloading an actual image; constructing a deterministic URL alone is not proof of a match.

Primary source: [Libretro thumbnail repository and namespace submodules](https://github.com/libretro-thumbnails/libretro-thumbnails).

### ScreenScraper

- Current official API v2 documentation confirms `jeuInfos.php` with `gameid`, `serialnum`, or checksum plus `romtaille`; `jeuRecherche.php` accepts `recherche` and `systemeid`.
- Responses are constrained to the expected platform identity. Regional names, dates, localized synopsis/genres, developer, publisher, player count and image references are read from the API's object/array forms.
- Normal matching chooses preferred regional media, then world/US/Japan fallbacks. Explicit artwork selection retains other region/type alternatives for the same game, up to thirty distinct references.
- API developer and optional user credentials are attached at request time. Media URLs returned by ScreenScraper are scrubbed before a candidate can be stored or shown. The UI receives local previews, never authenticated image URLs.
- A single ScreenScraper request lane covers both JSON and API image requests. The client uses the response's per-minute and daily limits, including the failed-match daily quota. Both the documented `maxrequestspermin` and legacy `maxrequestsperdmin` spelling are accepted.
- HTTP 401 is the documented temporary guest/inactive-user closure, rather than a definitive wrong-password response. 401/423/429 are retryable service/rate closures; 430/431 wait until the next day in the service's Paris time zone. HTTP 403 remains a credential failure. A busy/error payload never becomes a successful empty search.

Primary source: [ScreenScraper API v2](https://www.screenscraper.fr/webapi2.php), sections for errors, quotas, `jeuInfos.php`, `jeuRecherche.php` and image media.

### TheGamesDB

- The current official `spec.yaml` was checked directly on 2026-11-22. The client uses `v1.1/Games/ByGameName`, `v1/Games/ByGameID`, `v1/Games/ByGameHash` and `v1/Games/Images`.
- Hash lookup uses `hash` and `filter[type]=crc`; direct identity lookup uses `id`. Game and image requests remain filtered to the expected platform IDs.
- Developers are part of the default game response, not a documented optional `fields` token. The client parses that default and resolves developer IDs through the public developer catalog. Other metadata uses the supported fields list.
- Platform, genre, developer and publisher dictionaries are cached as public ID-to-name labels. Raw API responses and their credential-bearing pagination links are never written to disk. Disk entries expire after thirty days and are size/count bounded.
- Image lists are fetched in a batch for the current game candidates. The normal cover is front box art; explicit selection retains alternative front images, fanart/screenshots and title screens. Back box art is not silently used as the cover.
- Remaining monthly/extra allowance and refresh timing are honored when supplied. A rejected quota response is distinct from an empty game result. CDN images do not need the API key and are not blocked merely because API allowance is exhausted.

Primary sources: [TheGamesDB API](https://api.thegamesdb.net/) and [current OpenAPI specification](https://api.thegamesdb.net/spec.yaml).

## Credentials, backup and TV transfer audit

The optional private-build names are:

```text
SCREENSCRAPER_DEV_ID=
SCREENSCRAPER_DEV_PASSWORD=
THEGAMESDB_API_KEY=
```

They use the existing `private-keys.env` allowlist and `PRIVATE_KEYS=1 ./build.sh release` path, matching Monogram. Both ScreenScraper application values must be present for a built-in identity. Built-in application keys take precedence over runtime keys, hide those runtime input fields and are excluded from all exports. ScreenScraper user credentials remain runtime-only.

Runtime secrets are stored in TokenStore's encrypted preferences. Portable provider/region/language/automatic settings remain in ordinary AppPreferences and contain no secret fields. Validation requires complete application/account pairs and bounded strings; invalid imports are rejected before other settings are applied. Omitted provider identities in old or partial backups preserve the receiver.

Backup schema 84 adds `extras.portable.gameMetadataOptions` and `extras.credentials.gameMetadata`, without adding another property to the near-limit top-level SettingsBackup constructor. The credentials group is omitted from ordinary exports and from preference-only TV transfers. Explicit credential sharing and account selection are both respected. Existing receiver provider credentials are preserved unless account overwrite was requested; ScreenScraper ID/password pairs move together.

Integration regression coverage includes ordinary versus credential exports, complete restore, legacy/partial preservation, invalid-pair rejection before mutation, account selection, transfer mode filtering and receiver overwrite policy. The existing companion field-classification test covers the newly added extras group, and the settings completeness test now sends nondefault game metadata options.

## URL and download audit

`GameMetadata.bounded()` now applies a shared pure URL policy to every artwork URL, source URL and per-image attribution map. SQLite read/write and portable preference getter/setter paths use that boundary. This also covers imported backups that bypass a provider parser.

- Persisted references must be bounded HTTP(S) URLs. User-info, non-web schemes and malformed URLs are rejected.
- Credential parameter names are removed regardless of case, percent encoding or repeated encoding. Both ampersand and semicolon query separators are handled. Fragments are dropped.
- Provider requests additionally require exact HTTPS provider/CDN hosts, with no user-info or alternative port. Every redirect is checked again; supplied credentials are removed before the current identity is attached only to the provider's API host. Redirect depth is limited to three.
- No network exception includes a request URL, response body or original exception cause. Credentials have a redacted `toString`.
- JSON is capped at 8 MiB before parsing. Artwork is streamed in 32 KiB chunks, capped at 8 MiB to match storage and preview validation, and must declare an image media type. Storage/coordinator validate actual image decoding before publication.
- Cancellation cancels the active OkHttp request and deletes its temporary destination. The coordinator owns final file placement and folder-removal race checks.

## Performance and embedding review

The provider client shares the application's connection pool/dispatcher while avoiding Plex authentication/routing interceptors. Requests have bounded connection/read/total timeouts. API concurrency is conservative; artwork concurrency is two, with ScreenScraper further serialized according to its service policy. The client does no full NAS ROM read, unbounded request fan-out or artwork prefetch while browsing.

Search results are bounded to twenty-four games. Alternate artwork is bounded to thirty distinct choices, and provider media records are bounded before constructing candidate lists. Catalog dictionary work is reused and explicit artwork selection skips unrelated genre/developer/publisher enrichment. Display renames retain stable provider identities and avoid unnecessary rematching.

The coordinator supplies SQLite-backed lookup status and retry ownership, and catalog rows render local images. The provider layer's only disk cache contains public static lookup labels; automatic per-ROM metadata stays in the catalog and does not inflate portable settings backups.

## Validation and remaining execution gates

Focused Kotlin compilation succeeded using the actual model, provider, parser, settings, URL policy and system namespace sources against cached real Android, OkHttp, coroutine, serialization and Hilt dependencies. Twenty-three JUnit tests passed. The narrow harness extracts the changed AppPreferences/TokenStore sections verbatim, uses an empty generated BuildConfig and an unrelated language-preference seam; it does not replace production provider behavior.

Evidence for this local run is in the workspace's `game-provider-validation/manifest.json`, `compile.log` and `junit.log`. EN/DE settings resource keys match (30 entries), every panel string reference resolves, and `git diff --check` passes.

Seven full backup/companion integration tests were added to `GameMetadataBackupTest`, along with the existing classification/completeness suites. Full Android/Compose/DI and backup execution remain PR CI gates because the local Gradle distribution is unavailable. No live authenticated ScreenScraper/TheGamesDB request was claimed: no user API credentials were available for such a smoke test. IGDB remains deferred, and no common Twitch client secret is embedded.
