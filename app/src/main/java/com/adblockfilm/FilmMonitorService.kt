package com.adblockfilm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Looper
import android.widget.Toast

/**
 * Vigila qué app está en primer plano (permiso "Acceso de uso", sin accesibilidad).
 * Enciende la VPN local cuando Film App pasa a primer plano y la apaga ~2 s después de que
 * otra app ocupe la pantalla, para que el resto de apps nunca vean una VPN activa.
 */
@Suppress("DEPRECATION")
class FilmMonitorService : Service() {

    companion object {
        @Volatile
        var running = false

        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 7
        private const val POLL_MS = 400L
        private const val STOP_DELAY_MS = 2000L
        // Ventanas del sistema que pueden aparecer encima de Film App sin que la hayas cerrado.
        private val IGNORED = setOf(
            "android", "com.android.systemui", "com.google.android.katniss",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.sec.android.app.launcher"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastQuery = 0L
    private var fg: String? = null
    private var leftSince = 0L
    private var warned = false
    private var lastTarget: String? = null
    private var screenOn = true
    private var installedAt = 0L
    private var installedAny = false
    private val fgsRunning = HashSet<String>() // radios con su servicio de reproducción activo
    private var homePkgs: Set<String> = emptySet()

    private val poll = object : Runnable {
        override fun run() {
            try { check() } catch (_: Exception) {}
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Vigilancia de Film App", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(getString(R.string.monitor_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        homePkgs = try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        screenOn = try { (getSystemService(POWER_SERVICE) as PowerManager).isInteractive } catch (e: Exception) { true }
        running = true
        lastQuery = System.currentTimeMillis() - 6 * 60 * 60 * 1000L // 6 h: reconstruye qué radio ya sonaba
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun check() {
        if (!AutoStart.isEnabled(this) || !AutoStart.hasUsageAccess(this)) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> if (pkg !in IGNORED) fg = pkg
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (pkg == fg) fg = null
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { fg = null; screenOn = false }
                19 -> if (Blocklist.targetFor(pkg)?.radio == true) fgsRunning.add(pkg)  // inicia servicio en primer plano
                20 -> fgsRunning.remove(pkg)                                             // termina servicio en primer plano
            }
        }
        lastQuery = now - 1000 // pequeño solape; reprocesar eventos es inofensivo

        val neutral = fg == null || fg in IGNORED || fg in homePkgs || fg == packageName
        val active = Blocklist.isActiveTarget(this, fg)
        if (active) lastTarget = fg else if (!neutral) lastTarget = null // abriste otra app
        val playing = fgsRunning.any { p ->
            val t = Blocklist.targetFor(p)
            t != null && Blocklist.isTargetEnabled(this, t.pkg)
        }
        val early = Blocklist.isEarlyArm(this) && anyTargetInstalled()
        if (!active && !playing && !(early && neutral && screenOn)) AdBlockVpnService.manualOff = false

        when {
            active -> {
                leftSince = 0
                if (!AdBlockVpnService.running && !AdBlockVpnService.manualOff) {
                    if (!AutoStart.startVpnIfReady(this) && !warned) {
                        warned = true
                        Toast.makeText(this,
                            "AdBlock Film: abre la app y pulsa «Activar bloqueo» una vez para dar el permiso de VPN",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            fg == packageName -> leftSince = 0 // estás en AdBlock Film: no se toca la VPN
            playing -> { // una radio sigue sonando: la VPN se mantiene (o se enciende) aunque abras otras apps
                leftSince = 0
                if (!AdBlockVpnService.running && !AdBlockVpnService.manualOff) AutoStart.startVpnIfReady(this)
            }
            early && neutral && screenOn -> { // modo anticipado: VPN lista antes de abrir la radio
                leftSince = 0
                if (!AdBlockVpnService.running && !AdBlockVpnService.manualOff) AutoStart.startVpnIfReady(this)
            }
            neutral && radioInBackground() -> leftSince = 0 // respaldo: última app fue una radio y estás en el inicio
            AdBlockVpnService.running -> {
                if (leftSince == 0L) leftSince = now
                else if (now - leftSince >= STOP_DELAY_MS) {
                    AutoStart.stopVpn(this)
                    leftSince = 0
                    lastTarget = null
                }
            }
            else -> leftSince = 0
        }
    }

    private fun anyTargetInstalled(): Boolean {
        val t = SystemClock.elapsedRealtime()
        if (t - installedAt > 30_000L) {
            installedAt = t
            installedAny = Blocklist.TARGETS.any {
                Blocklist.isTargetEnabled(this, it.pkg) && Blocklist.installedPkgs(packageManager, it).isNotEmpty()
            }
        }
        return installedAny
    }

    /** Una app de radio marcada fue la última en uso y estás en el inicio o con la pantalla apagada. */
    private fun radioInBackground(): Boolean {
        val t = Blocklist.targetFor(lastTarget) ?: return false
        return t.radio && Blocklist.isTargetEnabled(this, t.pkg)
    }
}
