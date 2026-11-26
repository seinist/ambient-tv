/*
 * Copyright (C) 2026 Ambient TV Contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef LIBRETRODROID_REWINDTIMELINE_H
#define LIBRETRODROID_REWINDTIMELINE_H

#include "rewindbuffer.h"

#include <algorithm>
#include <cstdint>
#include <limits>

namespace libretrodroid {

/**
 * GL-thread-only timing for states captured BEFORE running the next frame.
 * pending is the distance from the buffer's latest anchor to the live core.
 * Restoring an anchor runs one frame to reconstruct its picture, so the oldest
 * displayed position is one frame after the oldest stored state.
 *
 * No allocation, clock sampling or frame-rate assumptions. The caller publishes
 * historyFrames() atomically after each complete native step, never mid-capture.
 */
class RewindTimeline {
public:
    void configure(uint64_t targetFrames, uint64_t maxCaptureInterval) {
        target = std::max<uint64_t>(1, targetFrames);
        maximumInterval = std::min(target, std::max<uint64_t>(1, maxCaptureInterval));
        reset();
    }

    /** Reset after a load/restart/destroy; retain the configured duration limit. */
    void reset() {
        pending = 0;
        interval = 1;
        workInterval = 1;
        averageWorkNs = 0;
        averageCaptureNs = 0;
        peakCaptureNs = 0;
        deadlineInterval = 1;
        resetFrameObservation();
    }

    /** Call only when at least one forward frame will actually run. */
    bool shouldCapture(const RewindBuffer& buffer) const {
        return buffer.getValidCount() == 0 || pending >= captureInterval();
    }

    /** Call only after a successful push(data, size, pendingFrames()). */
    void captured(const RewindBuffer& buffer) {
        pending = 0;
        const size_t count = buffer.getValidCount();
        if (count < 2 || buffer.getStoredBytes() == 0) {
            interval = 1;
            return;
        }

        const size_t records = count - 1;
        const size_t stored = buffer.getStoredBytes();
        // Round the mean upward: the estimated capacity is conservative. This
        // division avoids target*bytes and capacity*records overflow on ARM32.
        const size_t meanBytes = stored / records + (stored % records != 0 ? 1 : 0);
        const size_t estimatedRecords = std::max<size_t>(1, std::min(
            buffer.getCapacity() - 1, buffer.getStorageCapacity() / meanBytes));
        const uint64_t capacity = static_cast<uint64_t>(estimatedRecords);
        const uint64_t desired = target / capacity + (target % capacity != 0 ? 1 : 0);
        interval = std::max<uint64_t>(1, std::min(maximumInterval, desired));
    }

    /** Start-to-start cadence sees the previous step's renderer tail and EGL swap too.
     * Only a capture-associated overrun raises the penalty; ordinary vsync waits,
     * batched catch-up frames and long lifecycle gaps are not charged as CPU work. */
    void observeFrameStart(uint64_t nowNs) {
        if (previousStartNs && nowNs > previousStartNs && previousBudgetNs) {
            const uint64_t elapsed = nowNs - previousStartNs;
            const bool continuous = elapsed <= previousBudgetNs * 8;
            const bool late = elapsed > previousBudgetNs + previousBudgetNs / 4;
            if (continuous && late && previousCaptured) {
                deadlineInterval = std::min(maximumInterval, deadlineInterval * 2);
                stableCycles = 0;
            } else if (continuous && !late && ++stableCycles >= 16) {
                deadlineInterval = std::max<uint64_t>(1, (deadlineInterval + 1) / 2);
                stableCycles = 0;
            } else if (!continuous || late) {
                stableCycles = 0;
            }
        }
        previousStartNs = nowNs;
        previousBudgetNs = 0;
        previousCaptured = false;
    }

    void resetFrameObservation() {
        previousStartNs = 0;
        previousBudgetNs = 0;
        previousCaptured = false;
        stableCycles = 0;
    }

    /**
     * Samples on the emulation thread, without counting the pacing sleep. Capture remains
     * on this thread because a libretro core may not be serialized concurrently with retro_run.
     * Bound its amortized cost to 1/8 frame (and at most half the observed spare time).
     * Spikes increase spacing immediately; recovery is smoothed to avoid oscillation.
     */
    void observeFrameWork(uint64_t workNs, uint64_t captureNs, uint64_t frameBudgetNs,
                          unsigned frames = 1) {
        if (!frameBudgetNs || !frames) return;
        // Keep cadence and workload separate: the former includes presentation waits.
        previousBudgetNs = frames == 1 ? std::min<uint64_t>(frameBudgetNs, 1000000000ULL) : 0;
        previousCaptured = captureNs > 0;
        // Cap samples before arithmetic, including anomalous stalls or suspended clocks.
        constexpr uint64_t cap = 1000000000ULL;
        const uint64_t baseline = std::min(cap, (workNs > captureNs ? workNs - captureNs : 0) / frames);
        averageWorkNs = averageWorkNs == 0 ? baseline : (averageWorkNs * 7 + baseline) / 8;
        if (captureNs > 0) {
            const uint64_t sample = std::min(cap, captureNs);
            averageCaptureNs = averageCaptureNs == 0 ? sample : (averageCaptureNs * 7 + sample) / 8;
            // Decay a capture spike only after subsequent captures, not on skipped frames.
            peakCaptureNs = std::max(sample, peakCaptureNs * 7 / 8);
        }
        if (!averageCaptureNs) return;
        const uint64_t observedWork = std::max(averageWorkNs, baseline);
        const uint64_t slack = frameBudgetNs > observedWork ? frameBudgetNs - observedWork : 0;
        const uint64_t allowed = std::max<uint64_t>(1, std::min(frameBudgetNs / 8, slack / 2));
        const uint64_t cost = std::max(averageCaptureNs, peakCaptureNs);
        const uint64_t desired = cost / allowed + (cost % allowed != 0 ? 1 : 0);
        workInterval = std::max<uint64_t>(1, std::min(maximumInterval, desired));
    }

    /** Include every emulated forward frame while an anchor exists, even when capture is skipped. */
    void advanced(uint64_t frames) {
        pending = saturatedAdd(pending, frames);
    }

    /**
     * pop returns the latest anchor and makes its predecessor the new anchor.
     * removedSpan is history-before minus history-after for that FINAL pop;
     * any fast-rewind discards must happen before measuring history-before.
     * Call after successfully restoring and drawing the returned state's frame.
     */
    void restored(const RewindBuffer& buffer, uint64_t removedSpan) {
        pending = buffer.getValidCount() > 0 ? saturatedAdd(removedSpan, 1) : 0;
    }

    uint64_t historyFrames(const RewindBuffer& buffer) const {
        if (buffer.getValidCount() == 0) return 0;
        const uint64_t liveDistance = pending > 0 ? pending - 1 : 0;
        return std::min(target, saturatedAdd(buffer.getHistoryFrames(), liveDistance));
    }

    uint64_t pendingFrames() const { return pending; }
    uint64_t captureInterval() const { return std::max(interval, std::max(workInterval, deadlineInterval)); }

private:
    static uint64_t saturatedAdd(uint64_t a, uint64_t b) {
        const uint64_t maximum = std::numeric_limits<uint64_t>::max();
        return b > maximum - a ? maximum : a + b;
    }

    uint64_t target = 1;
    uint64_t maximumInterval = 1;
    uint64_t pending = 0;
    uint64_t interval = 1;
    uint64_t workInterval = 1;
    uint64_t averageWorkNs = 0;
    uint64_t averageCaptureNs = 0;
    uint64_t peakCaptureNs = 0;
    uint64_t deadlineInterval = 1;
    uint64_t previousStartNs = 0;
    uint64_t previousBudgetNs = 0;
    bool previousCaptured = false;
    unsigned stableCycles = 0;
};

} // namespace libretrodroid

#endif
