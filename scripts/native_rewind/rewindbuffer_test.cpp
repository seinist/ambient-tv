#include "rewindbuffer.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

// Only production operations run with tracking enabled. Test model allocations
// are outside those scopes, so they cannot conceal per-frame codec allocations.
namespace allocation_probe {
bool enabled = false;
size_t calls = 0;
size_t bytes = 0;
}

[[gnu::noinline]] void* operator new(std::size_t size) {
    void* result = std::malloc(size ? size : 1);
    if (!result) throw std::bad_alloc();
    if (allocation_probe::enabled) {
        ++allocation_probe::calls;
        allocation_probe::bytes += size;
    }
    return result;
}
[[gnu::noinline]] void* operator new[](std::size_t size) { return ::operator new(size); }
[[gnu::noinline]] void operator delete(void* pointer) noexcept { std::free(pointer); }
[[gnu::noinline]] void operator delete[](void* pointer) noexcept { std::free(pointer); }
[[gnu::noinline]] void operator delete(void* pointer, std::size_t) noexcept { std::free(pointer); }
[[gnu::noinline]] void operator delete[](void* pointer, std::size_t) noexcept { std::free(pointer); }

using libretrodroid::RewindBuffer;
using Bytes = std::vector<uint8_t>;
constexpr size_t MiB = 1024 * 1024;

[[noreturn]] static void fail(const char* expression, int line) {
    std::cerr << "FAILED at line " << line << ": " << expression << '\n';
    std::exit(1);
}
#define CHECK(expression) do { if (!(expression)) fail(#expression, __LINE__); } while (false)

class AllocationScope {
public:
    AllocationScope() {
        CHECK(!allocation_probe::enabled);
        allocation_probe::enabled = true;
        allocation_probe::calls = 0;
        allocation_probe::bytes = 0;
    }
    ~AllocationScope() { allocation_probe::enabled = false; }
};

static bool push(RewindBuffer& buffer, const uint8_t* data, size_t size, uint64_t span = 1) {
    bool result;
    {
        AllocationScope probe;
        result = buffer.push(data, size, span);
        CHECK(allocation_probe::calls == 0);
    }
    return result;
}
static bool push(RewindBuffer& buffer, const Bytes& state, uint64_t span = 1) {
    return push(buffer, state.data(), state.size(), span);
}
static bool pop(RewindBuffer& buffer, uint8_t* data, size_t* size) {
    bool result;
    {
        AllocationScope probe;
        result = buffer.pop(data, size);
        CHECK(allocation_probe::calls == 0);
    }
    return result;
}
static bool discard(RewindBuffer& buffer) {
    bool result;
    {
        AllocationScope probe;
        result = buffer.discard();
        CHECK(allocation_probe::calls == 0);
    }
    return result;
}
static void clear(RewindBuffer& buffer) {
    AllocationScope probe;
    buffer.clear();
    CHECK(allocation_probe::calls == 0);
}

static uint32_t nextRandom(uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}
static void randomize(Bytes& state, uint32_t seed) {
    for (uint8_t& value : state) value = static_cast<uint8_t>(nextRandom(seed));
}
static Bytes randomState(size_t size, uint32_t seed) {
    Bytes result(size);
    randomize(result, seed);
    return result;
}
static void checkMetrics(const RewindBuffer& buffer, size_t budget) {
    CHECK(buffer.getMemoryUsage() <= budget);
    CHECK(buffer.getValidCount() <= buffer.getCapacity());
    CHECK(buffer.getStoredBytes() <= buffer.getMemoryUsage());
    CHECK(buffer.getUsage() >= 0.0f && buffer.getUsage() <= 1.0f);
    if (buffer.getValidCount() <= 1) CHECK(buffer.getHistoryFrames() == 0);
}
static void popEquals(RewindBuffer& buffer, const Bytes& expected, Bytes& output) {
    size_t size = output.size();
    CHECK(pop(buffer, output.data(), &size));
    CHECK(size == expected.size());
    CHECK(std::equal(expected.begin(), expected.end(), output.begin()));
}

static void emptyAndInvalidOperations() {
    constexpr size_t budget = 4096;
    RewindBuffer buffer(8, 257, budget, 1200);
    Bytes state = randomState(257, 12345);
    Bytes output(257, 0xa5);
    size_t outputSize = output.size();
    CHECK(buffer.getValidCount() == 0);
    CHECK(buffer.getHistoryFrames() == 0);
    CHECK(!pop(buffer, output.data(), &outputSize));
    CHECK(!discard(buffer));
    CHECK(!push(buffer, nullptr, 257));
    CHECK(!push(buffer, state.data(), 0));
    CHECK(!push(buffer, state.data(), 258));
    CHECK(push(buffer, state));
    CHECK(buffer.getValidCount() == 1 && buffer.getHistoryFrames() == 0);
    CHECK(!push(buffer, state, 0));
    CHECK(!pop(buffer, nullptr, &outputSize));
    CHECK(!pop(buffer, output.data(), nullptr));
    CHECK(buffer.getValidCount() == 1);
    popEquals(buffer, state, output);
    CHECK(buffer.getValidCount() == 0);
    CHECK(!discard(buffer));
    clear(buffer);
    clear(buffer);
    CHECK(push(buffer, state));
    popEquals(buffer, state, output);
    checkMetrics(buffer, budget);
}

static void constructorAndBudgetBoundaries() {
    const auto invalid = [](size_t slots, size_t size, size_t budget) {
        bool rejected = false;
        try { RewindBuffer buffer(slots, size, budget, 1200); }
        catch (const std::invalid_argument&) { rejected = true; }
        CHECK(rejected);
    };
    invalid(0, 16, 4096);
    invalid(1, 16, 4096);
    invalid(4, 0, 4096);
    invalid(4, 128, 0);
    invalid(4, 128, 128);
    invalid(std::numeric_limits<size_t>::max(), 128, MiB);
    invalid(8, std::numeric_limits<size_t>::max(), MiB);
    // The constructors must reject overflow before asking the allocator for a
    // huge array, including values that would overflow a 32-bit Android build.
    invalid(static_cast<size_t>(UINT32_MAX), 16, MiB);
    invalid(8, static_cast<size_t>(UINT32_MAX), MiB);

    constexpr size_t budget = 64 * MiB;
    {
        AllocationScope probe;
        RewindBuffer buffer(1201, 801 * 1024, budget, 1200);
        CHECK(allocation_probe::bytes + sizeof(buffer) <= budget);
        CHECK(buffer.getMemoryUsage() <= budget);
    }

    // Discover the exact minimum from the public constructor contract, without
    // repeating the implementation's private metadata-size formula.
    size_t low = 0;
    size_t high = 64 * 1024;
    while (low + 1 < high) {
        const size_t midpoint = low + (high - low) / 2;
        try {
            RewindBuffer buffer(3, 257, midpoint, 0);
            high = midpoint;
        } catch (const std::invalid_argument&) {
            low = midpoint;
        }
    }
    invalid(3, 257, high - 1);
    RewindBuffer minimal(3, 257, high, 0);
    Bytes a = randomState(257, 11);
    Bytes b = randomState(257, 17);
    Bytes c = randomState(257, 29);
    Bytes output(257);
    CHECK(push(minimal, a));
    CHECK(push(minimal, b));
    CHECK(minimal.getValidCount() >= 2);
    CHECK(push(minimal, c));
    CHECK(minimal.getValidCount() >= 2);
    popEquals(minimal, c, output);
    popEquals(minimal, b, output);
    checkMetrics(minimal, high);
}

static void everyShortSizeAndIdenticalStates() {
    const size_t lengths[] = {1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33,
                             63, 64, 65, 127, 128, 129, 255, 256, 257, 1023};
    for (size_t length : lengths) {
        RewindBuffer buffer(64, length, MiB, 0);
        std::deque<Bytes> expected;
        Bytes state = randomState(length, static_cast<uint32_t>(length + 1));
        Bytes output(length);
        for (size_t frame = 0; frame < 40; ++frame) {
            if (frame % 4 == 0) randomize(state, static_cast<uint32_t>(frame + 37));
            if (frame % 4 == 1) state[frame % length] ^= 0x81;
            // Other frames are intentionally identical: zero deltas still
            // represent distinct instants and must retain their frame spans.
            CHECK(push(buffer, state));
            expected.push_back(state);
            CHECK(buffer.getValidCount() == expected.size());
            CHECK(buffer.getHistoryFrames() == expected.size() - 1);
        }
        while (!expected.empty()) {
            popEquals(buffer, expected.back(), output);
            expected.pop_back();
            CHECK(buffer.getValidCount() == expected.size());
            CHECK(buffer.getHistoryFrames() == (expected.empty() ? 0 : expected.size() - 1));
        }
    }
}

struct TimedState {
    Bytes bytes;
    uint64_t time;
};
static uint64_t historyFrames(const std::deque<TimedState>& expected) {
    return expected.empty() ? 0 : expected.back().time - expected.front().time;
}
static void exactFrameSpansAndTimeEviction() {
    constexpr size_t slots = 7;
    constexpr uint64_t frameLimit = 12;
    RewindBuffer buffer(slots, 513, MiB, frameLimit);
    std::deque<TimedState> expected;
    Bytes state = randomState(513, 877);
    Bytes output(513);
    uint64_t time = 0;
    for (unsigned frame = 0; frame < 200; ++frame) {
        const uint64_t span = 1u << (frame % 3); // Normal, catch-up and fast-forward frames.
        if (frame) time += span;
        state[frame % state.size()] ^= static_cast<uint8_t>(frame + 1);
        CHECK(push(buffer, state, span));
        expected.push_back({state, time});
        while (expected.size() > slots || historyFrames(expected) > frameLimit) expected.pop_front();
        CHECK(buffer.getValidCount() == expected.size());
        CHECK(buffer.getHistoryFrames() == historyFrames(expected));
    }
    while (!expected.empty()) {
        if (expected.size() % 2) popEquals(buffer, expected.back().bytes, output);
        else CHECK(discard(buffer));
        expected.pop_back();
        CHECK(buffer.getValidCount() == expected.size());
        CHECK(buffer.getHistoryFrames() == historyFrames(expected));
    }
    CHECK(push(buffer, state, 1));
    CHECK(push(buffer, state, frameLimit + 1));
    CHECK(buffer.getValidCount() == 1 && buffer.getHistoryFrames() == 0);
    popEquals(buffer, state, output);

    RewindBuffer unlimited(7, 513, MiB, 0);
    CHECK(push(unlimited, state));
    CHECK(push(unlimited, state, UINT64_MAX));
    CHECK(unlimited.getHistoryFrames() == UINT64_MAX);
    CHECK(!push(unlimited, state, 1)); // No uint64 wraparound may mutate the chain.
    CHECK(unlimited.getValidCount() == 2 && unlimited.getHistoryFrames() == UINT64_MAX);
    popEquals(unlimited, state, output);
    CHECK(unlimited.getHistoryFrames() == 0);
}

static void rawFallbackRingWrapAndEviction() {
    constexpr size_t size = 1025;
    constexpr size_t budget = 64 * 1024;
    RewindBuffer buffer(128, size, budget, 0);
    std::deque<TimedState> expected;
    Bytes state(size);
    Bytes output(size);
    size_t evictions = 0;
    bool storedDensePayloads = false;
    uint64_t time = 0;
    for (unsigned frame = 0; frame < 1000; ++frame) {
        randomize(state, 0x75193u + frame * 1367u);
        const uint64_t span = 1 + frame % 3;
        if (frame) time += span;
        CHECK(push(buffer, state, span));
        expected.push_back({state, time});
        while (expected.size() > buffer.getValidCount()) {
            expected.pop_front();
            ++evictions;
        }
        CHECK(expected.size() >= (frame == 0 ? 1u : 2u));
        CHECK(buffer.getHistoryFrames() == historyFrames(expected));
        if (frame > 10) storedDensePayloads |= buffer.getStoredBytes() >= 8 * size;
        checkMetrics(buffer, budget);
        // Interleave rewinds and fresh branches while the physical byte ring
        // wraps, rather than only filling and draining once.
        if (frame % 17 == 16) {
            popEquals(buffer, expected.back().bytes, output);
            expected.pop_back();
            CHECK(discard(buffer));
            expected.pop_back();
            CHECK(buffer.getHistoryFrames() == historyFrames(expected));
            time = expected.empty() ? 0 : expected.back().time;
        }
    }
    CHECK(evictions > 100);
    CHECK(storedDensePayloads);
    while (!expected.empty()) {
        popEquals(buffer, expected.back().bytes, output);
        expected.pop_back();
        CHECK(buffer.getHistoryFrames() == historyFrames(expected));
    }
}

static void rewindThenBranchAndClear() {
    constexpr size_t size = 1027;
    RewindBuffer buffer(64, size, MiB, 0);
    std::deque<Bytes> expected;
    Bytes state = randomState(size, 123);
    Bytes output(size);
    for (unsigned frame = 0; frame < 32; ++frame) {
        state[frame * 31 % size] ^= static_cast<uint8_t>(frame + 1);
        CHECK(push(buffer, state));
        expected.push_back(state);
    }
    for (int count = 0; count < 5; ++count) {
        popEquals(buffer, expected.back(), output);
        expected.pop_back();
    }
    state = output; // Branch from the state that the emulator actually restored.
    state[0] ^= 0xff;
    state[size - 1] ^= 0x53;
    CHECK(push(buffer, state, 2));
    expected.push_back(state);
    CHECK(buffer.getHistoryFrames() == expected.size()); // One two-frame link.
    while (!expected.empty()) {
        popEquals(buffer, expected.back(), output);
        expected.pop_back();
    }
    CHECK(push(buffer, state));
    CHECK(push(buffer, state));
    clear(buffer);
    CHECK(buffer.getValidCount() == 0 && buffer.getHistoryFrames() == 0);
    CHECK(buffer.getStoredBytes() == 0);
    randomize(state, 9817);
    CHECK(push(buffer, state));
    popEquals(buffer, state, output);
    CHECK(buffer.getValidCount() == 0); // No pre-load or abandoned future state survives.
}

static void mixedCompressedRingWrap() {
    constexpr size_t maximum = 1031;
    constexpr size_t budget = 8 * 1024;
    RewindBuffer buffer(96, maximum, budget, 0);
    std::deque<TimedState> expected;
    Bytes state = randomState(maximum, 6631);
    Bytes output(maximum);
    uint32_t rng = 0xdde721u;
    size_t compressedWrapEvictions = 0;
    for (unsigned operation = 0; operation < 4000; ++operation) {
        if (expected.empty() || nextRandom(rng) % 10 < 7) {
            if (!expected.empty()) state = expected.back().bytes;
            if (operation % 19 == 0) {
                // Exercise raw links between different-sized states too.
                state.resize(1 + nextRandom(rng) % maximum);
                randomize(state, nextRandom(rng));
            } else {
                // Alternating zero/literal runs create records of different
                // lengths, so both headers and payloads cross the byte-ring end.
                for (unsigned change = 0; change < 1 + operation % 12; ++change) {
                    state[nextRandom(rng) % state.size()] ^= static_cast<uint8_t>(1 + operation % 255);
                }
            }
            const uint64_t span = 1 + nextRandom(rng) % 4;
            const uint64_t time = expected.empty() ? 0 : expected.back().time + span;
            CHECK(push(buffer, state, span));
            expected.push_back({state, time});
            while (expected.size() > buffer.getValidCount()) {
                expected.pop_front();
                ++compressedWrapEvictions;
            }
        } else {
            if (operation % 3 == 0) CHECK(discard(buffer));
            else popEquals(buffer, expected.back().bytes, output);
            expected.pop_back();
        }
        CHECK(buffer.getValidCount() == expected.size());
        CHECK(buffer.getHistoryFrames() == historyFrames(expected));
        checkMetrics(buffer, budget);
    }
    CHECK(compressedWrapEvictions > 100);
    while (!expected.empty()) {
        popEquals(buffer, expected.back().bytes, output);
        expected.pop_back();
        CHECK(buffer.getHistoryFrames() == historyFrames(expected));
    }
}

static void changingSnapshotSizesAndLongRuns() {
    constexpr size_t maximum = 1024 * 1024 + 3;
    RewindBuffer buffer(32, maximum, 32 * MiB, 0);
    const size_t lengths[] = {maximum, 1, 17, maximum, 257, 65536, maximum};
    std::deque<Bytes> expected;
    Bytes output(maximum);
    for (size_t length : lengths) {
        Bytes state = randomState(length, static_cast<uint32_t>(length + expected.size() + 1));
        CHECK(push(buffer, state));
        expected.push_back(state);
    }
    while (!expected.empty()) {
        popEquals(buffer, expected.back(), output);
        expected.pop_back();
    }

    Bytes state(maximum, 0);
    const size_t positions[] = {0, 7, 8, 15, 16, 127, 128, 65535 * 8 - 1,
                                65535 * 8, 65536 * 8, maximum - 1};
    CHECK(push(buffer, state));
    expected.push_back(state);
    for (size_t position : positions) {
        state[position] ^= 0xa7;
        CHECK(push(buffer, state));
        expected.push_back(state);
    }
    while (!expected.empty()) {
        popEquals(buffer, expected.back(), output);
        expected.pop_back();
    }
}

// XOR mutations are reversible, so the long-history test needs only two SNES
// snapshots, not a gigabyte of reference copies. Regions outside those touched
// below remain random and must still be reconstructed byte-for-byte.
static void mutateSnesFrame(Bytes& state, unsigned frame) {
    uint32_t seed = 0x9e3779b9u ^ (frame * 2654435761u);
    for (unsigned region = 0; region < 2; ++region) {
        const size_t start = nextRandom(seed) % (state.size() - 4096);
        for (size_t byte = 0; byte < 4096; ++byte) {
            state[start + byte] ^= static_cast<uint8_t>(nextRandom(seed));
        }
    }
    for (unsigned reg = 0; reg < 16; ++reg) {
        const size_t index = nextRandom(seed) % state.size();
        state[index] ^= static_cast<uint8_t>(nextRandom(seed));
    }
}
static void snesTwentySecondHistoryWithinBudget() {
    constexpr size_t stateSize = 801 * 1024 + 8192;
    constexpr size_t totalBudget = 64 * MiB;
    constexpr uint64_t retainedFrames = 1200;
    // Include the frontend's capture scratch in the 64 MiB total, matching the
    // integration rather than granting an extra full snapshot to this test.
    constexpr size_t bufferBudget = totalBudget - stateSize;
    RewindBuffer buffer(retainedFrames + 1, stateSize, bufferBudget, retainedFrames);
    Bytes state = randomState(stateSize, 0x82191u);
    Bytes output(stateSize);
    CHECK(buffer.getMemoryUsage() + state.size() <= totalBudget);
    CHECK(push(buffer, state));
    const auto begin = std::chrono::steady_clock::now();
    constexpr unsigned recordedFrames = 1600;
    for (unsigned frame = 1; frame <= recordedFrames; ++frame) {
        mutateSnesFrame(state, frame);
        CHECK(push(buffer, state));
        const uint64_t history = std::min<uint64_t>(frame, retainedFrames);
        CHECK(buffer.getHistoryFrames() == history);
        CHECK(buffer.getValidCount() == history + 1);
        checkMetrics(buffer, bufferBudget);
    }
    const auto elapsed = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - begin).count();
    CHECK(buffer.getHistoryFrames() == 20 * 60);
    CHECK(buffer.getStoredBytes() < 20 * MiB);
    const size_t compressedBytes = buffer.getStoredBytes();
    for (unsigned frame = recordedFrames; frame >= recordedFrames - retainedFrames; --frame) {
        popEquals(buffer, state, output);
        mutateSnesFrame(state, frame);
    }
    CHECK(buffer.getValidCount() == 0 && buffer.getHistoryFrames() == 0);
    std::cout << "SNES-like fixture: 20 seconds retained; " << compressedBytes
              << " history bytes, " << elapsed / recordedFrames
              << " ms per capture including fixture mutations (host measurement)\n";
}

static bool peek(const RewindBuffer& buffer, size_t steps, uint8_t* data, size_t* size) {
    bool result;
    {
        AllocationScope probe;
        result = buffer.peek(steps, data, size);
        CHECK(allocation_probe::calls == 0);
    }
    return result;
}

/** Seeking in the paused timeline reads any stored state without consuming history. */
static void nonDestructivePeekAndIncrementalSeek() {
    constexpr size_t budget = 256 * 1024;
    RewindBuffer buffer(96, 1031, budget, 0);
    std::deque<Bytes> expected;
    std::deque<uint64_t> spans;
    Bytes output(1031);
    for (unsigned frame = 0; frame < 300; ++frame) {
        Bytes state = expected.empty() ? randomState(1031, 77) : expected.back();
        // Mostly small changes (compressed records), sometimes a whole new state (raw records)
        // and a different size, so peeks cross raw, compressed and size-changing records.
        if (frame % 41 == 40) state = randomState(1031, 9000 + frame);
        else for (unsigned i = 0; i < 9; ++i) state[(frame * 131 + i * 17) % state.size()] ^= 0x5a;
        if (frame % 53 == 52) state.resize(1031 - frame % 7);
        const uint64_t span = 1 + frame % 4;
        CHECK(push(buffer, state, span));
        expected.push_back(state);
        if (expected.size() > 1) spans.push_back(span);
        while (expected.size() > buffer.getValidCount()) { expected.pop_front(); spans.pop_front(); }
        checkMetrics(buffer, budget);
    }
    const size_t records = buffer.getValidCount() - 1;
    const uint64_t history = buffer.getHistoryFrames();
    CHECK(records > 20);
    // Every depth matches the state pushed that many records earlier.
    for (size_t steps = 0; steps <= records; ++steps) {
        size_t size = output.size();
        CHECK(peek(buffer, steps, output.data(), &size));
        const Bytes& want = expected[expected.size() - 1 - steps];
        CHECK(size == want.size());
        CHECK(std::equal(want.begin(), want.end(), output.begin()));
        if (steps < records) CHECK(buffer.recordFrames(steps) == spans[spans.size() - 1 - steps]);
    }
    CHECK(buffer.recordFrames(records) == 0);
    size_t ignored = output.size();
    CHECK(!peek(buffer, records + 1, output.data(), &ignored));
    // Nothing was consumed.
    CHECK(buffer.getValidCount() == records + 1);
    CHECK(buffer.getHistoryFrames() == history);
    // Continuing a peek further back equals a fresh peek; a step forward is rejected.
    size_t size = output.size();
    CHECK(peek(buffer, 3, output.data(), &size));
    size_t from = 3;
    for (size_t to : {3u, 7u, 8u, 20u, static_cast<unsigned>(records)}) {
        CHECK(buffer.peekFurther(from, to, output.data(), &size));
        const Bytes& want = expected[expected.size() - 1 - to];
        CHECK(size == want.size());
        CHECK(std::equal(want.begin(), want.end(), output.begin()));
        from = to;
    }
    CHECK(!buffer.peekFurther(records, 2, output.data(), &size));
    // The destructive path still returns the same states afterwards.
    while (!expected.empty()) {
        popEquals(buffer, expected.back(), output);
        expected.pop_back();
    }
    CHECK(buffer.getValidCount() == 0);
    size = output.size();
    CHECK(!peek(buffer, 0, output.data(), &size));
}

int main() {
    const struct { const char* name; void (*test)(); } tests[] = {
        {"empty and invalid operations", emptyAndInvalidOperations},
        {"constructor and budget boundaries", constructorAndBudgetBoundaries},
        {"short, odd and identical states", everyShortSizeAndIdenticalStates},
        {"frame spans and time eviction", exactFrameSpansAndTimeEviction},
        {"raw fallback, ring wrap and eviction", rawFallbackRingWrapAndEviction},
        {"rewind, branch and clear", rewindThenBranchAndClear},
        {"mixed compressed ring wrap", mixedCompressedRingWrap},
        {"changing sizes and long runs", changingSnapshotSizesAndLongRuns},
        {"SNES twenty-second bounded history", snesTwentySecondHistoryWithinBudget},
        {"non-destructive peek and incremental seek", nonDestructivePeekAndIncrementalSeek},
    };
    for (const auto& test : tests) {
        test.test();
        std::cout << "PASS: " << test.name << '\n';
    }
    std::cout << "All rewind contracts passed\n";
}
