#include "slot_pool.h"
#include <cassert>

using namespace ambient::vlc_ahb;
int main() {
    using Pool = SlotPool<2>;
    using State = Pool::State;
    {
        Pool cached;
        auto first = cached.acquire(cached.epoch(), 1);
        assert(first && first.index == 1);
        auto second = cached.acquire(cached.epoch(), 1);
        assert(second && second.index == 0);
        assert(!cached.acquire(cached.epoch(), 1));
        assert(cached.cancel(first));
        auto reused = cached.acquire(cached.epoch(), 1);
        assert(reused && reused.index == 1 && reused.generation != first.generation);
        assert(!cached.cancel(first));
        assert(cached.cancel(reused) && cached.cancel(second) && cached.drained());
    }
    {
        Pool async;
        auto t = async.acquire(async.epoch());
        assert(async.submitted(t));
        assert(async.begin_render_waiting_conversion(t));
        assert(async.begin_reclaim(t));
        // A consumer handoff alone cannot free the slot or release a decoder.
        assert(!async.reclaimed(t));
        assert(async.advance_epoch());
        assert(async.converted(t));
        assert(!async.converted(t));
        assert(async.reclaimed(t) && async.drained());
        auto stale = async.acquire(async.epoch());
        assert(async.submitted(stale));
        assert(async.advance_epoch());
        assert(!async.begin_render_waiting_conversion(stale));
        assert(async.converted(stale) && async.drained());
    }
    Pool p;
    auto epoch = p.epoch();
    auto a = p.acquire(epoch);
    auto b = p.acquire(epoch);
    assert(a && b && a.index != b.index && !p.acquire(epoch));
    assert(p.submitted(a));
    assert(!p.cancel(a));
    assert(!p.begin_render(a));
    assert(p.converted(a));
    assert(p.begin_render(a));
    assert(!p.drop_ready(a));
    assert(!p.reclaimed(a));
    assert(p.begin_reclaim(a));
    assert(!p.acquire(epoch));
    assert(p.reclaimed(a));
    auto c = p.acquire(epoch);
    assert(c && c.index == a.index && c.generation != a.generation);
    assert(!p.cancel(a) && !p.converted(a));
    assert(p.submitted(c));
    assert(p.advance_epoch());
    assert(!p.acquire(epoch));
    assert(p.cancel(b));
    assert(p.converted(c));
    assert(p.state(c.index) == State::Free && !p.begin_render(c));
    assert(p.drained());

    auto d = p.acquire(p.epoch());
    assert(p.submitted(d) && p.converted(d));
    assert(p.advance_epoch());
    assert(p.state(d.index) == State::Free);
    assert(!p.begin_render(d));

    auto e = p.acquire(p.epoch());
    assert(p.submitted(e) && p.converted(e) && p.begin_render(e));
    assert(p.advance_epoch());
    assert(p.state(e.index) == State::Rendering);
    assert(p.begin_reclaim(e) && p.reclaimed(e));
    assert(p.drained());

    auto f = p.acquire(p.epoch());
    assert(p.submitted(f));
    assert(p.quarantine(f));
    assert(p.advance_epoch());
    assert(p.state(f.index) == State::Quarantined);
    assert(!p.converted(f) && !p.drained());
    auto g = p.acquire(p.epoch());
    assert(g && g.index != f.index && !p.acquire(p.epoch()));
    assert(p.cancel(g));
}
