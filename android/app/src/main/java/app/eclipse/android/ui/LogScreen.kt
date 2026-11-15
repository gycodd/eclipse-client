package app.eclipse.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eclipse.android.data.AppLog
import app.eclipse.android.data.ToastKind
import app.eclipse.android.data.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LogScreen(modifier: Modifier, onBack: () -> Unit) {
    val c = EC.c
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            text = withContext(Dispatchers.IO) { AppLog.dump() }
            delay(2000)
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = c.text) }
            Text("Журнал", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", text))
                Toasts.show("Журнал скопирован", ToastKind.INFO)
            }) { Icon(Icons.Filled.ContentCopy, "Копировать", tint = c.accentLight) }
            IconButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                ctx.startActivity(Intent.createChooser(i, "Экспорт журнала").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(Icons.Filled.Share, "Экспорт", tint = c.accentLight) }
            IconButton(onClick = { AppLog.clear(); text = "" }) { Icon(Icons.Filled.Delete, "Очистить", tint = c.bad) }
        }
        Text(
            text.ifEmpty { "Журнал пуст" },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.text,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        )
    }
}
