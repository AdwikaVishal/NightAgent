package com.example.nightagent.map

import com.example.nightagent.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTileSourcesTest {

    @Test
    fun `locally supplied key is available in BuildConfig`() {
        assertTrue(MapTileSources.hasConfiguredKey())
    }

    @Test
    fun `tile url uses MapTiler raster endpoint and configured key`() {
        val url = MapTileSources.tileUrl(3, 4, 2)

        assertTrue(
            url.startsWith("https://api.maptiler.com/maps/streets-v4/256/3/4/2.png?key=")
        )
        assertTrue(url.endsWith(BuildConfig.MAPTILER_API_KEY.trim()))
    }
}
