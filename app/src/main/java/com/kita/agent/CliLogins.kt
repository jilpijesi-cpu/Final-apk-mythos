package com.kita.agent

internal class CliLogin(
    val name: String,
    val account: String,
    val bin: String,
    val install: String,
    val login: String,
    val check: String,
    val run: String,
    val tip: String = ""
)

private const val NODE = "pkg install -y nodejs && "

internal val CLI_LOGINS = listOf(
    CliLogin(
        "Codex (ChatGPT)", "ChatGPT", "codex",
        NODE + "npm install -g @openai/codex", "codex login",
        "test -s ~/.codex/auth.json",
        "codex exec --skip-git-repo-check {prompt} 2>/dev/null"
    ),
    CliLogin(
        "Gemini CLI (Google)", "Google", "gemini",
        NODE + "npm install -g @google/gemini-cli", "gemini",
        "test -s ~/.gemini/oauth_creds.json",
        "gemini -p {prompt} 2>/dev/null",
        "Pilih \"Login with Google\", lalu keluar dari Gemini setelah berhasil"
    ),
    CliLogin(
        "Claude Code (Claude.ai)", "Claude.ai", "claude",
        NODE + "npm install -g @anthropic-ai/claude-code", "claude",
        "test -s ~/.claude/.credentials.json -o -s ~/.claude/credentials.json",
        "claude -p {prompt} 2>/dev/null",
        "Ikuti login di browser, lalu keluar dari Claude setelah berhasil"
    )
)

internal fun cliLoginOf(name: String): CliLogin? = CLI_LOGINS.firstOrNull { it.name == name }