# projectM performance validation

The native runtime is pinned in `gradle/external-versions.properties`. Patches under
`native/ambientprojectm/patches` are reapplied to a clean pinned checkout.
The normal build uses O3/ThinLTO, the driver-keyed bounded shader cache and indexed
warp mesh. `PROJECTM_DIRECT_CLASSIC_COMPOSITE=ON` is now the default after the Mesa
GLES pixel-equivalence test passed on head `fa31c56` (62,437,824 channels;
maximum byte difference 4, mean rounded to 0.00000). Set it to `OFF` to build
the reference fallback if a device-specific rendering issue is found.

## Host regression checks

```bash
g++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie \
  native/ambientprojectm/tests/pcm_tap_test.cpp -o /tmp/projectm-pcm-test
/tmp/projectm-pcm-test
bash scripts/test_projectm_classic_composite.sh
```

The latter compiles the actual projectM code twice and compares the reference and
direct classic composite outputs on Mesa GLES, including echo orientations, filters,
gamma and multiple viewport shapes. A pass is not proof of driver-specific parity.

## Collect PGO data on a device

1. Set the same NDK used for the final native build. Choose the installed APK ABI
   (not necessarily the first ABI advertised by the device).
2. Build developer-instrumented libraries with
   `PROJECTM_PGO_GENERATE=ON PROJECTM_ABIS=arm64-v8a bash scripts/build_projectm_android.sh`.
   Use the same optimizer, LTO, architecture tuning and composite flags for training
   and subsequent profile use. The generation build intentionally does not publish
   a production cache marker. Package the generated libraries into the debuggable
   projectM audit APK without rebuilding the libraries in ordinary mode.
3. Run the existing playback instrumentation with the `projectmAudio=true` argument
   (MPV and VLC, file and live audio-only playback). After its last successful
   rendering check it writes `cache/projectm-audit.profraw` in the audit package.
4. Pull the raw file and use the exact NDK's llvm-profdata:

   ```bash
   bash scripts/collect_projectm_pgo.sh DEVICE_SERIAL AUDIT_PACKAGE arm64-v8a ./profiles
   ```

5. Build with `PROJECTM_ABIS=arm64-v8a PROJECTM_PGO_DIR=./profiles bash scripts/build_projectm_android.sh`.
   For an all-ABI build provide one matching `<abi>.profdata` for every selected ABI.
   Missing or stale profile data must not silently fall back to a purported PGO build.

## Device comparison

Compare regular and profile-use builds separately from any resolution changes.
Use the same preset sequence, audio, output resolution, mesh and HDR mode.
Measure rendered frame cadence, p95/p99 frame times, CPU load and GPU load where
supported. Repeat on ARM64 Shield and a phone, including pause/resume, preset
switches, shader-heavy presets and HDR routing. Host software rendering timings
must not be treated as Android GPU benchmarks. No device performance claim is
made until such measurements exist.
