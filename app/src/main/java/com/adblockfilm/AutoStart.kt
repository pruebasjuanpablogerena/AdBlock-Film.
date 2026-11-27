package com.adblockfilm

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Process

/** Lógica para activar el bloqueo automáticamente cuando se abre Film App. */
object AutoStart {
    private const val PREFS = "adblock"
    private const val KEY_AUTO = "auto_start"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
        if (on) startMonitor(ctx) else stopMonitor(ctx)
    }

    /** ¿Tiene la app el permiso especial "Acceso de uso"? */
    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** Arranca el vigilante de primer plano (solo si el modo automático y el permiso están listos). */
    fun startMonitor(ctx: Context) {
        if (!isEnabled(ctx) || !hasUsageAccess(ctx) || FilmMonitorService.running) return
        try {
            val i = Intent(ctx, FilmMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
        }
    }

    fun stopMonitor(ctx: Context) {
        try { ctx.stopService(Intent(ctx, FilmMonitorService::class.java)) } catch (_: Exception) {}
    }

    /** Arranca la VPN si el permiso ya fue concedido alguna vez. Devuelve false si falta el permiso. */
    fun startVpnIfReady(ctx: Context, restart: Boolean = false): Boolean {
        if (AdBlockVpnService.running && !restart) return true
        if (VpnService.prepare(ctx) != null) return false
        return try {
            ctx.startService(Intent(ctx, AdBlockVpnService::class.java)
                .setAction(AdBlockVpnService.ACTION_START)
                .putExtra(AdBlockVpnService.EXTRA_RESTART, restart))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stopVpn(ctx: Context) {
        try {
            ctx.startService(Intent(ctx, AdBlockVpnService::class.java)
                .setAction(AdBlockVpnService.ACTION_STOP))
        } catch (_: Exception) {
        }
    }
}
