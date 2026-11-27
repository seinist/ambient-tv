#!/usr/bin/env bash
# Shared provenance format for generation and application of SVP PGO profiles.
# Source and ABI arguments are already validated by the respective callers.
svp_pgo_metadata() {
    local source="$1" abi="$2" profile="$3" base_flags="$4"
    [[ -s "$profile" && -r "$ANDROID_NDK_HOME/source.properties" ]] || return 1
    printf '%s\n' \
        "format=2" \
        "source=$source" \
        "rustc=$(rustc -vV | sha256sum | cut -d ' ' -f1)" \
        "ndk=$(sha256sum "$ANDROID_NDK_HOME/source.properties" | cut -d ' ' -f1)" \
        "flags=$(printf '%s' "$base_flags" | sha256sum | cut -d ' ' -f1)" \
        "api=29" \
        "abi=$abi" \
        "sha256=$(sha256sum "$profile" | cut -d ' ' -f1)"
}
