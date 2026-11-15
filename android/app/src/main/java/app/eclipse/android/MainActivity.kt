package app.eclipse.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.eclipse.android.data.ToastKind
import app.eclipse.android.data.Toasts
import app.eclipse.android.ui.*
import app.eclipse.android.vpn.VpnController
import app.eclipse.android.vpn.VpnState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root() {
    val app = EclipseApp.instance
    val settings = app.settings
    val store = app.store

    EclipseTheme(settings.appearance) {
        val ctx = LocalContext.current
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showLog by rememberSaveable { mutableStateOf(false) }
        val snack = remember { SnackbarHostState() }
        val vpnState by VpnController.state.collectAsState()
        val error by VpnController.error.collectAsState()
        var pendingStart by remember { mutableStateOf<String?>(null) }

        val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val id = pendingStart
            pendingStart = null
            if (r.resultCode == Activity.RESULT_OK && id != null) VpnController.start(ctx, id)
            else if (id != null) Toasts.show("Нужно разрешение на VPN", ToastKind.ERROR)
        }
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        fun connect(id: String) {
            val i = VpnService.prepare(ctx)
            if (i != null) { pendingStart = id; vpnPermission.launch(i) }
            else VpnController.start(ctx, id)
        }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            store.autoRefresh()
        }
        LaunchedEffect(Unit) { Toasts.flow.collect { snack.showSnackbar(it.text, duration = SnackbarDuration.Short) } }
        LaunchedEffect(error) {
            error?.let { VpnController.clearError(); snack.showSnackbar(it, duration = SnackbarDuration.Long) }
        }

        val onToggle: () -> Unit = {
            when (vpnState) {
                VpnState.CONNECTED, VpnState.CONNECTING -> VpnController.stop(ctx)
                VpnState.DISCONNECTED -> {
                    val s = store.selected
                    if (s == null) Toasts.show("Сначала добавьте подписку или сервер", ToastKind.ERROR) else connect(s.id)
                }
                VpnState.DISCONNECTING -> {}
            }
        }
        val onPick: (String) -> Unit = { id ->
            store.select(id)
            // Если VPN уже работает — переключаем сервер на лету.
            if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) connect(id)
        }

        BackHandler(enabled = showLog) { showLog = false }

        Scaffold(
            containerColor = EC.c.bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (!showLog) NavigationBar(containerColor = EC.c.card) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Shield, null) }, label = { Text("Главная") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Dns, null) }, label = { Text("Серверы") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when {
                showLog -> LogScreen(m, onBack = { showLog = false })
                tab == 0 -> HomeScreen(m, store, onToggle, onOpenServers = { tab = 1 })
                tab == 1 -> ServersScreen(m, store, settings, onPick)
                else -> SettingsScreen(m, settings, store, onOpenLog = { showLog = true })
            }
        }
    }
}
