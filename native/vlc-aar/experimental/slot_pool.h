#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace ambient::vlc_ahb {

// Owner-thread-only bounded slot scheduler. No frame may overwrite a target
// still used by either Vulkan conversion or libplacebo. Tickets prevent stale
// completion callbacks from retiring a newly reused slot.
template <size_t N> class SlotPool {
    static_assert(N > 0 && N <= 16, "Bound decoder backlog");
public:
    enum class State { Free, Recording, Converting, Ready, Rendering, Reclaiming, Quarantined };
    struct Ticket {
        size_t index = N;
        uint64_t generation = 0;
        explicit operator bool() const { return index < N && generation != 0; }
    };

    // Prefer a fully retired slot with a matching cached decoder buffer.
    // If it is busy, fall back to the normal bounded round-robin policy.
    Ticket acquire(uint64_t epoch, size_t preferred = N) {
        if (epoch != epoch_ || exhausted_) return {};
        for (size_t n = 0; n < N; ++n) {
            const size_t start = preferred < N && slots_[preferred].state == State::Free ? preferred : next_;
            const size_t i = (start + n) % N;
            auto &s = slots_[i];
            if (s.state != State::Free) continue;
            if (s.generation == std::numeric_limits<uint64_t>::max()) {
                exhausted_ = true;
                return {};
            }
            ++s.generation;
            s.epoch = epoch_;
            s.conversion_done = false;
            s.state = State::Recording;
            next_ = (i + 1) % N;
            return {i, s.generation};
        }
        return {}; // backpressure, never wait on a GPU fence here
    }

    bool cancel(Ticket t) { return transition(t, State::Recording, State::Free); }
    bool submitted(Ticket t) { return transition(t, State::Recording, State::Converting); }

    // Only on a signaled conversion fence; old-epoch frames are not displayed.
    bool converted(Ticket t) {
        auto *s = lookup(t);
        if (!s || s->conversion_done ||
            (s->state != State::Converting && s->state != State::Rendering &&
             s->state != State::Reclaiming)) return false;
        s->conversion_done = true;
        if (s->state == State::Converting)
            s->state = s->epoch == epoch_ ? State::Ready : State::Free;
        return true;
    }

    bool begin_render(Ticket t) {
        auto *s = lookup(t);
        return s && s->epoch == epoch_ && transition(t, State::Ready, State::Rendering);
    }
    // The consumer may enqueue work before conversion finishes if its first
    // use waits on the conversion-complete semaphore. This records ownership,
    // NOT proof that the producer/decoder has completed.
    bool begin_render_waiting_conversion(Ticket t) {
        auto *s = lookup(t);
        return s && s->epoch == epoch_ && transition(t, State::Converting, State::Rendering);
    }
    bool drop_ready(Ticket t) { return transition(t, State::Ready, State::Free); }
    bool begin_reclaim(Ticket t) { return transition(t, State::Rendering, State::Reclaiming); }
    // Only after libplacebo's hold semaphore has been consumed and its GPU
    // completion proven. Never use conversion completion for this transition.
    bool reclaimed(Ticket t) {
        auto *s = lookup(t);
        return s && s->conversion_done && transition(t, State::Reclaiming, State::Free);
    }

    bool quarantine(Ticket t) {
        auto *s = lookup(t);
        if (!s || (s->state != State::Converting && s->state != State::Rendering &&
                   s->state != State::Reclaiming)) return false;
        s->state = State::Quarantined;
        return true;
    }

    // Seek/source/window change: invalidate display eligibility, not ownership.
    // Submitted and rendering slots remain outstanding until their own completion.
    bool advance_epoch() {
        if (epoch_ == std::numeric_limits<uint64_t>::max()) {
            exhausted_ = true;
            return false;
        }
        ++epoch_;
        for (auto &s : slots_) {
            if (s.state == State::Ready) s.state = State::Free;
        }
        return true;
    }

    uint64_t epoch() const { return epoch_; }
    State state(size_t i) const {
        return i < N ? slots_[i].state : State::Quarantined;
    }
    bool drained() const {
        for (const auto &s : slots_) if (s.state != State::Free) return false;
        return true;
    }
private:
    struct Slot {
        State state = State::Free;
        uint64_t generation = 0;
        uint64_t epoch = 0;
        bool conversion_done = false;
    };
    Slot *lookup(Ticket t) {
        if (!t || slots_[t.index].generation != t.generation) return nullptr;
        return &slots_[t.index];
    }
    bool transition(Ticket t, State before, State after) {
        auto *s = lookup(t);
        if (!s || s->state != before) return false;
        s->state = after;
        return true;
    }
    std::array<Slot, N> slots_{};
    uint64_t epoch_ = 1;
    size_t next_ = 0;
    bool exhausted_ = false;
};
} // namespace ambient::vlc_ahb
