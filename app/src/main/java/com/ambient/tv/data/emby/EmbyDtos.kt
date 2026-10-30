package com.ambient.tv.data.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbySystemInfo(
    @SerialName("ServerName") val serverName: String = "Emby",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
)

@Serializable
data class EmbyAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
data class EmbyAuthResponse(
    @SerialName("User") val user: EmbyUser,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class EmbyConnectPin(
    @SerialName("Id") val id: String = "",
    @SerialName("Pin") val pin: String = "",
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("IsExpired") val isExpired: Boolean = false,
    @SerialName("IsConfirmed") val isConfirmed: Boolean = false,
    @SerialName("AccessToken") val accessToken: String = "",
)

@Serializable
data class EmbyConnectAuth(
    @SerialName("UserId") val userId: String,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
data class EmbyConnectServer(
    @SerialName("AccessKey") val accessKey: String,
    @SerialName("SystemId") val systemId: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Url") val url: String? = null,
    @SerialName("LocalAddress") val localAddress: String? = null,
)

@Serializable
data class EmbyConnectExchange(
    @SerialName("LocalUserId") val localUserId: String,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
data class EmbyUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

@Serializable
data class EmbyItemsResponse(
    @SerialName("Items") val items: List<EmbyItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = items.size,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
data class EmbyItem(
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
    @SerialName("Studios") val studios: List<EmbyNameId> = emptyList(),
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("People") val people: List<EmbyPerson> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("UserData") val userData: EmbyUserData? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSource> = emptyList(),
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Chapters") val chapters: List<EmbyChapter> = emptyList(),
    @SerialName("Trickplay") val trickplay: Map<String, Map<String, EmbyTrickplay?>?> = emptyMap(),
)

@Serializable
data class EmbyNameId(
    @SerialName("Name") val name: String = "",
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class EmbyPerson(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class EmbyUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    /** Sent for series, seasons and collections: episodes or titles this user has not played. */
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
data class EmbyMediaSource(
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
    @SerialName("MediaStreams") val mediaStreams: List<EmbyMediaStream> = emptyList(),
)

@Serializable
data class EmbyMediaStream(
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
data class EmbyPlaybackInfoRequest(
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
data class EmbyPlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
data class EmbyPlaybackProgress(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

@Serializable
data class EmbyUserDataUpdate(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long,
    @SerialName("Played") val played: Boolean,
    @SerialName("PlayCount") val playCount: Int,
)


@Serializable
data class EmbyChapter(
    @SerialName("MarkerType") val markerType: String? = null,
    @SerialName("StartPositionTicks") val startTicks: Long = -1,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
)
@Serializable
data class EmbyTrickplay(
    @SerialName("Width") val width: Int = 0,
    @SerialName("Height") val height: Int = 0,
    @SerialName("TileWidth") val columns: Int = 0,
    @SerialName("TileHeight") val rows: Int = 0,
    @SerialName("Interval") val intervalMs: Long = 0,
    @SerialName("ThumbnailCount") val count: Long = 0,
)

@Serializable
data class EmbyRemoteImages(@SerialName("Images") val images: List<EmbyRemoteImage> = emptyList())

@Serializable
data class EmbyRemoteImage(@SerialName("Url") val url: String? = null)
