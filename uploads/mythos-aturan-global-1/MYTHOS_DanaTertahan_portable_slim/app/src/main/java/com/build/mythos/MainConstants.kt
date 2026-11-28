package com.build.mythos

// Konstanta tanpa status yang dulu menjadi properti MainActivity. Dipindah ke tingkat berkas; cara pakainya
// (nama saja) tidak berubah. termuxHome / termuxTmp / retryable sengaja tetap di MainActivity karena dipakai
// dengan awalan objek (mis. a.termuxHome) atau bertabrakan dengan nama variabel lokal.

internal val skipWords = listOf(
    "image", "tts", "embed", "computer-use", "deep-research", "imagen", "veo", "live", "audio",
    "robotics", "aqa", "banana", "lyria", "whisper", "dall-e", "moderation", "davinci", "babbage", "transcribe", "realtime"
)

internal val roCmds = setOf(
    "ls", "cat", "grep", "egrep", "fgrep", "head", "tail", "wc", "pwd", "tree",
    "find", "du", "df", "stat", "diff", "cut", "basename", "dirname", "which"
)

internal val findBad = setOf(
    "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls"
)

internal val truncMsg = "Argumen tool tidak valid atau terpotong (kemungkinan batas token tercapai). " +
    "Pecah pekerjaan menjadi bagian lebih kecil, misalnya beberapa file yang lebih pendek, lalu coba lagi."

internal val allowCmd =
    "mkdir -p ~/.termux && (grep -q '^allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || " +
    "echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings"

internal val toolCheckCmd =
    "for t in java git; do command -v \$t >/dev/null 2>&1 && echo \$t=ok || echo \$t=no; done; " +
    "(ls ${WorkspacePaths.SDCARD_DOWNLOAD} >/dev/null 2>&1 && echo storage=ok) || echo storage=no"

internal val buildRe = Regex("""gradle\S*\s+.*assemble""")

internal val fenceRe = Regex("```([^\\n`]*)\\n(.*?)(```|\\z)", RegexOption.DOT_MATCHES_ALL)

internal val headRe = Regex("^(#{1,6})\\s+(.*)$")

internal val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")

// Grup: 1 kode inline, 2 tebal, 3 miring, 4 teks tautan, 5 tujuan tautan ([teks](tujuan), tujuan tanpa spasi/kurung tutup).
internal val inlineRe = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])|\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)")

internal val kwSet = setOf(
    "fun", "val", "var", "class", "object", "interface", "override", "private", "public", "protected", "internal",
    "if", "else", "when", "for", "while", "do", "return", "break", "continue", "import", "package", "null",
    "true", "false", "this", "super", "is", "in", "as", "try", "catch", "finally", "throw", "new", "static",
    "final", "void", "int", "long", "double", "float", "boolean", "char", "string", "let", "const", "function",
    "def", "lambda", "from", "with", "pass", "None", "True", "False", "elif", "except", "raise", "yield",
    "async", "await", "fi", "then", "done", "esac", "case", "echo", "cd", "export", "set", "local", "sudo",
    "enum", "data", "sealed", "abstract", "open", "lateinit", "by", "init", "constructor", "extends",
    "implements", "switch", "default", "struct", "impl", "pub", "use", "mod", "mut", "fn", "match", "self"
)

internal val hashLangs = setOf("bash", "sh", "shell", "zsh", "python", "py", "yaml", "yml", "toml", "ruby", "rb", "properties", "dockerfile")
