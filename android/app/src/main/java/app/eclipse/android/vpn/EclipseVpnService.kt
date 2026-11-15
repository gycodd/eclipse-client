package app.eclipse.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.eclipse.android.EclipseApp
import app.eclipse.android.MainActivity
import app.eclipse.android.R
import app.eclipse.android.core.XrayConfigBuilder
import app.eclipse.android.core.XrayProfile
import app.eclipse.android.data.AppLog
import app.eclipse.android.data.ToastKind
import app.eclipse.android.data.Toasts
import app.eclipse.android.model.Server
import app.eclipse.android.model.TunnelOptions
import app.eclipse.android.net.Socks5Probe
import app.eclipse.xraybridge.Xraybridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Android-аналог PacketTunnelProvider:
 *   Xray/sing-box (локальный SOCKS5 с паролем)  <-  tun2socks (внутри Go-библиотеки)  <-  TUN от VpnService.
 * Сокеты самого приложения (а значит и ядра) исключены из VPN через addDisallowedApplication,
 * поэтому исходящие соединения ядра идут мимо туннеля и петли не возникает.
 */
class EclipseVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.eclipse.android.START"
        const val ACTION_STOP = "app.eclipse.android.STOP"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL = "eclipse.vpn"
        private const val NOTIF_ID = 1
        private const val FAIL_THRESHOLD = 3
    }

    private class Active(
        val server: Server, val profile: XrayProfile, val options: TunnelOptions,
        val candidates: List<Server>,
    )

    // Всё, что трогает ядро и TUN, выполняется строго по очереди в одном потоке.
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val bg = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private var cur: Active? = null
    private var tried = mutableSetOf<String>()
    private var healthJob: Job? = null
    private var restartJob: Job? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    @Volatile private var stopping = false
    private var failStreak = 0

    private val app get() = EclipseApp.instance

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { disconnect("запрос пользователя") }
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_SERVER_ID)
                goForeground(getString(R.string.app_name), "Подключение…")
                VpnController._state.value = VpnState.CONNECTING
                scope.launch { connect(id) }
            }
            else -> if (VpnController._state.value == VpnState.DISCONNECTED) { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Пользователь выключил VPN в системных настройках или включил другой VPN.
        AppLog.write("onRevoke")
        scope.launch { disconnect("VPN отозван системой") }
    }

    override fun onDestroy() {
        stopping = true
        runCatching { stopStack() }
        scope.cancel(); bg.cancel()
        executor.shutdown()
        VpnController._state.value = VpnState.DISCONNECTED
        super.onDestroy()
    }

    // ------------------------------------------------------------------ подключение

    private suspend fun connect(serverId: String?) {
        stopping = false
        try {
            cancelJobs()
            stopStack()
            val store = app.store
            val server = store.server(serverId) ?: store.selected
                ?: return fail("Сервер не выбран")
            val opts = store.options(server)
            val candidates = if (app.settings.autoSwitch) listOf(server) + store.fallbackCandidates(server) else listOf(server)
            tried = mutableSetOf(server.id)
            AppLog.write("startTunnel: Android ${Build.VERSION.RELEASE}, xray ${runCatching { Xraybridge.version() }.getOrDefault("?")}")
            AppLog.write("сервер ${server.host}:${server.port}, ядро=${server.core}, mtu=${opts.mtu}, dns=${opts.dns.joinToString(",")}${if (opts.dohURL != null) " (DoH)" else ""}")
            AppLog.write("автопереключение: ${if (app.settings.autoSwitch) "вкл, запасных серверов ${candidates.size - 1}" else "выкл"}")
            bringUp(server, opts, candidates)
            registerNetworkWatcher()
            VpnController._state.value = VpnState.CONNECTED
            VpnController._activeServerId.value = server.id
            AppLog.write("tunnel up")
            startHealthLoop()
        } catch (e: Throwable) {
            AppLog.write("ошибка подключения: ${e.message}")
            stopStack()
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Запускает ядро и TUN для выбранного сервера. Бросает исключение при ошибке. */
    private fun bringUp(server: Server, opts: TunnelOptions, candidates: List<Server>) {
        val logFile = AppLog.coreLogFile
        runCatching { logFile?.delete() }
        var profile = XrayConfigBuilder.build(server, opts, logFile?.path)
        profile = profile.copy(json = withCoreLog(profile.json, profile.core, logFile?.path))
        cur = Active(server, profile, opts, candidates)
        startCore()
        startTun()
        updateNotification(server.name)
    }

    private fun startCore() {
        val c = cur ?: throw IllegalStateException("нет конфигурации")
        Xraybridge.setMemoryLimit(c.options.memoryLimit)
        val geo = app.settings.geoDir.path
        try {
            if (c.profile.core == "singbox") Xraybridge.startSingbox(c.profile.json, geo)
            else Xraybridge.start(c.profile.json, geo)
        } catch (e: Exception) {
            throw RuntimeException("Ошибка ядра: ${e.message}")
        }
        AppLog.write("core started (${c.profile.core})")
    }

    private fun startTun() {
        val c = cur ?: throw IllegalStateException("нет конфигурации")
        val b = Builder()
            .setSession("Eclipse")
            .setMtu(c.options.mtu)
            .addAddress("10.255.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
        c.options.dns.filter { !it.contains(':') }.ifEmpty { listOf("1.1.1.1") }.forEach { b.addDnsServer(it) }
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

        val pfd: ParcelFileDescriptor = b.establish()
            ?: throw RuntimeException("Не удалось создать VPN-интерфейс (нет разрешения?)")
        val fd = pfd.detachFd()   // дальше дескриптором владеет tun2socks
        try {
            Xraybridge.startTun(fd, c.options.mtu, c.profile.socksPort, c.profile.socksUser, c.profile.socksPass)
        } catch (e: Exception) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw RuntimeException("Туннель не запустился: ${e.message}")
        }
        AppLog.write("tun2socks запущен")
    }

    private fun stopStack() {
        runCatching { Xraybridge.stopTun() }
        runCatching { Xraybridge.stop() }
    }

    private fun withCoreLog(json: String, core: String, path: String?): String {
        if (path == null) return json
        return try {
            val o = JSONObject(json)
            val l = o.optJSONObject("log") ?: JSONObject()
            if (core == "singbox") { l.put("output", path); l.put("level", "info") }
            else { l.put("error", path); l.put("loglevel", "warning") }
            o.put("log", l)
            o.toString()
        } catch (_: Exception) { json }
    }

    // ------------------------------------------------------------------ перезапуск при смене сети

    private fun registerNetworkWatcher() {
        if (netCb != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = lastNetwork
                lastNetwork = network
                if (prev != null && prev != network) scheduleRestart("сеть сменилась")
            }

            override fun onLost(network: Network) {
                if (network == lastNetwork) lastNetwork = null
            }
        }
        netCb = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    /** Смена сети: перезапускаем ядро И tun2socks, иначе UDP-сессии (DNS, QUIC) остаются мёртвыми. */
    private fun scheduleRestart(reason: String) {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(1500)
            if (stopping) return@launch
            val c = cur ?: return@launch
            AppLog.write("$reason → restart core + tun2socks")
            failStreak = 0
            try {
                stopStack()
                startCore()
                startTun()
            } catch (e: Throwable) {
                AppLog.write("перезапуск не удался: ${e.message}")
                disconnect("ошибка перезапуска: ${e.message}", error = e.message)
                return@launch
            }
            probeOnce(c, 2000)
        }
    }

    // ------------------------------------------------------------------ проверка и автопереключение

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = scope.launch {
            probeOnce(cur ?: return@launch, 1500)
            while (isActive && !stopping) {
                delay(20_000)
                val c = cur ?: break
                val r = Socks5Probe.check(c.profile.socksPort, c.profile.socksUser, c.profile.socksPass)
                VpnController._health.value = (if (r.ok) "OK: " else "FAIL: ") + r.text
                if (r.ok) { failStreak = 0; continue }
                failStreak++
                AppLog.write("health fail #$failStreak: ${r.text}")
                if (failStreak >= FAIL_THRESHOLD && app.settings.autoSwitch) switchToNext()
            }
        }
    }

    private suspend fun probeOnce(c: Active, firstDelayMs: Long) {
        delay(firstDelayMs)
        if (stopping) return
        val r = Socks5Probe.check(c.profile.socksPort, c.profile.socksUser, c.profile.socksPass)
        VpnController._health.value = (if (r.ok) "OK: " else "FAIL: ") + r.text
        AppLog.write("health: ${r.text}")
        if (!r.ok) AppLog.coreTail(12).takeIf { it.isNotEmpty() }?.let { AppLog.write("журнал ядра:\n$it") }
    }

    private suspend fun switchToNext() {
        val c = cur ?: return
        val next = c.candidates.firstOrNull { it.id !in tried }
        if (next == null) {
            AppLog.write("автопереключение: запасных серверов не осталось")
            tried = mutableSetOf(c.server.id)
            failStreak = 0
            return
        }
        tried.add(next.id)
        AppLog.write("автопереключение: ${c.server.name} → ${next.name}")
        failStreak = 0
        try {
            stopStack()
            bringUp(next, app.store.options(next), c.candidates)
            VpnController._activeServerId.value = next.id
            app.store.select(next.id)
            Toasts.show("Автопереключение: теперь ${next.name}", ToastKind.INFO)
        } catch (e: Throwable) {
            AppLog.write("переключение не удалось: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ остановка

    private fun cancelJobs() {
        healthJob?.cancel(); healthJob = null
        restartJob?.cancel(); restartJob = null
    }

    private suspend fun disconnect(reason: String, error: String? = null) {
        AppLog.write("stopTunnel: $reason")
        stopping = true
        VpnController._state.value = VpnState.DISCONNECTING
        cancelJobs()
        netCb?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        netCb = null; lastNetwork = null
        stopStack()
        cur = null
        VpnController._activeServerId.value = null
        VpnController._health.value = null
        if (error != null) VpnController._error.value = error
        VpnController._state.value = VpnState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        VpnController._error.value = msg
        VpnController._state.value = VpnState.DISCONNECTED
        VpnController._activeServerId.value = null
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ уведомление

    private fun goForeground(title: String, text: String) {
        val n = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(serverName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification("Eclipse · защищено", serverName))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EclipseVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .build()
    }
}
