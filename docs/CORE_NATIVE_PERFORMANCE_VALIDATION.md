# Native core performance acceptance

This PR adds diagnostic candidates and build/measurement tooling. A successful
compiler build is not proof of correct gameplay or a performance improvement.

## ARM64 JIT candidates

* `citra-arm64-jit`: pinned Azahar Dynarmic A32 backend; shared RAM backing,
  reserved 4 GiB guest address space, host-page-coherent alias mappings and
  callback fallback for non-shareable, MMIO, rasterizer-cached or incompatible
  16 KiB host pages. Cross-page accesses fall back. The candidate still needs
  Android integration, multiple process-switch, savestate, GPU cache and
  exclusive-access tests on both 4 KiB and 16 KiB devices.
* `yabause-arm64-jit`: bounded native ARM64 straight-line blocks; integer ALU,
  immediate operations, shifts, comparisons and T flag operations. Native
  differential tests cover emitted opcode encodings including shift/rotate T
  flags, carry/borrow, overflow, CMP/STR and mixed instruction blocks. Non-delay BT/BF branches use their exact cycle charge in the block
  exit; other terminal operations reuse a validated fetch and invoke the
  predecoded native MOV memory path, or prebound original opcode handler for
  complex operations. All MOV addressing forms preserve mapped bus callbacks,
  register aliasing, sign extension, retry cycles and write notifications.
  Predecoded complex memory operations include TAS, byte read-modify-write,
  system-register loads/stores, MAC.W/MAC.L, TRAPA and RTE. Delay-slot execution
  retains its original handler. Native ARM64 blocks now also emit signed/unsigned
  16- and 32-bit multiplication, 64-bit products, system-register transfers,
  CLRMAC and DIV0U with independent guest cycle accounting. Native terminal
  control handles BRA/BSR, register jumps/returns, delayed conditional branches,
  MOVA, DIV0S, DIV1 and SLEEP. Delay slots now fetch at the original execution
  point and use the native single-op path (including PC-relative loads and
  mapped bus helpers). Cold ALU instructions use lazily generated immutable
  W^X slabs indexed by opcode; no mapping is allocated for memory/control
  operations. The two SH2 CPUs own independent single-op slabs. Each cached
  block retains four polymorphic outgoing links (MRU first) to stable slots
  in a 256-entry, two-way set-associative per-CPU translation cache and can execute a
  bounded chain of 16 validated blocks without returning to the outer loop.
  Links recheck mapping and cycle budget. Physical code-page generations are
  shared between CPUs; T2 writes mark only the affected page(s), including
  aliases and cross-page writes. Uncached code validation coalesces contiguous
  source spans into exact host-endian comparisons. Within a pure linked scope,
  unchanged pages can reuse validation; entry and mapped-helper boundaries
  require exact checks again to catch raw savestate/reset/cheat writes. Direct
  BRA/BSR targets with a pure native delay slot are fused into one trace:
  guest register allocation spans the branch, slot and target; BSR updates
  PR before the slot. The native code falls through directly without host
  dispatch. Every non-contiguous source address is checked before execution.
  BRAF/BSRF/JMP/JSR/RTS with pure native delay slots now return their
  computed target directly from the generated ARM64 trace. The target is
  captured before the slot (including PR/Rn aliasing), while the register
  allocator spans the prefix and slot. Registers are committed at mapped-bus/helper and dynamic-exit boundaries. Cache-enabled SH2 execution uses the same native single-op dispatcher with
  exact cache/data-array fetches, including delay slots. Cache-enabled traces
  compile from already resident, cache-use lines only; side-effect-free tag
  peeks verify every instruction before entry. The real fetches are replayed
  in guest order to retain exact LRU state without speculative refills.
  Cache misses, cache-through and data-array regions retain single-op execution. Illegal opcodes, debugging and executable mapping
  failure retain upstream fallbacks.
  Pure ALU traces now span up to 32 guest instructions and retain up to ten
  frequently accessed GPRs, SR and system registers in caller-saved AArch64
  registers. Flag-heavy traces load and store SR at most once per trace when
  allocated, including full SR writes and multiply accumulator transfers.
  Pure self-loops can execute up to 64 complete iterations in one native
  call, limited by the actual remaining guest cycle budget. Initial loads
  are hoisted and allocated registers stay resident across the generated
  backward branch. Dirty values are committed once on exit. Cache-emulated
  loops still replay each actual fetch and run one iteration at a time.
  Distinct pure static-exit traces may also transfer directly via an ARM64
  tail branch to a separately validated successor. Both source ranges and
  the combined cycle budget are checked before entry; no writable RX code,
  unvalidated third block, cached-fetch bypass or dynamic helper is allowed.
  Dirty values are written back before any helper or trace exit. Memory/control-only hot
  blocks are cached without executable allocation;
  short ALU prefixes can now end in a native terminal. The pinned upstream
  differential harness verifies control flow, registers, cycles and delay calls.
  A second AArch64 harness executes the actual native single-op dispatcher
  for every legal encoding, including real delay-slot ALU, memory and system
  operations against the original handlers and a deterministic mapped bus.
  The mapped no-cache bus directly specializes
  aligned High/Low WRAM loads/stores only when the exact original table target
  matches. MMIO, debug tracing, cache-enabled and unusual accesses retain
  the original bus paths. This is not a full native SH2 JIT.
* Both are opt-in signed experimental channels. Artifact-only validation
  precedes manual main-only signing. Default channels do not silently select
  these binaries; on-device game acceptance remains required.

## Additional renderer / recompiler candidates

* PicoDrive: fold high guest-PC bits into both the translated-block and
  unresolved-link hash tables to reduce systematic collisions. Insertion,
  lookup and deletion retain the same exact PC identity checks.
* Snes9x2010: vectorize the in-place RGB565 HD Mode 7 NTSC downsample with
  NEON deinterleaving and bit-exact halving arithmetic. Dedicated and combined
  optimized profiles receive the same pin-checked source patches. The PR gate
  uses their dedicated builds instead of redundant source-only jobs to stay
  below GitHub Actions' 256-job matrix limit.
* NooDS: ARM NEON pixel doubling for integer 2x screen scaling, with scalar
  tails and unchanged layouts; cross-compile and pixel-equivalence tests.
* Panda3DS: reuse exact-match native shader callbacks for consecutive draws
  with the same entrypoint; update only the instruction callback on an
  entrypoint change. Collision, accuracy-mode and eviction tests remain.

## Synthetic AArch64 diagnostic

The native SH2 differential harness additionally runs three paired, alternating
rounds of a deterministic 32-opcode ALU/branch loop (3.3 million guest cycles
per path and round). It measures the upstream predecoded interpreter and the
actual warmed native translation/linked dispatcher using monotonic elapsed
time, and asserts identical architectural state and cycle counts. These
numbers are host microbenchmarks with a mocked fetch/bus, not Shield/Android
frame-time or game performance; no timing threshold is imposed on shared CI.

## Reproducible benchmark execution

Build the runner with the project's NDK, or download its corresponding ABI
artifact from the Core benchmark runner workflow:

    export ANDROID_NDK_HOME=/path/to/ndk/30.0.16248370
    bash scripts/build_core_benchmark_runner.sh arm64-v8a out/core-benchmark-runner

Use a connected Android device (ADB debugging) and content/BIOS that you can
legally use. The standalone runner creates isolated temporary directories and
never modifies Ambient TV's installed cores or saves. The runner supports
software, GLES2/3 pbuffer and synchronous headless Vulkan (v1/v2 negotiation).
Select renderer `vulkan` in the fixture or `--renderer vulkan` for a single run.
Vulkan uses one sync slot and a fence per submitted frame, including core
command buffers and semaphores. Cross-queue-family image handoffs use semaphore-ordered acquire/release
barriers on the frontend queue. Missing synchronization and unsupported
interfaces fail explicitly. Real multi-family GPU validation remains required. This measures completed GPU work in a serialized
frontend, not real presentation or pipelined input latency.

Create a local fixture JSON with paths relative to that JSON:

    {
      "runner": "core-benchmark-runner",
      "content": "content",
      "rom": "game.rom",
      "system": "bios",
      "input": "input.txt",
      "options": "options.txt",
      "warmup_frames": 300,
      "frames": 1800,
      "profiles": {
        "baseline": {"binary":"baseline.so","report":"baseline-build.json"},
        "optimized": {"binary":"optimized.so","report":"optimized-build.json"},
        "optimized-a57": {"binary":"a57.so","report":"a57-build.json"}
      }
    }

`input.txt` contains exactly 2100 input records, one per line. Each record is a hexadecimal joypad mask, optionally followed by seven space-separated decimal values: left X/Y, right X/Y, pointer X/Y (each -32767 to 32767), and pointer pressed (0 or 1). Omitted fields are zero. The runner accepts legacy, v1 and v2 core option declarations using the English descriptors.
`options.txt` contains optional key=value pairs matching advertised core
options (an empty file is valid). Add a matching `runtime` path to
libc++_shared.so if needed. Each round starts in a new save directory.
For non-ARM64 or specialized profiles, explicitly set `required_profiles`
to the intended supported set including baseline.

    python3 scripts/benchmark_core_matrix.py --fixture fixture.json \
      --output reports/mgba-shield --rounds 3

The output includes complete raw samples, thermal evidence, file digests,
build reports, per-round results and comparison.json. The runner pins the ADB
serial, checks transferred runner/input/options/runtime hashes and rejects local
fixture mutations during capture. Paired rounds also require an identical
runtime digest and device serial. The comparison rejects
incomplete paired rounds, mismatched device/fixture/renderer, absent or stale
thermal evidence and thermal throttling. Save-state fingerprints are
diagnostic because some cores have nondeterministic state metadata. Do not
compare standalone pbuffer measurements with app trace measurements.

## PGO

Select the artifact-only `pgo-generate` profile for exactly one core/ABI.
Train that instrumented binary with representative, user-owned fixtures:

    python3 scripts/run_core_benchmark.py --runner core-benchmark-runner \
      --binary instrumented.so --report build.json --content content \
      --rom game.rom --system bios --input input.txt --options options.txt \
      --frames 2100 --output training --training

For multiple representative runs repeat with different output directories.
Merge with the matching NDK's llvm-profdata:

    python3 scripts/core_pgo.py merge --llvm-profdata /path/to/llvm-profdata \
      --directory trained --report build.json training/*.profraw

Local profiles can be evaluated locally. For CI pgo-use, use the protected,
manually dispatched Core PGO training workflow on a configured self-hosted
Android benchmark runner. Only its successful same-repository main-branch
artifact is accepted; supply the training run ID and SHA-256 explicitly. Never reuse profiles across different source pins, ABIs or
NDK versions. Training and evaluation content should be separate. No profile
is automatically published and no speedup is claimed without device evidence.

## Audit follow-up

Distinct Citra and Yabause JIT channels are selectable as opt-in signed sources.
Publication is manual, main-only, and requires a successful dedicated JIT
validation workflow on the identical commit; it does not change baseline or
optimized channels. Device gameplay evidence is still required before broad
promotion. Full catalog validation shards baseline and optimized by ABI plus
ARM64 A57. No shard publishes. GLES runner measurements now synchronize each
frame and verify all transferred fixture content and BIOS digests.

The audit also bounds Cemu's queued plus active pipeline compilation, adds
completion-driven Dolphin shader waits and validates texture upload dimensions
before transitions, reduces the Panda3DS shader cache on low-memory Android,
and bounds/flushes PPSSPP audio with bit-exact NEON conversion. ARMSX2 checks
compressed-row upload sizes and invalid input before allocation.

SH2 pure execution scopes now survive successive native dispatcher calls
within one scheduler slice; bus/control helpers and singleton fallback end
the scope. Reset, savestate and DMA notifications advance a shared bulk epoch.
The tracked read-only BIOS can reuse page validation across slices; writable
RAM retains exact entry validation at scheduler boundaries. ARM64 indirect
branches may directly invoke a validated, matching predicted successor, and
BT/BF loops account for taken and not-taken guest cycles independently.
Code slots share per-CPU W^X pages, reducing 16 KiB mapping overhead.

PPSSPP refreshes its compute scheduling budget at the frontend frame boundary
(every 60 frames on Android) using the current CPU affinity. Parallel-loop
width and new global compute dispatch follow that budget, while existing
worker indices and independent IO workers remain stable; no active tasks
are cancelled or migrated during a cpuset change. Worker count is fixed
until core teardown, intentionally avoiding unsafe live pool resizing.

Automatic core updates now preflight all reviewed hotpath source anchors
against the exact proposed upstream commit without executing upstream code.
Shared variants on the same pin update atomically; different pins remain
separate. Citra and Yabause JIT reference pins require a coordinated manual
rebase of recipes and differential tests. A failed preflight keeps the old
pin and reports the reason, while independent compatible cores can update.

Paired diagnostic runs now also validate output workload: real (non-null)
video frames, duplicated frames, audio sample counts and output geometry.
Video and fresh-frame counts may vary by at most 1%, audio by at most 2%
(with a one-count rounding allowance); zero/nonzero audio must match.
Counters are captured before save-state serialization and teardown. This
rejects comparisons that render materially less work rather than running
it faster. It does not replace visual or audio quality verification.

Compiler caches use the per-core semantic release fingerprint (including
specialized JIT and source patches), rather than hashing every unrelated
catalog entry. PGO-use keys also include the exact trained profile digest.
Fallback cache restoration is restricted to the same core, ABI, profile
and NDK; ccache checks compiler content, flags and included source files.
A cache hit is never substituted for ELF or link-evidence verification.

Signed core releases now recheck every downloaded binary against its exact
planned core/ABI/pin/profile/NDK/fingerprint, report SHA-256, byte count,
ELF architecture and PT_LOAD alignment before packaging. The build report
must attest complete exports, linked objects and allowed dependencies.
The signed manifest binds a persistent build-evidence.json asset, containing
new build reports and the originating signed entries of reused binaries.
The ARM64 differential gate also applies the production Yabause hotpath and
JIT source patchers to the actual pinned checkout before approving a candidate.

ELF page checking reads only the program header table, verifies file and
memory segment bounds, rejects malformed alignments and requires congruent
file/virtual addresses in addition to the 16 KiB minimum. Build evidence
retains each report's exact UTF-8 representation for independent hash checks.

## Separate update processes

- `./build.sh deps-check` / `deps-update [key]`: Maven catalog dependencies.
- `./build.sh libs-check` / `libs-update [svp|monogram|libretro]`: external libraries, including LibretroDroid, but no emulator cores.
- `./build.sh cores-check` / `cores-update [id[,id...]]`: all emulator core pins or a selected upstream group. This never updates other libraries.

Yabause and YabaSanshiro follow independent upstream branches. Native JIT
updates must pass production patch preflight on an exact disposable checkout;
CI tests the catalog pin. Incompatible candidates keep their existing pin.
Use `./build.sh cores --profile citra-arm64-jit` or `--profile yabause-arm64-jit`
to build artifacts. Main-only signing still requires the dedicated validation gate.

Audit hardening: the updater shares the build planner's catalog validation
before any remote request; source patch checks include NDK/CMake recipes for
Mupen64Plus, Snes9x2010, Citra, Panda3DS and SwanStation. Blocked candidates
are reported separately from current pins. Updater-only branch metadata is
excluded from binary fingerprints. Manual validation shards have distinct
concurrency keys; an independent updater-policy CI job validates shell dispatch.

The benchmark runner accepts an optional warm-up frame count; its summary
counters exclude the warm-up interval and include explicit window metadata.
Matrix comparisons reject old or mismatching runner summaries. Timing slices
still retain all frames for consistent warm-up exclusion. PGO artifact builds
can be dispatched with `./build.sh cores mgba --abis arm64-v8a --profile pgo-generate`
and `./build.sh cores mgba --abis arm64-v8a --profile pgo-use --pgo-run-id ID --pgo-sha256 HASH`.
The latter requires the validated training artifact; both remain artifact-only.

## Optional deterministic correctness and state interchange replay

For deterministic fixtures, set `"verify_output": true` in the matrix fixture.
After timing, each profile runs a separate fresh replay and compares visible
software pixels (excluding padding and unused color bits) and audio samples.
GLES correctness replay reads the frontend pbuffer, preserving pixel pack and
framebuffer bindings. Vulkan correctness replay copies the supplied image to a
host-visible staging buffer, preserving image layout and queue ownership; it
supports RGB/RGBA/BGR/BGRA 8-bit UNORM/sRGB, common packed 4/5/6/10-bit formats,
and 16/32-bit RGB/RGBA color payloads (including floating point). The hash
also binds the view format and component mapping. Unsupported or oversized
readbacks fail explicitly; the staging budget is capped at 256 MiB.
`"require_video_hash": true` explicitly rejects missing video proof.
The diagnostic FNV-1a fingerprints are not cryptographic or a universal
visual-quality metric. Asynchronous or nondeterministic fixtures may fail strict
comparison. Do not mix these correctness runs with performance samples.

Set `"verify_states": true` to additionally export each profile's state, load the
baseline state into fresh instances of all selected profiles, then load each
candidate's state into itself and baseline. Both directions compare replayed
output, and every replay must meet the measured workload checks. Unsupported
serialization or rejected state loads fail the requested validation. This is
not proof of the app's complete rewind lifecycle, real display output, or all
hardware formats.

The signed release fingerprint now binds the effective acceleration rules for
each core/ABI/profile plus the shared parser and evidence implementation. A
rule change for an unrelated core no longer invalidates all binaries; shared
verification changes still invalidate every affected build. PicoDrive and
SwanStation require linked CPU/frontend/render evidence even for interpreter
fallback architectures; SH2 and fastmem contracts remain conditional on ABI.

PPSSPP's allocated compute capacity is now independent of the current active
width. Idle compute workers outside that width do not claim new global work;
pinned/private tasks still run, and growing the width wakes newly eligible
workers when global work is queued. Width changes and global claims use the
same mutex, without cancelling running tasks or changing stable worker IDs.

The matrix now requires strict output correctness replay by default. An explicit
`"verify_output": false` opts out and marks the report as not correctness-verified.
The diagnostic runner records the active per-frame FPS and sample rate and
checks the dynamic geometry/rotation/timing schedule across profiles. GPU-backed
measurements include synchronous GPU completion, not just CPU time.

Input fixtures remain backward compatible. Append `| port:device:index:id:value`
entries to a frame to specify additional controllers (ports 0–7), keyboard,
mouse, lightgun, analog and multitouch state. Values use Libretro device and
button IDs. A sparse record describes a complete frame; unspecified controls
are released. Keyboard transitions are also delivered through the registered
Libretro keyboard callback. Set `@controller.1=1` (or another Libretro device ID)
in options.txt to select an explicit device on a port. These frontend settings
are included in the existing fixture hashes.

Set `"verify_rewind": true` to validate each selected binary against the actual
production reverse-delta buffer and asynchronous capture worker. The separate
correctness replay captures eight four-frame anchors, verifies a non-destructive
preview, pops retained anchors in reverse order, compares restored video/audio
and workload with the original forward segments, then clears and resumes on a
fresh branch. `"rewind_budget_mib"` defaults to 256; impossible budgets, failed
serialization, too little retained history or changed output fail explicitly.
This exercises real core states, not Android UI/renderer lifecycle integration.

PGO generation and use now share the public optimized profile's O3/ThinLTO
and applicable SH2/DSP-JIT/threaded-renderer contracts. Experimental Citra
and Yabause native JIT builds remain separate from ordinary PGO training.
PPSSPP queue publication and worker notification now use the same lock as
active-width changes; both growing and shrinking wake the appropriate workers.
Flycast regression coverage stresses producer overflow and consumer-side flush
while preserving stereo pairs and init/deinit ordering.

The host benchmark workflow now executes an asset-free mock core through actual
option negotiation, multiplayer/keyboard input, dynamic A/V scheduling, state
export and production rewind. A separate host test builds the pinned PPSSPP
ThreadManager source with its exact production replacements and simulated Android
CPU-affinity changes while concurrent producers enqueue work.

## Full-catalog real-device validation

The `Core device validation (trusted Android runner)` workflow accepts successful
same-main-commit catalog and benchmark-runner artifacts, and optionally the
separate JIT candidate artifacts. It never executes PR artifacts on a trusted
self-hosted device. For each selected ABI it defaults to every supported core
and all public profiles. Local fixtures live under
`$AMBIENT_BENCHMARK_FIXTURES/<core>/fixture.json`, with content/system/input/options
paths inside that directory, ROM, timing and renderer settings, and explicit
`verify_states` / `verify_rewind` booleans. Any unavailable state check needs a
nonempty `state_validation_limitation`; the summary distinguishes limited from
full state coverage. Output correctness is always mandatory. The workflow
uploads only the summary, not ROMs, BIOS, captured states or input files.

For pre-merge testing, use the same `scripts/validate_core_device_matrix.py`
locally with a trusted checkout, reviewed downloaded artifact directories and
an attached device. The protected workflow must be configured with the
`core-device-validation` environment and a self-hosted Android runner before
use. Without actual device results there is no claim of measured speedup or
universal rewind compatibility.

Thermal evidence now covers the complete measured interval, including gaps
between frames and the final diagnostic sample. Legacy app trace captures
remain distinguishable from standalone diagnostic GPU-synchronized timings.
Device summary artifacts bind fixture identities by digest instead of
publishing raw local filenames or ADB serial numbers.
