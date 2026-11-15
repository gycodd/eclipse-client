package app.eclipse.android.core

import app.eclipse.android.model.Server
import app.eclipse.android.model.TunnelOptions
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

data class XrayProfile(
    val json: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    /** Какое ядро поднимать: "xray" или "singbox". */
    val core: String = "xray",
)

class ConfigException(message: String) : Exception(message)

private typealias M = Map<String, Any?>

private fun toJson(m: M): String = JSONObject(m).toString()

object XrayConfigBuilder {
    fun build(server: Server, options: TunnelOptions, logPath: String?, socksPort: Int = 10808): XrayProfile {
        if (server.core == "singbox") return SingboxConfigBuilder.build(server, options, socksPort)

        val user = token(8)
        val pass = token(16)
        val outbound = LinkedHashMap(makeOutbound(server))
        outbound["tag"] = "proxy"

        val muxOK = server.proto in listOf("vless", "vmess", "trojan") && !server.link.contains("flow=")
        if (options.mux && muxOK) outbound["mux"] = mapOf("enabled" to true, "concurrency" to 8)

        val extra = mutableListOf<M>(mapOf("tag" to "direct", "protocol" to "freedom"))
        if (options.fragment) {
            @Suppress("UNCHECKED_CAST")
            val ss = LinkedHashMap((outbound["streamSettings"] as? M) ?: emptyMap<String, Any?>())
            ss["sockopt"] = mapOf("dialerProxy" to "fragment")
            outbound["streamSettings"] = ss
            extra.add(
                mapOf(
                    "tag" to "fragment", "protocol" to "freedom",
                    "settings" to mapOf(
                        "fragment" to mapOf("packets" to "tlshello", "length" to "100-200", "interval" to "10-20")
                    )
                )
            )
        }

        val log = LinkedHashMap<String, Any?>(mapOf("loglevel" to "warning", "access" to "none"))
        if (logPath != null) log["error"] = logPath

        val dnsServers: List<String> =
            if (options.dohURL != null) listOf(options.dohURL.replace("https://", "https+local://"))
            else options.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }

        val sniffing = LinkedHashMap<String, Any?>(mapOf("enabled" to options.sniffing))
        if (options.sniffing) {
            sniffing["destOverride"] = listOf("http", "tls", "quic")
            sniffing["routeOnly"] = true
        }

        val cfg = LinkedHashMap<String, Any?>()
        cfg["log"] = log
        cfg["dns"] = mapOf("servers" to dnsServers, "queryStrategy" to "UseIP")
        cfg["inbounds"] = listOf(
            mapOf(
                "tag" to "socks-in", "listen" to "127.0.0.1", "port" to socksPort, "protocol" to "socks",
                "settings" to mapOf(
                    "auth" to "password", "accounts" to listOf(mapOf("user" to user, "pass" to pass)),
                    "udp" to true, "ip" to "127.0.0.1"
                ),
                "sniffing" to sniffing
            )
        )
        cfg["outbounds"] = listOf<M>(outbound) + extra
        routing(options)?.let { cfg["routing"] = it }

        return XrayProfile(toJson(cfg), socksPort, user, pass)
    }

    private fun routing(o: TunnelOptions): M? {
        val domains = mutableListOf<String>()
        val ips = mutableListOf<String>()
        if (o.bypassLAN) ips.add("geoip:private")
        val known = listOf("domain:", "full:", "keyword:", "regexp:")
        for (line in o.directRules) {
            val r = line.trim()
            val low = r.lowercase()
            if (r.isEmpty() || r.startsWith("#")) continue
            when {
                low.startsWith("geoip:") -> { if (o.geoAvailable) ips.add(r) }
                low.startsWith("geosite:") -> { if (o.geoAvailable) domains.add(r) }
                known.any { low.startsWith(it) } -> domains.add(r)
                isIP(r) -> ips.add(r)
                else -> domains.add("domain:$low")
            }
        }
        val rules = mutableListOf<M>()
        if (domains.isNotEmpty()) rules.add(mapOf("type" to "field", "domain" to domains, "outboundTag" to "direct"))
        if (ips.isNotEmpty()) rules.add(mapOf("type" to "field", "ip" to ips, "outboundTag" to "direct"))
        return if (rules.isEmpty()) null else mapOf("domainStrategy" to "AsIs", "rules" to rules)
    }

    /** IPv4/IPv6 адрес или подсеть (a.b.c.d/nn). Без DNS-запросов. */
    fun isIP(s: String): Boolean {
        val base = s.substringBefore('/')
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        if (v4.matches(base)) return base.split('.').all { it.toInt() in 0..255 }
        if (base.contains(':') && base.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return try { InetAddress.getByName(base); true } catch (_: Exception) { false }
        }
        return false
    }

    fun token(n: Int): String = UUID.randomUUID().toString().replace("-", "").take(n)

    private fun makeOutbound(server: Server): M = when (server.proto) {
        "vless" -> vless(server.link)
        "trojan" -> trojan(server.link)
        "vmess" -> vmess(server.link)
        "ss" -> shadowsocks(server.link)
        else -> throw ConfigException("Не поддерживается: ${server.proto}")
    }

    private class Parts(val host: String, val port: Int, val userInfo: String, val query: Map<String, String>)

    private fun parts(link: String): Parts {
        val (body, _) = LinkParser.splitFragment(link)
        val sch = body.indexOf("://")
        if (sch < 0) throw ConfigException("Некорректная ссылка сервера")
        var rest = body.substring(sch + 3)
        var query: Map<String, String> = emptyMap()
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = LinkParser.parseQuery(rest.substring(q + 1))
            rest = rest.substring(0, q)
        }
        val sl = rest.indexOf('/'); if (sl >= 0) rest = rest.substring(0, sl)
        val at = rest.lastIndexOf('@')
        if (at < 0) throw ConfigException("Некорректная ссылка сервера")
        val (host, port) = LinkParser.splitHostPort(rest.substring(at + 1))
            ?: throw ConfigException("Некорректная ссылка сервера")
        return Parts(host, port, LinkParser.percentDecode(rest.substring(0, at)), query)
    }

    private fun vless(link: String): M {
        val p = parts(link); val q = p.query
        val user = LinkedHashMap<String, Any?>(mapOf("id" to p.userInfo, "encryption" to (q["encryption"] ?: "none")))
        q["flow"]?.takeIf { it.isNotEmpty() }?.let { user["flow"] = it }
        return mapOf(
            "protocol" to "vless",
            "settings" to mapOf("vnext" to listOf(mapOf("address" to p.host, "port" to p.port, "users" to listOf(user)))),
            "streamSettings" to stream(q["type"] ?: "tcp", q["security"] ?: "none", q, p.host)
        )
    }

    private fun trojan(link: String): M {
        val p = parts(link); val q = p.query
        return mapOf(
            "protocol" to "trojan",
            "settings" to mapOf("servers" to listOf(mapOf("address" to p.host, "port" to p.port, "password" to p.userInfo))),
            "streamSettings" to stream(q["type"] ?: "tcp", q["security"] ?: "tls", q, p.host)
        )
    }

    private fun vmess(link: String): M {
        val json = LinkParser.decodeBase64(link.removePrefix("vmess://")) ?: throw ConfigException("Некорректная ссылка сервера")
        val o = try { JSONObject(json) } catch (_: Exception) { throw ConfigException("Некорректная ссылка сервера") }
        val host = o.optString("add", ""); val id = o.optString("id", "")
        if (host.isEmpty() || id.isEmpty()) throw ConfigException("Некорректная ссылка сервера")
        val port = o.opt("port")?.toString()?.toIntOrNull() ?: 0
        val aid = o.opt("aid")?.toString()?.toIntOrNull() ?: 0
        val path = o.optString("path", "")
        val q = mapOf(
            "path" to path, "serviceName" to path,
            "host" to o.optString("host", ""), "sni" to o.optString("sni", ""),
            "alpn" to o.optString("alpn", ""), "fp" to o.optString("fp", "")
        )
        val tls = if (o.optString("tls", "").lowercase() == "tls") "tls" else "none"
        return mapOf(
            "protocol" to "vmess",
            "settings" to mapOf(
                "vnext" to listOf(
                    mapOf(
                        "address" to host, "port" to port,
                        "users" to listOf(mapOf("id" to id, "alterId" to aid, "security" to o.optString("scy", "auto").ifEmpty { "auto" }))
                    )
                )
            ),
            "streamSettings" to stream(o.optString("net", "tcp"), tls, q, host)
        )
    }

    private fun shadowsocks(link: String): M {
        val (b0, _) = LinkParser.splitFragment(link)
        var body = b0.removePrefix("ss://")
        val q = body.indexOf('?'); if (q >= 0) body = body.substring(0, q)
        val s = body.indexOf('/'); if (s >= 0) body = body.substring(0, s)
        val cred: String
        val hostPort: String
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val ui = body.substring(0, at)
            hostPort = body.substring(at + 1)
            cred = LinkParser.decodeBase64(ui)?.takeIf { it.contains(':') } ?: LinkParser.percentDecode(ui)
        } else {
            val dec = LinkParser.decodeBase64(body) ?: throw ConfigException("Некорректная ссылка сервера")
            val a = dec.lastIndexOf('@')
            if (a < 0) throw ConfigException("Некорректная ссылка сервера")
            cred = dec.substring(0, a); hostPort = dec.substring(a + 1)
        }
        val c = cred.indexOf(':')
        val (host, port) = LinkParser.splitHostPort(hostPort)?.takeIf { c >= 0 }
            ?: throw ConfigException("Некорректная ссылка сервера")
        return mapOf(
            "protocol" to "shadowsocks",
            "settings" to mapOf(
                "servers" to listOf(
                    mapOf("address" to host, "port" to port, "method" to cred.substring(0, c), "password" to cred.substring(c + 1))
                )
            )
        )
    }

    private fun stream(network: String, security: String, q: Map<String, String>, fallbackHost: String): M {
        val s = LinkedHashMap<String, Any?>()
        val host = q["host"] ?: ""
        when (val n = network.lowercase()) {
            "tcp", "" -> s["network"] = "tcp"
            "ws" -> {
                s["network"] = "ws"
                val ws = LinkedHashMap<String, Any?>(mapOf("path" to (q["path"] ?: "/")))
                if (host.isNotEmpty()) ws["headers"] = mapOf("Host" to host)
                s["wsSettings"] = ws
            }
            "grpc" -> {
                s["network"] = "grpc"
                s["grpcSettings"] = mapOf("serviceName" to (q["serviceName"] ?: q["path"] ?: ""), "multiMode" to (q["mode"] == "multi"))
            }
            "httpupgrade" -> {
                s["network"] = "httpupgrade"
                s["httpupgradeSettings"] = mapOf("path" to (q["path"] ?: "/"), "host" to host)
            }
            "xhttp", "splithttp" -> {
                s["network"] = "xhttp"
                val x = LinkedHashMap<String, Any?>(mapOf("path" to (q["path"] ?: "/")))
                if (host.isNotEmpty()) x["host"] = host
                q["mode"]?.takeIf { it.isNotEmpty() }?.let { x["mode"] = it }
                s["xhttpSettings"] = x
            }
            "h2", "http" -> {
                s["network"] = "h2"
                s["httpSettings"] = mapOf("path" to (q["path"] ?: "/"), "host" to if (host.isEmpty()) emptyList() else listOf(host))
            }
            else -> throw ConfigException("Не поддерживается: transport $n")
        }

        val sni = q["sni"]?.takeIf { it.isNotEmpty() } ?: q["peer"] ?: host.ifEmpty { fallbackHost }
        when (security.lowercase()) {
            "tls" -> {
                s["security"] = "tls"
                val t = LinkedHashMap<String, Any?>(mapOf("serverName" to sni))
                q["fp"]?.takeIf { it.isNotEmpty() }?.let { t["fingerprint"] = it }
                q["alpn"]?.takeIf { it.isNotEmpty() }?.let { t["alpn"] = it.split(",") }
                if (q["allowInsecure"] == "1") t["allowInsecure"] = true
                s["tlsSettings"] = t
            }
            "reality" -> {
                val pbk = q["pbk"]?.takeIf { it.isNotEmpty() } ?: throw ConfigException("Не поддерживается: REALITY без pbk")
                s["security"] = "reality"
                s["realitySettings"] = mapOf(
                    "serverName" to sni,
                    "fingerprint" to (q["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome"),
                    "publicKey" to pbk, "shortId" to (q["sid"] ?: ""), "spiderX" to (q["spx"] ?: "")
                )
            }
            else -> s["security"] = "none"
        }
        return s
    }
}

/**
 * Конфиг sing-box (TUIC и Hysteria2). Поднимает локальный SOCKS5 с паролем — как Xray,
 * поэтому остальная часть туннеля одинакова для обоих ядер.
 * geoip:/geosite: для sing-box не поддерживаются (нужны rule-set'ы), такие правила пропускаются.
 */
object SingboxConfigBuilder {
    fun build(server: Server, options: TunnelOptions, socksPort: Int): XrayProfile {
        val user = XrayConfigBuilder.token(8)
        val pass = XrayConfigBuilder.token(16)

        fun tlsBlock(rawSni: String?, rawAlpn: String?, insecure: Boolean): M {
            val tls = LinkedHashMap<String, Any?>(mapOf("enabled" to true))
            val sni = (rawSni ?: "").trim()
            if (sni.isNotEmpty()) tls["server_name"] = sni
            else if (!XrayConfigBuilder.isIP(server.host)) tls["server_name"] = server.host
            if (insecure) tls["insecure"] = true
            val alpn = (rawAlpn ?: "h3").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            tls["alpn"] = alpn.ifEmpty { listOf("h3") }
            return tls
        }

        val proxy: M = when (server.proto) {
            "tuic" -> {
                val uuid = server.tuicUUID?.takeIf { it.isNotEmpty() } ?: throw ConfigException("Некорректная ссылка сервера")
                val cc = (server.tuicCongestion ?: "").lowercase()
                val tuic = LinkedHashMap<String, Any?>(
                    mapOf(
                        "type" to "tuic", "tag" to "proxy",
                        "server" to server.host, "server_port" to server.port,
                        "uuid" to uuid, "password" to (server.tuicPassword ?: ""),
                        "congestion_control" to if (cc in listOf("cubic", "new_reno", "bbr")) cc else "bbr",
                        "zero_rtt_handshake" to false,
                        "heartbeat" to "10s",
                        "tls" to tlsBlock(server.tuicSNI, server.tuicALPN, server.tuicInsecure == true)
                    )
                )
                val mode = (server.tuicUDPMode ?: "").lowercase()
                if (mode == "native" || mode == "quic") tuic["udp_relay_mode"] = mode
                tuic
            }
            "hysteria2" -> {
                val hy = LinkedHashMap<String, Any?>(
                    mapOf(
                        "type" to "hysteria2", "tag" to "proxy",
                        "server" to server.host, "server_port" to server.port,
                        "password" to (server.hy2Auth ?: ""),
                        "tls" to tlsBlock(server.hy2SNI, null, server.hy2Insecure)
                    )
                )
                server.hy2Obfs?.takeIf { it.isNotEmpty() }?.let {
                    hy["obfs"] = mapOf("type" to it, "password" to (server.hy2ObfsPassword ?: ""))
                }
                hy
            }
            else -> throw ConfigException("Не поддерживается: ${server.proto}")
        }

        val dnsIP = options.dns.firstOrNull { XrayConfigBuilder.isIP(it) } ?: "1.1.1.1"
        val route = LinkedHashMap<String, Any?>(mapOf("final" to "proxy", "default_domain_resolver" to "dns-bootstrap"))
        val rules = directRules(options)
        if (rules.isNotEmpty()) route["rules"] = rules

        val cfg = mapOf(
            "log" to mapOf("level" to "warn", "timestamp" to false),
            "dns" to mapOf("servers" to listOf(mapOf("type" to "udp", "tag" to "dns-bootstrap", "server" to dnsIP))),
            "inbounds" to listOf(
                mapOf(
                    "type" to "socks", "tag" to "socks-in", "listen" to "127.0.0.1", "listen_port" to socksPort,
                    "users" to listOf(mapOf("username" to user, "password" to pass))
                )
            ),
            "outbounds" to listOf(proxy, mapOf("type" to "direct", "tag" to "direct")),
            "route" to route
        )
        return XrayProfile(toJson(cfg), socksPort, user, pass, core = "singbox")
    }

    private fun directRules(o: TunnelOptions): List<M> {
        val suffix = mutableListOf<String>(); val full = mutableListOf<String>()
        val keyword = mutableListOf<String>(); val regex = mutableListOf<String>()
        val cidr = mutableListOf<String>()
        for (line in o.directRules) {
            val r = line.trim(); val low = r.lowercase()
            if (r.isEmpty() || r.startsWith("#")) continue
            when {
                low.startsWith("geoip:") || low.startsWith("geosite:") -> {}
                low.startsWith("domain:") -> suffix.add(r.drop(7))
                low.startsWith("full:") -> full.add(r.drop(5))
                low.startsWith("keyword:") -> keyword.add(r.drop(8))
                low.startsWith("regexp:") -> regex.add(r.drop(7))
                XrayConfigBuilder.isIP(r) -> cidr.add(r)
                else -> suffix.add(low)
            }
        }
        val out = mutableListOf<M>()
        if (o.bypassLAN) out.add(mapOf("ip_is_private" to true, "action" to "route", "outbound" to "direct"))
        fun rule(key: String, v: List<String>) {
            if (v.isNotEmpty()) out.add(mapOf(key to v, "action" to "route", "outbound" to "direct"))
        }
        rule("domain_suffix", suffix); rule("domain", full)
        rule("domain_keyword", keyword); rule("domain_regex", regex); rule("ip_cidr", cidr)
        return out
    }
}
