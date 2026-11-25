# Gaming performance audit — implementation tracking

Base: 50dff9f8770569c788d6a7b8e11a1a914bd47c69. This change set begins with bounded, low-risk optimizations.

## Stage 1
- Move built-in Vulkan Slang asset extraction and SHA-256 verification away from Compose/main; serialize extraction and key results to the active selection. Pending preparation retains user fullscreen intent but does not allow raw/stale presentation.
- Send aggregate input directly for the changed port, preserving the existing generic observer for tests and other consumers.
- Pack padded ES2 software frames into the reusable upload buffer for one texture upload; preserve immutable source bytes, original pitch metadata and existing color conversion.
- Regression test: repeated unchanged touch input emits no port callback.

## Required checks
Run PR CI including the native patch application, GLES/HDR and Vulkan test harnesses, and Kotlin unit tests. Device timings are not established by static review.

## Remaining stages (not claimed complete)
Native frame-slot synchronization and removal of the Vulkan/GLES handoff require explicit ownership and device tests; never remove a fence or glFinish without an equivalent completion proof. Further work: asynchronous rewind compression with bounded memory and nonblocking ownership, frame timing instrumentation, shader-resource reuse and resolution policy, performance profiles, device comparison. Do not silently enable experimental native WSI or change filter semantics.
