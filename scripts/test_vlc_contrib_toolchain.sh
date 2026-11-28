#!/usr/bin/env bash
# Fast, real NDK checks for configure probes before the four expensive builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}}"
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Unsupported host" >&2; exit 1 ;;
esac
bin="$NDK/toolchains/llvm/prebuilt/$host/bin"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf '%s\n' 'static __thread int value;' 'int *ambient_tls_probe(void) { return &value; }' > "$tmp/tls.c"
printf '%s\n' 'int ambient_vpx_probe(void) { return 0; }' > "$tmp/vpx.c"
mkdir -p "$tmp/bin"
ln -s "$ROOT/scripts/vlc_ccache_compiler.sh" "$tmp/bin/clang"
ln -s "$ROOT/scripts/vlc_ccache_compiler.sh" "$tmp/bin/clang++"
export AMBIENT_VLC_CCACHE_ROOT="$tmp/cache"
for target in armv7a-linux-androideabi29 aarch64-linux-android29 i686-linux-android29 x86_64-linux-android29; do
    export AMBIENT_VLC_REAL_CLANG="$bin/$target-clang"
    flags=(-g -O2 -fPIC -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -DNDEBUG)
    for compiler in "$AMBIENT_VLC_REAL_CLANG" "$tmp/bin/clang"; do
        "$compiler" "${flags[@]}" -Werror -c "$tmp/tls.c" -o "$tmp/tls.o"
        "$compiler" -shared "$tmp/tls.o" -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o "$tmp/tls.so"
        "$compiler" "${flags[@]}" -Werror -c "$tmp/vpx.c" -o "$tmp/vpx.o"
    done
    "$tmp/bin/clang++" "${flags[@]}" -Werror -x c++ -c "$tmp/vpx.c" -o "$tmp/vpx-cxx.o"
    "$bin/llvm-readelf" -sW "$tmp/tls.so" | grep -E 'TLS.*value'
    "$bin/llvm-readelf" -lW "$tmp/tls.so" | awk '/^[[:space:]]*LOAD / {n++; if ($NF != "0x4000") bad=1} END {exit (n==0 || bad)}'
    echo "PASS: $target TLS, strict C/C++ probe and 16 KiB ELF link"
done
