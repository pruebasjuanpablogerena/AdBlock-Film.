package com.adblockfilm

/** Icono "miRadio (sin anuncios)": abre la primera versión de miRadio (cualquier país) que esté instalada. */
class LaunchMiRadioActivity : LaunchFilmActivity() {
    override val targetPackage: String
        get() {
            val t = Blocklist.targetFor("moldesbrothers.miradioco")
            return (if (t != null) Blocklist.installedPkgs(packageManager, t).firstOrNull() else null)
                ?: "moldesbrothers.miradioco"
        }
}
