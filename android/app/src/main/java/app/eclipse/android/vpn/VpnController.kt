package app.eclipse.android.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/** Общее состояние VPN: сервис пишет, интерфейс и плитка читают. */
object VpnController {
    internal val _state = MutableStateFlow(VpnState.DISCONNECTED)
    internal val _error = MutableStateFlow<String?>(null)
    internal val _activeServerId = MutableStateFlow<String?>(null)
    internal val _health = MutableStateFlow<String?>(null)

    val state: StateFlow<VpnState> = _state
    val error: StateFlow<String?> = _error
    val activeServerId: StateFlow<String?> = _activeServerId
    /** Результат последней проверки "трафик идёт". */
    val health: StateFlow<String?> = _health

    fun clearError() { _error.value = null }

    /** Разрешение VpnService.prepare() уже должно быть получено. */
    fun start(ctx: Context, serverId: String) {
        _error.value = null
        val i = Intent(ctx, EclipseVpnService::class.java)
            .setAction(EclipseVpnService.ACTION_START)
            .putExtra(EclipseVpnService.EXTRA_SERVER_ID, serverId)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(ctx: Context) {
        if (_state.value == VpnState.DISCONNECTED) return
        ctx.startService(Intent(ctx, EclipseVpnService::class.java).setAction(EclipseVpnService.ACTION_STOP))
    }
}
