package app.eclipse.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GeoAssets {
    private const val BASE = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download"

    /** Скачивает geoip.dat и geosite.dat в [dir]. Xray читает их сам (XRAY_LOCATION_ASSET). */
    suspend fun download(dir: File) = withContext(Dispatchers.IO) {
        fetch("$BASE/geoip.dat", File(dir, "geoip.dat"))
        fetch("$BASE/geosite.dat", File(dir, "geosite.dat"))
    }

    private fun fetch(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw RuntimeException("Сервер геоданных ответил кодом ${c.responseCode}")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        } finally { c.disconnect() }
        if (tmp.length() < 10_000) { tmp.delete(); throw RuntimeException("Файл геоданных слишком мал") }
        dest.delete()
        if (!tmp.renameTo(dest)) throw RuntimeException("Не удалось сохранить ${dest.name}")
    }
}
