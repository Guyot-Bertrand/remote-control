package fr.remotecontrol.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test fun work() = assertEquals(QrTarget.Work(42), parseQr("rc://w/42"))

    @Test fun season_with_surrounding_whitespace() = assertEquals(QrTarget.Season(7), parseQr(" rc://s/7\n"))

    @Test fun uppercase_scheme_and_host() = assertEquals(QrTarget.Work(42), parseQr("RC://W/42"))

    @Test fun rejects_everything_else() {
        listOf(
            "rc://x/1", "rc://w/abc", "https://exemple.fr", "", "   ",
            "rc://w/", "rc://w/0", "rc://w/-1", "rc://w/+1", "rc://w/1.5", "rc://w/1/", "rc://w/1?x=1", "rc://w/1#a",
            "rc://w//1", "rc:/w/1", "xrc://w/1", "rc://w/1 2", "rc://ws/1", "rc://w/99999999999999999999",
            "rc://w/١٢", // chiffres non ASCII
        ).forEach { assertNull(it, parseQr(it)) }
    }

    @Test fun leading_zeros_accepted() = assertEquals(QrTarget.Season(7), parseQr("rc://s/007"))
}
