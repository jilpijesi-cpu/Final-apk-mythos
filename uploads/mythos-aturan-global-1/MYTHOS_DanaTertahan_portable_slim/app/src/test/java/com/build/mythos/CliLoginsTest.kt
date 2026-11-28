package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliLoginsTest {
    @Test
    fun codexUsesTermuxRepositoryPackageAndRealAuthStatus() {
        val codex = requireNotNull(cliLoginOf("Codex (ChatGPT)"))
        assertTrue(codex.install.contains("pkg install -y nodejs codex"))
        assertTrue(codex.install.contains("pkg update"))
        assertEquals("codex login", codex.login)
        assertEquals("codex login status", codex.check)
        assertTrue(codex.run.startsWith("codex exec --skip-git-repo-check"))
        assertTrue("CLI errors must remain visible", !codex.run.contains("2>/dev/null"))
    }

    @Test
    fun codexReadyCheckRequiresInstalledTermuxPackageAndExecutable() {
        val codex = requireNotNull(cliLoginOf("Codex (ChatGPT)"))
        assertTrue(codex.ready.contains("dpkg-query"))
        assertTrue(codex.ready.contains("command -v codex"))
        assertTrue(codex.ready.contains("command -v node"))
        assertTrue(codex.ready.contains("codex --version"))
        assertTrue(codex.ready.contains("codex app-server --help"))
    }

    @Test
    fun geminiAcceptsGoogleOAuthOrApiKeyAuthentication() {
        val gemini = requireNotNull(cliLoginOf("Gemini CLI (Google)"))
        assertTrue(gemini.check.contains("oauth_creds.json"))
        assertTrue(gemini.check.contains("GEMINI_API_KEY"))
        assertTrue(gemini.check.contains(".gemini/.env"))
        assertTrue(gemini.check.contains(".bashrc"))
        assertTrue(gemini.check.contains(".profile"))
        assertEquals("gemini -p {prompt}", gemini.run)
        assertTrue("Gemini errors must remain visible", !gemini.run.contains("2>/dev/null"))
        assertEquals("gemini -p {prompt}", normalizeGeminiCommandTemplate("gemini -p {prompt} 2>/dev/null"))
        assertEquals("gemini -p {prompt}", normalizeGeminiCommandTemplate("gemini -p {prompt}"))
        val envSetup = geminiEnvironmentSetupCommand()
        assertTrue(envSetup.contains(".gemini/.env"))
        assertTrue(envSetup.contains(".bashrc"))
    }

    @Test
    fun otherCliPresetsKeepTheirExistingAuthenticationChecks() {
        assertEquals(
            "test -s ~/.claude/.credentials.json -o -s ~/.claude/credentials.json",
            cliLoginOf("Claude Code (Claude.ai)")?.check
        )
    }

    @Test fun geminiTerpatokKeVersiUji() {
        val cmd = CLI_LOGINS.first { it.name.startsWith("Gemini") }.install
        assertTrue(cmd, cmd.contains("@google/gemini-cli@$GEMINI_CLI_VERSION"))
    }

    @Test fun versiUjiTercatat() {
        assertEquals("0.71.1", GEMINI_CLI_VERSION)
        assertEquals("0.172.0", CODEX_TESTED_VERSION)
        val codex = requireNotNull(cliLoginOf("Codex (ChatGPT)"))
        assertTrue(codex.tip.contains(CODEX_TESTED_VERSION))
        assertTrue(!codex.install.contains("codex="))
    }
}
