// Actual production file reads, content ownership and libretro-common VFS close.
// Linker wrappers inject syscall failures without production-only test hooks.
#include "gamecontent.h"
#include "utils/utils.h"
#include "vfs/vfs.h"
#include "vfs/vfs_implementation.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

using libretrodroid::GameContent;
using libretrodroid::Utils;
using libretrodroid::VFS;
using libretrodroid::VFSFile;

namespace {
enum class ReadFault { None, Short, Interrupt, Error, EndEarly };
ReadFault readFault = ReadFault::None;
bool negativeSize = false;
int replaceClosedFd = -1;
int replacementFd = -1;
std::string replacementPath;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template<class F> void expectError(F&& action) {
    bool failed = false;
    try { action(); } catch (const std::exception&) { failed = true; }
    require(failed, "Expected a checked file-read failure");
}

size_t openDescriptors() {
    DIR* directory = opendir("/proc/self/fd");
    require(directory != nullptr, "Cannot count open descriptors");
    size_t count = 0;
    while (auto* entry = readdir(directory)) {
        if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) ++count;
    }
    closedir(directory);
    return count;
}

struct TempFile {
    std::string path;
    int fd;
    TempFile(const std::string& directory, const std::vector<char>& bytes)
        : path(directory + "/content-XXXXXX"), fd(mkstemp(path.data())) {
        require(fd >= 0, "Cannot create test content");
        size_t offset = 0;
        while (offset < bytes.size()) {
            const ssize_t count = write(fd, bytes.data() + offset, bytes.size() - offset);
            require(count > 0, "Cannot write test content");
            offset += static_cast<size_t>(count);
        }
    }
    ~TempFile() { close(fd); unlink(path.c_str()); }
};

std::vector<char> payload(size_t size) {
    std::vector<char> bytes(size);
    for (size_t i = 0; i < size; ++i) bytes[i] = static_cast<char>((i * 17) & 255);
    return bytes;
}

void requirePayload(const Utils::ReadResult& content, const std::vector<char>& expected) {
    require(content.size == expected.size(), "Content length changed");
    require(content.data != nullptr, "Content has no owned buffer");
    require(memcmp(content.data.get(), expected.data(), expected.size()) == 0,
            "Content bytes changed or a short read was accepted");
}

void readsAndFailures(const std::string& directory) {
    const auto expected = payload(8193);
    TempFile source(directory, expected);
    const size_t descriptorCount = openDescriptors();
    requirePayload(Utils::readFileAsBytes(source.path), expected);
    require(lseek(source.fd, 7, SEEK_SET) == 7, "Cannot set borrowed descriptor position");
    requirePayload(Utils::readFileAsBytes(source.fd), expected);
    require(fcntl(source.fd, F_GETFD) >= 0, "Reader closed a borrowed descriptor");
    require(lseek(source.fd, 0, SEEK_CUR) == 7, "Reader changed a borrowed descriptor position");

    readFault = ReadFault::Short;
    requirePayload(Utils::readFileAsBytes(source.path), expected);
    readFault = ReadFault::Interrupt;
    requirePayload(Utils::readFileAsBytes(source.fd), expected);
    readFault = ReadFault::Error;
    expectError([&] { Utils::readFileAsBytes(source.path); });
    readFault = ReadFault::EndEarly;
    expectError([&] { Utils::readFileAsBytes(source.fd); });
    readFault = ReadFault::None;
    negativeSize = true;
    expectError([&] { Utils::readFileAsBytes(source.path); });
    negativeSize = false;
    expectError([&] { Utils::readFileAsBytes(source.path + ".missing"); });
    expectError([] { Utils::readFileAsBytes(-1); });
    expectError([] { Utils::getFileSize(nullptr); });
    {
        TempFile empty(directory, {});
        expectError([&] { Utils::readFileAsBytes(empty.path); });
    }
    int pipeFds[2];
    require(pipe(pipeFds) == 0, "Cannot create nonseekable descriptor");
    expectError([&] { Utils::readFileAsBytes(pipeFds[0]); });
    close(pipeFds[0]);
    close(pipeFds[1]);

    FILE* stream = fdopen(dup(source.fd), "rb");
    require(stream != nullptr, "Cannot create size-check stream");
    require(Utils::getFileSize(stream) == expected.size(), "Incorrect file size");
    require(lseek(source.fd, 0, SEEK_CUR) == 7, "File size query changed seek position");
    fclose(stream);
    require(openDescriptors() == descriptorCount, "File-read error or success leaked a descriptor");
}

void ownedContentSurvivesUntilTeardown(const std::string& directory) {
    const auto expected = payload(1024 * 1024 + 31);
    TempFile source(directory, expected);
    const size_t descriptorCount = openDescriptors();
    for (int iteration = 0; iteration < 96; ++iteration) {
        auto bytes = Utils::readFileAsBytes(source.path);
        const void* originalBuffer = bytes.data.get();
        auto candidate = std::make_unique<GameContent>("a.nds", std::move(bytes));
        require(bytes.data == nullptr, "Content copied instead of taking ownership");
        const retro_game_info* coreInfo = candidate->get();
        auto retained = std::move(candidate);
        require(coreInfo == retained->get(), "Moving an owner invalidated game info");
        require(coreInfo->data == originalBuffer, "Content ownership added another ROM copy");
        require(strcmp(coreInfo->path, "a.nds") == 0, "A short content path became invalid");
        require(coreInfo->size == expected.size(), "Owned content size changed");
        // A core may read both pointers during failed-start cleanup and unload.
        require(memcmp(coreInfo->data, expected.data(), expected.size()) == 0,
                "Content did not survive through simulated unload/deinit");
        retained.reset();
        // Exceptions/cancelled candidates release the actual production owner.
        try {
            auto cancelled = std::make_unique<GameContent>(source.path, Utils::readFileAsBytes(source.fd));
            throw std::runtime_error("cancel startup");
        } catch (const std::runtime_error&) { }
    }
    GameContent fullPath("disc/subdir/game.cue");
    require(strcmp(fullPath.get()->path, "disc/subdir/game.cue") == 0,
            "Full-path content lost the path needed for relative includes");
    require(fullPath.get()->data == nullptr && fullPath.get()->size == 0,
            "Full-path core received an unnecessary memory copy");
    auto memoryOnly = std::make_unique<GameContent>("", Utils::readFileAsBytes(source.path));
    require(memoryOnly->get()->path == nullptr, "Memory-only loading fabricated a path");
    require(openDescriptors() == descriptorCount, "Repeated or cancelled reads leaked descriptors");
}

void vfsOwnershipAndFailures(const std::string& directory) {
    const auto expected = payload(513);
    TempFile source(directory, expected);
    const size_t descriptorCount = openDescriptors();
    for (int iteration = 0; iteration < 64; ++iteration) {
        const int ownedFd = dup(source.fd);
        const int sidecarFd = dup(source.fd);
        require(ownedFd >= 0 && sidecarFd >= 0, "Cannot duplicate virtual files");
        std::vector<VFSFile> files;
        files.emplace_back("virtual/rom/game.nds", ownedFd);
        files.emplace_back("virtual/rom/relative.bin", sidecarFd);
        VFS::getInstance().initialize(std::move(files));
        auto* stream = VFS::open("virtual/rom/game.nds", RETRO_VFS_FILE_ACCESS_READ,
                                 RETRO_VFS_FILE_ACCESS_HINT_FREQUENT_ACCESS);
        require(stream != nullptr, "Virtual main content did not open");
        require(VFS::size(stream) == static_cast<int64_t>(expected.size()), "Incorrect VFS size");
        std::vector<char> read(expected.size());
        require(VFS::read(stream, read.data(), read.size()) == static_cast<int64_t>(read.size()),
                "VFS failed to read complete content");
        require(read == expected, "VFS changed content bytes");
        // Arrange FD reuse between fclose and upstream's optional close(fd).
        // The buffered handle must have only one descriptor owner.
        replaceClosedFd = fileno(stream->fp);
        replacementPath = source.path;
        require(VFS::close(stream) == 0, "VFS close failed");
        require(replacementFd == replaceClosedFd, "FD-reuse test was not exercised");
        require(fcntl(replacementFd, F_GETFD) >= 0, "VFS double-close closed a reused descriptor");
        close(replacementFd);
        replaceClosedFd = replacementFd = -1;
        require(fcntl(ownedFd, F_GETFD) >= 0, "VFS stream closed the source descriptor");

        auto* sidecar = VFS::open("virtual/rom/relative.bin", RETRO_VFS_FILE_ACCESS_READ, 0);
        require(sidecar != nullptr, "Relative/include virtual file was lost");
        require(VFS::close(sidecar) == 0, "VFS sidecar close failed");
        negativeSize = true;
        require(VFS::open("virtual/rom/game.nds", RETRO_VFS_FILE_ACCESS_READ, 0) == nullptr,
                "A VFS size error escaped as a usable stream");
        negativeSize = false;
        VFS::getInstance().deinitialize();
        require(fcntl(ownedFd, F_GETFD) == -1 && errno == EBADF,
                "Virtual content descriptor survived deinitialization");
    }
    std::vector<VFSFile> invalid;
    invalid.emplace_back("virtual/invalid/game.nds", -1);
    VFS::getInstance().initialize(std::move(invalid));
    require(VFS::open("virtual/invalid/game.nds", RETRO_VFS_FILE_ACCESS_READ, 0) == nullptr,
            "Invalid virtual descriptor was not rejected");
    VFS::getInstance().deinitialize();
    require(openDescriptors() == descriptorCount, "VFS startup/close/error leaked descriptors");

    // A transferred descriptor may legitimately be zero on a process with no
    // stdin. FDWrapper must own it just like every other nonnegative descriptor.
    const int savedStdin = dup(STDIN_FILENO);
    require(dup2(source.fd, STDIN_FILENO) == STDIN_FILENO, "Cannot test descriptor zero");
    { VFSFile zero("virtual/zero.nds", STDIN_FILENO); }
    const bool zeroClosed = fcntl(STDIN_FILENO, F_GETFD) == -1 && errno == EBADF;
    if (savedStdin >= 0) {
        require(dup2(savedStdin, STDIN_FILENO) == STDIN_FILENO, "Cannot restore stdin");
        close(savedStdin);
    }
    require(zeroClosed, "FDWrapper did not close an owned descriptor zero");
}
}

extern "C" ssize_t __real_pread(int, void*, size_t, off_t);
extern "C" ssize_t __wrap_pread(int fd, void* data, size_t size, off_t offset) {
    if (readFault == ReadFault::Interrupt) {
        readFault = ReadFault::None;
        errno = EINTR;
        return -1;
    }
    if (readFault == ReadFault::Error && offset > 0) { errno = EIO; return -1; }
    if (readFault == ReadFault::EndEarly && offset > 0) return 0;
    if (readFault != ReadFault::None) size = std::min(size, static_cast<size_t>(17));
    return __real_pread(fd, data, size, offset);
}

extern "C" int __real_fstat(int, struct stat*);
extern "C" int __wrap_fstat(int fd, struct stat* status) {
    const int result = __real_fstat(fd, status);
    if (result == 0 && negativeSize) status->st_size = -1;
    return result;
}

extern "C" int __real_fclose(FILE*);
extern "C" int __wrap_fclose(FILE* stream) {
    const int fd = fileno(stream);
    const int result = __real_fclose(stream);
    if (fd == replaceClosedFd) {
        const int newlyOpened = open(replacementPath.c_str(), O_RDONLY);
        if (newlyOpened == fd) replacementFd = newlyOpened;
        else if (newlyOpened >= 0) {
            replacementFd = dup2(newlyOpened, fd);
            close(newlyOpened);
        }
    }
    return result;
}

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Expected temporary test directory");
        readsAndFailures(argv[1]);
        ownedContentSurvivesUntilTeardown(argv[1]);
        vfsOwnershipAndFailures(argv[1]);
        std::cout << "Content lifecycle: binary/partial/interrupted/error reads, ownership, cancellation, "
                     "96 content lifecycles and 64 VFS close/reuse cycles passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Content lifecycle failed: " << error.what() << '\n';
        return 1;
    }
}
