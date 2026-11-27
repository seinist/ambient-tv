#!/usr/bin/env bash
# Build the actual pinned projectM classic compositor twice on Mesa GLES3.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pin="$(sed -n 's/^PROJECTM_COMMIT=//p' "$root/gradle/external-versions.properties")"
[[ "$pin" =~ ^[0-9a-f]{40}$ ]]
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
src="$work/source"
git init -q "$src"
git -C "$src" remote add origin https://github.com/projectM-visualizer/projectm.git
git -C "$src" fetch -q --depth 1 origin "$pin"
git -C "$src" checkout -q --detach FETCH_HEAD
test "$(git -C "$src" rev-parse HEAD)" = "$pin"
git -C "$src" submodule update --init --recursive --depth 1
for patch in "$root"/native/ambientprojectm/patches/*.patch; do
    git -C "$src" apply --check "$patch"
    git -C "$src" apply "$patch"
done
cp "$root/native/ambientprojectm/tests/classic_composite_test.cpp" "$src/src/libprojectM/ambient_classic_test.cpp"
cat >> "$src/src/libprojectM/MilkdropPreset/CMakeLists.txt" <<'CMAKE'

if(AMBIENT_CLASSIC_TEST_DIRECT)
    set_source_files_properties(VideoEcho.cpp MilkdropPreset.cpp PROPERTIES COMPILE_DEFINITIONS AMBIENT_PROJECTM_DIRECT_CLASSIC_COMPOSITE=1)
else()
    set_source_files_properties(BlurTexture.cpp PROPERTIES COMPILE_DEFINITIONS AMBIENT_PROJECTM_FORCE_BLUR_COPY=1)
endif()
CMAKE
cat >> "$src/src/libprojectM/CMakeLists.txt" <<'CMAKE'

add_executable(ambient_classic_test ambient_classic_test.cpp)
target_include_directories(ambient_classic_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_definitions(ambient_classic_test PRIVATE USE_GLES PROJECTM_STATIC_DEFINE)
target_link_libraries(ambient_classic_test PRIVATE projectM GLM::GLM projectM::Eval EGL)
if(AMBIENT_CLASSIC_TEST_DIRECT)
    target_compile_definitions(ambient_classic_test PRIVATE AMBIENT_PROJECTM_DIRECT_CLASSIC_COMPOSITE=1)
endif()
CMAKE
export EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1
for mode in OFF ON; do
    cmake -S "$src" -B "$work/build" -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_GLES=ON -DBUILD_SHARED_LIBS=OFF -DENABLE_PLAYLIST=OFF \
        -DENABLE_SYSTEM_PROJECTM_EVAL=OFF -DENABLE_INSTALL=OFF \
        -DENABLE_SDL_UI=OFF -DBUILD_TESTING=OFF \
        -DAMBIENT_CLASSIC_TEST_DIRECT="$mode"
    cmake --build "$work/build" --target ambient_classic_test --parallel 4
    "$work/build/src/libprojectM/ambient_classic_test" "$work/$mode.rgba" "$work/$mode.blur"
done
python3 - "$work/OFF.rgba" "$work/ON.rgba" <<'PY'
import sys
import numpy as np
a, b = [np.fromfile(path, dtype=np.uint8).astype(np.int16) for path in sys.argv[1:]]
assert a.size == b.size and a.size > 1_000_000, "incomplete render corpus"
assert np.ptp(a) > 100 and np.ptp(b) > 100, "degenerate output"
error = np.abs(a - b)
print(f"Classic composite comparison: {a.size} channels, max={error.max()}, mean={error.mean():.5f}, >2={np.mean(error > 2):.7f}")
# Preserve byte-level color apart from small floating-point interpolation differences.
assert error.mean() <= 0.15 and np.mean(error > 2) <= 0.001 and error.max() <= 12, "classic composite visual regression"
PY

python3 - "$work/OFF.blur" "$work/ON.blur" <<'PY'
import sys
import numpy as np
a, b = [np.fromfile(path, dtype=np.uint8).astype(np.int16) for path in sys.argv[1:]]
assert a.size == b.size and a.size > 10_000, "incomplete blur corpus"
assert np.ptp(a) > 100 and np.ptp(b) > 100, "degenerate blur output"
error = np.abs(a - b)
print(f"Blur FBO comparison: {a.size} channels, max={error.max()}, mean={error.mean():.5f}, >2={np.mean(error > 2):.7f}")
assert error.mean() <= 0.15 and np.mean(error > 2) <= 0.001 and error.max() <= 12, "blur visual regression"
PY
