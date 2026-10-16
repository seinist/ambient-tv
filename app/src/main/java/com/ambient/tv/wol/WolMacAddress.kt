package com.ambient.tv.wol

/** Canonical 12-hex-digit MAC key used for cross-system WOL de-duplication. */
internal fun canonicalWolMacKey(macAddress: String?): String? {
    val compact = macAddress
        ?.trim()
        ?.replace(":", "")
        ?.replace("-", "")
        ?.replace(".", "")
        .orEmpty()
    if (compact.length != 12 || compact.any { it.digitToIntOrNull(16) == null }) return null
    return compact.lowercase()
}

/** Formats a partial MAC while typing, including pasted separator variants. */
internal fun formatMacAddressInput(value: String): String = value
    .filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    .take(12)
    .uppercase()
    .chunked(2)
    .joinToString(":")

/** Keep selection anchored to the hex digits when separators are inserted or removed. */
internal fun formatMacAddressInput(value: androidx.compose.ui.text.input.TextFieldValue): androidx.compose.ui.text.input.TextFieldValue {
    val formatted = formatMacAddressInput(value.text)
    fun offset(position: Int): Int {
        val digits = value.text.take(position).count { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.coerceAtMost(12)
        return (digits + ((digits - 1).coerceAtLeast(0) / 2)).coerceAtMost(formatted.length)
    }
    if (formatted == value.text) return value
    return androidx.compose.ui.text.input.TextFieldValue(
        text = formatted,
        selection = androidx.compose.ui.text.TextRange(offset(value.selection.start), offset(value.selection.end)),
    )
}
