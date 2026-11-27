package com.adblockfilm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tras reiniciar el aparato, vuelve a poner el vigilante (la VPN sigue apagada hasta abrir Film App). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AutoStart.startMonitor(context)
    }
}
