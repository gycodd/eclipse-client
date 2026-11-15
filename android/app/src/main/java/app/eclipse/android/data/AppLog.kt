package app.eclipse.android.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Журнал приложения и туннеля + хвост журнала ядра. Секреты вырезаются. */
object AppLog {
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private var coreFile: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tunnel.log")
        coreFile = File(ctx.cacheDir, "core.log")
    }

    val coreLogFile: File? get() = coreFile

    @Synchronized
    fun write(s: String) {
        val line = "${fmt.format(Date())} ${clean(s)}\n"
        lines.addLast(line)
        while (lines.size > 600) lines.removeFirst()
        try {
            val f = file ?: return
            if (f.length() > 512_000) f.writeText("")
            f.appendText(line)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText(""); coreFile?.writeText("") } catch (_: Exception) {}
    }

    @Synchronized
    fun dump(): String {
        val core = coreTail()
        return lines.joinToString("") + if (core.isNotEmpty()) "\n--- журнал ядра ---\n$core\n" else ""
    }

    fun coreTail(n: Int = 60): String = try {
        coreFile?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: ""
    } catch (_: Exception) { "" }

    private val uuidRe = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val kvRe = Regex("(password|passwd|pass|auth|token|secret|key|uuid|id)=[^&\\s\"']+", RegexOption.IGNORE_CASE)
    private val linkRe = Regex("(vless|vmess|trojan|ss|hysteria2|hy2|tuic)://[^\\s\"']+", RegexOption.IGNORE_CASE)

    fun clean(s: String): String =
        s.replace(uuidRe, "***").replace(kvRe) { "${it.groupValues[1]}=***" }.replace(linkRe) { "${it.groupValues[1]}://***" }
}
