package com.adblockfilm

/** Icono "YouTube (sin anuncios)": enciende la VPN y abre YouTube (versión de móvil o de TV, la que esté instalada). */
class LaunchYouTubeActivity : LaunchFilmActivity() {
    override val targetPackage: String
        get() {
            val t = Blocklist.targetFor("com.google.android.youtube")
            return (if (t != null) Blocklist.installedPkgs(packageManager, t).firstOrNull() else null)
                ?: "com.google.android.youtube"
        }
}
