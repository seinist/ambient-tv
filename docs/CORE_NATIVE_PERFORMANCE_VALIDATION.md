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
  Dirty values
  are written back before any helper or trace exit. Memory/control-only hot
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
* Both are artifact-only until the CI and device acceptance gates pass. Public
  channels do not silently select these binaries.

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

`input.txt` contains exactly 2100 hexadecimal joypad masks, one per line.
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

Upload the resulting ambient.profdata and metadata.json as a same-repository
Actions artifact named pgo-CORE-ABI (the explicitly supplied run ID and
SHA-256 are required for pgo-use). Alternatively use the protected, manually
dispatched Core PGO training workflow on a configured self-hosted Android
benchmark runner. Never reuse profiles across different source pins, ABIs or
NDK versions. Training and evaluation content should be separate. No profile
is automatically published and no speedup is claimed without device evidence.
