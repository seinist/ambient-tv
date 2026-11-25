/*
 * Copyright (C) 2024 Argosy Contributors
 * Copyright (C) 2026 Ambient TV Contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef LIBRETRODROID_REWINDBUFFER_H
#define LIBRETRODROID_REWINDBUFFER_H

#include <cstddef>
#include <cstdint>
#include <memory>

namespace libretrodroid {

/**
 * GL-thread-owned reverse-delta history. Allocation is fixed at construction: one
 * latest state, bounded record metadata and a byte ring. The caller's capture
 * buffer is not owned here and must be deducted from its total rewind budget.
 *
 * A record reconstructs the state BEFORE its successor. Evicting the oldest
 * record therefore never requires decoding another state or retaining a chain
 * to an evicted keyframe. A rewind pop keeps the historical API: return the
 * newest stored state, consume it, and make its predecessor the new anchor.
 */
class RewindBuffer {
public:
    RewindBuffer(size_t maxSlots, size_t maxStateSize, size_t budgetBytes,
                 uint64_t maxHistoryFrames = 0);
    ~RewindBuffer() = default;
    RewindBuffer(const RewindBuffer&) = delete;
    RewindBuffer& operator=(const RewindBuffer&) = delete;
    RewindBuffer(RewindBuffer&&) = delete;
    RewindBuffer& operator=(RewindBuffer&&) = delete;

    /** frameSpan is the emulated distance from the previous stored state. */
    bool push(const uint8_t* data, size_t size, uint64_t frameSpan = 1);
    /** outData must have getMaxStateSize() bytes; rejected arguments do not mutate history. */
    bool pop(uint8_t* outData, size_t* outSize);
    bool discard();
    /**
     * Non-destructive: reconstruct the state [steps] records older than the newest stored
     * state (0 = the newest) into outData (getMaxStateSize() bytes). History is unchanged.
     */
    bool peek(size_t steps, uint8_t* outData, size_t* outSize) const;
    /**
     * Continue a peek without starting again at the newest state: data holds the state
     * [from] records back (size in *size) and is decoded further to [to] (from <= to).
     */
    bool peekFurther(size_t from, size_t to, uint8_t* data, size_t* size) const;
    /** Emulated frames of the [index]-th newest record (0: newest state to its predecessor). */
    uint64_t recordFrames(size_t index) const;
    /** Retains all allocations so reset/load/restart cannot allocate per frame. */
    void clear();

    size_t getValidCount() const { return latestSize ? recordCount + 1 : 0; }
    size_t getCapacity() const { return recordCapacity + 1; }
    size_t getMaxStateSize() const { return maxSize; }
    uint64_t getHistoryFrames() const { return historyFrames; }
    size_t getStoredBytes() const { return usedBytes; }
    size_t getStorageCapacity() const { return byteCapacity; }
    /** Requested allocation bytes plus this object's size; allocator bookkeeping is external. */
    size_t getMemoryUsage() const { return memoryUsage; }
    float getUsage() const {
        return static_cast<float>(usedBytes) / static_cast<float>(byteCapacity);
    }

private:
    struct Record {
        size_t offset;
        size_t length;
        size_t stateSize;
        uint64_t frames;
        bool raw;
    };

    static constexpr size_t WORD_BYTES = sizeof(uint64_t);
    static size_t wordSize(size_t offset, size_t size);
    static bool sameWord(const uint8_t* a, const uint8_t* b, size_t size);
    static size_t varintSize(uint64_t value);

    // A run covers consecutive eight-byte words (and possibly the final partial
    // word). Low header bit: 0=unchanged, 1=XOR literal; other bits: word count.
    size_t encodedSize(const uint8_t* data, size_t size) const;
    void encode(const uint8_t* data, size_t size);
    bool decode(const Record& record);
    /** Apply [record] to target, which holds its successor state of targetSize bytes. */
    bool decodeInto(const Record& record, uint8_t* target, size_t targetSize) const;
    const Record& newestRecord(size_t index) const;
    void writeBytes(const uint8_t* data, size_t size);
    void writeVarint(uint64_t value);
    bool readBytes(size_t& offset, size_t& remaining, uint8_t* out, size_t size) const;
    bool readVarint(size_t& offset, size_t& remaining, uint64_t& value) const;
    void evictOldest();
    void consumeLatest();

    size_t maxSize;
    size_t recordCapacity;
    size_t byteCapacity;
    size_t memoryUsage;
    uint64_t frameLimit;
    std::unique_ptr<uint8_t[]> latest;
    std::unique_ptr<Record[]> records;
    std::unique_ptr<uint8_t[]> bytes;
    size_t latestSize = 0;
    size_t firstRecord = 0;
    size_t recordCount = 0;
    size_t writeOffset = 0;
    size_t usedBytes = 0;
    uint64_t historyFrames = 0;
};

} // namespace libretrodroid

#endif
