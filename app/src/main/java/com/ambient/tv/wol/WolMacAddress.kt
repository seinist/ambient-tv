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
