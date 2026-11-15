package app.eclipse.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проверка, что трафик реально проходит через локальный SOCKS5 ядра:
 * SOCKS5 (user/pass) -> CONNECT host:80 -> GET /generate_204 -> ждём "HTTP/".
 */
object Socks5Probe {
    data class Result(val ok: Boolean, val text: String)

    suspend fun check(port: Int, user: String, pass: String, timeoutMs: Int = 8000): Result = withContext(Dispatchers.IO) {
        var last = Result(false, "нет ответа")
        for ((host, path) in listOf("cp.cloudflare.com" to "/generate_204", "connectivitycheck.gstatic.com" to "/generate_204")) {
            last = one(port, user, pass, host, path, timeoutMs)
            if (last.ok) break
        }
        last
    }

    private fun one(port: Int, user: String, pass: String, host: String, path: String, timeoutMs: Int): Result {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                out.write(byteArrayOf(5, 1, 2)); out.flush()
                val m = ByteArray(2); inp.readFully(m)
                if (m[1].toInt() != 2) return@use Result(false, "SOCKS5: метод авторизации отклонён")
                val u = user.toByteArray(); val p = pass.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                val a = ByteArray(2); inp.readFully(a)
                if (a[1].toInt() != 0) return@use Result(false, "SOCKS5: неверный логин/пароль")
                val h = host.toByteArray()
                out.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf(0, 80)); out.flush()
                val r = ByteArray(4); inp.readFully(r)
                if (r[1].toInt() != 0) return@use Result(false, "SOCKS5: ядро не смогло подключиться к $host (код ${r[1]})")
                when (r[3].toInt()) {
                    1 -> inp.readFully(ByteArray(6))
                    4 -> inp.readFully(ByteArray(18))
                    3 -> { val l = inp.readUnsignedByte(); inp.readFully(ByteArray(l + 2)) }
                }
                out.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                val resp = ByteArray(5); inp.readFully(resp)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (String(resp) == "HTTP/") Result(true, "трафик идёт ($host, $ms мс)")
                else Result(false, "$host: неожиданный ответ")
            }
        } catch (e: Exception) {
            Result(false, "$host: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }
}
