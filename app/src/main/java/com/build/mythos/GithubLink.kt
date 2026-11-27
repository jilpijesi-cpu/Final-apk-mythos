package com.build.mythos

// Link repository GitHub dari Pengaturan -> owner + nama repo (logika murni, diuji di GithubLinkTest).
// Pengguna cukup mengisi satu link (dan token); pemilik dan nama repo diambil dari link itu.

internal data class RepoRef(val owner: String, val repo: String) {
    val url: String get() = "https://github.com/$owner/$repo"
}

private val NAME_RE = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
private val HOST_RE = Regex("^(https?://)?(www\\.)?github\\.com/", RegexOption.IGNORE_CASE)

/**
 * Terima: https://github.com/owner/repo, .../repo.git, .../repo/tree/main, git@github.com:owner/repo.git,
 * ssh://git@github.com/owner/repo, github.com/owner/repo, dan "owner/repo". Selain itu null.
 * Nama dibatasi huruf/angka/_ . - sehingga aman dipakai di perintah shell.
 */
internal fun parseRepoLink(raw: String): RepoRef? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    s = when {
        s.startsWith("git@github.com:", ignoreCase = true) -> s.substring("git@github.com:".length)
        s.startsWith("ssh://git@github.com/", ignoreCase = true) -> s.substring("ssh://git@github.com/".length)
        else -> s.replace(HOST_RE, "")
    }
    s = s.substringBefore('?').substringBefore('#').trimEnd('/')
    val parts = s.split('/')
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1].removeSuffix(".git")
    if (!NAME_RE.matches(owner) || !NAME_RE.matches(repo)) return null
    if (owner == "." || owner == ".." || repo == "." || repo == "..") return null
    return RepoRef(owner, repo)
}
