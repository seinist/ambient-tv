// SPDX-License-Identifier: GPL-3.0-or-later
#include "fpssync.h"
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <initializer_list>

using namespace std::chrono;
using libretrodroid::FramePacingDeadline;
using libretrodroid::FPSSync;
using Tick = FramePacingDeadline::TimePoint;

int main() {
    FramePacingDeadline clock;
    const Tick epoch{};
    clock.setRate(60.0);
    const auto period = clock.interval();
    assert(period == nanoseconds(16666667));
    assert(!clock.armed() && clock.waitTarget(epoch) == epoch);
    assert(clock.advance(epoch, false) == 1 && clock.target() == epoch + period);
    assert(clock.waitTarget(epoch + period / 2) == epoch + period);
    assert(clock.waitTarget(epoch + period * 2) == epoch + period * 2);
    // Steady delivery, fractional lateness and capped catch-up preserve phase.
    assert(clock.advance(epoch + period, false) == 1 && clock.target() == epoch + period * 2);
    assert(clock.advance(epoch + period * 3 + period / 2, false) == 1);
    assert(clock.target() == epoch + period * 3);
    assert(clock.advance(epoch + period * 6 + period / 3, false) == 2);
    assert(clock.target() == epoch + period * 6);
    // Even a stall exceeding a 32-bit unsigned frame count cannot wrap the result.
    const auto longStall = period * (static_cast<int64_t>(std::numeric_limits<unsigned>::max()) + 23);
    const auto now = clock.target() + longStall + period / 4;
    assert(clock.advance(now, false) == 2);
    assert(clock.target() == now - period / 4);
    assert(clock.advance(now, false) == 1 && clock.target() > now);
    // Vsync reanchors after a full missed interval, never emits catch-up frames.
    clock.reset();
    assert(clock.advance(epoch, true) == 1);
    assert(clock.advance(epoch + period * 4 + period / 3, true) == 1);
    assert(clock.target() == epoch + period * 5 + period / 3);
    clock.reset();
    assert(clock.waitTarget(now) == now);
    assert(clock.advance(now, true) == 1 && clock.target() == now + period);
    // Synthetic early callbacks hit the existing future-deadline safety guard.
    for (int i = 0; i < 100; ++i) clock.advance(now, true);
    assert(clock.waitTarget(now) == now && clock.target() == now);
    // Reconfiguration disarms the deadline; invalid rates never create zero ticks.
    clock.setRate(50.0);
    assert(!clock.armed() && clock.interval() == milliseconds(20));
    for (double bad : {0.0, -1.0, 0.5, 1.0e15, std::numeric_limits<double>::infinity(),
                       std::numeric_limits<double>::quiet_NaN()}) {
        assert(!FramePacingDeadline::validRate(bad));
        clock.setRate(bad);
        assert(clock.interval() == milliseconds(20));
    }
    // 1M frames per rate: bounded nanosecond quantization, no cumulative
    // microsecond truncation. This measures clock arithmetic, not device pacing.
    for (double rate : {24.0, 50.0, 59.73, 60000.0 / 1001.0, 60.0, 90.0, 120.0, 144.0}) {
        clock.setRate(rate);
        constexpr int frames = 1000000;
        assert(clock.advance(epoch, false) == 1);
        for (int i = 1; i < frames; ++i) assert(clock.advance(clock.target(), false) == 1);
        const auto ideal = 1.0e9 * frames / rate;
        const auto actual = duration_cast<nanoseconds>(clock.target() - epoch).count();
        assert(std::abs(actual - ideal) <= frames * 0.5 + 1.0);
    }
    // A near-match VSync path uses the same physical rate as audio; strict,
    // nonmatching and external BFI timing keep the nominal core cadence.
    assert(FramePacingDeadline::cadenceRate(59.73, 60.0, true, false) == 60.0);
    assert(FramePacingDeadline::cadenceRate(59.73, 60.0, false, false) == 59.73);
    assert(FramePacingDeadline::cadenceRate(50.0, 100.0, true, true) == 50.0);
    assert(FramePacingDeadline::cadenceRate(60.0, 0.0, true, false) == 60.0);
    // Exercise the real FPSSync adapter, including unchanged display/BFI policy.
    FPSSync normal(59.73, 60.0);
    assert(std::abs(normal.getTimeStretchFactor() - 59.73 / 60.0) < 1e-12);
    normal.reset(); normal.wait(); assert(normal.advanceFrames() == 1);
    normal.updateContentRefreshRate(50.0);
    assert(normal.getTimeStretchFactor() == 1.0);
    normal.setExternalTimingControl(true);
    assert(normal.getTimeStretchFactor() == 50.0 / 60.0);
    normal.updateContentRefreshRate(std::numeric_limits<double>::quiet_NaN());
    assert(normal.getTimeStretchFactor() == 50.0 / 60.0);
    FPSSync strict(59.73, 60.0, true);
    strict.setExternalTimingControl(true);
    assert(strict.getTimeStretchFactor() == 1.0);
    FPSSync invalid(0.0, 0.0);
    invalid.setExternalTimingControl(true);
    assert(invalid.getTimeStretchFactor() == 1.0 && invalid.advanceFrames() == 1);
    std::puts("PASS: 8,000,000 synthetic frame deadlines, bounded stalls, reset and display timing policy");
}
