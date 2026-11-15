package app.eclipse.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eclipse.android.data.AppSettings
import app.eclipse.android.data.Store
import app.eclipse.android.model.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier, s: AppSettings, store: Store, onOpenLog: () -> Unit) {
    val c = EC.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var geoBusy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Настройки", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(top = 20.dp))

        SectionTitle("Сеть")
        Card2 {
            FieldRow("MTU", s.mtu.toString(), { v -> v.toIntOrNull()?.let { s.put("mtu", it.coerceIn(576, 9000)) } }, numeric = true)
            ChoiceRow("DNS", s.dnsPreset, DNSPreset.entries, { it.title }) { s.put("dnsPreset", it.name) }
            Text(s.dnsPreset.subtitle, color = c.muted, fontSize = 12.sp)
            if (s.dnsPreset == DNSPreset.CUSTOM)
                FieldRow("Свой DNS", s.customDNS, { s.put("customDNS", it) }, hint = "IP-адреса и/или https://… (DoH), через запятую")
            else ToggleRow("DNS поверх HTTPS (DoH)", "Внутренний DNS ядра ходит по HTTPS", s.dnsOverHTTPS) { s.put("dnsOverHTTPS", it) }
            ToggleRow("Определение трафика (sniffing)", "Нужно для маршрутизации по доменам", s.sniffing) { s.put("sniffing", it) }
            ToggleRow("Mux", "Мультиплексирование соединений (VLESS/VMess/Trojan)", s.mux) { s.put("mux", it) }
            ToggleRow("Фрагментация TLS", "Для обхода DPI-блокировок", s.fragment) { s.put("fragment", it) }
            FieldRow("Лимит памяти ядра, МБ", s.memoryLimit.toString(), { v -> v.toIntOrNull()?.let { s.put("memoryLimit", it.coerceIn(32, 2048)) } }, numeric = true)
        }

        SectionTitle("Маршрутизация")
        Card2 {
            ToggleRow("Обходить локальные сети", null, s.bypassLAN) { s.put("bypassLAN", it) }
            ToggleRow("Маршрутизация по геоданным", "Трафик выбранных стран/категорий идёт напрямую", s.useGeoRouting) { s.put("useGeoRouting", it) }
            if (s.useGeoRouting) {
                FieldRow("GeoIP напрямую", s.geoipDirect, { s.put("geoipDirect", it) }, hint = "Например: ru, cn (через запятую)")
                FieldRow("GeoSite напрямую", s.geositeDirect, { s.put("geositeDirect", it) }, hint = "Например: category-ads-all")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.geoAvailable) "Геобазы установлены" else "Геобаз нет — правила geo не применяются",
                        color = if (s.geoAvailable) c.muted else c.warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(enabled = !geoBusy, onClick = { scope.launch { geoBusy = true; store.downloadGeo(); geoBusy = false } }) {
                        if (geoBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Скачать")
                    }
                }
                Text("Для Hysteria2/TUIC (sing-box) geo-правила не поддерживаются.", color = c.muted, fontSize = 12.sp)
            }
            FieldRow(
                "Прямые правила", s.directRules, { s.put("directRules", it) },
                hint = "По одному в строке: домен, IP/подсеть, domain:, full:, keyword:, regexp:, geoip:, geosite:",
                singleLine = false, minLines = 3,
            )
        }

        SectionTitle("Подписки")
        Card2 {
            ToggleRow("Автообновление", null, s.autoUpdate) { s.put("autoUpdate", it) }
            FieldRow("Интервал, часов", s.updateHours.toString(), { v -> v.toIntOrNull()?.let { s.put("updateHours", it.coerceIn(1, 720)) } }, numeric = true)
            FieldRow("Таймаут запроса, с", s.requestTimeout.toString(), { v -> v.toIntOrNull()?.let { s.put("requestTimeout", it.coerceIn(5, 120)) } }, numeric = true)
            ChoiceRow("User-Agent", s.uaPreset, UAPreset.entries, { it.title }) { s.put("uaPreset", it.name) }
            if (s.uaPreset == UAPreset.CUSTOM) FieldRow("Свой User-Agent", s.customUA, { s.put("customUA", it) })
            ToggleRow("Отправлять HWID", "Заголовки устройства для провайдеров с привязкой к устройству", s.sendHWID) { s.put("sendHWID", it) }
        }

        SectionTitle("Серверы")
        Card2 {
            ToggleRow("Автопереключение", "Если трафик перестал идти — перейти на следующий сервер подписки", s.autoSwitch) { s.put("autoSwitch", it) }
            ToggleRow("Сортировать по пингу", null, s.sortByPing) { s.put("sortByPing", it) }
            ChoiceRow("Способ пинга", s.pingProtocol, PingProtocol.entries, { it.title }) { s.put("pingProtocol", it.name) }
            Text(s.pingProtocol.subtitle, color = c.muted, fontSize = 12.sp)
            ChoiceRow("Отображение пинга", s.pingDisplay, PingDisplay.entries, { it.title }) { s.put("pingDisplay", it.name) }
        }

        SectionTitle("Интерфейс")
        Card2 {
            ChoiceRow("Тема", s.appearance, AppearanceMode.entries, { it.title }) { s.put("appearance", it.name) }
        }

        SectionTitle("Система")
        Card2 {
            Row(
                Modifier.fillMaxWidth().clickable {
                    ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Постоянный VPN и блокировка без VPN", color = c.text, fontSize = 15.sp)
                    Text("Системные настройки: автоподключение и kill-switch (аналог On-Demand)", color = c.muted, fontSize = 12.sp)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = c.muted)
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenLog).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Журнал", color = c.text, fontSize = 15.sp)
                    Text("Приложение, туннель и ядро", color = c.muted, fontSize = 12.sp)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = c.muted)
            }
            TextButton(onClick = { confirmReset = true }) { Text("Сбросить настройки", color = c.bad) }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmReset) ConfirmDialog("Сбросить настройки?", "Подписки и серверы останутся.", "Сбросить", { confirmReset = false }) { s.reset() }
}
