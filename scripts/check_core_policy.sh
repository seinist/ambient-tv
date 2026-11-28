#!/usr/bin/env bash
# Shared gate for core build, release and diagnostic workflows.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
shopt -s nullglob
tests=(scripts/test_core_*.py)
(( ${#tests[@]} > 0 ))
python3 -m py_compile scripts/core_*.py "${tests[@]}" scripts/check_elf_pages.py scripts/validate_core_device_matrix.py scripts/run_core_benchmark.py scripts/benchmark_core_matrix.py
# Separate processes keep test monkeypatches and fixtures isolated.
for test in "${tests[@]}"; do
  python3 -m unittest discover -s scripts -p "${test##*/}"
done
python3 scripts/test_game_rewind.py
