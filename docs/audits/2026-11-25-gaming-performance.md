# Gaming performance audit — implementation tracking

Base: 50dff9f8770569c788d6a7b8e11a1a914bd47c69. This change set begins with bounded, low-risk optimizations.

## Stage 1
- Move built-in Vulkan Slang asset extraction and SHA-256 verification away from Compose/main; serialize extraction and key results to the active selection. Pending preparation retains user fullscreen intent but does not allow raw/stale presentation.
- Send aggregate input directly for the changed port, preserving the existing generic observer for tests and other consumers.
- Pack padded ES2 software frames into the reusable upload buffer for one texture upload; preserve immutable source bytes, original pitch metadata and existing color conversion.
- Regression test: repeated unchanged touch input emits no port callback.

## Stage 2 (safe subset)
- Add native RGB565 padded, unaligned, partial-redraw pixel checks.
- Cache ES2 texture sampling state across unchanged frames, including paused shader preparation. Capture restores sampler state.
- Batch reverse-delta ring reads/writes in fixed 256-byte stack chunks. Wire format, frame spans, budget, and zero heap allocation unchanged.
- Re-audit: while asynchronous shader IO is pending, demote fullscreen without setting the temporary raw preset. The new filter resets the status before promotion.

## Required checks
Run PR CI including the native patch application, GLES/HDR and Vulkan test harnesses, and Kotlin unit tests. Device timings are not established by static review.

## Remaining stages (not claimed complete)
Native frame-slot synchronization and removal of the Vulkan/GLES handoff require explicit ownership and device tests; never remove a fence or glFinish without an equivalent completion proof. Further work: asynchronous rewind compression with bounded memory and nonblocking ownership, frame timing instrumentation, shader-resource reuse and resolution policy, performance profiles, device comparison. Do not silently enable experimental native WSI or change filter semantics.

## Stage 2 CI repair and GLES2 storage re-audit (2026-11-25)
- **CI failure confirmed:** head `9e8706890732962dc1478834cfdf8f5555545051`, [PR checks 45697308967](https://github.com/seinist/ambient-tv/actions/runs/45697308967): unit-tests and three native Vulkan ABI jobs failed at `git apply` for patch 0039 (cpp hunk at 52); games-changes succeeded. No native or Kotlin test pass is claimed for that head.
- **Implemented, CI pending:** restore 0039 byte-for-byte to current Main (`50dff9f8770569c788d6a7b8e11a1a914bd47c69`); place the pitched upload and sampling changes together in the final 0092 patch, regenerated against the pinned upstream ES2 files after the existing 0018/0036/0039 patches. Exact old-side matches and new-patch round-trip checked for both ES2 files; actual full patch-stack application and compilation remain CI gates.
- **Implemented, CI pending:** in 0092 use one upload even on allocation, and track actual storage format so RGB565↔RGBA switches at identical dimensions reallocate rather than submit incompatible subimages. Keep source pitch/data immutable, existing color conversion and sampler ownership; do not cache context-global unpack state or alter synchronization.
- **Regression added:** `scripts/native_game_hdr_filter_audit_test.cpp` now checks same-size RGB565→XRGB→RGB565 colors and GL errors, sampler switches, and a paused filter change. Existing pitched RGB565/unaligned/partial-redraw checks retained. No physical-device timings or host execution claimed; CI must run against the new head.
- **Open:** other GLES resource/uniform caches, shader IO/coalescing, pacing and input/audio/companion/thread hotspots, asynchronous rewind, Vulkan ownership and caching, HDR chains/profiles/device checks. No completion or merge approval implied.
