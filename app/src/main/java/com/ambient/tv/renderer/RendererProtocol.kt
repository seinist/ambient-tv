package com.ambient.tv.renderer

import com.ambient.tv.data.dlna.childElements
import com.ambient.tv.data.dlna.localTag
import com.ambient.tv.data.dlna.parseXmlDocument
import java.util.Locale
import org.w3c.dom.Element

// UPnP/DLNA MediaRenderer wire format, kept free of Android types for unit tests.

internal const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
internal const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
internal const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
internal const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

/** One renderer service: its type, id and the paths the device description points at. */
internal enum class RendererService(val type: String, val id: String, val pathName: String) {
    AVTRANSPORT(AV_TRANSPORT, "urn:upnp-org:serviceId:AVTransport", "AVTransport"),
    RENDERING(RENDERING_CONTROL, "urn:upnp-org:serviceId:RenderingControl", "RenderingControl"),
    CONNECTIONS(CONNECTION_MANAGER, "urn:upnp-org:serviceId:ConnectionManager", "ConnectionManager");

    val scpdPath get() = "/$pathName.xml"
    val controlPath get() = "/ctl/$pathName"
    val eventPath get() = "/evt/$pathName"

    companion object {
        fun byControlPath(path: String) = entries.firstOrNull { it.controlPath == path }
        fun byEventPath(path: String) = entries.firstOrNull { it.eventPath == path }
        fun byScpdPath(path: String) = entries.firstOrNull { it.scpdPath == path }
    }
}

/** What the renderer accepts; control points pick a matching resource from this list. */
internal val SINK_PROTOCOL_INFO: String = listOf(
    "video/mp4", "video/x-m4v", "video/x-matroska", "video/webm", "video/quicktime", "video/x-msvideo", "video/avi",
    "video/mp2t", "video/vnd.dlna.mpeg-tts", "video/mpeg", "video/ogg",
    "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml",
    "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-flac", "audio/ogg", "audio/opus",
    "audio/wav", "audio/x-wav", "audio/webm",
).joinToString(",") { "http-get:*:$it:*" }

internal fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t') Unit else append(ch)
        }
    }
}

internal fun deviceDescription(udn: String, friendlyName: String, modelName: String, version: String): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">""")
    append("<specVersion><major>1</major><minor>0</minor></specVersion><device>")
    append("<deviceType>").append(MEDIA_RENDERER).append("</deviceType>")
    append("<friendlyName>").append(escapeXml(friendlyName)).append("</friendlyName>")
    append("<manufacturer>").append(escapeXml(modelName)).append("</manufacturer>")
    append("<modelName>").append(escapeXml(modelName)).append("</modelName>")
    append("<modelNumber>").append(escapeXml(version)).append("</modelNumber>")
    append("<UDN>").append(escapeXml(udn)).append("</UDN>")
    append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>")
    RendererService.entries.forEach { service ->
        append("<service><serviceType>").append(service.type).append("</serviceType>")
        append("<serviceId>").append(service.id).append("</serviceId>")
        append("<SCPDURL>").append(service.scpdPath).append("</SCPDURL>")
        append("<controlURL>").append(service.controlPath).append("</controlURL>")
        append("<eventSubURL>").append(service.eventPath).append("</eventSubURL></service>")
    }
    append("</serviceList></device></root>")
}

private class Arg(val name: String, val out: Boolean, val variable: String)
private class Action(val name: String, val args: List<Arg>)
private class Variable(val name: String, val type: String = "string", val events: Boolean = false, val allowed: List<String> = emptyList())

private fun inArg(name: String, variable: String) = Arg(name, false, variable)
private fun outArg(name: String, variable: String) = Arg(name, true, variable)
private val instance = inArg("InstanceID", "A_ARG_TYPE_InstanceID")

private fun scpd(actions: List<Action>, variables: List<Variable>): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?><scpd xmlns="urn:schemas-upnp-org:service-1-0">""")
    append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
    actions.forEach { action ->
        append("<action><name>").append(action.name).append("</name>")
        if (action.args.isNotEmpty()) {
            append("<argumentList>")
            action.args.forEach { arg ->
                append("<argument><name>").append(arg.name).append("</name><direction>")
                append(if (arg.out) "out" else "in").append("</direction><relatedStateVariable>")
                append(arg.variable).append("</relatedStateVariable></argument>")
            }
            append("</argumentList>")
        }
        append("</action>")
    }
    append("</actionList><serviceStateTable>")
    variables.forEach { variable ->
        append("<stateVariable sendEvents=\"").append(if (variable.events) "yes" else "no").append("\">")
        append("<name>").append(variable.name).append("</name><dataType>").append(variable.type).append("</dataType>")
        if (variable.allowed.isNotEmpty()) {
            append("<allowedValueList>")
            variable.allowed.forEach { append("<allowedValue>").append(it).append("</allowedValue>") }
            append("</allowedValueList>")
        }
        if (variable.name == "Volume") append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>")
        append("</stateVariable>")
    }
    append("</serviceStateTable></scpd>")
}

internal val AV_TRANSPORT_SCPD: String = scpd(
    listOf(
        Action("SetAVTransportURI", listOf(instance, inArg("CurrentURI", "AVTransportURI"), inArg("CurrentURIMetaData", "AVTransportURIMetaData"))),
        Action("GetMediaInfo", listOf(instance, outArg("NrTracks", "NumberOfTracks"), outArg("MediaDuration", "CurrentMediaDuration"),
            outArg("CurrentURI", "AVTransportURI"), outArg("CurrentURIMetaData", "AVTransportURIMetaData"),
            outArg("NextURI", "NextAVTransportURI"), outArg("NextURIMetaData", "NextAVTransportURIMetaData"),
            outArg("PlayMedium", "PlaybackStorageMedium"), outArg("RecordMedium", "RecordStorageMedium"),
            outArg("WriteStatus", "RecordMediumWriteStatus"))),
        Action("GetTransportInfo", listOf(instance, outArg("CurrentTransportState", "TransportState"),
            outArg("CurrentTransportStatus", "TransportStatus"), outArg("CurrentSpeed", "TransportPlaySpeed"))),
        Action("GetPositionInfo", listOf(instance, outArg("Track", "CurrentTrack"), outArg("TrackDuration", "CurrentTrackDuration"),
            outArg("TrackMetaData", "CurrentTrackMetaData"), outArg("TrackURI", "CurrentTrackURI"),
            outArg("RelTime", "RelativeTimePosition"), outArg("AbsTime", "AbsoluteTimePosition"),
            outArg("RelCount", "RelativeCounterPosition"), outArg("AbsCount", "AbsoluteCounterPosition"))),
        Action("GetDeviceCapabilities", listOf(instance, outArg("PlayMedia", "PossiblePlaybackStorageMedia"),
            outArg("RecMedia", "PossibleRecordStorageMedia"), outArg("RecQualityModes", "PossibleRecordQualityModes"))),
        Action("GetTransportSettings", listOf(instance, outArg("PlayMode", "CurrentPlayMode"), outArg("RecQualityMode", "CurrentRecordQualityMode"))),
        Action("GetCurrentTransportActions", listOf(instance, outArg("Actions", "CurrentTransportActions"))),
        Action("Stop", listOf(instance)),
        Action("Play", listOf(instance, inArg("Speed", "TransportPlaySpeed"))),
        Action("Pause", listOf(instance)),
        Action("Seek", listOf(instance, inArg("Unit", "A_ARG_TYPE_SeekMode"), inArg("Target", "A_ARG_TYPE_SeekTarget"))),
        Action("Next", listOf(instance)),
        Action("Previous", listOf(instance)),
    ),
    listOf(
        Variable("TransportState", allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")),
        Variable("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED")),
        Variable("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK")),
        Variable("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED")),
        Variable("PossiblePlaybackStorageMedia"),
        Variable("PossibleRecordStorageMedia"),
        Variable("CurrentPlayMode", allowed = listOf("NORMAL")),
        Variable("TransportPlaySpeed", allowed = listOf("1")),
        Variable("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED")),
        Variable("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED")),
        Variable("PossibleRecordQualityModes"),
        Variable("NumberOfTracks", "ui4"),
        Variable("CurrentTrack", "ui4"),
        Variable("CurrentTrackDuration"),
        Variable("CurrentMediaDuration"),
        Variable("CurrentTrackMetaData"),
        Variable("CurrentTrackURI"),
        Variable("AVTransportURI"),
        Variable("AVTransportURIMetaData"),
        Variable("NextAVTransportURI"),
        Variable("NextAVTransportURIMetaData"),
        Variable("RelativeTimePosition"),
        Variable("AbsoluteTimePosition"),
        Variable("RelativeCounterPosition", "i4"),
        Variable("AbsoluteCounterPosition", "i4"),
        Variable("CurrentTransportActions"),
        Variable("LastChange", events = true),
        Variable("A_ARG_TYPE_SeekMode", allowed = listOf("REL_TIME", "ABS_TIME")),
        Variable("A_ARG_TYPE_SeekTarget"),
        Variable("A_ARG_TYPE_InstanceID", "ui4"),
    ),
)

internal val RENDERING_CONTROL_SCPD: String = scpd(
    listOf(
        Action("ListPresets", listOf(instance, outArg("CurrentPresetNameList", "PresetNameList"))),
        Action("SelectPreset", listOf(instance, inArg("PresetName", "A_ARG_TYPE_PresetName"))),
        Action("GetMute", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), outArg("CurrentMute", "Mute"))),
        Action("SetMute", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), inArg("DesiredMute", "Mute"))),
        Action("GetVolume", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), outArg("CurrentVolume", "Volume"))),
        Action("SetVolume", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), inArg("DesiredVolume", "Volume"))),
    ),
    listOf(
        Variable("PresetNameList"),
        Variable("Mute", "boolean"),
        Variable("Volume", "ui2"),
        Variable("LastChange", events = true),
        Variable("A_ARG_TYPE_Channel", allowed = listOf("Master")),
        Variable("A_ARG_TYPE_InstanceID", "ui4"),
        Variable("A_ARG_TYPE_PresetName", allowed = listOf("FactoryDefaults")),
    ),
)

internal val CONNECTION_MANAGER_SCPD: String = scpd(
    listOf(
        Action("GetProtocolInfo", listOf(outArg("Source", "SourceProtocolInfo"), outArg("Sink", "SinkProtocolInfo"))),
        Action("GetCurrentConnectionIDs", listOf(outArg("ConnectionIDs", "CurrentConnectionIDs"))),
        Action("GetCurrentConnectionInfo", listOf(inArg("ConnectionID", "A_ARG_TYPE_ConnectionID"),
            outArg("RcsID", "A_ARG_TYPE_RcsID"), outArg("AVTransportID", "A_ARG_TYPE_AVTransportID"),
            outArg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), outArg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
            outArg("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), outArg("Direction", "A_ARG_TYPE_Direction"),
            outArg("Status", "A_ARG_TYPE_ConnectionStatus"))),
    ),
    listOf(
        Variable("SourceProtocolInfo", events = true),
        Variable("SinkProtocolInfo", events = true),
        Variable("CurrentConnectionIDs", events = true),
        Variable("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
        Variable("A_ARG_TYPE_ConnectionManager"),
        Variable("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output")),
        Variable("A_ARG_TYPE_ProtocolInfo"),
        Variable("A_ARG_TYPE_ConnectionID", "i4"),
        Variable("A_ARG_TYPE_AVTransportID", "i4"),
        Variable("A_ARG_TYPE_RcsID", "i4"),
    ),
)

internal fun scpdFor(service: RendererService): String = when (service) {
    RendererService.AVTRANSPORT -> AV_TRANSPORT_SCPD
    RendererService.RENDERING -> RENDERING_CONTROL_SCPD
    RendererService.CONNECTIONS -> CONNECTION_MANAGER_SCPD
}

/** `"urn:…:AVTransport:1#Play"` (quotes optional) → service type and action name. */
internal fun parseSoapAction(header: String?): Pair<String, String>? {
    val value = header?.trim()?.removeSurrounding("\"")?.trim() ?: return null
    val separator = value.lastIndexOf('#')
    if (separator <= 0 || separator == value.lastIndex) return null
    val action = value.substring(separator + 1)
    if (!action.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) return null
    return value.substring(0, separator) to action
}

/** The `in` arguments of the SOAP body's action element, by name. */
internal fun parseSoapArguments(xml: String, action: String): Map<String, String> {
    val root = parseXmlDocument(xml).documentElement
    val body = root.childElements().firstOrNull { it.localTag() == "body" } ?: return emptyMap()
    val element = body.childElements().firstOrNull { it.localTag() == action.lowercase(Locale.ROOT) } ?: return emptyMap()
    return element.childElements().associate { (it.localName ?: it.tagName.substringAfter(':')) to it.textContent.orEmpty() }
}

internal fun soapResponse(serviceType: String, action: String, values: List<Pair<String, String>>): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
    append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
    values.forEach { (name, value) -> append('<').append(name).append('>').append(escapeXml(value)).append("</").append(name).append('>') }
    append("</u:").append(action).append("Response></s:Body></s:Envelope>")
}

internal fun soapFault(code: Int, description: String): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
    append("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>")
    append("""<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>""").append(code).append("</errorCode>")
    append("<errorDescription>").append(escapeXml(description)).append("</errorDescription></UPnPError>")
    append("</detail></s:Fault></s:Body></s:Envelope>")
}

/**
 * The sender as the prompt shows it: the client's product token, then its address. It is also
 * what "Always from this device" stores, so a lasting permission belongs to an app at an address.
 */
internal fun rendererSender(userAgent: String?, address: String): String {
    val product = userAgent?.split(' ')?.firstOrNull { it.contains('/') && !it.startsWith("Dalvik") && !it.startsWith("Linux") && !it.startsWith("UPnP") }
        ?.substringBefore('/')?.filter { it.isLetterOrDigit() || it in " ._-" }?.take(40)?.takeIf { it.isNotBlank() }
    return if (product != null) "$product ($address)" else address
}

/** Title, UPnP class and MIME type of the first resource in DIDL-Lite metadata. */
internal data class DidlItem(val title: String?, val upnpClass: String?, val mimeType: String?)

internal fun parseDidl(xml: String?): DidlItem? {
    if (xml.isNullOrBlank() || xml.length > 64 * 1024) return null
    return runCatching {
        val root = parseXmlDocument(xml).documentElement
        val item = root.childElements().firstOrNull { it.localTag() == "item" } ?: root
        fun child(tag: String): Element? = item.childElements().firstOrNull { it.localTag() == tag }
        val protocol = child("res")?.getAttribute("protocolInfo").orEmpty()
        DidlItem(
            title = child("title")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            upnpClass = child("class")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            mimeType = protocol.split(':').getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && it != "*" },
        )
    }.getOrNull()
}

/** UPnP duration/position format `H+:MM:SS`. */
internal fun formatUpnpTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
}

/** `H+:MM:SS[.F+]` or `H+:MM:SS[.F0/F1]` → milliseconds; null when malformed. */
internal fun parseUpnpTime(value: String?): Long? {
    val text = value?.trim()?.removePrefix("+") ?: return null
    val match = Regex("""(\d{1,5}):(\d{1,2}):(\d{1,2})(?:\.(\d{1,3})\d*|\.\d+/\d+)?""").matchEntire(text) ?: return null
    val (hours, minutes, seconds, fraction) = match.destructured
    val m = minutes.toLong(); val s = seconds.toLong()
    if (m > 59 || s > 59) return null
    val millis = fraction.takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLong() ?: 0L
    return ((hours.toLong() * 60 + m) * 60 + s) * 1000 + millis
}

/** GENA LastChange body for one instance; values are XML-escaped twice as UPnP requires. */
internal fun lastChangeEvent(service: RendererService, values: List<Pair<String, String>>): String {
    val namespace = if (service == RendererService.RENDERING) "urn:schemas-upnp-org:metadata-1-0/RCS/" else "urn:schemas-upnp-org:metadata-1-0/AVT/"
    val inner = buildString {
        append("<Event xmlns=\"").append(namespace).append("\"><InstanceID val=\"0\">")
        values.forEach { (name, value) ->
            append('<').append(name)
            if (service == RendererService.RENDERING) append(" channel=\"Master\"")
            append(" val=\"").append(escapeXml(value)).append("\"/>")
        }
        append("</InstanceID></Event>")
    }
    return propertySet(listOf("LastChange" to inner))
}

internal fun propertySet(values: List<Pair<String, String>>): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""")
    values.forEach { (name, value) -> append("<e:property><").append(name).append('>').append(escapeXml(value)).append("</").append(name).append("></e:property>") }
    append("</e:propertyset>")
}

/** `<http://host:port/path>` entries of a GENA CALLBACK header. */
internal fun parseCallbacks(header: String?): List<String> =
    Regex("<([^>]{1,512})>").findAll(header.orEmpty()).map { it.groupValues[1] }.take(4).toList()

/** `Second-1800` / `infinite` → seconds, clamped to one minute .. one hour (renewals keep it alive). */
internal fun parseTimeoutSeconds(header: String?): Int {
    val value = header?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (value == "infinite") return 3600
    val seconds = value.removePrefix("second-").toIntOrNull() ?: 1800
    return seconds.coerceIn(60, 3600)
}

// --- SSDP ---------------------------------------------------------------------------------

/** Notification/search types this device answers to, with their unique service names. */
internal fun ssdpTargets(udn: String): List<Pair<String, String>> = buildList {
    add("upnp:rootdevice" to "$udn::upnp:rootdevice")
    add(udn to udn)
    add(MEDIA_RENDERER to "$udn::$MEDIA_RENDERER")
    RendererService.entries.forEach { add(it.type to "$udn::${it.type}") }
}

/** Search target and MX of an `M-SEARCH`, or null for anything else. */
internal fun parseMSearch(message: String): Pair<String, Int>? {
    val lines = message.split("\r\n", "\n")
    if (!lines.firstOrNull().orEmpty().trim().startsWith("M-SEARCH * HTTP/1.1", ignoreCase = true)) return null
    val headers = lines.drop(1).mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) null else line.substring(0, separator).trim().lowercase(Locale.ROOT) to line.substring(separator + 1).trim()
    }.toMap()
    if (headers["man"]?.removeSurrounding("\"") != "ssdp:discover") return null
    val st = headers["st"]?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
    return st to (headers["mx"]?.toIntOrNull() ?: 1).coerceIn(0, 5)
}

/** The targets an `ST` matches: all for `ssdp:all`, else the exact one (version ≤ ours). */
internal fun matchingTargets(st: String, udn: String): List<Pair<String, String>> {
    val targets = ssdpTargets(udn)
    if (st.equals("ssdp:all", ignoreCase = true)) return targets
    val base = st.substringBeforeLast(':')
    val version = st.substringAfterLast(':').toIntOrNull()
    return targets.filter { (nt, _) ->
        nt.equals(st, ignoreCase = true) ||
            (version != null && version <= 1 && nt.startsWith("urn:") && nt.substringBeforeLast(':').equals(base, ignoreCase = true))
    }.map { (nt, usn) -> st to usn.replace(nt, st) }
}

internal fun ssdpSearchResponse(st: String, usn: String, location: String, server: String, date: String): String =
    "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: $date\r\nEXT:\r\nLOCATION: $location\r\n" +
        "SERVER: $server\r\nST: $st\r\nUSN: $usn\r\nContent-Length: 0\r\n\r\n"

internal fun ssdpNotify(nt: String, usn: String, location: String?, server: String, alive: Boolean): String = buildString {
    append("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n")
    if (alive) append("CACHE-CONTROL: max-age=1800\r\nLOCATION: ").append(location).append("\r\nSERVER: ").append(server).append("\r\n")
    append("NT: ").append(nt).append("\r\nNTS: ").append(if (alive) "ssdp:alive" else "ssdp:byebye").append("\r\n")
    append("USN: ").append(usn).append("\r\n\r\n")
}
