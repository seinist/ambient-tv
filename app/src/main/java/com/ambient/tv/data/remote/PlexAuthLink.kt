package com.ambient.tv.data.remote

/**
 * Plex account-link QR target. The strong pin from `POST api/v2/pins?strong=true` is a long
 * opaque code. It belongs in the app.plex.tv auth deep link, not appended to plex.tv/link.
 * A separate 4-character PIN is not part of that response today; [typedCode] still prefers one
 * if the API starts returning it.
 */
object PlexAuthLink {
    fun qrPayload(clientIdentifier: String, strongCode: String): String =
        "https://app.plex.tv/auth/#!?clientID=${query(clientIdentifier)}&code=${query(strongCode)}"

    /** Code the user can type at plex.tv/link. The strong code itself when no short PIN is returned. */
    fun typedCode(strongCode: String, shortCode: String?): String =
        shortCode?.takeIf { it.isNotBlank() && it != strongCode } ?: strongCode

    private fun query(value: String): String = buildString {
        value.forEach { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                append(ch)
            } else {
                ch.toString().encodeToByteArray().forEach { byte ->
                    append('%')
                    append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}
