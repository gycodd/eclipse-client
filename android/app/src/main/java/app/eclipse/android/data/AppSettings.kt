package app.eclipse.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import app.eclipse.android.core.XrayConfigBuilder
import app.eclipse.android.model.*
import java.io.File

class AppSettings(private val ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("eclipse.settings", Context.MODE_PRIVATE)
    private val cache = mutableStateMapOf<String, Any>()

    init {
        sp.all.forEach { (k, v) -> if (v != null) cache[k] = v }
    }

    fun bool(k: String, d: Boolean) = cache[k] as? Boolean ?: d
    fun int(k: String, d: Int) = cache[k] as? Int ?: d
    fun str(k: String, d: String) = cache[k] as? String ?: d

    fun put(k: String, v: Any) {
        cache[k] = v
        sp.edit().apply {
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is String -> putString(k, v)
            }
        }.apply()
    }

    fun reset() {
        cache.clear()
        sp.edit().clear().apply()
    }

    // --- типизированные геттеры (читают snapshot-state, поэтому UI перерисовывается) ---
    val mtu get() = int("mtu", 1400)
    val dnsPreset get() = DNSPreset.of(str("dnsPreset", "CLOUDFLARE"))
    val customDNS get() = str("customDNS", "")
    val dnsOverHTTPS get() = bool("dnsOverHTTPS", false)
    val bypassLAN get() = bool("bypassLAN", true)
    val directRules get() = str("directRules", "")
    val useGeoRouting get() = bool("useGeoRouting", false)
    val geoipDirect get() = str("geoipDirect", "ru")
    val geositeDirect get() = str("geositeDirect", "category-ads-all")
    val sniffing get() = bool("sniffing", true)
    val mux get() = bool("mux", false)
    val fragment get() = bool("fragment", false)
    val memoryLimit get() = int("memoryLimit", 256)
    val autoUpdate get() = bool("autoUpdate", true)
    val updateHours get() = int("updateHours", 12)
    val requestTimeout get() = int("requestTimeout", 20)
    val uaPreset get() = UAPreset.of(str("uaPreset", "ECLIPSE"))
    val customUA get() = str("customUA", "")
    val sendHWID get() = bool("sendHWID", false)
    val sortByPing get() = bool("sortByPing", false)
    val pingDisplay get() = PingDisplay.of(str("pingDisplay", "TIME"))
    val pingProtocol get() = PingProtocol.of(str("pingProtocol", "TCP")) ?: PingProtocol.TCP
    val appearance get() = AppearanceMode.of(str("appearance", "DARK"))
    val autoSwitch get() = bool("autoSwitch", true)

    val userAgent: String
        get() = if (uaPreset == UAPreset.CUSTOM) customUA.trim().ifEmpty { UAPreset.ECLIPSE.value } else uaPreset.value

    /** Устойчивый идентификатор установки для заголовков HWID. */
    val hwid: String
        get() = str("hwid", "").ifEmpty {
            java.util.UUID.randomUUID().toString().also { put("hwid", it) }
        }

    val geoDir: File get() = File(ctx.filesDir, "geo").also { it.mkdirs() }
    val geoAvailable: Boolean
        get() = File(geoDir, "geoip.dat").length() > 10_000 && File(geoDir, "geosite.dat").length() > 10_000

    data class ResolvedDNS(val ips: List<String>, val dohURL: String?)

    fun resolveDNS(preset: DNSPreset, custom: String, doh: Boolean): ResolvedDNS {
        if (preset != DNSPreset.CUSTOM) return ResolvedDNS(preset.servers, if (doh) preset.dohURL else null)
        val ips = mutableListOf<String>()
        var url: String? = null
        for (item in custom.split(',', ' ', '\n', ';').filter { it.isNotEmpty() }) {
            if (item.lowercase().startsWith("https://")) url = item
            else if (XrayConfigBuilder.isIP(item)) ips.add(item)
        }
        if (ips.isEmpty()) ips.addAll(DNSPreset.CLOUDFLARE.servers)
        return ResolvedDNS(ips, url)
    }

    val tunnelOptions: TunnelOptions
        get() {
            val r = resolveDNS(dnsPreset, customDNS, dnsOverHTTPS)
            val rules = directRules.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (useGeoRouting) {
                geoipDirect.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { rules.add("geoip:$it") }
                geositeDirect.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { rules.add("geosite:$it") }
            }
            return TunnelOptions(
                dns = r.ips, mtu = mtu, mux = mux, fragment = fragment, sniffing = sniffing,
                bypassLAN = bypassLAN, directRules = rules, memoryLimit = memoryLimit,
                dohURL = r.dohURL, geoAvailable = geoAvailable,
            )
        }
}
