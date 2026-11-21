# Ambient TV Games — post-audit remediation (2026-11-21)

Scope: source-level findings in the current local Libretro integration. RomM remains postponed.

## Addressed in this branch

- **Core rollback:** use an atomic same-filesystem POSIX rename to replace an installed native core. Never delete the current executable first. Pre-stage rollback metadata, preserve the previous binary if replacement fails, and test the failure path.
- **Signed core trust:** AUTO prefers verified, pinned, signed Ambient-TV core builds. If no signed build can be used, display an explicit warning and ask before downloading a Libretro buildbot binary. The explicitly selected BUILDBOT source remains a deliberate opt-in. A signature-integrity failure does not downgrade.
- **Core release workflow:** fail before starting the 26-core/4-ABI matrix if the signing secret is absent or mismatches the checked-in public key; never publish unsigned native cores.
- **Multi-file ROMs:** reject unsafe paths, preserve relative directories, detect filename collisions, refuse missing disc sidecars, validate CUE/GDI/M3U dependencies before reusing cache, and verify extracted-file size manifests rather than trusting a marker alone.
- **Netplay:** stream SHA-256 of the full ROM, not just its first and last regions; include binary build, Vulkan/GLES mode, effective core options, and known per-system BIOS file hashes in the session identity. Netplay protocol version is now 4 and is intentionally incompatible with older versions. After disconnect/failure, restore single-player rewind/cheats and resume the game if no menu is open.
- **CI:** select the four-ABI patched LibretroDroid and pinned Vulkan-Slang runtime checks by changed paths on PRs and main, not only by a one-off PR title or branch name.
- **Regression tests:** rollback failure, full-ROM hash changed in the middle of a large disc image, option/BIOS mismatch, nested archive tracks, traversal, cache sidecar completeness.

## Operational action required

The public verification key lives at `cores/signing-public.pem`, but the **private** matching key must be entered manually as the GitHub Actions repository secret `CORES_SIGNING_KEY`. Never commit or log the private key. The preflight explicitly rejects missing or mismatched keys; the earlier automated release failed without the secret. Once configured, run the Cores workflow with `publish=true` and verify the signed release is available before treating it as operational.

## Hardware / feature acceptance still required

These source changes are not physical-device tests. The experimental Vulkan/Slang path is opt-in. NVIDIA Shield, Philips TV and Android phone tests remain tracked in issue #439, including 4K/60, real HDR EOTF, native WSI lifetime/fences, OSD overlay order, touch and gamepad focus, GPU memory, final signed split/universal APK size and recovery from surface loss. Authentic arbitrary RetroArch Slang preset imports, broader per-core input abstractions (Wii Remote, DS second screen, Wii U GamePad, physical mouse/keyboard/lightgun) are separate features tracked in issue #435 and docs/LIBRETRO_FOLLOWUPS.md; their existence cannot be inferred from SVG controller diagrams.

Netplay still requires a trusted LAN and deliberate host approval; this patch improves deterministic compatibility checks but **does not** add authenticated/encrypted peer transport. Avoid exposing the Netplay port publicly. A 4-GiB ROM can take time to fingerprint because the complete content is deliberately read sequentially on a background IO dispatcher rather than on the emulation/render thread.

Release qualification requires green CI at the exact head and device evidence; this branch alone is not a production HDR certification.
