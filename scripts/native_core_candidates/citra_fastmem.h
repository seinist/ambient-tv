// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <map>
#include <mutex>
#include <new>
#include <optional>
#include <set>
#include <vector>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace AmbientFastmem {
constexpr size_t GuestPage = 4096;
constexpr uint64_t AddressSpace = uint64_t{1} << 32;
// Cross-page RAM accesses must not wrap or split into device callbacks.
inline bool split_ram_access(bool first_ram,bool last_ram,uint32_t address,size_t bytes) {
    return first_ram && last_ram && bytes && bytes <= GuestPage &&
           uint64_t(address)+bytes <= AddressSpace;
}
struct Allocation { size_t bytes; int fd; };
class Arena;
struct Registry {
    std::mutex mutex;
    std::map<uintptr_t, Allocation> allocations;
    std::vector<Arena*> arenas;
    std::set<uint64_t> live_generations;
};
inline Registry& registry() {
    // Core globals may release backing allocations during library teardown.
    static Registry* instance = new Registry;
    return *instance;
}
inline size_t host_page() {
    const long p = sysconf(_SC_PAGESIZE);
    return p == 4096 || p == 16384 || p == 65536 ? size_t(p) : 0;
}
inline int backing_fd() {
#ifdef SYS_memfd_create
    return static_cast<int>(syscall(SYS_memfd_create, "ambient-citra-ram", 1u));
#else
    return -1;
#endif
}
class Arena {
public:
    Arena() : generation(next_generation.fetch_add(1, std::memory_order_relaxed)) {
        auto& r=registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        r.live_generations.insert(generation);
    }
    uint64_t id() const noexcept { return generation; }
    uint64_t mapping_id() const noexcept { return mapping_generation.load(std::memory_order_relaxed); }
    void mapping_changed() noexcept { mapping_generation.fetch_add(1,std::memory_order_relaxed); }
    static bool is_live(uint64_t id) {
        if(!id)return true; // the null page-table JIT
        auto& r=registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        return r.live_generations.count(id)!=0;
    }
    void reset_mappings() {
        auto& r=registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        if(base) {
            // Replace the entire reservation; this drops aliases even for
            // guest addresses outside the managed VM region.
            if(mmap(base,AddressSpace+page,PROT_NONE,MAP_FIXED|MAP_PRIVATE|MAP_ANONYMOUS,-1,0)==MAP_FAILED) {
                for(const auto& item:mapped)revoke(item.first);
            }
            mapped.clear();
        }
        mapping_changed();
    }
    Arena(const Arena&) = delete;
    Arena& operator=(const Arena&) = delete;
    ~Arena() {
        auto& r = registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        r.live_generations.erase(generation);
        if (!base) return;
        r.arenas.erase(std::remove(r.arenas.begin(), r.arenas.end(), this), r.arenas.end());
        munmap(base, AddressSpace + page);
    }

    template<class Pointers>
    std::optional<uintptr_t> ensure(const Pointers& pointers) {
        auto& r = registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        if (!attempted) {
            attempted = true;
            page = host_page();
            if (!page || sizeof(uintptr_t) < 8) return {};
            void* p = mmap(nullptr, AddressSpace + page, PROT_NONE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (p == MAP_FAILED) return {};
            base = static_cast<unsigned char*>(p);
            r.arenas.push_back(this);
            sync_locked(pointers, 0, AddressSpace / GuestPage, r);
        }
        if (!base) return {};
        return reinterpret_cast<uintptr_t>(base);
    }

    template<class Pointers>
    void sync(const Pointers& pointers, uint32_t first, uint32_t count) {
        if (!count) return;
        auto& r = registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        if(base)sync_locked(pointers, first, count, r);
    }

    // Before backing storage is freed or reallocated, revoke all its aliases,
    // including aliases in inactive guest page tables.
    void revoke_allocation(uintptr_t address, size_t bytes) {
        for (auto it = mapped.begin(); it != mapped.end();) {
            if (it->second >= address && it->second - address < bytes) {
                revoke(it->first);
                it = mapped.erase(it);
            } else ++it;
        }
    }

private:
    void revoke(uint64_t guest) {
        if (mmap(base + guest, page, PROT_NONE,
                 MAP_FIXED | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0) == MAP_FAILED &&
            mprotect(base + guest, page, PROT_NONE) != 0)
            std::abort(); // Never leave an obsolete writable alias.
    }

    template<class Pointers>
    void sync_locked(const Pointers& pointers, uint32_t first, uint32_t count, Registry& r) {
        const uint64_t start = uint64_t(first) * GuestPage / page * page;
        const uint64_t end = std::min(AddressSpace,
            (uint64_t(first + uint64_t(count)) * GuestPage + page - 1) / page * page);
        for (uint64_t guest = start; guest < end; guest += page) {
            const size_t index = guest / GuestPage;
            const uintptr_t canonical = reinterpret_cast<uintptr_t>(pointers[index]);
            auto old = mapped.find(guest);
            if (!canonical && old == mapped.end())
                continue;
            bool valid = canonical != 0 && canonical % page == 0;
            // Host pages may be 16 KiB while guest pages are 4 KiB.
            // Every subpage must map consecutively to the same shared backing.
            for (size_t offset = GuestPage; valid && offset < page; offset += GuestPage)
                valid = reinterpret_cast<uintptr_t>(pointers[index + offset / GuestPage]) ==
                        canonical + offset;
            auto allocation = r.allocations.upper_bound(canonical);
            if (allocation == r.allocations.begin()) valid = false;
            else {
                --allocation;
                valid = valid && canonical >= allocation->first &&
                        canonical - allocation->first <= allocation->second.bytes &&
                        page <= allocation->second.bytes - (canonical - allocation->first);
            }
            if (valid && old != mapped.end() && old->second == canonical) continue;
            if (old != mapped.end()) {
                revoke(guest);
                mapped.erase(old);
            }
            if (!valid) continue;
            // Coalesce contiguous, newly mapped host pages from the same
            // allocation. Avoid thousands of mmap syscalls when loading ROM/RAM.
            uint64_t length = page;
            const size_t remaining = allocation->second.bytes -
                                     (canonical - allocation->first);
            while (guest + length < end && length + page <= remaining &&
                   mapped.find(guest + length) == mapped.end()) {
                bool contiguous = true;
                for (size_t offset = 0; offset < page; offset += GuestPage) {
                    const auto next = reinterpret_cast<uintptr_t>(
                        pointers[(guest + length + offset) / GuestPage]);
                    if (next != canonical + length + offset) {
                        contiguous = false;
                        break;
                    }
                }
                if (!contiguous) break;
                length += page;
            }
            void* p = mmap(base + guest, length, PROT_READ | PROT_WRITE,
                           MAP_FIXED | MAP_SHARED, allocation->second.fd,
                           static_cast<off_t>(canonical - allocation->first));
            if (p != MAP_FAILED) {
                for (uint64_t offset = 0; offset < length; offset += page)
                    mapped.emplace(guest + offset, canonical + offset);
                guest += length - page;
            }
            // Otherwise the reservation remains inaccessible: Dynarmic falls back.
        }
    }

    inline static std::atomic<uint64_t> next_generation{1};
    const uint64_t generation;
    std::atomic<uint64_t> mapping_generation{1};
    unsigned char* base = nullptr;
    size_t page = 0;
    bool attempted = false;
    std::map<uint64_t, uintptr_t> mapped;
};

template<class T> struct Allocator {
    using value_type = T;
    Allocator() noexcept = default;
    template<class U> Allocator(const Allocator<U>&) noexcept {}
    T* allocate(size_t n) {
        if (n > std::numeric_limits<size_t>::max() / sizeof(T)) throw std::bad_alloc();
        const size_t bytes = n * sizeof(T);
        const size_t page = host_page();
        if (bytes && page && bytes <= std::numeric_limits<size_t>::max() - page) {
            const size_t rounded = (bytes + page - 1) / page * page;
            int fd = backing_fd();
            if (fd >= 0) {
                if (ftruncate(fd, static_cast<off_t>(rounded)) == 0) {
                    void* p = mmap(nullptr, rounded, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                    if (p != MAP_FAILED) {
                        auto& r = registry();
                        try {
                            std::lock_guard<std::mutex> lock(r.mutex);
                            r.allocations.emplace(reinterpret_cast<uintptr_t>(p), Allocation{rounded, fd});
                        } catch (...) {
                            munmap(p, rounded);
                            close(fd);
                            throw;
                        }
                        return static_cast<T*>(p);
                    }
                }
                close(fd);
            }
        }
        return static_cast<T*>(::operator new(bytes));
    }
    void deallocate(T* p, size_t) noexcept {
        auto& r = registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        auto it = r.allocations.find(reinterpret_cast<uintptr_t>(p));
        if (it == r.allocations.end()) { ::operator delete(p); return; }
        for (Arena* arena : r.arenas) arena->revoke_allocation(it->first, it->second.bytes);
        munmap(p, it->second.bytes);
        close(it->second.fd);
        r.allocations.erase(it);
    }
    template<class U> bool operator==(const Allocator<U>&) const noexcept { return true; }
    template<class U> bool operator!=(const Allocator<U>&) const noexcept { return false; }
};

// A compatible byte-vector for kernel-owned allocations. Loader temporaries
// retain their ordinary vector API and are copied only during game loading.
class MemoryBlock : public std::vector<uint8_t, Allocator<uint8_t>> {
    using Base = std::vector<uint8_t, Allocator<uint8_t>>;
public:
    using Base::Base;
    MemoryBlock() = default;
    explicit MemoryBlock(std::vector<uint8_t>&& source) : Base(source.begin(), source.end()) {}
};
} // namespace AmbientFastmem
