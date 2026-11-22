# Kodi / RetroArch follow-up implementation and audit

Base: `b84c23914e5602160da96883e3238c5eb4fd9f6a` (PR #445).

## Acceptance contract

Every completed feature receives a focused functional, integration and performance
audit before it is considered finished. Measurements distinguish local fixtures,
CI verification and physical phone/TV acceptance; no estimated speedup is presented
as a device measurement. Caches must not wake a NAS or start a library scan.

Permanent user choices remain portable through the existing logical backup and
companion contracts. Rebuildable caches are device-local. ROMs, artwork bitmaps,
save-state binaries and credentials keep their existing appropriate storage.

## Work packages

1. Targeted game-library database queries, indexed lookups and display ordering.
2. Persistent audio tags and cached-first, bounded incremental folder enrichment.
3. Shared reactive controller selection and configurable system-aware touch UI.
4. Selective artwork analysis eviction and explicit image refresh integration.
5. Row-based local playback state, preserving migrations and source identity.
6. Bounded indexed programme-guide storage and time-window reads.
7. Game save-state actions, off-main-thread persistence and core input contracts.
8. Integrated performance checks, migration/backup review and regression validation.

The comparison's longer-term optional features (a new NAS video/NFO library,
external game-cover services and speculative execution such as run-ahead) depend
on separate product/core acceptance and are not prerequisites for these existing
integration improvements.

Feature-specific documents record changes, findings, fixes, tests and outstanding
device-only checks. This index is updated as those audits complete.
