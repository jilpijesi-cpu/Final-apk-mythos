package com.mythos.app.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Status CLI yang aman ditampilkan; tidak pernah berisi token/kredensial Codex. */
enum class CodexCliStatus {
    TERMUX_MISSING,
    PERMISSION_REQUIRED,
    TERMUX_SETUP_REQUIRED,
    NOT_INSTALLED,
    NOT_READY,
    LOGIN_REQUIRED,
    READY,
    UNKNOWN,
}

/**
 * Login dan sesi Codex berjalan langsung di Termux, satu proses per permintaan; tidak ada daemon Python,
 * HTTP API, atau port tetap.
 */
object TermuxLauncher {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BIN = "/data/data/com.termux/files/usr/bin"
    private const val BASH = "$BIN/bash"
    private const val PRELOAD = "/data/data/com.termux/files/usr/lib/libtermux-exec-ld-preload.so"
    private const val STATUS_TIMEOUT_SECONDS = 20L
    private const val SOCKET_CONNECT_TIMEOUT_MS = 30_000
    private const val APP_SERVER_TIMEOUT_SECONDS = 15 * 60L
    private const val STALE_FILE_MINUTES = 60
    /**
     * Prompt sampai batas ini dibawa sebagai argumen runner (cara base.apk), sehingga soket hanya jalur tambahan.
     * Argumen tunggal Linux dibatasi 128 KB dan sudah memuat runner base64, jadi prompt lebih besar memakai soket.
     */
    internal const val ARGV_PROMPT_MAX_BYTES = 60_000
    private val LIVE_FILE_RE = Regex("\\\$HOME/\\.mythos_codex/live/[A-Za-z0-9-]{8,64}\\.jsonl")
    private val TOKEN_RE = Regex("[A-Za-z0-9_-]{32,128}")

    fun isValidToken(token: String) = TOKEN_RE.matches(token)

    /** Batas panjang baris handshake token (token sah hanya 32-128 karakter). */
    const val HANDSHAKE_MAX_CHARS = 256
    const val HANDSHAKE_TIMEOUT_MS = 10_000

    /**
     * Baca satu baris dengan batas panjang. readLine() polos tanpa batas memungkinkan aplikasi lokal lain yang
     * lebih dulu tersambung ke port loopback mengirim data tanpa newline sampai memori habis.
     */
    internal fun readLineLimited(reader: java.io.Reader, maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length >= maxChars) throw java.io.IOException("Baris handshake melebihi $maxChars karakter.")
            sb.append(c.toChar())
        }
    }

    /** Perbandingan waktu-konstan; null (koneksi tanpa data) selalu ditolak. */
    internal fun tokenMatches(received: String?, expected: String): Boolean =
        received != null && java.security.MessageDigest.isEqual(
            received.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8)
        )

    internal data class AuthenticatedSocket(val socket: Socket, val reader: BufferedReader)

    /** Terus menerima klien lokal sampai handshake token sah atau batas waktu total habis. */
    internal fun acceptAuthenticatedSocket(
        server: ServerSocket,
        expectedToken: String,
        totalTimeoutMs: Int,
        handshakeTimeoutMs: Int
    ): AuthenticatedSocket? {
        require(totalTimeoutMs > 0 && handshakeTimeoutMs > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs.toLong())
        fun remainingMillis(): Int {
            val nanos = deadline - System.nanoTime()
            if (nanos <= 0L) return 0
            return ((nanos - 1L) / 1_000_000L + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        while (true) {
            val remaining = remainingMillis()
            if (remaining == 0) return null
            server.soTimeout = remaining
            val socket = try {
                server.accept()
            } catch (_: java.net.SocketTimeoutException) {
                return null
            }
            var authenticated = false
            try {
                val handshakeRemaining = remainingMillis()
                if (handshakeRemaining == 0) return null
                socket.soTimeout = minOf(handshakeTimeoutMs, handshakeRemaining)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                if (tokenMatches(readLineLimited(reader, HANDSHAKE_MAX_CHARS), expectedToken)) {
                    authenticated = true
                    return AuthenticatedSocket(socket, reader)
                }
            } catch (_: Exception) {
                // Klien lokal yang gagal/tidak menyelesaikan handshake tidak boleh mengakhiri listener.
            } finally {
                if (!authenticated) runCatching { socket.close() }
            }
        }
    }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun permitted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** CLI app-server harus ada, dapat dijalankan, dan memiliki dependensi Node Termux. */
    internal fun codexReadyCommand(): String =
        "dpkg-query -W -f='\${Status}' codex 2>/dev/null | grep -q 'install ok installed' && " +
            "command -v codex >/dev/null 2>&1 && command -v node >/dev/null 2>&1 && " +
            "codex --version >/dev/null 2>&1 && codex app-server --help >/dev/null 2>&1"

    /** Perintah yang menginstal CLI dari repositori Termux aktif bila perlu, lalu membuka login ChatGPT yang terlihat. */
    internal fun buildCodexLoginCommand(): String =
        "export BROWSER=termux-open-url; " +
            "if ! ( ${codexReadyCommand()} ) >/dev/null 2>&1; then " +
            "echo 'Menyiapkan Node.js dan Codex CLI dari repositori Termux…'; " +
            "pkg update -y && pkg install -y nodejs codex || exit \$?; fi; " +
            "echo 'Membuka login Codex. Selesaikan login di browser, lalu kembali ke aplikasi Mythos.'; " +
            "codex login; rc=\$?; echo; " +
            "if [ \$rc -eq 0 ]; then echo 'Perintah login selesai. Kembali ke Mythos untuk memeriksa koneksi.'; " +
            "else echo 'Login belum selesai. Periksa pesan di atas dan coba lagi dari Mythos.'; fi; exit \$rc"

    /** Hanya mengembalikan label status; tidak membaca atau meneruskan isi berkas auth.json. */
    internal fun buildCodexStatusCommand(): String =
        legacyBridgeCleanupCommand() +
            "if ! ( dpkg-query -W -f='\${Status}' codex 2>/dev/null | grep -q 'install ok installed' && " +
            "command -v codex >/dev/null 2>&1 ); then echo MYTHOS_CODEX_NOT_INSTALLED; " +
            "elif ! ( command -v node >/dev/null 2>&1 && codex --version >/dev/null 2>&1 && " +
            "codex app-server --help >/dev/null 2>&1 ); then echo MYTHOS_CODEX_NOT_READY; " +
            "elif codex login status >/dev/null 2>&1; then echo MYTHOS_CODEX_READY; " +
            "else echo MYTHOS_CODEX_LOGIN_REQUIRED; fi"

    internal fun parseCodexCliStatus(output: String): CodexCliStatus {
        val marker = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("MYTHOS_CODEX_") }
        return when (marker) {
            "MYTHOS_CODEX_NOT_INSTALLED" -> CodexCliStatus.NOT_INSTALLED
            "MYTHOS_CODEX_NOT_READY" -> CodexCliStatus.NOT_READY
            "MYTHOS_CODEX_LOGIN_REQUIRED" -> CodexCliStatus.LOGIN_REQUIRED
            "MYTHOS_CODEX_READY" -> CodexCliStatus.READY
            else -> CodexCliStatus.UNKNOWN
        }
    }

    /** Wrapper RUN_COMMAND untuk interpreter/shebang Termux; LD_PRELOAD aktif sejak bash dimulai. */
    private fun preloadedArguments(command: String): Array<String> {
        val wrapper = "L=$PRELOAD; if [ -f \"\$L\" ] && [ \"\${LD_PRELOAD:-}\" != \"\$L\" ]; then " +
            "exec $BIN/env LD_PRELOAD=\"\$L\" $BASH -c \"\$1\"; fi; eval \"\$1\""
        return arrayOf("-c", wrapper, "mythos", "export PATH=$BIN:\$PATH; $command")
    }

    /** Hanya hentikan proses bridge versi lama yang benar-benar menjalankan skrip lama di HOME Termux Mythos. */
    private fun legacyBridgeCleanupCommand(): String =
        "for p in \$(pgrep -f '[c]odex_bridge.py' 2>/dev/null); do " +
            "[ \"\$p\" = \"\$\$\" ] && continue; [ \"\$p\" = \"\$PPID\" ] && continue; " +
            "args=\$(ps -o args= -p \"\$p\" 2>/dev/null); " +
            "case \"\$args\" in *\"\$HOME/codex_bridge.py\"*) kill \"\$p\" 2>/dev/null;; esac; done; "

    /**
     * Hapus sisa sesi yang tertinggal (aplikasi dimatikan paksa): berkas live berisi jawaban model dan runner sementara yang
     * lebih tua dari [STALE_FILE_MINUTES]. Batas itu jauh di atas timeout sesi (15 menit), jadi sesi paralel yang masih hidup aman.
     */
    private fun staleFilesCleanupCommand(): String =
        "find \"\$HOME/.mythos_codex/live\" -maxdepth 1 -type f -mmin +$STALE_FILE_MINUTES -delete 2>/dev/null; " +
            "find \"\$HOME/.mythos_codex\" -maxdepth 1 -type f -name 'app_server.js.*' -mmin +$STALE_FILE_MINUTES -delete 2>/dev/null; "

    /**
     * Jalur pembatalan cadangan: bila sinyal "cancel" lewat soket tidak sampai (soket belum tersambung, atau sesi gagal/timeout),
     * hentikan runner node milik sesi ini lewat port+token uniknya (runner meneruskan SIGTERM ke process group Codex), paksa
     * dengan SIGKILL bila masih hidup, lalu hapus berkas live. Pola "[a]pp_server" agar shell pemanggil tidak ikut cocok.
     */
    internal fun buildKillRunnerCommand(port: Int, token: String, liveFile: String): String {
        require(port in 1..65535) { "port tidak valid" }
        require(isValidToken(token)) { "token sesi tidak valid" }
        require(LIVE_FILE_RE.matches(liveFile)) { "jalur berkas live tidak valid" }
        val pattern = "'[a]pp_server\\.js $port $token'"
        return "pkill -TERM -f $pattern 2>/dev/null; sleep 2; pkill -KILL -f $pattern 2>/dev/null; " +
            "rm -f \"$liveFile\" \"$liveFile.rc\"; true"
    }

    /** Placeholder versi di runner JS; diganti dengan versionName saat runtime supaya tidak ada nomor versi yang ditulis keras. */
    internal const val VERSION_PLACEHOLDER = "__MYTHOS_VERSION__"
    private val SAFE_VERSION_RE = Regex("[0-9A-Za-z.+-]{1,32}")

    internal fun withAppVersion(runnerSource: String, versionName: String): String =
        runnerSource.replace(VERSION_PLACEHOLDER, if (SAFE_VERSION_RE.matches(versionName)) versionName else "0")

    internal fun buildAppServerCommand(
        runnerSource: String, port: Int, token: String, prompt: String? = null, liveFile: String? = null
    ): String {
        require(port in 1..65535) { "port tidak valid" }
        require(isValidToken(token)) { "token sesi tidak valid" }
        require(liveFile == null || LIVE_FILE_RE.matches(liveFile)) { "jalur berkas live tidak valid" }
        val runner64 = Base64.getEncoder().encodeToString(runnerSource.toByteArray(Charsets.UTF_8))
        val promptArg = if (prompt == null) "" else " '" + Base64.getEncoder().encodeToString(prompt.toByteArray(Charsets.UTF_8)) + "'"
        return legacyBridgeCleanupCommand() + "umask 077; " + staleFilesCleanupCommand() + "mkdir -p \"\$HOME/.mythos_codex/session\" && " +
            // Tulis ke berkas sementara unik ($$ = PID shell) lalu mv atomik: permintaan paralel tidak pernah membaca app_server.js yang terpotong.
            "printf %s '$runner64' | base64 -d > \"\$HOME/.mythos_codex/app_server.js.\$\$\" && " +
            "chmod 600 \"\$HOME/.mythos_codex/app_server.js.\$\$\" && " +
            "mv -f \"\$HOME/.mythos_codex/app_server.js.\$\$\" \"\$HOME/.mythos_codex/app_server.js\" && " +
            "cd \"\$HOME/.mythos_codex/session\" && " +
            if (liveFile == null) "node \"\$HOME/.mythos_codex/app_server.js\" $port '$token'$promptArg"
            else "mkdir -p \"\$HOME/.mythos_codex/live\" && L=\"$liveFile\" && rm -f \"\$L\" && " +
                // stdout runner ke berkas live (dibaca aplikasi lewat RUN_COMMAND); ekor berkas ikut dikembalikan sebagai cadangan terakhir.
                "{ node \"\$HOME/.mythos_codex/app_server.js\" $port '$token'$promptArg > \"\$L\"; rc=\$?; " +
                "tail -c 90000 \"\$L\" 2>/dev/null; exit \$rc; }"
    }

    internal fun runCommandIntent(command: String, background: Boolean, result: PendingIntent? = null): Intent =
        Intent().apply {
            setClassName(PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", preloadedArguments(command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            if (result != null) putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
            if (!background) putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }

    /** Membuka sesi instalasi/login yang terlihat setelah pengguna menekan tombol Codex. */
    fun startCodexLogin(ctx: Context): String? {
        if (!installed(ctx)) return "Termux belum terpasang. Pasang Termux dari sumber tepercaya lalu coba lagi."
        if (!permitted(ctx)) return "Aplikasi belum diberi izin menjalankan perintah di Termux."
        return try {
            ctx.startService(runCommandIntent(buildCodexLoginCommand(), background = false))
            null
        } catch (e: SecurityException) {
            termuxPermissionHint()
        } catch (e: Exception) {
            "Termux tidak bisa membuka sesi login (${e.javaClass.simpleName})."
        }
    }

    /** Status autentikasi aman: hasil hanya sentinel, bukan isi konfigurasi atau token CLI. */
    suspend fun codexCliStatus(ctx: Context): CodexCliStatus = withContext(Dispatchers.IO) {
        if (!installed(ctx)) return@withContext CodexCliStatus.TERMUX_MISSING
        if (!permitted(ctx)) return@withContext CodexCliStatus.PERMISSION_REQUIRED
        val app = ctx.applicationContext
        val action = "${app.packageName}.CODEX_STATUS.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        var output = ""
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val result = intent.getBundleExtra("result")
                if (result != null) output = (result.getString("stdout") ?: "") + "\n" + (result.getString("stderr") ?: "")
                latch.countDown()
            }
        }
        var registered = false
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(app, UUID.randomUUID().hashCode() and 0x7fffffff, Intent(action).setPackage(app.packageName), flags)
            app.startService(runCommandIntent(buildCodexStatusCommand(), background = true, result = callback))
            if (!latch.await(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) CodexCliStatus.UNKNOWN else parseCodexCliStatus(output)
        } catch (e: SecurityException) {
            CodexCliStatus.TERMUX_SETUP_REQUIRED
        } catch (_: Exception) {
            CodexCliStatus.UNKNOWN
        } finally {
            if (registered) runCatching { app.unregisterReceiver(receiver) }
        }
    }

    /**
     * Satu sesi `node → codex app-server` per pesan, meniru base.apk. Stabilitas datang dari dua jalur yang saling menutup:
     * (1) soket loopback acak (aliran langsung, boleh putus kapan saja) dan (2) stdout runner yang ikut dikembalikan Termux
     * saat proses selesai. Tiap baris bernomor urut n; [CodexLineGate] membuang baris ganda/loncat, jadi hasil sama utuh
     * walau soket tidak pernah tersambung atau putus di tengah jalan. Prompt besar (> [ARGV_PROMPT_MAX_BYTES]) tetap
     * dikirim lewat soket seperti sebelumnya.
     */
    suspend fun runCodexAppServer(ctx: Context, prompt: String, onText: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (!installed(ctx)) throw LlmException("Termux belum terpasang.", false)
        if (!permitted(ctx)) throw LlmException("Izin Termux RUN_COMMAND belum diberikan.", false)
        if (prompt.isBlank()) throw LlmException("Prompt Codex kosong.", false)

        val app = ctx.applicationContext
        val argvPrompt = prompt.toByteArray(Charsets.UTF_8).size <= ARGV_PROMPT_MAX_BYTES
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        val serverPort = server.localPort
        val token = UUID.randomUUID().toString().replace("-", "")
        val action = "${app.packageName}.CODEX_APP_SERVER.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        val readerEnded = CountDownLatch(1)
        val state = CodexStreamState(onText)
        val run = AppServerRun()
        val clientRef = AtomicReference<Socket?>(null)
        var cancelHandle: DisposableHandle? = null
        var liveReader: CodexLiveReader? = null
        var registered = false
        var runnerStarted = false   // perintah runner sudah dikirim ke Termux
        var completed = false       // sesi selesai wajar; bila tidak, finally mematikan runner lewat jalur cadangan
        var livePath: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val result = intent.getBundleExtra("result")
                if (result != null) {
                    run.hasResult = true
                    run.exit = result.getInt("exitCode", -1)
                    run.stdout = result.getString("stdout") ?: ""
                    run.stderr = (result.getString("stderr") ?: "").takeLast(8000)
                }
                latch.countDown()
            }
        }
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(app, UUID.randomUUID().hashCode() and 0x7fffffff, Intent(action).setPackage(app.packageName), flags)
            val runner = withAppVersion(app.assets.open("codex_app_server.js").bufferedReader(Charsets.UTF_8).use { it.readText() }, com.mythos.app.BuildConfig.VERSION_NAME)
            val path = "\$HOME/.mythos_codex/live/${UUID.randomUUID()}.jsonl"
            livePath = path
            val command = buildAppServerCommand(runner, serverPort, token, if (argvPrompt) prompt else null, path)
            liveReader = CodexLiveReader(app, path, state) { run.connected }

            // Pembaca soket di thread sendiri; apa pun yang terjadi di sini TIDAK menggagalkan sesi bila prompt sudah di argumen.
            val reader = Thread {
                try {
                    val accepted = acceptAuthenticatedSocket(server, token, SOCKET_CONNECT_TIMEOUT_MS, HANDSHAKE_TIMEOUT_MS)
                        ?: return@Thread
                    val s = accepted.socket
                    clientRef.set(s)
                    val input = accepted.reader
                    if (!argvPrompt) {
                        val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                        writer.write(JSONObject().put("prompt", prompt).toString())
                        writer.newLine()
                        writer.flush()
                    }
                    run.connected = true
                    s.soTimeout = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isNotBlank()) state.accept(line)
                    }
                } catch (_: Exception) {
                    // soket opsional: jalur stdout menutup kekurangannya
                } finally {
                    run.connected = false
                    readerEnded.countDown()
                    runCatching { clientRef.get()?.close() }
                    runCatching { server.close() }
                }
            }.apply { isDaemon = true; name = "codex-app-server-socket" }
            reader.start()

            cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    // Thread terpisah: tulis ke soket tidak boleh jalan di thread utama. Runner menghentikan Codex saat menerima "cancel".
                    Thread {
                        runCatching { clientRef.get()?.getOutputStream()?.apply { write("cancel\n".toByteArray()); flush() } }
                        runCatching { clientRef.get()?.close() }
                        runCatching { server.close() }
                    }.start()
                    latch.countDown()
                }
            }
            try {
                app.startService(runCommandIntent(command, background = true, result = callback))
                runnerStarted = true
            } catch (e: SecurityException) {
                throw LlmException(termuxPermissionHint(), false)
            } catch (e: Exception) {
                throw LlmException("Termux tidak dapat memulai Codex app-server: ${e.message ?: e.javaClass.simpleName}", true)
            }
            liveReader?.start() // jalur utama aliran: baca berkas live lewat RUN_COMMAND; soket hanya mempercepat

            // Tunggu hasil proses. Bila soket sudah membawa "done" tetapi hasil Termux tak kunjung datang, beri tenggang singkat lalu lanjut.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(APP_SERVER_TIMEOUT_SECONDS + 60)
            var finished = false
            while (!finished) {
                finished = latch.await(500, TimeUnit.MILLISECONDS)
                currentCoroutineContext().ensureActive()
                if (!finished && state.isDone) finished = latch.await(5, TimeUnit.SECONDS) || true
                if (!finished && System.nanoTime() > deadline) throw LlmException("Codex app-server melewati batas waktu.", true)
            }
            currentCoroutineContext().ensureActive()
            completed = true

            // Soket: beri waktu singkat agar baris terakhir yang masih di jalan terbaca, lalu tutup.
            if (run.connected) readerEnded.await(3, TimeUnit.SECONDS)
            runCatching { clientRef.get()?.close() }
            runCatching { server.close() }
            liveReader?.finishAndDrain() // baca sisa berkas live sampai habis
            // Jalur cadangan: putar ulang ekor stdout runner; baris yang sudah diterima lewat soket dibuang oleh gate.
            run.stdout.lineSequence().forEach { line -> if (line.isNotBlank()) state.accept(line.trim()) }

            state.error?.let { throw LlmException(it, retryable = state.errorRetryable) }
            val answer = state.answer()
            if (answer.isEmpty()) {
                if (!run.hasResult) throw LlmException("Termux tidak mengembalikan hasil proses Codex.", true)
                val detail = run.stderr.trim().ifEmpty { "Codex app-server keluar dengan kode ${run.exit}." }
                throw LlmException(detail.take(8000), retryable = run.exit == -1 || run.exit == 75)
            }
            if (run.hasResult && run.exit != 0 && !state.isDone) {
                throw LlmException(run.stderr.trim().ifEmpty { "Codex app-server berhenti dengan kode ${run.exit} sebelum selesai." }.take(8000), retryable = true)
            }
            answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Codex app-server gagal: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        } finally {
            cancelHandle?.dispose()
            // Pembatalan/timeout/galat sebelum runner selesai: sinyal soket bisa tidak sampai, jadi bunuh prosesnya dari luar.
            val live = livePath
            if (runnerStarted && !completed && live != null) {
                runCatching { app.startService(runCommandIntent(buildKillRunnerCommand(serverPort, token, live), background = true)) }
            }
            runCatching { liveReader?.close() }
            runCatching { clientRef.get()?.close() }
            runCatching { server.close() }
            if (registered) runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private fun termuxPermissionHint(): String =
        "Termux menolak. Aktifkan allow-external-apps=true di ~/.termux/termux.properties, lalu tutup dan buka Termux."
}

/** Gerbang urutan: hanya baris bernomor berikutnya yang diterima; baris lama/loncat dibuang. Tanpa nomor selalu diterima. */
internal class CodexLineGate {
    private var last = 0
    fun accept(n: Int?): Boolean {
        if (n == null) return true
        if (n == last + 1) { last = n; return true }
        return false
    }
}

/** Menggabungkan baris protokol dari soket dan stdout menjadi satu jawaban; thread-safe. */
internal class CodexStreamState(private val onText: (String) -> Unit) {
    private val lock = Any()
    private val gate = CodexLineGate()
    private val text = StringBuilder()
    @Volatile var isDone = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var errorRetryable = false
        private set

    fun accept(line: String) {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return
        synchronized(lock) {
            if (!gate.accept(if (event.has("n")) event.optInt("n") else null)) return
            when (event.optString("type")) {
                "delta" -> {
                    val delta = event.optString("text")
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        runCatching { onText(text.toString()) }
                    }
                }
                "error" -> {
                    error = event.optString("message").takeIf { it.isNotBlank() } ?: error
                    errorRetryable = event.optBoolean("retryable", false)
                }
                "done" -> isDone = true
            }
        }
    }

    fun answer(): String = synchronized(lock) { text.toString() }
}

/** Hasil proses dan status soket satu sesi; field volatil karena ditulis dari receiver/thread soket dan dibaca coroutine. */
internal class AppServerRun {
    @Volatile var exit = Int.MIN_VALUE
    @Volatile var hasResult = false
    @Volatile var stdout = ""
    @Volatile var stderr = ""
    @Volatile var connected = false
}

/** Memecah aliran byte menjadi baris utuh (batas \n pada level byte, aman untuk UTF-8) dan meneruskannya ke [sink]. */
internal class CodexLineSplitter(private val sink: (String) -> Unit) {
    private var pending = ByteArray(0)

    @Synchronized fun push(bytes: ByteArray) {
        pending += bytes
        val last = pending.lastIndexOf('\n'.code.toByte())
        if (last < 0) return
        val complete = String(pending, 0, last + 1, Charsets.UTF_8)
        pending = pending.copyOfRange(last + 1, pending.size)
        complete.lineSequence().forEach { line -> if (line.isNotBlank()) sink(line.trim()) }
    }
}

/**
 * Meniru LiveTail base.apk: membaca berkas live runner di Termux berdasarkan offset byte lewat RUN_COMMAND (potongan
 * base64 agar UTF-8 tidak rusak). Jeda naik bertahap saat tidak ada data baru dan lebih jarang saat soket tersambung.
 * Gagal baca tidak menggagalkan sesi; baris ganda dari soket/berkas dibuang oleh [CodexStreamState].
 */
internal class CodexLiveReader(
    private val app: Context,
    private val path: String, // memuat $HOME; diperluas oleh shell Termux
    state: CodexStreamState,
    private val socketConnected: () -> Boolean
) {
    private class Slot { val latch = CountDownLatch(1); @Volatile var stdout = ""; @Volatile var exit = -1 }

    private val action = "${app.packageName}.CODEX_POLL.${UUID.randomUUID()}"
    private val slots = java.util.concurrent.ConcurrentHashMap<String, Slot>()
    private val splitter = CodexLineSplitter { state.accept(it) }
    @Volatile private var running = false
    private var thread: Thread? = null
    private var offset = 0L
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val slot = slots[intent.getStringExtra("rid") ?: return] ?: return
            val result = intent.getBundleExtra("result")
            if (result != null) {
                slot.exit = result.getInt("exitCode", -1)
                slot.stdout = result.getString("stdout") ?: ""
            }
            slot.latch.countDown()
        }
    }

    fun start() {
        if (running) return
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        registered = true
        running = true
        thread = Thread {
            var wait = POLL_MS
            while (running) {
                wait = if (readChunk() == 1) POLL_MS else (wait * 2).coerceAtMost(CEILING_MS)
                try { Thread.sleep(if (socketConnected()) maxOf(wait, THROTTLED_MS) else wait) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "codex-live-reader"; start() }
    }

    /** Hentikan polling lalu baca sisa berkas sampai kosong (proses runner sudah selesai). */
    fun finishAndDrain() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(12_000) }
        var failures = 0
        var attempts = 0
        while (attempts++ < 400) {
            when (readChunk()) {
                1 -> failures = 0
                0 -> return
                else -> if (++failures >= 3) return
            }
        }
    }

    fun close() {
        running = false
        thread?.interrupt()
        if (registered) {
            runCatching { app.startService(TermuxLauncher.runCommandIntent("rm -f \"$path\" \"$path.rc\"", background = true)) }
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** 1 = ada data baru, 0 = belum ada data, -1 = gagal baca. */
    @Synchronized private fun readChunk(): Int {
        val f = "\"$path\""
        val rc = "\"$path.rc\""
        val command = "if [ -f $f ]; then tail -c +${offset + 1} $f | head -c $CHUNK_BYTES > $rc; else : > $rc; fi; " +
            "n=\$(wc -c < $rc | tr -d ' '); printf '%s\\n' \"\$n\"; base64 $rc | tr -d '\\n'; printf '\\n'; rm -f $rc"
        val rid = UUID.randomUUID().toString()
        val slot = Slot()
        slots[rid] = slot
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                app, rid.hashCode() and 0x7fffffff,
                Intent(action).setPackage(app.packageName).putExtra("rid", rid), flags
            )
            app.startService(TermuxLauncher.runCommandIntent(command, background = true, result = callback))
            if (!slot.latch.await(10, TimeUnit.SECONDS) || slot.exit != 0) return -1
            val out = slot.stdout
            val nl = out.indexOf('\n')
            if (nl < 0) return -1
            val count = out.substring(0, nl).trim().toLongOrNull() ?: return -1
            if (count <= 0L) return 0
            val bytes = try { Base64.getDecoder().decode(out.substring(nl + 1).trim()) } catch (_: Exception) { return -1 }
            if (bytes.size.toLong() != count) return -1
            offset += count
            splitter.push(bytes)
            return 1
        } catch (_: InterruptedException) {
            return -1
        } catch (_: Exception) {
            return -1
        } finally {
            slots.remove(rid)
        }
    }

    companion object {
        private const val POLL_MS = 150L
        private const val CEILING_MS = 500L
        private const val THROTTLED_MS = 1500L
        private const val CHUNK_BYTES = 49152
    }
}
