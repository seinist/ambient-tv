package com.ambient.tv.data.weather

import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal fun weatherMapCapabilitiesUrl(provider: WeatherMapProvider, now: Instant): String = when (provider) {
    WeatherMapProvider.RAINVIEWER -> "https://api.rainviewer.com/public/weather-maps.json"
    WeatherMapProvider.DWD -> "https://maps.dwd.de/geoserver/dwd/Radar_wn-analysis_1x1km_ger/wms?service=WMS&version=1.3.0&request=GetCapabilities"
    WeatherMapProvider.EUMETSAT -> "https://view.eumetsat.int/geoserver/mumi/worldcloudmap_ir108/wms?service=WMS&version=1.3.0&request=GetCapabilities"
    WeatherMapProvider.NASA -> {
        val day = now.atZone(ZoneOffset.UTC).toLocalDate()
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/1.0.0/VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/GoogleMapsCompatible_Level9/all/${day.minusDays(7)}--$day.xml"
    }
}

internal fun parseWeatherMapSeries(provider: WeatherMapProvider, text: String, now: Instant): WeatherMapSeries {
    val frames: List<WeatherMapFrame>
    var bounds = WeatherMapBounds()
    when (provider) {
        WeatherMapProvider.RAINVIEWER -> {
            val root = Json.parseToJsonElement(text).jsonObject
            frames = root["radar"]?.jsonObject?.get("past")?.jsonArray.orEmpty().mapNotNull { item ->
                val obj = item.jsonObject
                val epoch = obj["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!Regex("/v2/radar/[0-9]{9,13}").matches(path) || epoch !in 1..now.epochSecond + 300) return@mapNotNull null
                WeatherMapFrame(Instant.ofEpochSecond(epoch).toString(), epoch * 1000, path)
            }.distinctBy { it.epochMillis }.sortedBy { it.epochMillis }.takeLast(13)
        }
        WeatherMapProvider.NASA -> {
            val document = weatherMapXml(text)
            val domains = document.getElementsByTagName("Domain")
            check(domains.length > 0) { "Missing image time domain" }
            frames = weatherMapTimes(domains.item(0).textContent, null, now, daily = true)
        }
        else -> {
            val document = weatherMapXml(text)
            val layers = document.getElementsByTagNameNS("*", "Layer")
            val layer = (0 until layers.length).map { layers.item(it) as Element }
                // GeoServer's layer-scoped capabilities omit the workspace prefix.
                .firstOrNull { it.direct("Name")?.textContent in setOf(provider.layer, provider.layer.substringAfter(':')) }
                ?: error("Published map layer is not available")
            val dimension = layer.directChildren("Dimension").firstOrNull { it.getAttribute("name") == "time" }
                ?: error("Missing image time dimension")
            frames = weatherMapTimes(dimension.textContent, dimension.getAttribute("default"), now, provider.daily)
            layer.direct("EX_GeographicBoundingBox")?.let { box ->
                bounds = WeatherMapBounds(
                    box.direct("westBoundLongitude")!!.textContent.toDouble(), box.direct("southBoundLatitude")!!.textContent.toDouble(),
                    box.direct("eastBoundLongitude")!!.textContent.toDouble(), box.direct("northBoundLatitude")!!.textContent.toDouble(),
                )
            } ?: layer.directChildren("BoundingBox").firstOrNull { it.getAttribute("CRS") == "EPSG:4326" }?.let { box ->
                // WMS 1.3 EPSG:4326 has latitude,longitude axis order (3857 GetMap uses x,y).
                bounds = WeatherMapBounds(box.getAttribute("miny").toDouble(), box.getAttribute("minx").toDouble(),
                    box.getAttribute("maxy").toDouble(), box.getAttribute("maxx").toDouble())
            }
        }
    }
    check(frames.isNotEmpty()) { "No published map times" }
    return WeatherMapSeries(provider.id, frames, now.toEpochMilli(), bounds)
}

private fun weatherMapXml(text: String): org.w3c.dom.Document {
    require(text.length <= 512_000 && !text.contains("<!DOCTYPE", ignoreCase = true))
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return builder.parse(InputSource(StringReader(text)))
}

private fun Element.direct(name: String): Element? = directChildren(name).firstOrNull()
private fun Element.directChildren(name: String): List<Element> = (0 until childNodes.length).mapNotNull {
    (childNodes.item(it) as? Element)?.takeIf { child -> (child.localName ?: child.tagName) == name }
}

internal fun weatherMapImageUrl(provider: WeatherMapProvider, viewport: WeatherMapViewport, frame: WeatherMapFrame): String =
    if (provider == WeatherMapProvider.RAINVIEWER) {
        require(Regex("/v2/radar/[0-9]{9,13}").matches(frame.path))
        // A single reflectivity palette for all precipitation; the legend has no snow-type claim.
        "https://tilecache.rainviewer.com${frame.path}/512/${viewport.zoom}/${viewport.x}/${viewport.y}/2/1_0.png"
    } else provider.endpoint.toHttpUrl().newBuilder()
        .addQueryParameter("SERVICE", "WMS").addQueryParameter("REQUEST", "GetMap")
        .addQueryParameter("VERSION", "1.3.0").addQueryParameter("CRS", "EPSG:3857")
        .addQueryParameter("LAYERS", provider.layer).addQueryParameter("STYLES", "")
        .addQueryParameter("BBOX", viewport.mercatorBounds).addQueryParameter("WIDTH", "512")
        .addQueryParameter("HEIGHT", "512").addQueryParameter("FORMAT", "image/png")
        .addQueryParameter("TRANSPARENT", "TRUE").addQueryParameter("TIME", frame.time).build().toString()

internal fun weatherMapCoverageUrl(viewport: WeatherMapViewport): String =
    "https://tilecache.rainviewer.com/v2/coverage/0/512/${viewport.zoom}/${viewport.x}/${viewport.y}/0/0_0.png"

internal fun weatherBaseMapUrl(viewport: WeatherMapViewport): String =
    "https://tile.openstreetmap.org/${viewport.zoom}/${viewport.x}/${viewport.y}.png"
