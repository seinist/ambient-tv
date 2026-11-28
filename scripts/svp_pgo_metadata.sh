#!/usr/bin/env bash
# Shared provenance format for generation and application of SVP PGO profiles.
# Source and ABI arguments are already validated by the respective callers.
svp_pgo_metadata() {
    local source="$1" abi="$2" profile="$3" base_flags="$4"
    local compiler ndk flags digest
    [[ -s "$profile" && -r "$ANDROID_NDK_HOME/source.properties" ]] || return 1
    # Explicit checks: command substitution can disable errexit in Bash.
    compiler="$(rustc -vV)" || return 1
    [[ -n "$compiler" ]] || return 1
    compiler="$(printf '%s\n' "$compiler" | sha256sum | cut -d ' ' -f1)" || return 1
    ndk="$(sha256sum "$ANDROID_NDK_HOME/source.properties" | cut -d ' ' -f1)" || return 1
    flags="$(printf '%s' "$base_flags" | sha256sum | cut -d ' ' -f1)" || return 1
    digest="$(sha256sum "$profile" | cut -d ' ' -f1)" || return 1
    local hash
    for hash in "$compiler" "$ndk" "$flags" "$digest"; do
        [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    done
    printf '%s\n' "format=2" "source=$source" "rustc=$compiler" "ndk=$ndk" \
        "flags=$flags" "api=29" "abi=$abi" "sha256=$digest"
}
