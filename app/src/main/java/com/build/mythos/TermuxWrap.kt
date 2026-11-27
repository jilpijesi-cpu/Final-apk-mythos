package com.build.mythos

// Pembungkus bash untuk perintah Termux lewat RUN_COMMAND. Murni Kotlin supaya bisa diuji (TermuxWrapTest).

internal const val TERMUX_BIN = WorkspacePaths.TERMUX_BIN
internal const val TERMUX_HOME = WorkspacePaths.TERMUX_HOME
internal const val TERMUX_PRELOAD_LIB = WorkspacePaths.TERMUX_PRELOAD_LIB

/**
 * Layanan RUN_COMMAND memulai bash TANPA termux-exec, sehingga skrip ber-shebang "#!/usr/bin/env node" (gemini, codex)
 * gagal dengan "bad interpreter". LD_PRELOAD harus sudah ada saat bash dimulai (mengekspornya dari dalam bash tidak berguna),
 * jadi bash dijalankan ulang sekali lewat env bila pustakanya ada. Pemakaian: argumen bash `-c <ini> <nama> <skrip>`;
 * skrip yang sebenarnya dibaca sebagai "$1" (di sini $0 = nama).
 */
internal fun preloadWrapper(bin: String = TERMUX_BIN, lib: String = TERMUX_PRELOAD_LIB): String =
    "L=$lib; if [ -f \"\$L\" ] && [ \"\${LD_PRELOAD:-}\" != \"\$L\" ]; then " +
        "exec $bin/env LD_PRELOAD=\"\$L\" $bin/bash -c \"\$1\"; fi; eval \"\$1\""
