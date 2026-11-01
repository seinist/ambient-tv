#!/usr/bin/env bash
# Monogram GPL-3.0-only native build. No Monogram app, UI, chat,
# profile, Room, network bridge, or feature module is compiled.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pins="$root/gradle/external-versions.properties"
# Pinned to an upstream release tag; the reviewed tag commit and mono-rs submodule
# commit guard against a moved tag or a changed submodule.
version="$(sed -n 's/^MONOGRAM_VERSION=//p' "$pins" | head -1)"
ref="$(sed -n 's/^MONOGRAM_REF=//p' "$pins" | head -1)"
mono_rs_ref="$(sed -n 's/^MONOGRAM_MONO_RS_REF=//p' "$pins" | head -1)"
[[ "$version" =~ ^v?[0-9]+(\.[0-9]+)+$ ]] || { echo "Invalid pinned Monogram release tag" >&2; exit 1; }
[[ "$ref" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid pinned Monogram SHA" >&2; exit 1; }
[[ "$mono_rs_ref" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid pinned mono-rs SHA" >&2; exit 1; }
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
    grep -Fx "version=$version" "$stamp" >/dev/null &&
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
# Fetch the release tag (not an arbitrary commit) and refuse a tag that moved.
git -C "$source_dir" fetch --depth=1 --no-tags origin "+refs/tags/$version:refs/tags/$version"
tag_commit="$(git -C "$source_dir" rev-parse "refs/tags/$version^{commit}")"
[[ "$tag_commit" == "$ref" ]] ||
    { echo "Monogram release $version now points to $tag_commit, not the reviewed $ref" >&2; exit 1; }
git -C "$source_dir" -c advice.detachedHead=false checkout --detach --force "refs/tags/$version"
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$ref" ]] ||
    { echo "Monogram checkout is not at the pinned release commit" >&2; exit 1; }
submodule_commit="$(git -C "$source_dir" ls-tree HEAD vendor/mono-rs | awk '{print $3}')"
[[ "$submodule_commit" == "$mono_rs_ref" ]] ||
    { echo "Monogram $version pins mono-rs $submodule_commit, not the reviewed $mono_rs_ref" >&2; exit 1; }
# Only mono-rs is required after the pinned Ambient TV media-only Rust patch.
# Do not fetch the huge tlottie submodule for sticker animations we do not ship.
git -C "$source_dir" submodule update --init --recursive vendor/mono-rs
[[ "$(git -C "$source_dir/vendor/mono-rs" rev-parse HEAD)" == "$mono_rs_ref" ]] ||
    { echo "mono-rs checkout is not at the pinned commit" >&2; exit 1; }
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
printf 'version=%s\nref=%s\nabis=%s\npatch=%s\n' "$version" "$ref" "$requested" "$patch_digest" > "$stamp"
echo "Only the upstream Monogram MTProto native module was compiled; GPL terms apply to the opt-in build."
