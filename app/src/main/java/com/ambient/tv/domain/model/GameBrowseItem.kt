package com.ambient.tv.domain.model

/** Presentation-only contract shared by local games, RomM and remotely streamed apps.
 * No emulator, file path, remote transport or playback policy is implied by this ID.
 */
interface GameBrowseItem {
    val fileId: String
    val title: String
    val systemId: String
    val favorite: Boolean
    val metadataYear: Int?
    val coverAspectRatio: Float
    val coverModel: Any?
    val systemLabel: String? get() = null
    val artworkIcon: Int? get() = null
}
