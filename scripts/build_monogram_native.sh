#!/usr/bin/env bash
# Monogram GPL-3.0-only native build. No Monogram app, UI, chat,
# profile, Room, network bridge, or feature module is compiled.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pins="$root/gradle/external-versions.properties"
ref="$(sed -n 's/^MONOGRAM_REF=//p' "$pins" | head -1)"
[[ "$ref" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid pinned Monogram SHA" >&2; exit 1; }
source_dir="$root/third_party/monogram"
output="$root/app/libs/monogram-mtproto.aar"
stamp="$root/app/libs/.monogram-native-version"
patch_script="$root/scripts/patch_monogram_leave.py"
patch_digest="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$patch_script")"
abis=(${MONOGRAM_ABIS:-arm64-v8a armeabi-v7a x86 x86_64})
[[ "${#abis[@]}" -gt 0 ]] || exit 1
for abi in "${abis[@]}"; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported ABI $abi" >&2; exit 1;; esac
done
requested="$(printf '%s\n' "${abis[@]}" | sort -u | tr '\n' ',')"
if [[ "${REBUILD_MONOGRAM:-0}" != "1" && -s "$output" && -f "$stamp" ]] &&
    grep -Fx "ref=$ref" "$stamp" >/dev/null &&
    grep -Fx "abis=$requested" "$stamp" >/dev/null &&
    grep -Fx "patch=$patch_digest" "$stamp" >/dev/null; then
    echo "Reusing pinned Monogram MTProto-only AAR ($requested)"
    exit 0
fi
for command in git java python3 cargo cargo-ndk rustup; do
    command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 1; }
done
[[ -n "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" ]] ||
    { echo "Set ANDROID_SDK_ROOT or ANDROID_HOME; Monogram requires Android SDK 37 and NDK." >&2; exit 1; }
if [[ ! -d "$source_dir/.git" ]]; then
    mkdir -p "$(dirname "$source_dir")"
    git clone --filter=blob:none --no-checkout https://github.com/monogram-android/monogram.git "$source_dir"
fi
git -C "$source_dir" fetch --depth=1 origin "$ref"
git -C "$source_dir" -c advice.detachedHead=false checkout --detach --force "$ref"
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$ref" ]] ||
    { echo "Monogram checkout is not at the pinned commit" >&2; exit 1; }
# Only mono-rs is required after the pinned Ambient TV media-only Rust patch.
# Do not fetch the huge tlottie submodule for sticker animations we do not ship.
git -C "$source_dir" submodule update --init --recursive vendor/mono-rs
python3 "$patch_script" "$source_dir"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
for abi in "${abis[@]}"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android ;;
        armeabi-v7a) target=armv7-linux-androideabi ;;
        x86) target=i686-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
    esac
    rustup target add "$target"
    echo "Building Monogram :native:mtproto ONLY for $abi"
    ( cd "$source_dir"
      ./gradlew --no-daemon --no-configuration-cache :native:mtproto:assembleRelease -PtargetAbi="$abi"
    )
    aar="$source_dir/native/mtproto/build/outputs/aar/mtproto-release.aar"
    [[ -s "$aar" ]] || { echo "Missing $aar" >&2; exit 1; }
    cp "$aar" "$scratch/$abi.aar"
done
args=()
for abi in "${abis[@]}"; do args+=("$abi=$scratch/$abi.aar"); done
python3 "$root/scripts/merge_monogram_aars.py" "$output" "${args[@]}"
printf 'ref=%s\nabis=%s\npatch=%s\n' "$ref" "$requested" "$patch_digest" > "$stamp"
echo "Only the upstream Monogram MTProto native module was compiled; GPL terms apply to the opt-in build."
