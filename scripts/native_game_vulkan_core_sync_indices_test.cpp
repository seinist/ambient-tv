// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkancoresyncindices.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>

using Indices = libretrodroid::VulkanCoreSyncIndices;
int main() {
    unsigned runs = 0;
    for (unsigned i = 0; i < 10000; ++i) {
        Indices indices;
        assert(indices.mask() == 1 && indices.index() == 0 && !indices.begin());
        assert(!indices.open(0) && !indices.open(3));
        for (unsigned count : {1u, 2u}) {
            assert(indices.open(count));
            const unsigned mask = (1u << count) - 1u;
            assert(indices.mask() == mask && indices.index() == 0);
            assert(!indices.open(3 - count));
            for (unsigned frame = 0; frame < 8; ++frame) {
                const unsigned index = frame % count;
                assert(indices.begin() && indices.index() == index);
                // Multiple video callbacks, duplicate frames and core queries
                // do not change the index during one retro_run invocation.
                for (unsigned query = 0; query < 4; ++query) {
                    assert(!indices.begin());
                    assert(indices.index() == index && indices.mask() == mask);
                }
                indices.cancel(); // failed pre-run wait: retry the same index
                assert(indices.begin() && indices.index() == index);
                indices.finish();
                assert(indices.index() == index && indices.mask() == mask);
                indices.finish(); // repeated end cannot advance a second time
                ++runs;
            }
            indices.resetAfterShutdown();
            assert(indices.mask() == 1 && indices.index() == 0 && !indices.begin());
        }
    }
    std::printf("PASS %u stable core sync-index run cycles\n", runs);
}
