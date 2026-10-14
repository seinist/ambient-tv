package com.ambient.tv.youtube

/** One activation address for the displayed link, browser and QR. Enter the code separately. */
object YouTubeSignInLink {
    const val ACTIVATE_ADDRESS = "yt.be/activate"
    const val ACTIVATE_URL = "https://yt.be/activate"

    fun qrPayload(): String = ACTIVATE_URL

    fun browserUrl(): String = ACTIVATE_URL
}
