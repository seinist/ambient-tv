#!/usr/bin/env python3
"""Exercise the actual patched UDP receive helper using Linux loopback sockets."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HARNESS = r"""
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#define FIRST_FRAME_TIMEOUT_SEC 10
#define ML_ERROR_NO_VIDEO_TRAFFIC -100
#define ML_ERROR_NO_VIDEO_FRAME -101
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <netinet/in.h>
#include <time.h>
"""
TEST = r"""
static void prepare(struct mmsghdr *messages, struct iovec *iov, char buffers[4][16]) {
    memset(messages, 0, sizeof(*messages) * 4);
    for (unsigned int i = 0; i < 4; i++) {
        iov[i].iov_base = buffers[i];
        iov[i].iov_len = sizeof(buffers[i]);
        messages[i].msg_hdr.msg_iov = &iov[i];
        messages[i].msg_hdr.msg_iovlen = 1;
    }
}
static void testAdaptiveBatch(void) {
    unsigned int full = 0, sparse = 0, target = 2;
    for (int i = 0; i < 2; i++) target = nextVideoBatch(target, target, &full, &sparse);
    assert(target == 2);
    target = nextVideoBatch(target, target, &full, &sparse);
    assert(target == 4);
    for (int i = 0; i < 3; i++) target = nextVideoBatch(target, target, &full, &sparse);
    assert(target == 8);
    for (int i = 0; i < 100; i++) target = nextVideoBatch(target, target, &full, &sparse);
    assert(target == 8);
    for (int i = 0; i < 11; i++) target = nextVideoBatch(target, 1, &full, &sparse);
    assert(target == 8);
    target = nextVideoBatch(target, 1, &full, &sparse);
    assert(target == 4);
    for (int i = 0; i < 24; i++) target = nextVideoBatch(target, 0, &full, &sparse);
    assert(target == 1);
}
int main(void) {
    testAdaptiveBatch();
    assert(videoStartupError(9999, 0, 0, false, false) == 0);
    assert(videoStartupError(10000, 0, 0, false, false) == ML_ERROR_NO_VIDEO_TRAFFIC);
    assert(videoStartupError(14999, 0, 5000, true, false) == 0);
    assert(videoStartupError(15000, 0, 5000, true, false) == ML_ERROR_NO_VIDEO_FRAME);
    assert(videoStartupError(90000, 0, 5000, true, true) == 0);
    int rx = socket(AF_INET, SOCK_DGRAM, 0);
    int tx = socket(AF_INET, SOCK_DGRAM, 0);
    assert(rx >= 0 && tx >= 0);
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(rx, (struct sockaddr*)&address, sizeof(address)) == 0);
    socklen_t len = sizeof(address);
    assert(getsockname(rx, (struct sockaddr*)&address, &len) == 0);
    assert(connect(tx, (struct sockaddr*)&address, len) == 0);
    struct timeval timeout = {0, 100000};
    assert(setsockopt(rx, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    struct mmsghdr messages[4];
    struct iovec iov[4];
    char buffers[4][16];

    // A single packet must return without waiting for a full burst.
    assert(send(tx, "a", 1, 0) == 1);
    prepare(messages, iov, buffers);
    assert(receiveVideoDatagrams(rx, messages, 4) == 1);
    assert(messages[0].msg_len == 1 && buffers[0][0] == 'a');

    // Already queued packets arrive in order, and the burst is bounded.
    for (char c = 'b'; c <= 'f'; c++) assert(send(tx, &c, 1, 0) == 1);
    prepare(messages, iov, buffers);
    assert(receiveVideoDatagrams(rx, messages, 4) == 4);
    for (int i = 0; i < 4; i++) {
        assert(messages[i].msg_len == 1 && buffers[i][0] == 'b' + i);
    }
    prepare(messages, iov, buffers);
    assert(receiveVideoDatagrams(rx, messages, 4) == 1 && buffers[0][0] == 'f');

    // Oversized datagrams carry the truncation flag, not an unsafe length.
    char large[32] = {0};
    assert(send(tx, large, sizeof(large), 0) == sizeof(large));
    prepare(messages, iov, buffers);
    assert(receiveVideoDatagrams(rx, messages, 4) == 1);
    assert(messages[0].msg_hdr.msg_flags & MSG_TRUNC);

    // Empty socket must respect SO_RCVTIMEO rather than block indefinitely.
    prepare(messages, iov, buffers);
    assert(receiveVideoDatagrams(rx, messages, 4) == -1);
    assert(errno == EAGAIN || errno == EWOULDBLOCK);
    close(rx);
    close(tx);
    return 0;
}
"""

def main():
    source = Path(sys.argv[1]).read_text()
    start = source.index("#define VIDEO_UDP_BATCH ")
    end = source.index("\n}", source.index("static int receiveVideoDatagrams(")) + 2
    startup = source.index("static int videoStartupError(")
    startup_end = source.index("\n}", startup) + 2
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        (path / "udp.c").write_text(HARNESS + source[start:end] + "\n" + source[startup:startup_end] + "\n" + TEST)
        subprocess.run([os.environ.get("CC", "cc"), "-D_GNU_SOURCE", "-std=c11",
                        "-O2", "-Wall", "-Wextra", "-Werror",
                        str(path / "udp.c"), "-o", str(path / "udp")], check=True)
        subprocess.run([str(path / "udp")], check=True, timeout=5)
    print("Moonlight UDP burst ordering, truncation and timeout passed")

if __name__ == "__main__":
    main()
