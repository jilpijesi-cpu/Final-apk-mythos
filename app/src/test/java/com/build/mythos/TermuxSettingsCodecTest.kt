package com.build.mythos

import org.junit.Assert.*
import org.junit.Test

class TermuxSettingsCodecTest {
    @Test fun roundTripKeepsUnicodeSpacesAndShellCharacters() {
        val workdir = "~/Proyek MYTHOS/versi-2"
        val repo = "https://github.com/JepriNicholee/automatic-couscous"
        val provider = "Gemini CLI (Google)"
        val model = "gemini-2.5-flash"
        val decoded = TermuxSettingsCodec.decode(TermuxSettingsCodec.encode(workdir, repo, provider, model, 123456L))
        assertNotNull(decoded)
        assertEquals(workdir, decoded?.workdir)
        assertEquals(repo, decoded?.repoUrl)
        assertEquals(provider, decoded?.provider)
        assertEquals(model, decoded?.model)
        assertEquals(123456L, decoded?.updatedAt)
    }

    @Test fun rejectsUnknownSchema() {
        assertNull(TermuxSettingsCodec.decode("schema=2\nworkdir_b64=TVlUSE9T\n"))
    }

    @Test fun rejectsCorruptBase64RatherThanApplyingPartialSettings() {
        assertNull(TermuxSettingsCodec.decode("schema=1\nworkdir_b64=%%%\n"))
    }

    @Test fun acceptsPartialVersionOneSettings() {
        val decoded = TermuxSettingsCodec.decode("schema=1\nrepo_url_b64=aHR0cHM6Ly9naXRodWIuY29tL2Evcg==\n")
        assertNotNull(decoded)
        assertNull(decoded?.workdir)
        assertEquals("https://github.com/a/r", decoded?.repoUrl)
        assertNull(decoded?.provider)
        assertNull(decoded?.model)
    }
}
