#include "slot_pool.h"
#include <cassert>

using namespace ambient::vlc_ahb;
int main() {
    using Pool = SlotPool<2>;
    using State = Pool::State;
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
