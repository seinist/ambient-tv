# Settings and playback panel presentation

- Date: 2026-10-29
- Baseline: `92435d7`
- Branch: `codex/unify-settings-panels`

## Purpose

Settings pages used a mixture of framed cards, individual field titles and stretched
action buttons. Playback panels had their own section style. The shared controls now
give pages, panels and their settings dialogs the same visual structure.

## Presentation rules

- `SettingsSectionHeader` supplies non-focusable headings, spacing and optional
  descriptions. `RightPanelSectionTitle` delegates to it.
- `SettingsSelect` has a direct label and optional description. Wide pages can put
  the value beside its label; narrow panels stack them. Selection rows retain their
  full target width and the existing select popup and focus behavior.
- `SettingsNavigationRow` identifies a destination with a trailing arrow. Direct
  player actions retain their existing action or state icons.
- `SettingsActionRow` gives Save, Test, Reset, Import, Export and similar actions
  their intrinsic width and wraps them when space is limited.
- `SettingsHint` is supporting text, without an additional D-pad stop.
- `SettingsGroup` is unframed by default. Connected forms, host cards and previews
  can explicitly retain a card.

## Coverage

The changes cover the general settings categories, media defaults, playback and
live-TV settings, image and audio panels, saved profiles, subtitles, seek behavior,
photos, clock, visualizer, screensaver, home, menu sounds, notifications, diagnostics,
backup and update actions. System pages include Plex, Jellyfin, Emby, TVheadend,
YouTube, Spotify, trailers, IPTV, files/DLNA, WireGuard, Wake, companion and receiver.

Existing callbacks and capability/visibility conditions are retained. Settings
storage is unchanged. Panel hints distinguish global preferences, the active
title/series/season/collection/channel scope, and current-playback controls. In
particular, seek behavior remains global; presentation size and speed remain
session controls. Audio delay retains its separate title-specific explanation.

## Search and focus

Inserted headings make numeric search offsets unreliable. Search now selects
semantic item keys. `indexedSettingsItems` records their actual lazy-list positions,
including conditional profile rows and batches of Wake hosts, without composing
off-screen content.

An explicit search with no visible matching anchor lands at the top of the category.
Normal Back navigation retains the saved position. Existing opener focus and panel
Back behavior remain in place. Search landing also runs on handheld layouts.

## Validation

- 12 SDK-independent JUnit tests pass using the actual Kotlin sources:
  `WakeHostGroupsTest` (7), `SettingsCopyTest` (4), `SettingsItemIndexTest` (1).
  They cover physical-host targeting, text contracts, and search versus normal
  return scroll behavior.
- Kotlin PSI parsing reports no syntax errors in all 40 changed/new Kotlin files.
  This is a syntax check, not Android type checking.
- `git diff --check` passes.
- The source audit reports exactly the same 93 missing English translations as
  the baseline; this change introduces no additional audit issues. The 97 new
  section/label strings have matching default, German and English keys and format
  placeholders.
- Independent source reviews checked callback retention, conditional controls,
  focus requesters, presentation scopes and shared helper signatures. Review
  findings concerning action arrows, generic search landing and scope labels were
  corrected.

The environment has no Android SDK, Compose dependencies or Android build setup.
No complete Android build, Compose test execution, emulator run or physical-TV
layout verification was possible. The additional lazy-list adapter regressions in
`SettingsIndexedListTest` and the updated `SettingsNavigationTest` remain for the
normal Android/Gradle test environment. Visual wrapping and D-pad navigation at
large UI scales still require a rendered/device check.
