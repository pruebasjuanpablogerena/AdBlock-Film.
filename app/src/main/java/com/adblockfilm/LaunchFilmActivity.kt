package com.adblockfilm

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Icono "Film App (sin anuncios)": enciende la VPN y abre Film App. Solo necesita el permiso de
 * VPN (un "Aceptar" la primera vez). Es el modo que no depende de "Acceso de uso".
 */
open class LaunchFilmActivity : Activity() {

    /** Paquete que abre este icono. Las clases hijas lo cambian. */
    protected open val targetPackage: String
        get() {
            val t = Blocklist.TARGETS[0]
            return Blocklist.installedPkgs(packageManager, t).firstOrNull() ?: t.pkg
        }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            go()
        } else {
            // Sin permiso de VPN: se pide desde la pantalla principal, que antes explica para qué sirve.
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ASK_VPN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) go() else finish()
    }

    private fun go() {
        // El icono lanza la app aunque su casilla esté desmarcada, así que la marcamos.
        Blocklist.targetFor(targetPackage)?.let { Blocklist.setTargetEnabled(this, it.pkg, true) }
        AutoStart.startVpnIfReady(this, restart = AdBlockVpnService.running)
        // Espera (máx. ~2 s) a que la VPN esté arriba antes de abrir Film App.
        var tries = 0
        handler.post(object : Runnable {
            override fun run() {
                if (AdBlockVpnService.running || tries++ >= 20) {
                    openFilm()
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        })
    }

    private fun openFilm() {
        val i = packageManager.getLeanbackLaunchIntentForPackage(targetPackage)
            ?: packageManager.getLaunchIntentForPackage(targetPackage)
        if (i != null) startActivity(i)
        else Toast.makeText(this, "La app no está instalada", Toast.LENGTH_SHORT).show()
        finish()
    }
}
