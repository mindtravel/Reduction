package com.mindtravel.reduction.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOriginTest {
    private val port = 8780

    @Test
    fun thePanelThePetAndThePhoneAreLetIn() {
        // The panel's own requests: its page is on localhost.
        assertTrue(LocalOrigin.allows(port, "localhost:8780", null))
        assertTrue(LocalOrigin.allows(port, "localhost:8780", "http://localhost:8780"))
        // The pet, and the phone's curl over SSH, which name the server by its address.
        assertTrue(LocalOrigin.allows(port, "127.0.0.1:8780", null))
        assertTrue(LocalOrigin.allows(port, "LOCALHOST:8780", null))
        assertTrue(LocalOrigin.allows(port, "[::1]:8780", "http://[::1]:8780"))
    }

    @Test
    fun aPageThatHadItsNamePointedHereIsRefused() {
        // DNS rebinding: the browser sends the attacker's name, whatever address it now resolves to.
        assertFalse(LocalOrigin.allows(port, "evil.example:8780", null))
        assertFalse(LocalOrigin.allows(port, "evil.example:8780", "http://evil.example:8780"))
        assertFalse(LocalOrigin.allows(port, "localhost.evil.example:8780", null))
        assertFalse(LocalOrigin.allows(port, "127.0.0.1.nip.io:8780", null))
    }

    @Test
    fun aRequestFromAnotherSiteIsRefusedEvenWhenAddressedRight() {
        assertFalse(LocalOrigin.allows(port, "localhost:8780", "https://evil.example"))
        assertFalse(LocalOrigin.allows(port, "localhost:8780", "http://localhost:9999"))
        assertFalse(LocalOrigin.allows(port, "localhost:8780", "https://localhost:8780"))
        assertFalse(LocalOrigin.allows(port, "localhost:8780", "null"))
        assertFalse(LocalOrigin.allows(port, "localhost:8780", "http://user@localhost:8780"))
    }

    @Test
    fun theHostMustNameThisPort() {
        assertFalse(LocalOrigin.allows(port, null, null))
        assertFalse(LocalOrigin.allows(port, "", null))
        assertFalse(LocalOrigin.allows(port, "localhost", null))
        assertFalse(LocalOrigin.allows(port, "localhost:8781", null))
        assertFalse(LocalOrigin.allows(port, "localhost:abc", null))
        assertTrue("port 80 may go unsaid", LocalOrigin.allows(80, "localhost", "http://localhost"))
    }
}
