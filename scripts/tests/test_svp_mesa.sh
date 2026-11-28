#!/usr/bin/env bash
# All host-compatible ignored tests, executed in independent CI shards.
# A failure is reported without preventing the remaining tests in a shard.
set -euo pipefail
cd "$(dirname "$0")/../.."
cargo_args=(+stable test --manifest-path native/ambientsvp/Cargo.toml --release --locked --lib)
export AMBIENTSVP_TEST_SOFTWARE_VULKAN=1
group="${1:-}"
case "$group" in
  math_motion)
    tests=(
      'resident_vulkan::tests::resident_upstream_subpixel_filters_match_phase_planes'
      'resident_vulkan::synthesis_tests::coverage_and_sad_masks_match_upstream'
      'resident_vulkan::synthesis_tests::all_synthesis_equations_match_reference'
      'resident::tests::resident_motion_matches_unpruned_scalar_search'
      'motion::tests::vulkan_candidate_costs_match_cpu'
      'motion::tests::vulkan_super_phase_and_pyramid_costs_match_cpu'
      'motion_select::tests::vulkan_ordered_selection_matches_cpu'
      'motion_select::tests::chained_cost_and_selection_without_intermediate_readback'
      'motion_select::tests::selected_complete_fields_enter_upstream_smoother'
      'motion_search::tests::hierarchical_search_produces_complete_valid_fields'
    ) ;;
  held_benchmark)
    tests=(
      'resident_vulkan::gpu_tests::held_cache_and_submission_benchmark'
      'resident_vulkan::gpu_tests::held_cache_and_submission_benchmark|23'
    ) ;;
  shared_device)
    tests=(
      'resident_vulkan::gpu_tests::native_resident_commands_keep_images_on_shared_device'
    ) ;;
  streaming)
    tests=(
      'pipelining_tests::experimental_hierarchical_motion_reaches_streaming_synthesis'
      'pipelining_tests::known_translation_quality_compares_upstream_and_experimental'
      'pipelining_tests::translation_and_occlusion_quality_matrix'
      'pipelining_tests::dual_output_matches_single_and_drops_prefetch_on_seek'
      'pipelining_tests::adjacent_ready_source_pairs_match_serial_output'
      'pipelining_tests::compact_motion_fields_match_float_fields'
      'pipelining_tests::complete_output_overwrite_matches_clear_on_unaligned_planes'
    ) ;;
  uploads)
    tests=(
      'gpu::tests::hd_staged_input_is_bounded_and_rejected_index_can_be_reused'
      'gpu::tests::texture_upload_round_trip_at_video_resolutions'
    ) ;;
  *) echo "Unknown Mesa test group: $group" >&2; exit 2 ;;
esac
# Exact names must exist: cargo otherwise succeeds with zero tests.
inventory="$(cargo "${cargo_args[@]}" -- --ignored --list)"
failed=()
for spec in "${tests[@]}"; do
    name="${spec%%|*}"
    if ! grep -Fxq "$name: test" <<<"$inventory"; then
        echo "::error::Missing Vulkan test: $name"
        failed+=("$spec (missing)")
        continue
    fi
    echo "::group::$spec"
    if [[ "$spec" == *'|'* ]]; then
        if ! AMBIENT_SVP_BENCH_ALGORITHM="${spec#*|}" cargo "${cargo_args[@]}" "$name" -- --ignored --exact --nocapture; then
            failed+=("$spec")
        fi
    elif ! cargo "${cargo_args[@]}" "$name" -- --ignored --exact --nocapture; then
        failed+=("$spec")
    fi
    echo "::endgroup::"
done
if (("${#failed[@]}" != 0)); then
    printf '::error::Failed Vulkan test: %s\n' "${failed[@]}"
    exit 1
fi
