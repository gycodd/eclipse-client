package app.eclipse.android.core

import android.util.Base64
import app.eclipse.android.model.Server
import org.json.JSONObject
import java.net.URLDecoder

object LinkParser {

    fun percentDecode(s: String): String =
        try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    fun decodeBase64(s: String): String? {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return try { String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { null }
    }

    fun parse(line: String): Server? {
        val s = line.trim()
        val idx = s.indexOf("://")
        if (idx < 0) return null
        return when (val scheme = s.substring(0, idx).lowercase()) {
            "vless", "trojan" -> parseUrlStyle(s, scheme)
            "vmess" -> parseVMess(s)
            "ss" -> parseSS(s)
            "hysteria2", "hy2" -> parseHysteria2(s)
            "tuic" -> parseTUIC(s)
            else -> null
        }
    }

    fun parseSubscription(text: String): List<Server> {
        var body = text
        if (!body.contains("://")) body = decodeBase64(text) ?: ""
        return body.lines().mapNotNull { parse(it) }
    }

    fun splitHostPort(hp: String): Pair<String, Int>? {
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end < 0) return null
            val host = hp.substring(1, end)
            val rest = hp.substring(end + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.drop(1).toIntOrNull() ?: return null
            return host to port
        }
        val i = hp.lastIndexOf(':')
        if (i < 0) return null
        val port = hp.substring(i + 1).toIntOrNull() ?: return null
        return hp.substring(0, i) to port
    }

    fun parseQuery(qs: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in qs.split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            out[pair.substring(0, eq)] = percentDecode(pair.substring(eq + 1))
        }
        return out
    }

    /** Возвращает (ссылка без фрагмента, имя из фрагмента). */
    fun splitFragment(s: String): Pair<String, String> {
        val h = s.indexOf('#')
        if (h < 0) return s to ""
        return s.substring(0, h) to percentDecode(s.substring(h + 1))
    }

    private class Raw(val rest: String, val query: Map<String, String>, val name: String)

    private fun raw(s: String): Raw? {
        val (body, name) = splitFragment(s)
        val sch = body.indexOf("://")
        if (sch < 0) return null
        var rest = body.substring(sch + 3)
        var query: Map<String, String> = emptyMap()
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = parseQuery(rest.substring(q + 1))
            rest = rest.substring(0, q)
        }
        return Raw(rest, query, name)
    }

    private fun parseUrlStyle(s: String, proto: String): Server? {
        val r = raw(s) ?: return null
        var rest = r.rest
        val sl = rest.indexOf('/')
        if (sl >= 0) rest = rest.substring(0, sl)
        val at = rest.lastIndexOf('@')
        if (at < 0) return null
        val (host, port) = splitHostPort(rest.substring(at + 1)) ?: return null
        val transport = (r.query["type"] ?: "tcp").lowercase()
        val security = (r.query["security"] ?: if (proto == "trojan") "tls" else "none").lowercase()
        return Server(
            name = r.name.ifEmpty { host }, proto = proto, host = host, port = port, link = s,
            transport = transport.ifEmpty { "tcp" }, security = security
        )
    }

    private fun parseVMess(s: String): Server? {
        val payload = s.removePrefix("vmess://")
        val json = decodeBase64(payload) ?: return null
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val host = o.optString("add", "")
        if (host.isEmpty()) return null
        val port = o.opt("port")?.toString()?.toIntOrNull() ?: 0
        if (port <= 0) return null
        val name = o.optString("ps", "").ifEmpty { host }
        val net = o.optString("net", "tcp").lowercase().ifEmpty { "tcp" }
        val tls = o.optString("tls", "").lowercase()
        return Server(
            name = name, proto = "vmess", host = host, port = port, link = s,
            transport = net, security = if (tls == "tls") "tls" else "none"
        )
    }

    private fun parseSS(s: String): Server? {
        val (body0, name) = splitFragment(s)
        var body = body0.removePrefix("ss://")
        val q = body.indexOf('?'); if (q >= 0) body = body.substring(0, q)
        val sl = body.indexOf('/'); if (sl >= 0) body = body.substring(0, sl)
        val hostPort: String? = if (body.contains("@")) {
            body.substring(body.lastIndexOf('@') + 1)
        } else {
            decodeBase64(body)?.let { d -> d.lastIndexOf('@').takeIf { it >= 0 }?.let { d.substring(it + 1) } }
        }
        val (host, port) = splitHostPort(hostPort ?: return null) ?: return null
        return Server(
            name = name.ifEmpty { host }, proto = "ss", host = host, port = port, link = s,
            transport = "tcp", security = null
        )
    }

    /** tuic://UUID:PASSWORD@host:port?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=example.com#name */
    private fun parseTUIC(s: String): Server? {
        val r = raw(s) ?: return null
        var rest = r.rest.removeSuffix("/")
        val at = rest.lastIndexOf('@')
        if (at < 0) return null
        val userInfo = rest.substring(0, at)
        val (host, port) = splitHostPort(rest.substring(at + 1)) ?: return null
        val uuid: String
        val password: String
        val c = userInfo.indexOf(':')
        if (c >= 0) {
            uuid = percentDecode(userInfo.substring(0, c))
            password = percentDecode(userInfo.substring(c + 1))
        } else {
            uuid = percentDecode(userInfo)
            password = r.query["password"] ?: ""
        }
        if (uuid.isEmpty()) return null
        fun flag(k: String): Boolean = r.query[k]?.lowercase().let { it == "1" || it == "true" }
        val q = r.query
        return Server(
            name = r.name.ifEmpty { host }, proto = "tuic", host = host, port = port, link = s,
            transport = "quic", security = "tls",
            tuicUUID = uuid, tuicPassword = password,
            tuicCongestion = q["congestion_control"] ?: q["congestion-control"] ?: q["cc"],
            tuicUDPMode = q["udp_relay_mode"] ?: q["udp-relay-mode"],
            tuicSNI = q["sni"] ?: q["peer"],
            tuicALPN = q["alpn"],
            tuicInsecure = flag("allow_insecure") || flag("allowInsecure") || flag("insecure") || flag("skip-cert-verify"),
        )
    }

    private fun parseHysteria2(s: String): Server? {
        val r = raw(s) ?: return null
        val rest = r.rest.removeSuffix("/")
        val at = rest.lastIndexOf('@')
        val auth = if (at >= 0) rest.substring(0, at) else null
        val hp = if (at >= 0) rest.substring(at + 1) else rest
        val (host, port) = splitHostPort(hp) ?: return null
        val q = r.query
        return Server(
            name = r.name.ifEmpty { host }, proto = "hysteria2", host = host, port = port, link = s,
            transport = "hysteria2", security = "tls",
            hy2Auth = auth?.let { percentDecode(it) },
            hy2Obfs = q["obfs"], hy2ObfsPassword = q["obfs-password"], hy2SNI = q["sni"],
            hy2Insecure = q["insecure"] == "1" || q["insecure"]?.lowercase() == "true",
        )
    }
}
