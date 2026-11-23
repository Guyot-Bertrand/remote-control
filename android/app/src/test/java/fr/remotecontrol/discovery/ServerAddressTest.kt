package fr.remotecontrol.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test fun host_only() = assertEquals(ServerAddress("192.168.1.20", 8765), parseManualAddress("192.168.1.20"))
    @Test fun host_port() = assertEquals(9000, parseManualAddress("192.168.1.20:9000")?.port)
    @Test fun url() = assertEquals(ServerAddress("mini-pc.local", 8765), parseManualAddress("http://mini-pc.local:8765/"))
    @Test fun url_scheme_case_insensitive() = assertEquals(ServerAddress("h", 1), parseManualAddress("HTTP://h:1"))
    @Test fun bracketed_ipv6() = assertEquals(ServerAddress("fe80::1", 9000), parseManualAddress("[fe80::1]:9000"))
    @Test fun bare_ipv6() = assertEquals(ServerAddress("fe80::1", 8765), parseManualAddress("fe80::1"))
    @Test fun invalid() {
        assertNull(parseManualAddress(""))
        assertNull(parseManualAddress("a b"))
        assertNull(parseManualAddress("x:70000"))
        assertNull(parseManualAddress("a#b"))
    }
    @Test fun base_url_brackets_ipv6() =
        assertEquals("http://[fe80::1]:8765/", ServerAddress("fe80::1").baseUrl.toString())
    @Test fun display() = assertEquals("192.168.1.20:8765", ServerAddress("192.168.1.20").display)
}
