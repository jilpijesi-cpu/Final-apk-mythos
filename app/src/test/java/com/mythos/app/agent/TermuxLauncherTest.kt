package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class TermuxLauncherTest {
    private val token = "a".repeat(64)

    @Test fun tokenSesiLoopbackValidDanTokenBerbahayaDitolak() {
        assertTrue(TermuxLauncher.isValidToken(token))
        assertTrue(TermuxLauncher.isValidToken("abc-DEF_123".repeat(4)))
        for (bad in listOf("", "pendek", "a'; rm -rf ~; '".padEnd(40, 'a'), "a b".repeat(20), "a\nb".repeat(20))) {
            assertFalse(TermuxLauncher.isValidToken(bad))
            assertFalse(runCatching { TermuxLauncher.buildAppServerCommand("console.log(1)", 41000, bad) }.isSuccess)
        }
    }

    @Test fun loginMenggunakanPaketCodexDanNodeTanpaPythonBridge() {
        val install = TermuxLauncher.buildCodexLoginCommand()
        val ready = TermuxLauncher.codexReadyCommand()
        val status = TermuxLauncher.buildCodexStatusCommand()
        assertTrue(install.contains("pkg install -y nodejs codex"))
        assertTrue(install.contains("codex login"))
        assertTrue(install.contains("BROWSER=termux-open-url"))
        assertTrue(ready.contains("codex app-server --help"))
        assertTrue(status.contains("codex login status"))
        assertFalse(install.contains("python"))
        assertFalse(status.contains("auth.json"))
    }

    @Test fun pemetaanStatusCLIStabil() {
        assertEquals(CodexCliStatus.READY, TermuxLauncher.parseCodexCliStatus("exit=0\nMYTHOS_CODEX_READY\n"))
        assertEquals(CodexCliStatus.LOGIN_REQUIRED, TermuxLauncher.parseCodexCliStatus("MYTHOS_CODEX_LOGIN_REQUIRED"))
        assertEquals(CodexCliStatus.NOT_INSTALLED, TermuxLauncher.parseCodexCliStatus("MYTHOS_CODEX_NOT_INSTALLED"))
        assertEquals(CodexCliStatus.NOT_READY, TermuxLauncher.parseCodexCliStatus("MYTHOS_CODEX_NOT_READY"))
        assertEquals(CodexCliStatus.UNKNOWN, TermuxLauncher.parseCodexCliStatus("timeout"))
    }

    @Test fun perintahMenjalankanNodeRunnerDenganSesiAcakTanpaDaemonPython() {
        val runner = "const cp=require('child_process');\nconsole.log('codex app-server');\n"
        val command = TermuxLauncher.buildAppServerCommand(runner, 49152, token)
        assertTrue(command.contains("node \"\$HOME/.mythos_codex/app_server.js\" 49152 '$token'"))
        assertTrue(command.contains("mkdir -p \"\$HOME/.mythos_codex/session\""))
        assertTrue(command.contains("kill \"\$p\""))
        assertTrue(command.contains("\$HOME/codex_bridge.py")) // hanya untuk menghentikan proses lama saat migrasi
        assertFalse(command.contains("python"))
        assertFalse(command.contains("8787"))
        assertFalse(command.contains("nohup"))
        assertFalse(command.contains("setsid"))
        val encoded = Regex("printf %s '([A-Za-z0-9+/=]+)' \\| base64 -d").find(command)!!.groupValues[1]
        assertEquals(runner, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
    }

    @Test fun runnerDitulisAtomikAgarPermintaanParalelTidakMembacaBerkasTerpotong() {
        val cmd = TermuxLauncher.buildAppServerCommand("console.log(1)", 49152, token)
        val tmp = "\"\$HOME/.mythos_codex/app_server.js.\$\$\""
        val finalPath = "\"\$HOME/.mythos_codex/app_server.js\""
        assertTrue(cmd.contains("base64 -d > $tmp"))
        assertTrue(cmd.contains("mv -f $tmp $finalPath"))
        // Tidak ada penulisan langsung (>) ke berkas akhir yang dibaca node.
        assertFalse(cmd.contains("base64 -d > $finalPath"))
        assertTrue(cmd.indexOf("mv -f") < cmd.indexOf("node \""))
    }

    @Test fun assetRunnerBerkomunikasiDenganAppServerViaStdio() {
        val asset = File("src/main/assets/codex_app_server.js")
        assertTrue("app-server runner asset missing", asset.exists())
        val source = asset.readText()
        assertTrue(source.contains("const HARDENED_ARGS = ['-c', 'features.shell_tool=false'"))
        // patch9: opsi tambahan (mis. web_search dimatikan) disisipkan, tetapi pengerasan inti tetap selalu dipasang paling depan.
        assertTrue(source.contains("HARDENED_ARGS.concat(EXTRA_ARG_TIERS[argTier], ['app-server', '--listen', 'stdio://'])"))
        assertTrue(source.contains("cp.spawn('codex', args,"))
        assertTrue(source.contains("item/agentMessage/delta"))
        assertTrue(source.contains("approvalPolicy: 'untrusted'"))
        assertTrue(source.contains("sandbox: 'read-only'"))
        assertTrue(source.contains("detached: true"))
        assertTrue(source.contains("process.kill(-child.pid, signal)"))
        assertTrue(source.contains("SIGKILL"))
        assertFalse(source.contains("codex_bridge.py"))
    }

}

class CodexAppServerStreamTest {
    @Test fun koneksiDenganTokenSalahDitutupDanListenerTetapMenerimaTokenSah() {
        val expectedToken = "a".repeat(64)
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val result = AtomicReference<TermuxLauncher.AuthenticatedSocket?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val listener = Thread {
            try {
                result.set(TermuxLauncher.acceptAuthenticatedSocket(server, expectedToken, 3_000, 500))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            listener.start()
            Socket("127.0.0.1", server.localPort).use { unauthorized ->
                unauthorized.soTimeout = 2_000
                unauthorized.getOutputStream().write("wrong-token\n".toByteArray(Charsets.UTF_8))
                unauthorized.getOutputStream().flush()
                assertEquals(-1, unauthorized.getInputStream().read())
            }
            Socket("127.0.0.1", server.localPort).use { authorized ->
                authorized.getOutputStream().write("$expectedToken\n".toByteArray(Charsets.UTF_8))
                authorized.getOutputStream().flush()
                listener.join(2_000)
                assertFalse("listener berhenti sebelum handshake sah", listener.isAlive)
                assertNull(failure.get())
                assertTrue(result.get() != null)
            }
        } finally {
            runCatching { server.close() }
            runCatching { result.get()?.socket?.close() }
            listener.join(2_000)
        }
    }

    @Test fun gerbangMenerimaBarisBerurutanDanMembuangGandaAtauLoncat() {
        val gate = CodexLineGate()
        assertTrue(gate.accept(1))
        assertTrue(!gate.accept(1))   // ganda
        assertTrue(!gate.accept(3))   // loncat
        assertTrue(gate.accept(2))
        assertTrue(gate.accept(null)) // tanpa nomor selalu diterima
    }

    @Test fun soketDanStdoutDigabungTanpaDuplikatDanStdoutMenutupSoketPutus() {
        val seen = mutableListOf<String>()
        val state = CodexStreamState { seen.add(it) }
        // Soket hanya sempat membawa baris 1, lalu putus.
        state.accept("""{"n":1,"type":"delta","text":"Halo "}""")
        // Stdout memutar ulang semuanya: baris 1 dibuang, 2 dan 3 diterima.
        listOf(
            """{"n":1,"type":"delta","text":"Halo "}""",
            """{"n":2,"type":"delta","text":"dunia"}""",
            """{"n":3,"type":"done"}"""
        ).forEach { state.accept(it) }
        assertEquals("Halo dunia", state.answer())
        assertEquals(listOf("Halo ", "Halo dunia"), seen)
        assertTrue(state.isDone)
    }

    @Test fun eventErrorDicatatDenganFlagRetryable() {
        val state = CodexStreamState { }
        state.accept("""{"n":1,"type":"error","message":"gagal","retryable":true}""")
        assertEquals("gagal", state.error)
        assertTrue(state.errorRetryable)
    }

    @Test fun promptKecilDibawaSebagaiArgumenBase64() {
        val token = "a".repeat(40)
        val prompt = "tanya 'ini' \"itu\" \$HOME\nbaris dua"
        val cmd = TermuxLauncher.buildAppServerCommand("console.log(1)", 49152, token, prompt)
        val encoded = Regex("'$token' '([A-Za-z0-9+/=]+)'$").find(cmd)!!.groupValues[1]
        assertEquals(prompt, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        // Tanpa prompt: perintah lama tidak berubah.
        assertTrue(TermuxLauncher.buildAppServerCommand("x", 49152, token).endsWith("49152 '$token'"))
    }
}

class CodexLiveReaderTest {
    private val token = "a".repeat(64)

    @Test fun pemecahBarisMenunggubarisUtuhDanAmanUntukUtf8TerpotongDiTengah() {
        val lines = mutableListOf<String>()
        val splitter = CodexLineSplitter { lines.add(it) }
        val full = "{\"n\":1,\"text\":\"héllo 😀\"}\n{\"n\":2}\n".toByteArray(Charsets.UTF_8)
        // Potong di tengah karakter multi-byte (é dan emoji) dan di tengah baris.
        val cut1 = 14
        val cut2 = 24
        splitter.push(full.copyOfRange(0, cut1))
        assertTrue(lines.isEmpty())
        splitter.push(full.copyOfRange(cut1, cut2))
        splitter.push(full.copyOfRange(cut2, full.size))
        assertEquals(listOf("{\"n\":1,\"text\":\"héllo 😀\"}", "{\"n\":2}"), lines)
    }

    @Test fun perintahRunnerMenulisStdoutKeBerkasLiveDanMengembalikanEkorSebagaiCadangan() {
        val token = "a".repeat(40)
        val live = "\$HOME/.mythos_codex/live/11111111-2222-3333-4444-555555555555.jsonl"
        val cmd = TermuxLauncher.buildAppServerCommand("x", 49152, token, "halo", live)
        assertTrue(cmd.contains("L=\"$live\""))
        assertTrue(cmd.contains("> \"\$L\"; rc=\$?"))
        assertTrue(cmd.contains("tail -c 90000 \"\$L\""))
        assertTrue(cmd.contains("exit \$rc"))
        assertTrue(cmd.contains("node \"\$HOME/.mythos_codex/app_server.js\" 49152 '$token' '"))
    }

    @Test fun jalurLiveHarusDiBawahFolderLiveDanTanpaKarakterBerbahaya() {
        val token = "a".repeat(40)
        for (bad in listOf("/tmp/x.jsonl", "\$HOME/.mythos_codex/live/../x.jsonl", "\$HOME/.mythos_codex/live/a b.jsonl", "\$HOME/.mythos_codex/live/x\";rm -rf ~.jsonl")) {
            assertFalse(bad, runCatching { TermuxLauncher.buildAppServerCommand("x", 49152, token, null, bad) }.isSuccess)
        }
    }

    @Test fun perintahSesiMembersihkanBerkasLiveDanRunnerSementaraYangKedaluwarsa() {
        val cmd = TermuxLauncher.buildAppServerCommand("x", 49152, token, null, "\$HOME/.mythos_codex/live/abcdef12-3456.jsonl")
        assertTrue(cmd.contains("find \"\$HOME/.mythos_codex/live\" -maxdepth 1 -type f -mmin +60 -delete"))
        assertTrue(cmd.contains("-name 'app_server.js.*' -mmin +60 -delete"))
        // pembersihan berjalan sebelum runner ditulis dan dijalankan
        assertTrue(cmd.indexOf("-mmin +60") < cmd.indexOf("base64 -d"))
    }

    @Test fun perintahBunuhRunnerCadanganMemakaiPortTokenDanMenghapusBerkasLive() {
        val live = "\$HOME/.mythos_codex/live/abcdef12-3456.jsonl"
        val cmd = TermuxLauncher.buildKillRunnerCommand(49152, token, live)
        assertTrue(cmd.contains("pkill -TERM -f '[a]pp_server\\.js 49152 $token'"))
        assertTrue(cmd.contains("pkill -KILL -f '[a]pp_server\\.js 49152 $token'"))
        assertTrue(cmd.contains("rm -f \"$live\" \"$live.rc\""))
        assertFalse(cmd.contains("pkill -f node"))
        assertFalse(runCatching { TermuxLauncher.buildKillRunnerCommand(0, token, live) }.isSuccess)
        assertFalse(runCatching { TermuxLauncher.buildKillRunnerCommand(49152, "pendek", live) }.isSuccess)
        assertFalse(runCatching { TermuxLauncher.buildKillRunnerCommand(49152, token, "\$HOME/x; rm -rf ~") }.isSuccess)
    }

    @Test fun sesiMemanggilJalurBunuhCadanganSaatTidakSelesaiWajar() {
        val src = File("src/main/java/com/mythos/app/agent/TermuxLauncher.kt").readText()
        assertTrue(src.contains("runnerStarted && !completed"))
        assertTrue(src.contains("buildKillRunnerCommand(serverPort, token, live)"))
    }
}
