package com.ambient.tv.livetv.teletext

/** Complete PSI sections from one TS PID, including their CRC bytes; CRC policy belongs to the caller. */
internal class PsiSectionAssembler(private val onSection: (ByteArray) -> Unit) {
    private val section = ByteArray(4096)
    private var length = 0
    private var total = 0
    private var lastCounter = -1

    fun reset() {
        clearSection()
        lastCounter = -1
    }

    /** [offset] points to a complete 188-byte transport packet of this assembler's PID. */
    fun packet(data: ByteArray, offset: Int) {
        fun u(index: Int) = data[index].toInt() and 0xFF
        val flags = u(offset + 1)
        val control = u(offset + 3)
        val adaptation = (control shr 4) and 3
        if (flags and 0x80 != 0 || control and 0xC0 != 0 || adaptation == 0) {
            reset() // An errored or scrambled packet cannot continue a section.
            return
        }
        val end = offset + 188
        var payload = offset + 4
        if (adaptation and 2 != 0) {
            val adaptationLength = u(payload)
            if (payload + 1 + adaptationLength > end) { reset(); return }
            if (adaptationLength > 0 && u(payload + 1) and 0x80 != 0) reset()
            payload += 1 + adaptationLength
        }
        if (adaptation and 1 == 0 || payload >= end) return

        val counter = control and 0x0F
        if (counter == lastCounter) return // A repeated payload packet must not be appended twice.
        if (lastCounter >= 0 && counter != ((lastCounter + 1) and 0x0F)) clearSection()
        lastCounter = counter

        if (flags and 0x40 != 0) {
            val nextSection = payload + 1 + u(payload)
            if (nextSection > end) { clearSection(); return }
            // The pointer bytes can finish the previous section before a new one starts.
            if (length > 0) append(data, payload + 1, nextSection, allowNewSections = false)
            clearSection() // A section still incomplete at this boundary was lost.
            append(data, nextSection, end, allowNewSections = true)
        } else if (length > 0) {
            append(data, payload, end, allowNewSections = false)
        }
    }

    private fun append(data: ByteArray, from: Int, end: Int, allowNewSections: Boolean) {
        var position = from
        while (position < end) {
            if (length == 0 && data[position].toInt() and 0xFF == 0xFF) return // stuffing
            if (length < 3) {
                val count = minOf(3 - length, end - position)
                data.copyInto(section, length, position, position + count)
                length += count
                position += count
                if (length < 3) return
                total = 3 + (((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF))
                if (total < 7 || total > section.size) { clearSection(); return }
            }
            val count = minOf(total - length, end - position)
            data.copyInto(section, length, position, position + count)
            length += count
            position += count
            if (length < total) return
            val complete = section.copyOf(total)
            clearSection()
            onSection(complete)
            if (!allowNewSections) return
        }
    }

    private fun clearSection() {
        length = 0
        total = 0
    }
}
