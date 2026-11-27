package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWorkspaceTest {
    private val base = "/data/data/com.termux/files/home/MYTHOS"
    private val q = { s: String -> "'" + s.replace("'", "'\\''") + "'" }

    @Test fun folderChatDariIdAman() {
        assertEquals("$base/chats/c1730000000000", ChatWorkspaceRules.chatDir("$base/", "c1730000000000"))
        assertNull(ChatWorkspaceRules.chatDir(base, "../etc"))
        assertNull(ChatWorkspaceRules.chatDir(base, "a b"))
        assertNull(ChatWorkspaceRules.chatDir(base, ""))
    }

    @Test fun hanyaFolderChatSahYangBolehDihapus() {
        assertTrue(ChatWorkspaceRules.isChatDir("$base/chats/c1730000000000"))
        assertFalse(ChatWorkspaceRules.isChatDir(base))
        assertFalse(ChatWorkspaceRules.isChatDir("$base/chats"))
        assertFalse(ChatWorkspaceRules.isChatDir("$base/chats/"))
        assertFalse(ChatWorkspaceRules.isChatDir("$base/chats/c1/../../reference"))
        assertFalse(ChatWorkspaceRules.isChatDir("$base/chats/c1/sub"))
        assertFalse(ChatWorkspaceRules.isChatDir("$base/reference"))
        assertFalse(ChatWorkspaceRules.isChatDir("/"))
        assertFalse(ChatWorkspaceRules.isChatDir("chats/c1"))
    }

    @Test fun perintahHapusDitolakUntukPathBerbahaya() {
        assertNull(ChatWorkspaceRules.deleteCommand(base, q))
        assertNull(ChatWorkspaceRules.deleteCommand("/", q))
        assertNull(ChatWorkspaceRules.deleteCommand("$base/chats/c1; rm -rf ~", q))
        val ok = ChatWorkspaceRules.deleteCommand("$base/chats/c1730000000000", q)
        assertNotNull(ok)
        assertTrue(ok!!.contains("rm -rf -- \"\$d\""))
        assertTrue(ok.contains("[ -L"))            // symlink dilepas, bukan diikuti
        assertTrue(ok.contains("MYTHOS_WS_DELETED"))
    }

    @Test fun chatLainTerdeteksiDanChatAktifBukan() {
        val cur = "$base/chats/c111111"
        assertTrue(ChatWorkspaceRules.isOtherChat("$base/chats/c222222/projects/x", base, cur))
        assertTrue(ChatWorkspaceRules.isOtherChat("$base/chats/c1111112", base, cur)) // awalan mirip, bukan chat aktif
        assertFalse(ChatWorkspaceRules.isOtherChat("$base/chats/c111111/projects/x", base, cur))
        assertFalse(ChatWorkspaceRules.isOtherChat(cur, base, cur))
        assertFalse(ChatWorkspaceRules.isOtherChat("$base/memory/chat_memory.json", base, cur))
        assertFalse(ChatWorkspaceRules.isOtherChat("/tmp/x", base, cur))
    }

    @Test fun penyapuHanyaMenyentuhIdAplikasiYangTidakDikenal() {
        val names = listOf("c111111", "c222222", "c333333", "notes", "c12", "-rf", "c444444 ", "")
        val out = ChatWorkspaceRules.orphans(names, setOf("c111111", "c333333"))
        assertEquals(listOf("c222222", "c444444"), out)
    }

    @Test fun hapusWorkspaceLamaDitolakBilaBaseAdalahHome() {
        val home = "/data/data/com.termux/files/home"
        assertNull(ChatWorkspaceRules.legacyWipeCommand(home, home, q))
        assertNull(ChatWorkspaceRules.legacyWipeCommand("$home/", home, q))
        assertNull(ChatWorkspaceRules.legacyWipeCommand("", home, q))
        val cmd = ChatWorkspaceRules.legacyWipeCommand("$home/MYTHOS", home, q)!!
        assertTrue(cmd.contains("'$home/MYTHOS/projects'"))
        assertTrue(cmd.contains("'$home/MYTHOS/attachments'"))
        assertFalse("reference tidak boleh disentuh", cmd.contains("reference"))
        assertFalse("folder chats tidak boleh disentuh", cmd.contains("chats"))
    }
}
