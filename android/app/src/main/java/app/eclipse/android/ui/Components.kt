package app.eclipse.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eclipse.android.model.PingDisplay

@Composable
fun Card2(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = EC.c.card),
        border = BorderStroke(1.dp, EC.c.border),
    ) { Column(Modifier.padding(14.dp), content = content) }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EC.c.accentLight,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(EC.c.accent.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EC.c.muted,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = EC.c.accent, contentColor = Color.White),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun PingBadge(ms: Int?, style: PingDisplay) {
    val c = EC.c
    when {
        ms == null -> {}
        ms < 0 -> Text(if (style == PingDisplay.TIME) "—" else "✕", color = c.bad, fontSize = 12.sp)
        else -> {
            val col = when { ms < 150 -> c.good; ms < 400 -> c.warn; else -> c.bad }
            when (style) {
                PingDisplay.TIME -> Text("$ms мс", color = col, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                PingDisplay.DOTS -> {
                    val n = when { ms < 100 -> 4; ms < 200 -> 3; ms < 400 -> 2; else -> 1 }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(4) { i ->
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i < n) col else c.muted.copy(alpha = 0.3f)))
                        }
                    }
                }
                PingDisplay.BAR -> {
                    val frac = (1f - (ms / 600f)).coerceIn(0.1f, 1f)
                    Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.muted.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(col))
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = EC.c.text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = EC.c.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun FieldRow(
    title: String, value: String, onChange: (String) -> Unit, hint: String? = null,
    singleLine: Boolean = true, minLines: Int = 1, numeric: Boolean = false,
) {
    // Локальная копия текста: поле можно очистить и набрать заново, даже если пустое значение не принимается.
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontSize = 15.sp, color = EC.c.text)
        if (hint != null) Text(hint, fontSize = 12.sp, color = EC.c.muted)
        OutlinedTextField(
            value = local, onValueChange = { local = it; onChange(it) }, singleLine = singleLine, minLines = minLines,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            keyboardOptions = if (numeric)
                androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            else androidx.compose.foundation.text.KeyboardOptions.Default,
        )
    }
}

@Composable
fun <T> ChoiceRow(title: String, value: T, options: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = EC.c.text, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(label(value), color = EC.c.accentLight) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onChange(o) })
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Удалить", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = EC.c.bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
