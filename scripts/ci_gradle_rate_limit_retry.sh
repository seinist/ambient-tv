#!/usr/bin/env bash
# Retry only explicit Maven/HTTP rate-limit failures, never arbitrary build failures.
set -euo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
for attempt in 1 2 3; do
    set +e
    ./gradlew "$@" 2>&1 | tee "$log"
    result=( "${PIPESTATUS[@]}" )
    set -e
    status="${result[0]}"
    if (( result[1] != 0 )); then
        exit "${result[1]}"
    fi
    if (( status == 0 )); then
        exit 0
    fi
    if (( attempt == 3 )) || ! grep -Eq 'Received status code 429 from server|HTTP (response code: )?429|429 Too Many Requests' "$log"; then
        exit "$status"
    fi
    # Leave enough time for the shared Maven Central rate limit to recover.
    delay=$((attempt * 30 + RANDOM % 15))
    printf '::warning::Gradle HTTP 429; retry %s/3 after %ss\n' "$((attempt + 1))" "$delay"
    sleep "$delay"
done
