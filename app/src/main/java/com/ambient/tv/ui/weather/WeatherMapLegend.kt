package com.ambient.tv.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherMapProvider

/** Representative values from the providers' published reflectivity legends, in dBZ. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherMapLegend(provider: WeatherMapProvider) {
    val entries = when (provider) {
        // https://www.rainviewer.com/files/rainviewer_api_colors_table.csv (Universal Blue)
        WeatherMapProvider.RAINVIEWER -> listOf("15" to 0xff88ddee, "25" to 0xff0077aa, "35" to 0xffffee00,
            "45" to 0xffff4400, "55" to 0xffffaaff, "65" to 0xffffffff)
        // Official WMS GetLegendGraphic, radar_wn-analysis_1x1km_ger, verified 2026-11-15.
        WeatherMapProvider.DWD -> listOf("7–9.5" to 0xff99ffff, "14.5–19" to 0xff009934, "32.5–37" to 0xffffff00,
            "41.5–46" to 0xffff8900, "50.5–55" to 0xffb40000, "65–75" to 0xff990099)
        else -> return
    }
    Text(stringResource(R.string.weather_maps_legend_samples), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        entries.forEach { (label, colour) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(46.dp).height(8.dp).background(Color(colour)))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    WeatherSourceLink(stringResource(R.string.weather_maps_full_legend), when (provider) {
        WeatherMapProvider.RAINVIEWER -> "https://www.rainviewer.com/api/color-schemes.html"
        else -> "https://maps.dwd.de/geoserver/ows?service=WMS&version=1.3.0&request=GetLegendGraphic&format=image/png&width=20&height=20&layer=dwd:Radar_wn-analysis_1x1km_ger"
    })
}
