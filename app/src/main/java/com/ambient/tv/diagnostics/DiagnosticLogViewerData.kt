package com.ambient.tv.diagnostics

/**
 * A bounded, read-only view of the diagnostic files. The existing rotating log remains the only
 * source of truth; opening the viewer never enables logging or starts another logger.
 */
internal data class DiagnosticViewerEntry(
    val sequence: Long,
    val timestamp: String,
    val priority: Int,
    val tag: String,
    val thread: String,
    val message: String,
) {
    val key: String get() = "${timestamp}/${sequence}"
    val headline: String get() = message.lineSequence().firstOrNull().orEmpty()
    val levelLetter: Char get() = DiagnosticLevel.letter(priority)
    fun copyText(): String = "${timestamp} ${levelLetter} ${tag} [${thread}] ${message}"
}

internal data class DiagnosticViewerSnapshot(
    val revision: String,
    /** Most recent events first; capped to avoid keeping the full log in UI memory. */
    val entries: List<DiagnosticViewerEntry>,
)

/** Stateful parser so stacktrace/continuation lines remain with the preceding timestamp. */
internal class DiagnosticViewerParser(
    private val maxEntries: Int = 2_000,
    private val maxMessageChars: Int = 24_000,
) {
    init {
        require(maxEntries > 0)
        require(maxMessageChars > 0)
    }

    private data class Pending(
        val sequence: Long,
        val timestamp: String,
        val priority: Int,
        val tag: String,
        val thread: String,
        val message: StringBuilder,
    )

    private val header = Regex(
        """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEF?]) (.*?) \[([^\]]*)] (.*)$""",
    )
    private val entries = ArrayDeque<DiagnosticViewerEntry>()
    private var current: Pending? = null
    private var sequence = 0L

    fun acceptLine(line: String) {
        val match = header.matchEntire(line)
        if (match != null) {
            flush()
            val letter = match.groupValues[2].single()
            val priority = when (letter) {
                'F' -> DiagnosticLevel.ASSERT
                'E' -> DiagnosticLevel.ERROR
                'W' -> DiagnosticLevel.WARN
                'I' -> DiagnosticLevel.INFO
                'D' -> DiagnosticLevel.DEBUG
                'V' -> DiagnosticLevel.VERBOSE
                else -> DiagnosticLevel.INFO
            }
            current = Pending(
                ++sequence, match.groupValues[1], priority,
                match.groupValues[3], match.groupValues[4],
                StringBuilder(match.groupValues[5].take(maxMessageChars)),
            )
        } else {
            current?.let { pending ->
                val space = maxMessageChars - pending.message.length
                if (space > 1) {
                    pending.message.append('\n')
                    pending.message.append(line.take(space - 1))
                }
            }
        }
    }

    fun finish(): List<DiagnosticViewerEntry> {
        flush()
        return entries.toList().asReversed()
    }

    private fun flush() {
        val pending = current ?: return
        entries.addLast(
            DiagnosticViewerEntry(
                pending.sequence, pending.timestamp, pending.priority, pending.tag,
                pending.thread, pending.message.toString(),
            ),
        )
        if (entries.size > maxEntries) entries.removeFirst()
        current = null
    }
}

/**
 * The same lock as append/rotate/clear ensures a coherent snapshot. Call from Dispatchers.IO.
 * The revision avoids re-reading up to 3 MiB every polling interval when nothing has changed.
 */
internal fun RotatingLogFiles.snapshotForViewer(previousRevision: String?): DiagnosticViewerSnapshot? = synchronized(this) {
    val sources = files()
    val revision = sources.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
    if (revision == previousRevision) return@synchronized null

    val parser = DiagnosticViewerParser()
    sources.forEach { source ->
        // A file may disappear following a storage issue; show any remaining readable entries.
        runCatching {
            source.forEachLine(Charsets.UTF_8) { line -> parser.acceptLine(redactDiagnostic(line)) }
        }
    }
    DiagnosticViewerSnapshot(revision, parser.finish())
}
