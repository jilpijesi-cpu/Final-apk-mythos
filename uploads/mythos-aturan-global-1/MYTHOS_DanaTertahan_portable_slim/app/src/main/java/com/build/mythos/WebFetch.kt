package com.build.mythos

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.io.OutputStream
import java.net.SocketAddress
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// Sisi jaringan dan kartu langkah untuk cari_web dan baca_web. Logika murni ada di WebTools.kt.
// Hanya GET. Pengalihan diikuti manual (maks WebTools.MAX_REDIRECTS) dan tiap tujuan diperiksa ulang.
// DNS rebinding (F-09): host di-resolve SEKALI, semua alamatnya divalidasi, lalu koneksi dibuat ke IP tervalidasi itu
// (bukan ke hasil resolusi kedua). SNI dan verifikasi sertifikat tetap memakai hostname asli, jadi TLS tetap utuh.



/**
 * SSLSocketFactory yang membungkus koneksi TCP ke IP tervalidasi, tetapi memakai [host] asli untuk SNI dan sesi TLS.
 * HttpsURLConnection membuka soket ke URL berhost-IP, lalu memanggil createSocket(soket, host-IP, port, ...); di sini host-IP
 * diganti hostname asli supaya sertifikat server diverifikasi terhadap nama yang benar.
 */
private val IPV4_LITERAL_RE = Regex("^\\d+(\\.\\d+){3}$")

private fun isSniDomain(host: String): Boolean =
    host.isNotEmpty() && !host.contains(':') && !IPV4_LITERAL_RE.matches(host)

private fun toSniHostName(host: String): SNIHostName? {
    val cleaned = host.trim().trimEnd('.')
    if (!isSniDomain(cleaned)) return null
    return try {
        val ascii = IDN.toASCII(cleaned)
        if (isSniDomain(ascii)) SNIHostName(ascii) else null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Pembungkus SSLSocket agar ekstensi TLS SNI ([SNIHostName]) selalu memakai [sniHost] asli dan tidak tertimpa
 * oleh alamat IP dari [WebTools.pinnedUrl] saat Android HttpsURLConnection/OkHttp menjalankan handshake.
 */
private class SniPinnedSocket(
    private val delegate: SSLSocket,
    private val sniHost: String
) : SSLSocket() {
    init {
        applySni()
    }

    private fun applySni() {
        val sni = toSniHostName(sniHost) ?: return
        val params = delegate.sslParameters
        params.serverNames = listOf(sni)
        delegate.sslParameters = params
    }

    override fun startHandshake() {
        applySni()
        delegate.startHandshake()
    }

    override fun getSSLParameters(): SSLParameters = delegate.sslParameters
    override fun setSSLParameters(params: SSLParameters?) {
        if (params != null && params.serverNames.isNullOrEmpty()) {
            toSniHostName(sniHost)?.let { params.serverNames = listOf(it) }
        }
        delegate.sslParameters = params
    }
    override fun getHandshakeSession(): SSLSession? = delegate.handshakeSession
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<out String>?) { delegate.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<out String>?) { delegate.enabledProtocols = protocols }
    override fun getSession(): SSLSession = delegate.session
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = delegate.addHandshakeCompletedListener(listener)
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = delegate.removeHandshakeCompletedListener(listener)
    override fun setUseClientMode(mode: Boolean) { delegate.useClientMode = mode }
    override fun getUseClientMode(): Boolean = delegate.useClientMode
    override fun setNeedClientAuth(need: Boolean) { delegate.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
    override fun setWantClientAuth(want: Boolean) { delegate.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { delegate.enableSessionCreation = flag }
    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation

    // Delegasi metode Socket dasar ke delegate
    override fun getInputStream(): InputStream = delegate.inputStream
    override fun getOutputStream(): OutputStream = delegate.outputStream
    override fun close() = delegate.close()
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
    override fun getSoLinger(): Int = delegate.soLinger
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
}

private class PinnedSniSocketFactory(private val base: SSLSocketFactory, private val host: String) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
    override fun createSocket(s: Socket, h: String, port: Int, autoClose: Boolean): Socket {
        val ssl = base.createSocket(s, host, port, autoClose)
        return if (ssl is SSLSocket) SniPinnedSocket(ssl, host) else ssl
    }
    // Jalur lain akan me-resolve nama sendiri; tidak dipakai oleh HttpsURLConnection dan sengaja ditolak.
    override fun createSocket(h: String, port: Int): Socket = throw IOException("createSocket(host) tidak didukung pada koneksi terikat IP")
    override fun createSocket(h: String, port: Int, la: InetAddress, lp: Int): Socket = throw IOException("tidak didukung pada koneksi terikat IP")
    override fun createSocket(a: InetAddress, port: Int): Socket = throw IOException("tidak didukung pada koneksi terikat IP")
    override fun createSocket(a: InetAddress, port: Int, la: InetAddress, lp: Int): Socket = throw IOException("tidak didukung pada koneksi terikat IP")
}

private fun readCapped(ins: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < cap) {
        val n = ins.read(buf, 0, minOf(buf.size, cap - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun charsetOf(contentType: String): Charset {
    val cs = Regex("charset\\s*=\\s*([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
    return try { if (cs != null) Charset.forName(cs) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
}

/** GET dengan pengaman; mengembalikan (URL akhir, isi). Melempar ConnectorException berisi alasan yang ramah. */
internal fun webGet(start: String): Pair<String, String> {
    var url = start
    for (hop in 0..WebTools.MAX_REDIRECTS) {
        WebTools.checkUrl(url)?.let { throw ConnectorException(it) }
        val u = URL(url)
        if (u.protocol.equals("http", ignoreCase = true)) {
            url = URL("https", u.host, if (u.port == 80) -1 else u.port, u.file).toString()
            continue
        }
        // Resolve SEKALI. Semua alamat harus publik; koneksi dibuat ke salah satu alamat itu (bukan ke DNS kedua).
        val addrs = InetAddress.getAllByName(u.host)
        if (addrs.isEmpty() || addrs.any { WebTools.isPrivateAddress(it) }) {
            throw ConnectorException("Alamat tujuan ada di jaringan lokal; ditolak.")
        }
        val hostHeader = if (u.port == -1 || u.port == 443) u.host else "${u.host}:${u.port}"
        val verifier = HostnameVerifier { _, session -> HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, session) }
        var lastIo: IOException? = null
        var redirected = false
        for (addr in addrs) {
            val c = WebTools.pinnedUrl(u, addr).openConnection() as HttpsURLConnection
            c.sslSocketFactory = PinnedSniSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), u.host)
            c.hostnameVerifier = verifier
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.requestMethod = "GET"
            c.setRequestProperty("Host", hostHeader)
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            c.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7"
            )
            c.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            c.setRequestProperty("Upgrade-Insecure-Requests", "1")
            try {
                val code = try { c.responseCode } catch (e: IOException) { lastIo = e; continue }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location") ?: throw ConnectorException("Pengalihan tanpa tujuan.")
                    url = URL(u, loc).toString()
                    redirected = true
                    break // lanjut ke hop berikutnya; tujuan baru diperiksa dan di-resolve ulang
                }
                if (code !in 200..299) throw ConnectorException("Server menjawab HTTP $code.")
                val type = (c.contentType ?: "").lowercase()
                if (type.isNotEmpty() && !(type.startsWith("text/") || type.contains("json") || type.contains("xml"))) {
                    throw ConnectorException("Jenis konten tidak didukung ($type); hanya teks atau HTML.")
                }
                val bytes = c.inputStream.use { readCapped(it, WebTools.MAX_BYTES) }
                return url to String(bytes, charsetOf(type))
            } finally {
                c.disconnect()
            }
        }
        // Semua alamat gagal tersambung (bukan pengalihan): laporkan galat jaringan terakhir.
        if (!redirected) throw (lastIo ?: ConnectorException("Tidak bisa tersambung ke server."))
    }
    throw ConnectorException("Terlalu banyak pengalihan.")
}

// ---------------------------------------------------------------- untuk agen API dalam-aplikasi (alat WebSearch/WebFetch di cc/KitTools.kt)
// Aturan keamanan sama dengan cari_web/baca_web (kueri tanpa data pribadi, hanya https publik, isi dibungkus sebagai data tak tepercaya).
// Mengembalikan (berhasil, teks) dan tidak butuh MainActivity; dipanggil di thread IO.

internal fun ccWebSearch(query: String): Pair<Boolean, String> {
    val q = query.trim()
    WebTools.checkQuery(q)?.let { return false to it }
    // Banyak mesin gratis berurutan (SearchEngines.ALL); berhenti di mesin pertama yang memberi hasil.
    val out = SearchEngines.search(q, { url -> webGet(url).second })
    if (out.engine == null) {
        val why = out.notes.joinToString("; ").take(900)
        return false to "Semua mesin pencari gagal ($why). Katakan terus terang bahwa kamu tidak bisa memverifikasi lewat web; " +
            "jangan menjawab dari ingatan seolah-olah sudah diperiksa."
    }
    out.results.forEach { SourceLog.add(it.title, it.url) }
    return true to Cx.untrusted("hasil pencarian web untuk \"$q\" (via ${out.engine})", WebTools.formatResults(out.results))
}

internal fun ccWebFetch(raw: String): Pair<Boolean, String> {
    val url = raw.trim()
    WebTools.checkUrl(url)?.let { return false to it }
    return try {
        val (finalUrl, body) = webGet(url)
        val isHtml = body.contains("<html", ignoreCase = true) || body.contains("<body", ignoreCase = true) || body.contains("<p", ignoreCase = true)
        val title = if (isHtml) WebTools.titleOf(body) else ""
        val text = if (isHtml) WebTools.htmlToText(body) else Cx.clip(body.trim(), WebTools.MAX_TEXT)
        if (text.isBlank()) {
            false to "The page has no readable text (it may need JavaScript or a login)."
        } else {
            SourceLog.add(title, finalUrl)
            true to Cx.untrusted("halaman web $finalUrl", (if (title.isNotBlank()) "Judul: $title\n" else "") + text)
        }
    } catch (e: Exception) {
        false to Cx.friendly(e)
    }
}
