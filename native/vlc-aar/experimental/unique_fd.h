#pragma once
#include <unistd.h>

namespace ambient::vlc_ahb {

// Ownership of Android sync file descriptors is explicit. -1 means already signaled
// only after a successful import/export or an AImage acquire with no fence.
class UniqueFd {
public:
    explicit UniqueFd(int fd = -1) : fd_(fd) {}
    UniqueFd(const UniqueFd &) = delete;
    UniqueFd &operator=(const UniqueFd &) = delete;
    ~UniqueFd() { reset(); }
    int get() const { return fd_; }
    int release() { int value = fd_; fd_ = -1; return value; }
    void reset(int fd = -1) {
        if (fd_ == fd) return;
        if (fd_ >= 0) close(fd_);
        fd_ = fd;
    }
private:
    int fd_;
};

} // namespace ambient::vlc_ahb
