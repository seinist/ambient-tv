#include "rewindtimeline.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <iostream>
#include <limits>
#include <vector>

using libretrodroid::RewindBuffer;
using libretrodroid::RewindTimeline;

[[noreturn]] static void fail(const char* expression, int line) {
    std::cerr << "FAILED at line " << line << ": " << expression << '\n';
    std::exit(1);
}
#define CHECK(expression) do { if (!(expression)) fail(#expression, __LINE__); } while (false)

static uint32_t randomNext(uint32_t& seed) {
    seed ^= seed << 13;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    return seed;
}

/** A toy core whose state stores an absolute emulated-frame index. */
class Core {
public:
    Core(uint64_t targetFrames = 120, uint64_t maxInterval = 15,
         size_t stateSize = 128, size_t budget = 64 * 1024)
        : buffer(static_cast<size_t>(targetFrames + 1), stateSize, budget, targetFrames),
          target(targetFrames), state(stateSize), output(stateSize) {
        CHECK(stateSize >= sizeof(current));
        timeline.configure(targetFrames, maxInterval);
    }

    void forward(uint64_t frames = 1, bool skipCapture = false) {
        if (frames == 0) {
            check();
            return;
        }
        if (!skipCapture && timeline.shouldCapture(buffer)) {
            if (dense) {
                uint32_t seed = static_cast<uint32_t>(current + 12345);
                for (auto& byte : state) byte = static_cast<uint8_t>(randomNext(seed));
            } else {
                std::fill(state.begin(), state.end(), 0);
            }
            std::memcpy(state.data(), &current, sizeof(current));
            CHECK(buffer.push(state.data(), state.size(), timeline.pendingFrames()));
            anchors.push_back(current);
            // Native may drop oldest records due to time, metadata or byte limits.
            while (anchors.size() > buffer.getValidCount()) anchors.pop_front();
            timeline.captured(buffer);
        }
        current += frames;
        if (buffer.getValidCount() != 0) timeline.advanced(frames);
        check();
    }

    void rewind(unsigned speed = 1) {
        CHECK(buffer.getValidCount() != 0);
        const uint64_t liveBefore = current;
        for (unsigned i = 1; i < speed && buffer.getValidCount() > 1; ++i) {
            CHECK(buffer.discard());
            anchors.pop_back();
        }
        const uint64_t historyBefore = buffer.getHistoryFrames();
        size_t size = output.size();
        CHECK(buffer.pop(output.data(), &size));
        CHECK(size == output.size());
        uint64_t restoredFrame = 0;
        std::memcpy(&restoredFrame, output.data(), sizeof(restoredFrame));
        CHECK(restoredFrame == anchors.back());
        anchors.pop_back();
        // A restored pre-run state executes one muted frame to reconstruct video.
        current = restoredFrame + 1;
        CHECK(current <= liveBefore);
        timeline.restored(buffer, historyBefore - buffer.getHistoryFrames());
        check();
    }

    void clear(bool restartCore = false) {
        buffer.clear();
        timeline.reset();
        anchors.clear();
        if (restartCore) current = 0;
        check();
    }

    uint64_t duration() const { return timeline.historyFrames(buffer); }

    void check() const {
        CHECK(buffer.getValidCount() == anchors.size());
        CHECK(timeline.captureInterval() >= 1);
        if (anchors.empty()) {
            CHECK(duration() == 0);
            CHECK(timeline.pendingFrames() == 0);
            return;
        }
        CHECK(current > anchors.back());
        CHECK(buffer.getHistoryFrames() == anchors.back() - anchors.front());
        CHECK(timeline.pendingFrames() == current - anchors.back());
        const uint64_t expected = current - (anchors.front() + 1);
        CHECK(duration() == std::min(target, expected));
    }

    RewindBuffer buffer;
    RewindTimeline timeline;
    uint64_t target;
    uint64_t current = 0;
    bool dense = false;
    std::vector<uint8_t> state;
    std::vector<uint8_t> output;
    std::deque<uint64_t> anchors;
};

static void initialStateAndFastForward() {
    Core core;
    CHECK(core.timeline.shouldCapture(core.buffer));
    core.forward(0);
    core.forward(1);
    CHECK(core.buffer.getValidCount() == 1);
    CHECK(core.duration() == 0);
    core.clear(true);
    core.forward(2);
    CHECK(core.buffer.getValidCount() == 1);
    CHECK(core.duration() == 1);
    core.rewind();
    CHECK(core.current == 1);
    CHECK(core.duration() == 0);
    CHECK(core.timeline.shouldCapture(core.buffer));
}

static void firstRewindNeverStepsForwardAndBranchKeepsItsDistance() {
    Core core;
    for (int i = 0; i < 10; ++i) core.forward();
    CHECK(core.current == 10 && core.duration() == 9);
    core.rewind();
    CHECK(core.current == 10 && core.duration() == 9);
    core.rewind();
    CHECK(core.current == 9 && core.duration() == 8);
    CHECK(core.timeline.pendingFrames() == 2);
    core.forward(3);
    CHECK(core.current == 12 && core.duration() == 11);
    core.rewind();
    CHECK(core.current == 10);
    core.rewind(100);
    CHECK(core.current == 1);
    CHECK(core.buffer.getValidCount() == 0);
    CHECK(core.duration() == 0);
}

static void variableBatchesSkippedCapturesAndZeroFrameSteps() {
    Core core;
    const uint64_t frames[] = {1, 0, 2, 3, 0, 1, 4, 2, 1, 0};
    for (unsigned round = 0; round < 40; ++round) {
        for (size_t i = 0; i < sizeof(frames) / sizeof(frames[0]); ++i) {
            core.forward(frames[i], i % 3 == 1);
        }
    }
    while (core.buffer.getValidCount() != 0) core.rewind(3);
    core.forward(2);
    CHECK(core.duration() == 1);
}

static void gapBeyondTargetReplacesOldHistoryAndClearRetainsConfiguration() {
    Core core(60, 15);
    for (int i = 0; i < 10; ++i) core.forward();
    core.forward(500, true);
    CHECK(core.duration() == 60);
    core.forward();
    CHECK(core.buffer.getValidCount() == 1);
    CHECK(core.duration() == 0);
    core.clear();
    CHECK(core.timeline.captureInterval() == 1);
    for (int i = 0; i < 100; ++i) core.forward();
    CHECK(core.duration() == 60);
}

static void captureFailuresClearHistoryUntilANewAnchorExists() {
    // Failed serialization, an invalid reported size and a rejected push all
    // take the same engine recovery path. Forward emulation must continue.
    for (unsigned failure = 0; failure < 3; ++failure) {
        Core core;
        for (int i = 0; i < 10; ++i) core.forward();
        CHECK(core.duration() > 0);
        if (failure == 1) {
            CHECK(!core.buffer.push(core.state.data(), core.state.size() + 1, core.timeline.pendingFrames()));
        } else if (failure == 2) {
            CHECK(!core.buffer.push(core.state.data(), core.state.size(), 0));
        }
        core.clear();
        const uint64_t before = core.current;
        core.forward(2, true);
        CHECK(core.current == before + 2);
        CHECK(core.buffer.getValidCount() == 0);
        CHECK(core.timeline.pendingFrames() == 0);
        CHECK(core.duration() == 0);
        core.forward();
        CHECK(core.buffer.getValidCount() == 1);
        CHECK(core.timeline.pendingFrames() == 1);
        CHECK(core.duration() == 0);
        core.forward(2);
        CHECK(core.duration() == 2);
        core.rewind(100);
        CHECK(core.current == before + 3);
        CHECK(core.duration() == 0);
    }
}

static void compressedAndIncompressibleIntervalsConverge() {
    Core core(120, 15, 4096, 64 * 1024);
    for (int i = 0; i < 500; ++i) core.forward();
    CHECK(core.timeline.captureInterval() == 1);
    CHECK(core.duration() == 120);
    core.dense = true;
    for (int i = 0; i < 1000; ++i) core.forward();
    CHECK(core.timeline.captureInterval() > 1);
    CHECK(core.timeline.captureInterval() <= 15);
    CHECK(core.duration() >= 100);
    core.rewind(5);
    core.forward(4);
    core.dense = false;
    for (int i = 0; i < 2000; ++i) core.forward();
    CHECK(core.timeline.captureInterval() == 1);
    CHECK(core.duration() == 120);
}

static void intervalLimitKeepsVeryLargeStatesResponsive() {
    Core core(120, 3, 8192, 32 * 1024);
    core.dense = true;
    for (int i = 0; i < 500; ++i) core.forward();
    CHECK(core.timeline.captureInterval() == 3);
    CHECK(core.duration() > 0 && core.duration() < 120);
    while (core.buffer.getValidCount() != 0) core.rewind();
}

static void randomizedTimelineMatchesAbsoluteFramePositions() {
    Core core(120, 15, 193, 16 * 1024);
    uint32_t seed = 0x2145ab39;
    for (int i = 0; i < 25000; ++i) {
        const uint32_t choice = randomNext(seed);
        if (choice % 53 == 0) core.clear(choice % 2 == 0);
        else if (choice % 4 == 0 && core.buffer.getValidCount() != 0) core.rewind(1 + choice % 6);
        else {
            core.dense = choice % 7 == 0;
            core.forward(choice % 5, choice % 17 == 0);
        }
    }
}

static void saturatingArithmeticCannotWrapTheTimeline() {
    Core core;
    core.forward(1);
    core.timeline.advanced(std::numeric_limits<uint64_t>::max());
    core.timeline.advanced(1);
    CHECK(core.timeline.pendingFrames() == std::numeric_limits<uint64_t>::max());
    CHECK(core.timeline.historyFrames(core.buffer) == core.target);
    core.timeline.restored(core.buffer, std::numeric_limits<uint64_t>::max());
    CHECK(core.timeline.pendingFrames() == std::numeric_limits<uint64_t>::max());
    CHECK(core.timeline.historyFrames(core.buffer) == core.target);
    core.clear();
    core.timeline.configure(0, 0);
    CHECK(core.timeline.captureInterval() == 1);
    CHECK(core.timeline.historyFrames(core.buffer) == 0);
}

static void expensiveCapturesYieldToFrameBudget() {
    RewindTimeline timeline;
    timeline.configure(120, 15);
    CHECK(timeline.captureInterval() == 1);
    timeline.observeFrameWork(4000000, 1000000, 16666666);
    CHECK(timeline.captureInterval() == 1);
    timeline.observeFrameWork(13000000, 8000000, 16666666);
    CHECK(timeline.captureInterval() >= 4);
    for (int i = 0; i < 64; ++i) timeline.observeFrameWork(17000000, 0, 16666666);
    CHECK(timeline.captureInterval() == 15);
    for (int i = 0; i < 128; ++i) timeline.observeFrameWork(3000000, 500000, 16666666);
    CHECK(timeline.captureInterval() == 1);
    timeline.observeFrameWork(UINT64_MAX, UINT64_MAX, 16666666);
    CHECK(timeline.captureInterval() == 15);
    timeline.reset();
    CHECK(timeline.captureInterval() == 1);
}

int main() {
    initialStateAndFastForward();
    firstRewindNeverStepsForwardAndBranchKeepsItsDistance();
    variableBatchesSkippedCapturesAndZeroFrameSteps();
    gapBeyondTargetReplacesOldHistoryAndClearRetainsConfiguration();
    captureFailuresClearHistoryUntilANewAnchorExists();
    compressedAndIncompressibleIntervalsConverge();
    intervalLimitKeepsVeryLargeStatesResponsive();
    randomizedTimelineMatchesAbsoluteFramePositions();
    saturatingArithmeticCannotWrapTheTimeline();
    expensiveCapturesYieldToFrameBudget();
    std::cout << "Rewind timeline: 10 integration tests passed (including 25000 randomized transitions).\n";
}
