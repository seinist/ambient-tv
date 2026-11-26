#include "frame_lease.h"
#include <cassert>
#include <cerrno>

using namespace ambient::vlc_ahb;
struct Image {
    int returned = 0;
    int fence = -2;
};
static void release(void *image, int fd) {
    auto &i = *static_cast<Image *>(image);
    ++i.returned;
    i.fence = fd;
    if (fd >= 0) close(fd);
}

int main() {
    // Drop before submission: original producer fence is returned, even after
    // duplicating it for a temporary Vulkan import which subsequently fails.
    int p[2];
    assert(pipe(p) == 0);
    Image discarded;
    {
        FrameLease lease(&discarded, p[0], release);
        UniqueFd duplicated;
        assert(lease.duplicate_acquire(duplicated));
        assert(duplicated.get() >= 0 && duplicated.get() != p[0]);
        assert(lease.discard());
        assert(lease.can_destroy_gpu_objects());
        assert(!lease.discard());
        assert(fcntl(p[0], F_GETFD) == -1 && errno == EBADF);
        assert(fcntl(duplicated.get(), F_GETFD) >= 0);
    }
    assert(discarded.returned == 1 && discarded.fence == p[0]);
    close(p[1]);

    // Decoder return with an exported fence is distinct from Vulkan retirement.
    Image rendered;
    {
        FrameLease lease(&rendered, -1, release);
        assert(lease.begin_submit());
        assert(!lease.begin_submit());
        assert(!lease.discard());
        assert(!lease.can_destroy_gpu_objects());
        UniqueFd fence;
        assert(!lease.return_with_fence(fence, false));
        assert(lease.return_with_fence(fence, true));
        assert(!lease.has_decoder_image());
        assert(!lease.can_destroy_gpu_objects());
        assert(!lease.complete(true, false));
        assert(!lease.complete(false, true));
        assert(lease.complete(true, true));
        assert(lease.can_destroy_gpu_objects());
    }
    assert(rendered.returned == 1 && rendered.fence == -1);

    // Failed export: hold image until both completion and ownership release.
    Image held;
    {
        FrameLease lease(&held, -1, release);
        assert(lease.begin_submit());
        assert(!lease.complete(false, false));
        assert(lease.has_decoder_image());
        assert(lease.complete(true, true));
        assert(lease.can_destroy_gpu_objects());
        assert(!lease.has_decoder_image());
    }
    assert(held.returned == 1);
    // Unknown submission: never silently return the potentially in-use image.
    Image uncertain;
    {
        FrameLease lease(&uncertain, -1, release);
        assert(lease.begin_submit());
    }
    assert(uncertain.returned == 0);
}
