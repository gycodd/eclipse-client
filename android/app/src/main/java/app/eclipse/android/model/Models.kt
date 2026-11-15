package app.eclipse.android.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val proto: String,
    val host: String,
    val port: Int,
    val link: String,
    val transport: String? = null,
    val security: String? = null,

    val hy2Auth: String? = null,
    val hy2Obfs: String? = null,
    val hy2ObfsPassword: String? = null,
    val hy2SNI: String? = null,
    val hy2Insecure: Boolean = false,

    val tuicUUID: String? = null,
    val tuicPassword: String? = null,
    val tuicCongestion: String? = null,
    val tuicUDPMode: String? = null,
    val tuicSNI: String? = null,
    val tuicALPN: String? = null,
    val tuicInsecure: Boolean? = null,
) {
    /** QUIC-протоколы слушают UDP — TCP-проверка порта для них бессмысленна. */
    val isUDPBased: Boolean get() = proto == "hysteria2" || proto == "tuic"

    /** TUIC и Hysteria2 обслуживает sing-box, остальное — Xray. */
    val core: String get() = if (proto == "tuic" || proto == "hysteria2") "singbox" else "xray"

    val protocolLabel: String
        get() {
            if (proto == "hysteria2") return "HYSTERIA 2"
            if (proto == "tuic") return "TUIC"
            val base = if (proto == "ss") "SHADOWSOCKS" else proto.uppercase()
            val s = security
            return if (!s.isNullOrEmpty() && s != "none") "$base + ${s.uppercase()}" else base
        }

    val transportLabel: String? get() = transport?.takeIf { it.isNotEmpty() }?.uppercase()
}

@Serializable
data class SubGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val servers: List<Server> = emptyList(),
    val updatedAt: Long? = null,
    val used: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,           // секунды unix
    val supportURL: String? = null,
    val webURL: String? = null,
    val updateIntervalHours: Int? = null,
    val pingProtocolRaw: String? = null,
    val announce: String? = null,
    val announceURL: String? = null,
    val dnsPreset: String? = null,
    val dnsCustom: String? = null,
    val dnsDoH: Boolean? = null,
)

@Serializable
data class TunnelOptions(
    val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val mtu: Int = 1400,
    val mux: Boolean = false,
    val fragment: Boolean = false,
    val sniffing: Boolean = true,
    val bypassLAN: Boolean = true,
    val directRules: List<String> = emptyList(),
    val memoryLimit: Int = 256,
    /** Если задан — внутренний DNS Xray ходит по HTTPS (DoH). */
    val dohURL: String? = null,
    /** Есть ли на устройстве geoip.dat / geosite.dat. */
    val geoAvailable: Boolean = false,
)

enum class PingProtocol(val title: String, val subtitle: String) {
    TCP("TCP", "Рекомендуется - самый быстрый"),
    HTTP_GET("HTTP GET", "GET-запрос, время до первого ответа сервера"),
    HTTP_HEAD("HTTP HEAD", "HEAD-запрос, время до первого ответа сервера"),
    ICMP("ICMP", "ICMP echo (часть серверов его блокирует)");

    companion object {
        fun of(raw: String?): PingProtocol? = entries.firstOrNull { it.name == raw }
    }
}

enum class PingDisplay(val title: String) {
    TIME("Время"), DOTS("Точки"), BAR("Шкала");

    companion object {
        fun of(raw: String?): PingDisplay = entries.firstOrNull { it.name == raw } ?: TIME
    }
}

enum class AppearanceMode(val title: String) {
    DARK("Тёмная"), LIGHT("Светлая"), SYSTEM("Как в системе");

    companion object {
        fun of(raw: String?): AppearanceMode = entries.firstOrNull { it.name == raw } ?: DARK
    }
}

enum class DNSPreset(val title: String, val servers: List<String>, val dohURL: String?, val subtitle: String) {
    CLOUDFLARE("Cloudflare", listOf("1.1.1.1", "1.0.0.1"), "https://cloudflare-dns.com/dns-query", "1.1.1.1 · быстрый, без фильтрации"),
    GOOGLE("Google", listOf("8.8.8.8", "8.8.4.4"), "https://dns.google/dns-query", "8.8.8.8 · быстрый"),
    QUAD9("Quad9", listOf("9.9.9.9", "149.112.112.112"), "https://dns.quad9.net/dns-query", "9.9.9.9 · блокирует вредоносные домены"),
    ADGUARD("AdGuard (без рекламы)", listOf("94.140.14.14", "94.140.15.15"), "https://dns.adguard-dns.com/dns-query", "блокирует рекламу и трекеры"),
    CUSTOM("Свой", listOf("1.1.1.1", "1.0.0.1"), null, "IP или https://… (DoH)");

    companion object {
        fun of(raw: String?): DNSPreset = entries.firstOrNull { it.name == raw } ?: CLOUDFLARE
    }
}

enum class UAPreset(val title: String, val value: String) {
    ECLIPSE("Eclipse", "Eclipse/1.3"),
    HAPP("Happ", "Happ/3.0.0"),
    V2RAYN("v2rayN", "v2rayN/7.0"),
    CUSTOM("Свой", "Eclipse/1.3");

    companion object {
        fun of(raw: String?): UAPreset = entries.firstOrNull { it.name == raw } ?: ECLIPSE
    }
}
