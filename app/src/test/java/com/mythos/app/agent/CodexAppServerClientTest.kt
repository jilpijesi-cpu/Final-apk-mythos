package com.mythos.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerClientTest {
    @Test fun promptMempertahankanProtokolMythosDanMenonaktifkanToolNative() {
        val prompt = codexPrompt(
            "Gunakan <tool_use> untuk tool aplikasi.",
            listOf(
                Msg(Role.USER, "Baca berkas"),
                Msg(Role.ASSISTANT, "<tool_use>{}</tool_use>"),
                Msg(Role.USER, "Hasil tool")
            )
        )
        assertTrue(prompt.contains("endpoint model bahasa untuk aplikasi Mythos"))
        assertTrue(prompt.contains("Jangan memakai tool bawaan Codex"))
        assertTrue(prompt.contains("<MYTHOS_SYSTEM>\nGunakan <tool_use>"))
        assertTrue(prompt.contains("<USER>\nBaca berkas\n</USER>"))
        assertTrue(prompt.contains("<ASSISTANT>\n<tool_use>{}</tool_use>\n</ASSISTANT>"))
    }
}
