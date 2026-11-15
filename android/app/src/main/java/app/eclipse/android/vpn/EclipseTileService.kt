package app.eclipse.android.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.eclipse.android.EclipseApp
import app.eclipse.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Плитка быстрых настроек: включает/выключает VPN на выбранном сервере. */
class EclipseTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        val s = CoroutineScope(Dispatchers.Main)
        scope = s
        job = s.launch { VpnController.state.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel(); scope?.cancel()
    }

    private fun render(st: VpnState) {
        val t = qsTile ?: return
        t.state = when (st) {
            VpnState.CONNECTED -> Tile.STATE_ACTIVE
            VpnState.CONNECTING, VpnState.DISCONNECTING -> Tile.STATE_UNAVAILABLE
            VpnState.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        t.label = "Eclipse"
        t.updateTile()
    }

    override fun onClick() {
        when (VpnController.state.value) {
            VpnState.CONNECTED -> VpnController.stop(this)
            VpnState.DISCONNECTED -> {
                val server = EclipseApp.instance.store.selected ?: return openApp()
                if (VpnService.prepare(this) != null) return openApp()   // нужно разрешение — спрашиваем в приложении
                VpnController.start(this, server.id)
            }
            else -> {}
        }
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
