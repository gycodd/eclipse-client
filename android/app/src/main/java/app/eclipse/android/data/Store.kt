package app.eclipse.android.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.eclipse.android.core.LinkParser
import app.eclipse.android.model.*
import app.eclipse.android.net.GeoAssets
import app.eclipse.android.net.Pinger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Store(private val ctx: Context, private val settings: AppSettings) {
    @Serializable
    private data class Saved(val groups: List<SubGroup> = emptyList(), val selectedID: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(ctx.filesDir, "store.json")

    var groups by mutableStateOf<List<SubGroup>>(emptyList())
        private set
    var selectedID by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf<Set<String>>(emptySet())
        private set
    var pingingGroups by mutableStateOf<Set<String>>(emptySet())
        private set
    val pings = mutableStateMapOf<String, Int>()
    var message by mutableStateOf<String?>(null)

    init {
        try {
            if (file.exists()) {
                val s = json.decodeFromString<Saved>(file.readText())
                groups = s.groups; selectedID = s.selectedID
            }
        } catch (e: Exception) {
            AppLog.write("[app] не удалось прочитать хранилище: ${e.message}")
        }
    }

    private fun save() {
        try {
            val tmp = File(file.parentFile, "store.json.tmp")
            tmp.writeText(json.encodeToString(Saved(groups, selectedID)))
            tmp.renameTo(file)
        } catch (e: Exception) {
            AppLog.write("[app] не удалось сохранить хранилище: ${e.message}")
        }
    }

    private fun setGroups(g: List<SubGroup>) { groups = g; save() }
    fun select(id: String?) { selectedID = id; save() }

    val allServers: List<Server> get() = groups.flatMap { it.servers }
    val selected: Server? get() = allServers.firstOrNull { it.id == selectedID } ?: allServers.firstOrNull()
    fun server(id: String?): Server? = allServers.firstOrNull { it.id == id }
    fun groupOf(serverId: String): SubGroup? = groups.firstOrNull { g -> g.servers.any { it.id == serverId } }

    /** Серверы группы в порядке показа (с сортировкой по пингу, если включена). */
    fun displayServers(g: SubGroup): List<Server> {
        if (!settings.sortByPing) return g.servers
        fun rank(s: Server): Int { val p = pings[s.id] ?: return Int.MAX_VALUE - 1; return if (p >= 0) p else Int.MAX_VALUE }
        return g.servers.sortedBy { rank(it) }
    }

    private fun fixSelection() {
        if (allServers.none { it.id == selectedID }) selectedID = allServers.firstOrNull()?.id
        save()
    }

    fun removeServer(id: String) {
        groups = groups.map { g -> g.copy(servers = g.servers.filter { it.id != id }) }
        fixSelection()
        Toasts.show("Сервер удалён", ToastKind.INFO)
    }

    fun removeGroup(id: String) {
        groups = groups.filter { it.id != id }
        fixSelection()
        Toasts.show("Подписка удалена", ToastKind.INFO)
    }

    fun renameServer(id: String, newName: String) {
        setGroups(groups.map { g -> g.copy(servers = g.servers.map { if (it.id == id) it.copy(name = newName) else it }) })
        Toasts.show("Переименовано")
    }

    fun updateGroupSettings(id: String, name: String?, url: String?, intervalHours: Int?, pingProtocol: PingProtocol?,
                            dnsPreset: String?, dnsCustom: String?, dnsDoH: Boolean?) {
        setGroups(groups.map { g ->
            if (g.id != id) g else g.copy(
                name = name?.takeIf { it.isNotEmpty() } ?: g.name,
                url = url ?: g.url,
                updateIntervalHours = intervalHours,
                pingProtocolRaw = pingProtocol?.name,
                dnsPreset = dnsPreset, dnsCustom = dnsCustom, dnsDoH = dnsDoH,
            )
        })
        Toasts.show("Настройки подписки сохранены")
    }

    /** Параметры туннеля для сервера: общие настройки + DNS его подписки. */
    fun options(server: Server): TunnelOptions {
        var o = settings.tunnelOptions
        val g = groupOf(server.id) ?: return o
        val raw = g.dnsPreset
        if (raw != null) {
            val r = settings.resolveDNS(DNSPreset.of(raw), g.dnsCustom ?: "", g.dnsDoH ?: false)
            o = o.copy(dns = r.ips, dohURL = r.dohURL)
        }
        return o
    }

    /** Запасные серверы для автопереключения: та же подписка, сначала с лучшим пингом. */
    fun fallbackCandidates(server: Server, limit: Int = 5): List<Server> {
        val g = groupOf(server.id) ?: return emptyList()
        fun rank(s: Server): Pair<Int, Int> {
            val p = pings[s.id] ?: return 1 to 0
            return if (p >= 0) 0 to p else 2 to 0
        }
        return g.servers.filter { it.id != server.id }
            .sortedWith(compareBy({ rank(it).first }, { rank(it).second })).take(limit)
    }

    private fun addManual(server: Server) {
        val i = groups.indexOfFirst { it.url.isEmpty() }
        groups = if (i >= 0) groups.mapIndexed { idx, g -> if (idx == i) g.copy(servers = g.servers + server) else g }
        else groups + SubGroup(name = "Мои серверы", url = "", servers = listOf(server))
        selectedID = server.id
        save()
    }

    suspend fun add(input: String): Boolean {
        message = null
        var text = input.trim()
        LinkParser.parse(text)?.let {
            addManual(it)
            Toasts.show("Сервер добавлен: ${it.name}")
            return true
        }
        val decoded = LinkParser.percentDecode(text)
        for (p in listOf("https://", "http://")) {
            val i = decoded.indexOf(p)
            if (i >= 0) { text = decoded.substring(i); break }
        }
        val lower = text.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            message = "Не похоже на ссылку подписки или сервера"
            Toasts.show(message!!, ToastKind.ERROR)
            return false
        }
        groups.firstOrNull { it.url == text }?.let { return refresh(it.id) }
        val host = try { URL(text).host } catch (_: Exception) { null }
        val g = SubGroup(name = host?.ifEmpty { null } ?: "Подписка", url = text)
        groups = groups + g
        val ok = refresh(g.id, quiet = true)
        if (!ok) {
            groups = groups.filter { it.id != g.id }; save()
        } else groups.firstOrNull { it.id == g.id }?.let {
            Toasts.show("Подписка добавлена: ${it.name} · ${it.servers.size} серв.")
        }
        return ok
    }

    private class Fetched(val body: String, val headers: Map<String, String>)

    private suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = settings.requestTimeout * 1000
            c.readTimeout = settings.requestTimeout * 1000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", settings.userAgent)
            if (settings.sendHWID) {
                c.setRequestProperty("x-hwid", settings.hwid)
                c.setRequestProperty("x-device-os", "Android")
                c.setRequestProperty("x-ver-os", Build.VERSION.RELEASE)
                c.setRequestProperty("x-device-model", Build.MODEL)
            }
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val h = HashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && !v.isNullOrEmpty()) h[k.lowercase()] = v.first() }
            Fetched(body, h)
        } finally { c.disconnect() }
    }

    suspend fun refresh(groupID: String, quiet: Boolean = false): Boolean {
        val g0 = groups.firstOrNull { it.id == groupID } ?: return false
        if (g0.url.isEmpty()) return false
        refreshing = refreshing + groupID
        message = null
        try {
            val f = fetch(g0.url)
            val seen = HashSet<String>()
            var parsed = LinkParser.parseSubscription(f.body).filter { seen.add(it.link) }
            if (parsed.isEmpty()) {
                message = "В подписке не найдено серверов"
                if (!quiet) Toasts.show(message!!, ToastKind.ERROR)
                return false
            }
            val cur = groups.firstOrNull { it.id == groupID } ?: return false
            val old = cur.servers
            parsed = parsed.map { n -> old.firstOrNull { it.link == n.link }?.let { n.copy(id = it.id) } ?: n }
            val selectedLink = selected?.link

            val info = parseUserInfo(f.headers["subscription-userinfo"])
            var announce = decodeHeader(f.headers["announce"])
            if (announce == null) {
                f.body.lines().map { it.trim() }.firstOrNull { it.lowercase().startsWith("#announce:") }
                    ?.let { announce = decodeHeader(it.drop(10)) }
            }
            val title = profileTitle(f.headers["profile-title"])
            groups = groups.map {
                if (it.id != groupID) it else it.copy(
                    servers = parsed, updatedAt = System.currentTimeMillis(),
                    used = info.used, total = info.total, expire = info.expire,
                    supportURL = f.headers["support-url"], webURL = f.headers["profile-web-page-url"],
                    announce = announce, announceURL = f.headers["announce-url"],
                    name = title ?: it.name,
                )
            }
            if (allServers.none { it.id == selectedID }) {
                selectedID = allServers.firstOrNull { it.link == selectedLink }?.id ?: allServers.firstOrNull()?.id
            }
            save()
            if (!quiet) Toasts.show("Подписка обновлена · ${parsed.size} серв.")
            return true
        } catch (e: Exception) {
            message = "Ошибка загрузки: ${e.message ?: e.javaClass.simpleName}"
            if (!quiet) Toasts.show("Не удалось обновить подписку: ${e.message ?: e.javaClass.simpleName}", ToastKind.ERROR)
            return false
        } finally {
            refreshing = refreshing - groupID
        }
    }

    suspend fun refreshAll() {
        val ids = groups.filter { it.url.isNotEmpty() }.map { it.id }
        var ok = 0
        for (id in ids) if (refresh(id, quiet = true)) ok++
        if (ids.isNotEmpty()) Toasts.show("Обновлено подписок: $ok из ${ids.size}", if (ok == ids.size) ToastKind.SUCCESS else ToastKind.ERROR)
    }

    suspend fun autoRefresh() {
        val now = System.currentTimeMillis()
        val ids = groups.filter { g ->
            if (g.url.isEmpty()) return@filter false
            val enabled = settings.autoUpdate || g.updateIntervalHours != null
            if (!enabled) return@filter false
            val hours = g.updateIntervalHours ?: settings.updateHours
            now - (g.updatedAt ?: 0L) > hours * 3_600_000L
        }.map { it.id }
        for (id in ids) refresh(id, quiet = true)
    }

    suspend fun downloadGeo(): Boolean = try {
        GeoAssets.download(settings.geoDir)
        Toasts.show("Геобазы обновлены")
        true
    } catch (e: Exception) {
        Toasts.show("Не удалось скачать геобазы: ${e.message}", ToastKind.ERROR)
        false
    }

    suspend fun pingAll(groupID: String) {
        val g = groups.firstOrNull { it.id == groupID } ?: return
        if (g.servers.isEmpty()) return
        pingingGroups = pingingGroups + groupID
        val mode = PingProtocol.of(g.pingProtocolRaw) ?: settings.pingProtocol
        coroutineScope {
            g.servers.map { s ->
                async { pings[s.id] = measure(s, mode) ?: -1 }
            }.awaitAll()
        }
        pingingGroups = pingingGroups - groupID
        val alive = g.servers.count { (pings[it.id] ?: -1) >= 0 }
        if (alive == 0) Toasts.show("Пинг: ни один сервер не ответил (${mode.title})", ToastKind.ERROR)
        else Toasts.show("Пинг завершён: доступно $alive из ${g.servers.size} (${mode.title})")
    }

    suspend fun pingSingle(s: Server) {
        val mode = PingProtocol.of(groupOf(s.id)?.pingProtocolRaw) ?: settings.pingProtocol
        val ms = measure(s, mode)
        pings[s.id] = ms ?: -1
        if (ms != null) Toasts.show("${s.name}: $ms мс (${mode.title})", ToastKind.INFO)
        else Toasts.show("${s.name}: нет ответа (${mode.title})", ToastKind.ERROR)
    }

    private suspend fun measure(s: Server, mode: PingProtocol): Int? {
        val sec = (s.security ?: "").lowercase()
        val tls: Boolean? = if (sec == "tls" || sec == "reality") true else null
        return Pinger.ping(s.host, s.port, mode, s.isUDPBased, tls)
    }

    companion object {
        data class UserInfo(val used: Long? = null, val total: Long? = null, val expire: Long? = null)

        fun parseUserInfo(header: String?): UserInfo {
            if (header == null) return UserInfo()
            var up = 0.0; var down = 0.0; var hasUsage = false
            var total: Long? = null; var expire: Long? = null
            for (part in header.split(";")) {
                val kv = part.split("=", limit = 2).map { it.trim() }
                if (kv.size != 2) continue
                val n = kv[1].toDoubleOrNull() ?: continue
                when (kv[0].lowercase()) {
                    "upload" -> { up = n; hasUsage = true }
                    "download" -> { down = n; hasUsage = true }
                    "total" -> if (n > 0) total = n.toLong()
                    "expire" -> if (n > 0) expire = n.toLong()
                }
            }
            return UserInfo(if (hasUsage) (up + down).toLong() else null, total, expire)
        }

        /** Заголовки подписки бывают в base64:, в percent-encoding или в "кривой" Latin-1 кодировке. */
        fun decodeHeader(raw: String?): String? {
            var t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t.lowercase().startsWith("base64:")) t = LinkParser.decodeBase64(t.drop(7)) ?: t
            else if (t.contains("%")) t = LinkParser.percentDecode(t)
            else {
                // HttpURLConnection отдаёт значения как Latin-1: перекодируем обратно в UTF-8
                if (t.all { it.code <= 0xFF }) {
                    val re = String(t.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    if (!re.contains(Char(0xFFFD))) t = re
                }
            }
            return t.trim().ifEmpty { null }
        }

        fun profileTitle(raw: String?): String? = decodeHeader(raw)
    }
}
