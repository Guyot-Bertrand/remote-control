package fr.remotecontrol

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun launcher_shows_brand_name_and_adaptive_icon() {
        val info = context.applicationInfo
        assertEquals("Remote-Control", info.loadLabel(context.packageManager).toString())
        assertTrue(info.icon != 0)
        assertEquals(R.mipmap.ic_launcher, info.icon)
        assertTrue(context.getDrawable(info.icon) is AdaptiveIconDrawable)
        assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }
}
