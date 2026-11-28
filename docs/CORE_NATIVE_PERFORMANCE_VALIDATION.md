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
  Complex MAC, system-register, exception and delay-slot memory operations
  retain their exact pinned handlers. This is not a full native SH2 JIT.
* Both are artifact-only until the CI and device acceptance gates pass. Public
  channels do not silently select these binaries.

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
command buffers and semaphores. Cross-queue-family image handoffs and unsupported
interfaces fail explicitly. This measures completed GPU work in a serialized
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
build reports, per-round results and comparison.json. The comparison rejects
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
