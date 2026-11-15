package app.eclipse.android.net

import app.eclipse.android.model.PingProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Pinger {
    /** Возвращает миллисекунды или null, если сервер не ответил. Для QUIC-серверов всегда ICMP. */
    suspend fun ping(
        host: String, port: Int, mode: PingProtocol = PingProtocol.TCP,
        udpBased: Boolean = false, useTLS: Boolean? = null, timeoutMs: Int = 4000,
    ): Int? = withContext(Dispatchers.IO) {
        try {
            if (udpBased) return@withContext icmp(host, timeoutMs)
            when (mode) {
                PingProtocol.TCP -> tcp(host, port, timeoutMs)
                PingProtocol.ICMP -> icmp(host, timeoutMs)
                PingProtocol.HTTP_GET -> http(host, port, "GET", useTLS ?: defaultTLS(port), timeoutMs)
                PingProtocol.HTTP_HEAD -> http(host, port, "HEAD", useTLS ?: defaultTLS(port), timeoutMs)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultTLS(port: Int) = port in listOf(443, 2053, 2083, 2087, 2096, 4443, 8443, 9443)

    private fun tcp(host: String, port: Int, timeoutMs: Int): Int? {
        val t0 = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
        }
        return ((System.nanoTime() - t0) / 1_000_000).toInt()
    }

    private fun icmp(host: String, timeoutMs: Int): Int? {
        val addr = InetAddress.getByName(host)
        val t0 = System.nanoTime()
        return if (addr.isReachable(timeoutMs)) ((System.nanoTime() - t0) / 1_000_000).toInt() else null
    }

    // Проверяется только время ответа, поэтому сертификат сервера не валидируем (на SNI-маскировке он чужой).
    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    private fun http(host: String, port: Int, method: String, tls: Boolean, timeoutMs: Int): Int? {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val sock: Socket = if (tls) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).also { it.startHandshake() }
        } else raw
        return sock.use { s ->
            val t0 = System.nanoTime()
            s.getOutputStream().write("$method / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val b = s.getInputStream().read()
            if (b < 0) null else ((System.nanoTime() - t0) / 1_000_000).toInt()
        }
    }
}
