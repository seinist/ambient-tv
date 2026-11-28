#include "unique_fd.h"
#include <cassert>
#include <cerrno>
#include <fcntl.h>

using ambient::vlc_ahb::UniqueFd;
static bool open_fd(int fd) { return fcntl(fd, F_GETFD) != -1; }

int main() {
    int pair[2];
    assert(pipe(pair) == 0);
    {
        UniqueFd a(pair[0]);
        a.reset(pair[0]);
        assert(open_fd(pair[0]));
        a.reset(pair[1]);
        assert(!open_fd(pair[0]) && errno == EBADF);
        assert(open_fd(pair[1]));
        int transferred = a.release();
        assert(a.get() == -1 && transferred == pair[1]);
    }
    assert(open_fd(pair[1]));
    close(pair[1]);
    assert(pipe(pair) == 0);
    {
        UniqueFd a(pair[0]);
    }
    assert(!open_fd(pair[0]) && errno == EBADF);
    close(pair[1]);
    UniqueFd signaled;
    assert(signaled.release() == -1);
}
