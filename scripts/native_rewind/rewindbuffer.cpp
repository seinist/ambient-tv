/*
 * Copyright (C) 2024 Argosy Contributors
 * Copyright (C) 2026 Ambient TV Contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "rewindbuffer.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace libretrodroid {

RewindBuffer::RewindBuffer(size_t maxSlots, size_t maxStateSize, size_t budgetBytes,
                           uint64_t maxHistoryFrames)
    : maxSize(maxStateSize), recordCapacity(0), byteCapacity(0), memoryUsage(0),
      frameLimit(maxHistoryFrames) {
    // Subtraction-based checks also protect 32-bit Android builds. Validate all
    // dimensions before making the first allocation (including hostile sizes).
    if (maxSlots < 2 || maxSize == 0 || budgetBytes < sizeof(*this)) {
        throw std::invalid_argument("Invalid rewind dimensions");
    }
    size_t available = budgetBytes - sizeof(*this);
    const size_t requestedRecords = maxSlots - 1;
    if (requestedRecords > available / sizeof(Record)) {
        throw std::invalid_argument("Rewind metadata exceeds budget");
    }
    available -= requestedRecords * sizeof(Record);
    // Always leave enough ring space for a full uncompressible predecessor.
    if (maxSize > available / 2) {
        throw std::invalid_argument("Rewind states exceed budget");
    }
    recordCapacity = requestedRecords;
    byteCapacity = available - maxSize;
    memoryUsage = budgetBytes;

    latest.reset(new uint8_t[maxSize]);
    records.reset(new Record[recordCapacity]);
    bytes.reset(new uint8_t[byteCapacity]);
}

size_t RewindBuffer::wordSize(size_t offset, size_t size) {
    return std::min(WORD_BYTES, size - offset);
}

bool RewindBuffer::sameWord(const uint8_t* a, const uint8_t* b, size_t size) {
    if (size == WORD_BYTES) {
        uint64_t left;
        uint64_t right;
        // Save-state addresses need not be aligned to uint64_t on ARM.
        std::memcpy(&left, a, WORD_BYTES);
        std::memcpy(&right, b, WORD_BYTES);
        return left == right;
    }
    return std::memcmp(a, b, size) == 0;
}

size_t RewindBuffer::varintSize(uint64_t value) {
    size_t size = 1;
    while (value >= 0x80) {
        value >>= 7;
        ++size;
    }
    return size;
}

size_t RewindBuffer::encodedSize(const uint8_t* data, size_t size) const {
    size_t result = 0;
    size_t offset = 0;
    while (offset < size) {
        const size_t begin = offset;
        const bool literal = !sameWord(latest.get() + offset, data + offset, wordSize(offset, size));
        size_t words = 0;
        do {
            offset += wordSize(offset, size);
            ++words;
        } while (offset < size &&
                 (!sameWord(latest.get() + offset, data + offset, wordSize(offset, size))) == literal);

        const size_t header = varintSize((static_cast<uint64_t>(words) << 1) | (literal ? 1u : 0u));
        // Stop as soon as raw storage is no larger. Never build an expanded
        // temporary delta or overflow the byte count on alternating short runs.
        if (header >= size - result) return size;
        result += header;
        if (literal) {
            const size_t payload = offset - begin;
            if (payload >= size - result) return size;
            result += payload;
        }
    }
    return result;
}

void RewindBuffer::writeBytes(const uint8_t* data, size_t size) {
    const size_t first = std::min(size, byteCapacity - writeOffset);
    std::memcpy(bytes.get() + writeOffset, data, first);
    if (size > first) std::memcpy(bytes.get(), data + first, size - first);
    // size is bounded by byteCapacity, and this form cannot overflow size_t.
    writeOffset = size >= byteCapacity - writeOffset ? size - (byteCapacity - writeOffset) : writeOffset + size;
}

void RewindBuffer::writeVarint(uint64_t value) {
    uint8_t encoded[10];
    size_t size = 0;
    do {
        encoded[size++] = static_cast<uint8_t>((value & 0x7f) | (value >= 0x80 ? 0x80 : 0));
        value >>= 7;
    } while (value != 0);
    writeBytes(encoded, size);
}

void RewindBuffer::encode(const uint8_t* data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const size_t begin = offset;
        const bool literal = !sameWord(latest.get() + offset, data + offset, wordSize(offset, size));
        size_t words = 0;
        do {
            offset += wordSize(offset, size);
            ++words;
        } while (offset < size &&
                 (!sameWord(latest.get() + offset, data + offset, wordSize(offset, size))) == literal);
        writeVarint((static_cast<uint64_t>(words) << 1) | (literal ? 1u : 0u));
        if (literal) {
            size_t position = begin;
            while (position < offset) {
                const size_t count = wordSize(position, size);
                uint64_t oldWord = 0;
                uint64_t newWord = 0;
                std::memcpy(&oldWord, latest.get() + position, count);
                std::memcpy(&newWord, data + position, count);
                const uint64_t delta = oldWord ^ newWord;
                writeBytes(reinterpret_cast<const uint8_t*>(&delta), count);
                position += count;
            }
        }
    }
}

bool RewindBuffer::readBytes(size_t& offset, size_t& remaining, uint8_t* out, size_t size) const {
    if (size > remaining) return false;
    const size_t first = std::min(size, byteCapacity - offset);
    std::memcpy(out, bytes.get() + offset, first);
    if (size > first) std::memcpy(out + first, bytes.get(), size - first);
    offset = size >= byteCapacity - offset ? size - (byteCapacity - offset) : offset + size;
    remaining -= size;
    return true;
}

bool RewindBuffer::readVarint(size_t& offset, size_t& remaining, uint64_t& value) const {
    value = 0;
    for (unsigned shift = 0; shift <= 63; shift += 7) {
        uint8_t part;
        if (!readBytes(offset, remaining, &part, 1)) return false;
        if (shift == 63 && (part & 0xfe) != 0) return false;
        value |= static_cast<uint64_t>(part & 0x7f) << shift;
        if ((part & 0x80) == 0) return true;
    }
    return false;
}

bool RewindBuffer::decode(const Record& record) {
    return decodeInto(record, latest.get(), latestSize);
}

bool RewindBuffer::decodeInto(const Record& record, uint8_t* target, size_t targetSize) const {
    size_t cursor = record.offset;
    size_t remaining = record.length;
    if (record.raw) {
        return record.length == record.stateSize &&
               readBytes(cursor, remaining, target, record.stateSize);
    }
    if (record.stateSize != targetSize) return false;
    size_t position = 0;
    while (remaining != 0 && position < record.stateSize) {
        uint64_t header;
        if (!readVarint(cursor, remaining, header)) return false;
        const uint64_t words = header >> 1;
        const size_t available = record.stateSize - position;
        const size_t availableWords = available / WORD_BYTES + (available % WORD_BYTES != 0 ? 1 : 0);
        if (words == 0 || words > availableWords) return false;
        // This multiplication is safe because words <= ceil(available/8),
        // except possibly its final partial word: avoid that multiplication.
        const size_t count = words == availableWords ? available : static_cast<size_t>(words) * WORD_BYTES;
        if ((header & 1) == 0) {
            position += count;
            continue;
        }
        size_t left = count;
        while (left != 0) {
            const size_t chunk = std::min(WORD_BYTES, left);
            uint64_t delta = 0;
            uint64_t value = 0;
            if (!readBytes(cursor, remaining, reinterpret_cast<uint8_t*>(&delta), chunk)) return false;
            std::memcpy(&value, target + position, chunk);
            value ^= delta;
            std::memcpy(target + position, &value, chunk);
            position += chunk;
            left -= chunk;
        }
    }
    return remaining == 0 && position == record.stateSize;
}

void RewindBuffer::evictOldest() {
    const Record& record = records[firstRecord];
    usedBytes -= record.length;
    historyFrames -= record.frames;
    firstRecord = firstRecord + 1 == recordCapacity ? 0 : firstRecord + 1;
    --recordCount;
}

bool RewindBuffer::push(const uint8_t* data, size_t size, uint64_t frameSpan) {
    if (!data || size == 0 || size > maxSize) return false;
    if (latestSize != 0 && (frameSpan == 0 || frameSpan > std::numeric_limits<uint64_t>::max() - historyFrames)) {
        return false;
    }
    if (latestSize == 0) {
        std::memcpy(latest.get(), data, size);
        latestSize = size;
        return true;
    }
    if (frameLimit != 0 && frameSpan > frameLimit) {
        clear();
        std::memcpy(latest.get(), data, size);
        latestSize = size;
        return true;
    }

    const size_t compressedSize = size == latestSize ? encodedSize(data, size) : latestSize;
    const bool raw = compressedSize >= latestSize;
    const size_t length = raw ? latestSize : compressedSize;
    while (recordCount != 0 &&
           (recordCount == recordCapacity || length > byteCapacity - usedBytes ||
            (frameLimit != 0 && historyFrames > frameLimit - frameSpan))) {
        evictOldest();
    }

    size_t index = firstRecord + recordCount;
    if (index >= recordCapacity) index -= recordCapacity;
    records[index] = Record{writeOffset, length, latestSize, frameSpan, raw};
    if (raw) writeBytes(latest.get(), latestSize);
    else encode(data, size);
    usedBytes += length;
    historyFrames += frameSpan;
    ++recordCount;
    std::memcpy(latest.get(), data, size);
    latestSize = size;
    return true;
}

void RewindBuffer::consumeLatest() {
    if (recordCount == 0) {
        clear();
        return;
    }
    size_t index = firstRecord + recordCount - 1;
    if (index >= recordCapacity) index -= recordCapacity;
    const Record& record = records[index];
    if (!decode(record)) {
        // An internal corrupt record cannot be allowed to poison another state.
        clear();
        return;
    }
    latestSize = record.stateSize;
    writeOffset = record.offset;
    usedBytes -= record.length;
    historyFrames -= record.frames;
    --recordCount;
}

bool RewindBuffer::pop(uint8_t* outData, size_t* outSize) {
    if (!outData || !outSize || latestSize == 0) return false;
    std::memcpy(outData, latest.get(), latestSize);
    *outSize = latestSize;
    consumeLatest();
    return true;
}

const RewindBuffer::Record& RewindBuffer::newestRecord(size_t index) const {
    size_t slot = firstRecord + recordCount - 1 - index;
    if (slot >= recordCapacity) slot -= recordCapacity;
    return records[slot];
}

uint64_t RewindBuffer::recordFrames(size_t index) const {
    return index < recordCount ? newestRecord(index).frames : 0;
}

bool RewindBuffer::peek(size_t steps, uint8_t* outData, size_t* outSize) const {
    if (!outData || !outSize || latestSize == 0 || steps > recordCount) return false;
    std::memcpy(outData, latest.get(), latestSize);
    *outSize = latestSize;
    return peekFurther(0, steps, outData, outSize);
}

bool RewindBuffer::peekFurther(size_t from, size_t to, uint8_t* data, size_t* size) const {
    if (!data || !size || latestSize == 0 || from > to || to > recordCount ||
        *size == 0 || *size > maxSize) return false;
    for (size_t index = from; index < to; ++index) {
        const Record& record = newestRecord(index);
        if (!decodeInto(record, data, *size)) return false;
        *size = record.stateSize;
    }
    return true;
}

bool RewindBuffer::discard() {
    if (latestSize == 0) return false;
    consumeLatest();
    return true;
}

void RewindBuffer::clear() {
    latestSize = 0;
    firstRecord = 0;
    recordCount = 0;
    writeOffset = 0;
    usedBytes = 0;
    historyFrames = 0;
}

} // namespace libretrodroid
