# Core serialization contracts audit

## Feature

The frontend acknowledges `SET_SERIALIZATION_QUIRKS`, exposes the accepted mask
through an atomic JNI read, and clears it between cores. Persisted state loads use
the existing core-validated loader after the app's core ID/build check; they do
not reject a legitimate variable-size state solely because the current size differs.

`MUST_INITIALIZE` retries size detection every 60 rendered frames until available.
The probe waits on a worker, not Android's UI thread. It restores a pending AUTO
state at most once when supported. SRAM remains independent of state support.

## Integration decisions

- `SINGLE_SESSION`: persistent save/load and automatic-state restoration are disabled.
  There is no misleading on-disk save that cannot work in the next session.
- `INCOMPLETE` or variable-sized states: ordinary persistent saves remain supported;
  fixed-ring rewind and frame-exact network play decline the unsupported contract.
- Rewind checks the contract in native code as well as in UI/input entry points.
- Netplay checks it in host/join entry points as well as menu availability.
- Architecture-dependent states remain device-local and must match the exact core
  binary fingerprint. No new cross-device save-state synchronization is introduced.
- Unknown flags are not acknowledged; FRONT_VARIABLE_SIZE is set only when the
  corresponding core flag is present. No run-ahead capability is advertised.

## Validation

Policy regression tests cover incomplete, variable-size, single-session and delayed
initialization behavior. Native patch application and ABI compilation are included
in the PR checks. These tests do not substitute for a core/device acceptance run.
Manual state menus have their own audit in `2026-11-22-game-state-actions.md`.

The independent integration review checked the native mask, JNI lifetime, existing
core-validated loader, UI and native rewind guards, netplay entry points, source/ABI
isolation and delayed initialization. All three pure policy tests passed locally,
and a clean pinned LibretroDroid fetch applied the entire patch series through 0027.
MUST_INITIALIZE checks a real bounded serialization as well as the reported size:
some cores report a size before they can actually serialize. Native allocation/load
also enforces the existing 64 MiB limit when a core changes size later in a session.
