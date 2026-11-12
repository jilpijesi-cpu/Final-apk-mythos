package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomainPolicyTest {
    @Test
    fun trustedDomainsAndSubdomainsAreAllowed() {
        assertTrue(BrowserDomainPolicy.isAllowed("https://kontak157.ojk.go.id/appkpublicportal/Home"))
        assertTrue(BrowserDomainPolicy.isAllowed("https://ojk.go.id/"))
        assertTrue(BrowserDomainPolicy.isAllowed("https://www.bi.go.id/id/default.aspx"))
        assertTrue(BrowserDomainPolicy.isAllowed("HTTPS://KONTAK157.OJK.GO.ID./x"))
    }

    @Test
    fun lookalikeHostsAreRejected() {
        listOf(
            "https://evilojk.go.id/", "https://ojk.go.id.evil.com/", "https://ojk.go.id@evil.com/",
            "https://evil.com/?u=ojk.go.id", "https://evil.com/ojk.go.id", "https://notbi.go.id/",
        ).forEach { assertFalse("harus ditolak: $it", BrowserDomainPolicy.isAllowed(it)) }
    }

    @Test
    fun onlyHttpsIsAccepted() {
        listOf(
            "http://kontak157.ojk.go.id/", "intent://ojk.go.id#Intent;end", "javascript:alert(1)",
            "file:///sdcard/x.html", "about:blank", "", "   ", "bukan url",
        ).forEach { assertFalse("harus ditolak: $it", BrowserDomainPolicy.isAllowed(it)) }
        assertNull(BrowserDomainPolicy.hostOf("http://ojk.go.id"))
    }

    @Test
    fun extraHostsAreExactMatchOnly() {
        val extra = listOf("login.example.com")
        assertTrue(BrowserDomainPolicy.isAllowed("https://login.example.com/a", extra))
        assertFalse(BrowserDomainPolicy.isAllowed("https://example.com/a", extra))
        assertFalse(BrowserDomainPolicy.isAllowed("https://evil.login.example.com/a", extra))
        assertFalse(BrowserDomainPolicy.isAllowed("http://login.example.com/a", extra))
    }

    @Test
    fun hostOfNormalizes() {
        assertEquals("kontak157.ojk.go.id", BrowserDomainPolicy.hostOf(" https://Kontak157.OJK.go.id:443/a?b=1 "))
    }

    @Test fun allowAllPermitsAnyHttpsButNeverHttp() {
        assertTrue(BrowserDomainPolicy.isAllowed("https://contoh.com/x", emptyList(), allowAll = true))
        org.junit.Assert.assertFalse(BrowserDomainPolicy.isAllowed("http://contoh.com", emptyList(), allowAll = true))
        org.junit.Assert.assertFalse(BrowserDomainPolicy.isAllowed("https://contoh.com", emptyList()))
    }
}
