# Game save-state feature audit

## Implemented

- Cards expose the same Load / Rename / Delete menu on phone and TV, with an explicit
  deletion confirmation. A manual state's optional name is backward compatible JSON metadata.
- Manual state capture/restore waits on a worker. State files, JPEG encoding, metadata scans
  and preview-file lookup run on IO. Coil decodes previews asynchronously at a bounded size.
- A busy gate prevents repeated save/load/reset requests and stops emulation during the operation.
- The store serializes reads, writes, rename and delete with existing automatic checkpoints.
  JPEG publication is atomic just like state/metadata publication.

## Integration audit and fixes

- Loading UNDO reads its old bytes before publishing a new UNDO state.
- Core ID/build compatibility, hardcore and netplay restrictions remain in the load path.
- Cancellation propagates, teardown rejects late native work, and file writes already underway
  finish independently of Compose visibility.
- AUTO deletion retains the checkpoint sequence fence; ordinary manual/UNDO deletion does not
  invalidate unrelated pending AUTO captures.
- Two saves sharing a millisecond previously reused a filename. They now receive distinct IDs
  and deterministic numbering/order, including retention and quick-load selection.
- Renaming preserves save bytes, preview, timestamp and core fingerprint. Renaming a deleted
  state fails instead of resurrecting metadata.
- Independent review found the initial metadata list still ran after the IO block; it now
  completes on IO before publishing the ready session. A failed preview encode/write removes
  the old AUTO/UNDO image rather than showing a different checkpoint's screenshot.
- Closing/renaming returns TV focus to the source card; deletion targets its nearest surviving
  neighbour and closes an empty strip. Focus waits for the dialog's window hand-back.
- Binary states and screenshots remain files, not SQLite BLOBs. This feature changes neither
  game backup semantics nor the companion input protocol.

## Verification

Added storage regressions for same-tick saves, reopen/rename, compatibility, stale rename and
label validation. Phone-width Compose interaction tests verify visible actions, confirmation
and editing without loading a core. Full runtime results are recorded in the integration audit.

Physical-device acceptance still needs one large-core save/load and Home/return during a save.
Worker dispatch removes the main-thread wait; it does not make a core serialize faster.

The first combined CI run exposed two errors in the new phone test fixture: phone-sized
Robolectric qualifiers did not supply `LocalHandheldUi`, and text entry skipped the field's
explicit tap-to-edit step. The fixture now uses the real handheld composition and theme,
touches the controls, enters edit mode, and closes the dialog through the same owner callbacks
as `GameStateStrip`. Assertions still require a visible deletion confirmation, no premature
delete/load, exactly one confirmed delete, the exact renamed label, and dialog removal.
No production assertion or behavior was relaxed to make those tests pass.
