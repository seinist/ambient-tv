package com.ambient.tv.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JellyfinSystemInfo(
    @SerialName("ServerName") val serverName: String = "Jellyfin",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
)

@Serializable
data class JellyfinAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
data class JellyfinAuthResponse(
    @SerialName("User") val user: JellyfinUser,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

@Serializable
data class JellyfinItemsResponse(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = items.size,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Taglines") val taglines: List<String> = emptyList(),
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("CriticRating") val criticRating: Double? = null,
    @SerialName("Studios") val studios: List<JellyfinNameId> = emptyList(),
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("People") val people: List<JellyfinPerson> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSource> = emptyList(),
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Chapters") val chapters: List<JellyfinChapter> = emptyList(),
    @SerialName("Trickplay") val trickplay: Map<String, Map<String, JellyfinTrickplay?>?> = emptyMap(),
)

@Serializable
data class JellyfinNameId(
    @SerialName("Name") val name: String = "",
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JellyfinPerson(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class JellyfinUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    /** Sent for series, seasons and collections: episodes or titles this user has not played. */
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
data class JellyfinMediaSource(
    @SerialName("Path") val path: String? = null,
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = true,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = true,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = true,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStream> = emptyList(),
)

@Serializable
data class JellyfinMediaStream(
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("ColorRange") val colorRange: String? = null,
    @SerialName("ColorSpace") val colorSpace: String? = null,
    @SerialName("ColorTransfer") val colorTransfer: String? = null,
    @SerialName("ColorPrimaries") val colorPrimaries: String? = null,
    @SerialName("DvProfile") val dvProfile: Int? = null,
    @SerialName("DvLevel") val dvLevel: Int? = null,
    @SerialName("DvBlSignalCompatibilityId") val dvBlSignalCompatibilityId: Int? = null,
    @SerialName("Hdr10PlusPresentFlag") val hdr10PlusPresentFlag: Boolean? = null,
    @SerialName("VideoDoViTitle") val videoDoViTitle: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

@Serializable
data class JellyfinPlaybackInfoRequest(
    @SerialName("UserId") val userId: String,
    @SerialName("StartTimeTicks") val startTimeTicks: Long = 0,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
)

@Serializable
data class JellyfinPlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
data class JellyfinPlaybackProgress(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

@Serializable
data class JellyfinUserDataUpdate(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long,
    @SerialName("Played") val played: Boolean,
    @SerialName("PlayCount") val playCount: Int,
)


@Serializable
data class JellyfinChapter(
    @SerialName("StartPositionTicks") val startTicks: Long = -1,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
)
@Serializable
data class JellyfinTrickplay(
    @SerialName("Width") val width: Int = 0,
    @SerialName("Height") val height: Int = 0,
    @SerialName("TileWidth") val columns: Int = 0,
    @SerialName("TileHeight") val rows: Int = 0,
    @SerialName("Interval") val intervalMs: Long = 0,
    @SerialName("ThumbnailCount") val count: Long = 0,
)

@Serializable
data class JellyfinSegmentsResponse(@SerialName("Items") val items: List<JellyfinSegment> = emptyList())
@Serializable
data class JellyfinSegment(
    @SerialName("Type") val type: String = "",
    @SerialName("StartTicks") val startTicks: Long = -1,
    @SerialName("EndTicks") val endTicks: Long = -1,
)
