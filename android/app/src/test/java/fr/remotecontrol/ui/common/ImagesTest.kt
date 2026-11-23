package fr.remotecontrol.ui.common

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesTest {
    @Test fun cache_key_is_host_independent() {
        assertEquals("asset/abc/thumb", assetCacheKey("abc", AssetSize.Thumb))
    }

    @Test fun url_points_to_asset_endpoint() {
        assertEquals(
            "http://192.168.1.20:8765/api/assets/abc/thumb.jpg",
            assetUrl("http://192.168.1.20:8765/".toHttpUrl(), "abc", AssetSize.Thumb),
        )
    }

    @Test fun different_hosts_share_cache_key() {
        val a = assetUrl("http://192.168.1.20:8765/".toHttpUrl(), "abc", AssetSize.Tv)
        val b = assetUrl("http://10.0.0.5:8765/".toHttpUrl(), "abc", AssetSize.Tv)
        assertNotEquals(a, b)
        assertEquals(assetCacheKey("abc", AssetSize.Tv), assetCacheKey("abc", AssetSize.Tv))
    }

    @Test fun offline_url_without_base() {
        assertTrue(assetUrl(null, "abc", AssetSize.Orig).startsWith("http://offline.invalid/"))
    }
}
