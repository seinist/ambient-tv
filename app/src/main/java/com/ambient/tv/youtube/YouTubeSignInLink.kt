package com.ambient.tv.youtube

import java.net.URLEncoder

/**
 * The short address is shown for typing by hand; QR and browser open the full activation page with
 * the code prefilled (`user_code` is undocumented but works there, not at yt.be). The code stays on
 * screen in case Google stops honouring it.
 */
object YouTubeSignInLink {
    const val ACTIVATE_ADDRESS = "yt.be/activate"
    const val ACTIVATE_URL = "https://www.youtube.com/activate"

    fun qrPayload(code: String): String = "$ACTIVATE_URL?user_code=${URLEncoder.encode(code.trim(), "UTF-8")}"

    fun browserUrl(code: String): String = qrPayload(code)
}
