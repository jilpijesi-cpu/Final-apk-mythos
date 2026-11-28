package com.build.mythos

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleOAuthTest {
    private val pkg = "com.build.mythos"

    @Test fun pkceChallengeMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun authUrlHasPkceStateScopeAndPackageRedirect() {
        val s = GoogleOAuth.newSession()
        val u = GoogleOAuth.authUrl("abc.apps.googleusercontent.com", pkg, s, "nama@gmail.com")
        assertTrue(u.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(u.contains("client_id=abc.apps.googleusercontent.com"))
        assertTrue(u.contains("redirect_uri=com.build.mythos%3A%2Foauth2redirect"))
        assertTrue(u.contains("code_challenge_method=S256"))
        assertTrue(u.contains("access_type=offline"))
        assertTrue(u.contains("state=" + s.state))
        assertTrue(u.contains("login_hint=nama%40gmail.com"))
        assertTrue(u.contains("gmail.readonly"))
        assertTrue(u.contains("gmail.send"))
        assertFalse(u.contains("mail.google.com"))
        assertFalse(u.contains(s.verifier))
    }

    @Test fun blankClientIdIsRejected() {
        try { GoogleOAuth.authUrl(" ", pkg, GoogleOAuth.newSession()); fail() } catch (e: ConnectorException) { assertTrue(e.message!!.contains("Client ID")) }
    }

    @Test fun redirectChecks() {
        val s = GoogleSession("st4te", "ver", 1_000L)
        val ok = GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("code" to "4/abc", "state" to "st4te"), pkg, s, 2_000L)
        assertEquals("4/abc", ok!!.code)
        assertNull(GoogleOAuth.parseRedirect("https", null, "/oauth2redirect", mapOf(), pkg, s, 2_000L))
        assertNull(GoogleOAuth.parseRedirect(pkg, null, "/lain", mapOf(), pkg, s, 2_000L))
        assertNull(GoogleOAuth.parseRedirect(pkg, "evil", "/oauth2redirect", mapOf(), pkg, s, 2_000L))
        assertNotNull(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("code" to "x", "state" to "salah"), pkg, s, 2_000L)!!.error)
        assertNotNull(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("code" to "x", "state" to "st4te"), pkg, null, 2_000L)!!.error)
        assertNotNull(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("code" to "x", "state" to "st4te"), pkg, s, 1_000L + GoogleOAuth.SESSION_TTL_MS + 1)!!.error)
        assertTrue(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("error" to "access_denied", "state" to "st4te"), pkg, s, 2_000L)!!.error!!.contains("ditolak"))
        assertTrue(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("error" to "server_error", "state" to "st4te"), pkg, s, 2_000L)!!.error!!.contains("server_error"))
        assertNotNull(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("state" to "st4te"), pkg, s, 2_000L)!!.error)
        assertNotNull(GoogleOAuth.parseRedirect(pkg, null, "/oauth2redirect", mapOf("code" to "   ", "state" to "st4te"), pkg, s, 2_000L)!!.error)
    }

    @Test fun invalidLoginHintIsOmittedFromAuthUrl() {
        val u = GoogleOAuth.authUrl("abc.apps.googleusercontent.com", pkg, GoogleOAuth.newSession(), "bukan-email&redirect_uri=https://evil.test")
        assertFalse(u.contains("login_hint="))
        assertFalse(u.contains("evil.test"))
    }

    @Test fun xoauth2StringFormat() {
        assertEquals("user=a@gmail.com\u0001auth=Bearer tok\u0001\u0001", GoogleOAuth.xoauth2("a@gmail.com", "tok"))
        val dec = String(Base64.getDecoder().decode(GoogleOAuth.xoauth2B64("a@gmail.com", "tok")), Charsets.UTF_8)
        assertEquals(GoogleOAuth.xoauth2("a@gmail.com", "tok"), dec)
    }
}

class ConnectorLogosTest {
    @Test fun everyHubConnectorHasHttpsLogoUrl() {
        for (id in listOf("email", "telegram", "wa_hp", "whatsapp")) {
            val u = ConnectorLogos.url(id)
            assertNotNull(id, u)
            assertTrue(u!!.startsWith("https://"))
            assertTrue(u.contains("domain=" + ConnectorLogos.info(id).domain))
        }
        assertNull(ConnectorLogos.url("tidak-ada"))
    }

    @Test fun cacheKeysDoNotCollideWithProviderNames() {
        assertEquals("konektor_email", ConnectorLogos.cacheKey("email"))
        assertTrue(ConnectorLogos.cacheKey("telegram") != ConnectorLogos.cacheKey("wa_hp"))
    }
}
