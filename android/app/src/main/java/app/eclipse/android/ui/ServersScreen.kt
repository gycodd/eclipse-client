package app.eclipse.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eclipse.android.data.AppSettings
import app.eclipse.android.data.Store
import app.eclipse.android.data.ToastKind
import app.eclipse.android.data.Toasts
import app.eclipse.android.model.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ServersScreen(modifier: Modifier, store: Store, settings: AppSettings, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = EC.c
    var showAdd by remember { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var adding by remember { mutableStateOf(false) }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@rememberLauncherForActivityResult
        scope.launch { store.add(text) }
    }
    fun launchScan() {
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
            .setOrientationLocked(false).setPrompt("Наведите камеру на QR-код подписки"))
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Серверы", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { store.refreshAll() } }) {
                    if (store.refreshing.isNotEmpty()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, "Обновить все", tint = c.accentLight)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Добавить подписку", Modifier.weight(1f)) { showAdd = true }
                OutlinedButton(onClick = ::launchScan, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, "QR", tint = c.accentLight)
                }
            }
        }
        store.message?.let { m -> item { Text(m, color = c.bad, fontSize = 13.sp) } }
        if (store.groups.isEmpty()) {
            item {
                Text(
                    "Пока пусто. Добавьте ссылку подписки, отсканируйте QR-код или вставьте ссылку " +
                        "vless:// / vmess:// / trojan:// / ss:// / hysteria2:// / tuic://.",
                    color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        items(store.groups, key = { it.id }) { g ->
            SubscriptionBlock(
                g, store, settings, collapsed = g.id in collapsed,
                onToggleCollapsed = { collapsed = if (g.id in collapsed) collapsed - g.id else collapsed + g.id },
                onPick = onPick,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAdd) AddDialog(
        busy = adding,
        onDismiss = { if (!adding) showAdd = false },
        onScan = { showAdd = false; launchScan() },
        onAdd = { text ->
            scope.launch {
                adding = true
                val ok = store.add(text)
                adding = false
                if (ok) showAdd = false
            }
        },
    )
}

@Composable
private fun AddDialog(busy: Boolean, onDismiss: () -> Unit, onScan: () -> Unit, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить") },
        text = {
            Column {
                Text("Ссылка подписки (https://…) или сервера (vless://, vmess://, trojan://, ss://, hysteria2://, tuic://)",
                    fontSize = 13.sp, color = EC.c.muted)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: text
                }) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Вставить из буфера") }
                TextButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сканировать QR") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank() && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SubscriptionBlock(
    g: SubGroup, store: Store, settings: AppSettings,
    collapsed: Boolean, onToggleCollapsed: () -> Unit, onPick: (String) -> Unit,
) {
    val c = EC.c
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val refreshing = g.id in store.refreshing
    val pinging = g.id in store.pingingGroups

    Card2(Modifier.animateContentSize()) {
        Row(Modifier.clickable(onClick = onToggleCollapsed), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.name, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val upd = g.updatedAt?.let { " · обновлено " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: ""
                Text("${g.servers.size} серв.$upd", color = c.muted, fontSize = 12.sp)
            }
            Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, null, tint = c.muted)
        }

        // трафик и срок
        if (g.total != null) {
            val used = g.used ?: 0L
            val frac = (used.toFloat() / g.total.toFloat()).coerceIn(0f, 1f)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            Text("${fmtBytes(used)} из ${fmtBytes(g.total)}", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else if (g.used != null) {
            Text("Израсходовано ${fmtBytes(g.used)}", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        g.expire?.let {
            val days = (it * 1000 - System.currentTimeMillis()) / 86_400_000L
            Text(if (days >= 0) "Осталось дней: $days" else "Срок истёк", color = if (days in 0..3 || days < 0) c.warn else c.muted, fontSize = 12.sp)
        }
        g.announce?.let { Text(it, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

        if (g.url.isNotEmpty()) Row(Modifier.padding(top = 6.dp)) {
            IconButton(onClick = { scope.launch { store.refresh(g.id) } }) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Refresh, "Обновить", tint = c.accentLight)
            }
            IconButton(onClick = { scope.launch { store.pingAll(g.id) } }) {
                if (pinging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Speed, "Пинг", tint = c.accentLight)
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Tune, "Настройки подписки", tint = c.accentLight) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить", tint = c.bad) }
        } else Row(Modifier.padding(top = 6.dp)) {
            IconButton(onClick = { scope.launch { store.pingAll(g.id) } }) {
                if (pinging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Speed, "Пинг", tint = c.accentLight)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить", tint = c.bad) }
        }

        if (!collapsed) {
            HorizontalDivider(color = c.border)
            store.displayServers(g).forEach { s ->
                ServerRow(s, store, settings, selected = s.id == store.selectedID, onPick = { onPick(s.id) })
            }
        }
    }

    if (confirmDelete) ConfirmDialog("Удалить подписку?", "«${g.name}» и все её серверы будут удалены.", onDismiss = { confirmDelete = false }) {
        store.removeGroup(g.id)
    }
    if (showSettings) GroupSettingsDialog(g, store) { showSettings = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(s: Server, store: Store, settings: AppSettings, selected: Boolean, onPick: () -> Unit) {
    val c = EC.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val style = settings.pingDisplay

    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onPick, onLongClick = { menu = true }).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (selected) c.accent else c.muted.copy(alpha = 0.3f))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                    Chip(s.protocolLabel)
                    s.transportLabel?.let { if (s.proto != "hysteria2" && s.proto != "tuic") Chip(it) }
                }
            }
            PingBadge(store.pings[s.id], style)
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню", tint = c.muted) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Проверить пинг") }, onClick = { menu = false; scope.launch { store.pingSingle(s) } })
            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Скопировать ссылку") }, onClick = {
                menu = false
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("server", s.link))
                Toasts.show("Ссылка скопирована", ToastKind.INFO)
            })
            DropdownMenuItem(text = { Text("Удалить", color = c.bad) }, onClick = { menu = false; confirm = true })
        }
    }
    if (rename) RenameDialog(s.name, onDismiss = { rename = false }) { store.renameServer(s.id, it) }
    if (confirm) ConfirmDialog("Удалить сервер?", s.name, onDismiss = { confirm = false }) { store.removeServer(s.id) }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Переименовать") },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (t.isNotBlank()) onSave(t.trim()); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun GroupSettingsDialog(g: SubGroup, store: Store, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(g.name) }
    var url by remember { mutableStateOf(g.url) }
    var hours by remember { mutableStateOf(g.updateIntervalHours?.toString() ?: "") }
    var ping by remember { mutableStateOf(PingProtocol.of(g.pingProtocolRaw)) }
    var preset by remember { mutableStateOf(g.dnsPreset?.let { DNSPreset.of(it) }) }
    var custom by remember { mutableStateOf(g.dnsCustom ?: "") }
    var doh by remember { mutableStateOf(g.dnsDoH ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Настройки подписки") },
        text = {
            Column(Modifier.verticalScrollCompat()) {
                FieldRow("Название", name, { name = it })
                FieldRow("Ссылка", url, { url = it })
                FieldRow("Обновлять каждые N часов", hours, { hours = it.filter(Char::isDigit) }, hint = "Пусто — как в общих настройках", numeric = true)
                ChoiceRow("Пинг", ping, listOf<PingProtocol?>(null) + PingProtocol.entries, { it?.title ?: "Как в общих" }) { ping = it }
                ChoiceRow("DNS", preset, listOf<DNSPreset?>(null) + DNSPreset.entries, { it?.title ?: "Как в общих" }) { preset = it }
                if (preset == DNSPreset.CUSTOM) FieldRow("Свой DNS", custom, { custom = it }, hint = "IP или https://… (DoH)")
                else if (preset != null) ToggleRow("DNS поверх HTTPS (DoH)", null, doh) { doh = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.updateGroupSettings(
                    g.id, name.trim(), url.trim(), hours.toIntOrNull()?.takeIf { it > 0 }, ping,
                    preset?.name, if (preset == DNSPreset.CUSTOM) custom else null, if (preset != null) doh else null,
                )
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

private fun fmtBytes(n: Long): String {
    val u = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "${v.toLong()} ${u[i]}" else String.format("%.1f %s", v, u[i])
}
