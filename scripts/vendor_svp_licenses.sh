#!/usr/bin/env bash
# Bundle exact Cargo dependency notices with the Android asset, including git workspace licenses.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/licenses/open-svpflow"
mkdir -p "$OUT"
# Actions runners share anonymous GitHub rate limits. Authenticate the fixed
# GitHub endpoints below when a token is available; local builds remain optional.
github_headers=()
if [[ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]]; then
    github_headers=(-H "Authorization: Bearer ${GH_TOKEN:-$GITHUB_TOKEN}")
fi
github_download() {
    local url="$1"
    shift
    if ! curl --fail --silent --show-error --location --retry 3 \
        "${github_headers[@]}" "$url" "$@"; then
        echo "SVP license download failed: $url (check GitHub access/rate limits)" >&2
        return 1
    fi
}
metadata="$(cargo metadata --manifest-path "$ROOT/native/ambientsvp/Cargo.toml" --format-version 1 --locked --filter-platform aarch64-linux-android)"
printf '%s' "$metadata" | jq -r '.packages[] | [.name,.version,.license,.repository] | @tsv' > "$OUT/dependencies.tsv"
while IFS=$'\t' read -r name version manifest; do
    [[ "$name" == ambientsvp ]] && continue
    dir="$(dirname "$manifest")"
    dest="$OUT/$name-$version"
    mkdir -p "$dest"
    found=0
    # hexf's author dedicates it to CC0 but does not ship a separate license file.
    if [[ "$name" == hexf-parse && "$version" == 0.2.1 ]]; then
        cp "$ROOT/native/ambientsvp/licenses/CC0-1.0.txt" "$dest/CC0-1.0.txt"
        found=1
    fi
    # wasm_sync declares MIT OR Apache-2.0 in its pinned manifest, with no
    # license/notice file in that repository. Distribute under the Apache option.
    if [[ "$name" == wasm_sync && "$version" == 0.1.2 ]]; then
        cp "$ROOT/native/ambientsvp/OPEN-SVPFLOW-LICENSE" "$dest/LICENSE-APACHE"
        found=1
    fi
    for level in 0 1 2; do
        while IFS= read -r file; do cp "$file" "$dest/$(basename "$file")"; found=1; done < <(
            find "$dir" -maxdepth 1 -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' -o -iname 'unlicense*' \)
        )
        [[ "$found" == 1 ]] && break
        dir="$(dirname "$dir")"
    done
    if [[ "$found" == 0 && -f "$(dirname "$manifest")/.cargo_vcs_info.json" ]]; then
        # Some crates omit their workspace LICENSE from the published archive.
        # Fetch only the exact publish commit, never a moving main/master branch.
        revision="$(jq -r '.git.sha1' "$(dirname "$manifest")/.cargo_vcs_info.json")"
        repository="$(printf '%s' "$metadata" | jq -r --arg name "$name" --arg version "$version" '.packages[] | select(.name==$name and .version==$version) | .repository')"
        [[ "$repository" == https://github.com/* ]] || { echo "License repository needs review: $name" >&2; exit 1; }
        repo="$(printf '%s' "${repository#https://github.com/}" | cut -d/ -f1,2)"
        repo="${repo%.git}"
        # Already cached exact-version notices make subsequent builds offline.
        if [[ -n "$(find "$dest" -maxdepth 1 -type f)" ]]; then found=1; else
            tree="$(github_download "https://api.github.com/repos/$repo/git/trees/$revision")"
            while IFS= read -r file; do
                [[ -n "$file" ]] || continue
                temporary="$(mktemp "$dest/.download-XXXXXX")"
                if ! github_download "https://raw.githubusercontent.com/$repo/$revision/$file" -o "$temporary"; then
                    rm -f "$temporary"
                    exit 1
                fi
                [[ -s "$temporary" ]] || { rm -f "$temporary"; echo "Empty license: $name $file" >&2; exit 1; }
                mv "$temporary" "$dest/$file"
                found=1
            done < <(printf '%s' "$tree" | jq -r '.tree[] | select(.type=="blob") | .path | select(test("^(license|copying|notice|unlicense)";"i"))')
        fi
    fi
    [[ "$found" == 1 ]] || { echo "Missing bundled license: $name $version" >&2; exit 1; }
done < <(printf '%s' "$metadata" | jq -r '.packages[] | [.name,.version,.manifest_path] | @tsv')
cp "$ROOT/native/ambientsvp/NOTICE.txt" "$OUT/NOTICE.txt"
