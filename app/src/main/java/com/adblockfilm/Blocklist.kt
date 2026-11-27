package com.adblockfilm

import android.content.Context
import android.content.pm.PackageManager

object Blocklist {
    class Target(
        val pkg: String,
        val title: String,
        val radio: Boolean,
        val launcher: Class<*>,   // icono de acceso directo «(sin anuncios)»
        val iconDefault: Boolean, // ¿el icono aparece por defecto en el menú?
        val alias: List<String> = emptyList(), // otras versiones de la misma app (p. ej. miRadio por país)
        val note: String = ""                  // aviso corto que se muestra bajo el nombre en el panel
    ) {
        val pkgs: List<String> get() = listOf(pkg) + alias
    }

    // Apps que pasan por el filtro (cada una se puede marcar/desmarcar en la pantalla principal).
    val TARGETS: List<Target> = listOf(
        Target("com.digitalproshare.filmapp", "Film App / Film Plus", false, LaunchFilmActivity::class.java, false,
            alias = listOf("com.dpsteam.filmplus")),
        Target("moldesbrothers.miradioco", "miRadio Colombia", true, LaunchMiRadioActivity::class.java, true),
        Target("com.google.android.youtube", "YouTube", false, LaunchYouTubeActivity::class.java, false,
            alias = listOf("com.google.android.youtube.tv"), // versión para Google TV / Android TV
            note = "Bloquea banners y rastreo. Los anuncios de video no se pueden quitar por este método.")
    )

    private const val PREFS = "adblock"
    private const val KEY_CUSTOM = "custom"

    class Group(
        val id: String,
        val title: String,
        val defaultOn: Boolean,
        val domains: Set<String>
    )

    // Se bloquea el dominio y todos sus subdominios.
    val GROUPS: List<Group> = listOf(
        Group("levelplay_ctl", "LevelPlay: control del SDK (déjalo sin marcar)", false,
            setOf("mediation.unity3d.com")),
        Group("ironsource", "ironSource (anuncios propios)", true,
            setOf("supersonicads.com", "supersonic.com", "ironsrc.com", "ironsrc.mobi",
                "ironsource.mobi", "ironsource.com",
                // detectados en Film Plus: CDN de anuncios y analítica de ironSource
                "ssacdn.com", "ironbeast.io")),
        Group("unity", "Unity Ads", true,
            setOf("unityads.unity3d.com", "ads.unity3d.com",
                // detectados en Film Plus: visor de anuncios y creatividades de Unity
                "iads.unity3d.com", "unity3dusercontent.com")),
        Group("pangle", "Pangle (ByteDance)", true,
            setOf("pangle.io", "pangleglobal.com", "byteoversea.com", "ibytedtos.com",
                "pglstatp-toutiao.com", "i18n-pglstatp.com")),
        Group("mintegral", "Mintegral", true,
            setOf("mintegral.com", "mintegral.net", "mtgglobals.com", "rayjump.com")),
        Group("applovin", "AppLovin", true,
            setOf("applovin.com", "applvn.com")),
        Group("vungle", "Vungle / Liftoff", true,
            setOf("vungle.com", "liftoff.io", "liftoff-creatives.io")),
        Group("others", "Fyber, Pubnative, Smaato, Yandex, StartApp y otras", true,
            setOf("pubnative.net", "inner-active.mobi", "inner-active.com", "fyber.com",
                "smaato.net", "smaato.com", "yandexadexchange.net", "adfox.yandex.ru",
                "startappservice.com", "startappexchange.com", "startapp.com",
                "chartboost.com", "adcolony.com", "inmobi.com", "tapjoy.com", "moloco.com")),
        Group("google", "Google Ads", true,
            setOf("googlesyndication.com", "googleadservices.com", "doubleclick.net",
                "admob.com", "adservice.google.com", "adtrafficquality.google",
                // miniaturas de productos de los anuncios de compras de Google
                "encrypted-tbn0.gstatic.com", "encrypted-tbn1.gstatic.com",
                "encrypted-tbn2.gstatic.com", "encrypted-tbn3.gstatic.com")),
        Group("amazon", "Amazon Ads / APS", true,
            setOf("amazon-adsystem.com")),
        Group("extras", "Extras para apps de radio: IMA de Google, AppLovin/Pangle regionales, analítica TikTok", true,
            setOf("imasdk.googleapis.com", "mobileads.google.com", "admob-gmats.uc.r.appspot.com",
                "applov.in", "appl.vn", "axon.ai", "2mdn.net", "googletagservices.com",
                "tiktokpangle.us", "tiktokpangle-b.us", "tiktokpangle-cdn-us.com", "tiktokv.us",
                "analytics.tiktok.com", "analytics.us.tiktok.com")),
        Group("web", "Anuncios web (WebView de sitios de streaming)", true,
            setOf("popads.net", "popcash.net", "propellerads.com", "exoclick.com",
                "adsterra.com", "juicyads.com", "trafficjunky.net", "clickadu.com"))
    )

    @Volatile
    private var all: Set<String> = emptySet()

    /** Si es true, las consultas permitidas se reenvían al DNS filtrante de AdGuard. */
    @Volatile
    var useAdGuard: Boolean = true
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isTargetEnabled(ctx: Context, pkg: String): Boolean = prefs(ctx).getBoolean("t_$pkg", true)

    fun setTargetEnabled(ctx: Context, pkg: String, on: Boolean) {
        prefs(ctx).edit().putBoolean("t_$pkg", on).apply()
    }

    /** ¿Es [pkg] una de las apps marcadas para protegerse? */
    fun isActiveTarget(ctx: Context, pkg: String?): Boolean =
        pkg != null && TARGETS.any { pkg in it.pkgs && isTargetEnabled(ctx, it.pkg) }

    /** Target al que pertenece un paquete (incluye las versiones alias). */
    fun targetFor(pkg: String?): Target? =
        if (pkg == null) null else TARGETS.firstOrNull { pkg in it.pkgs }

    /** Paquetes de ese target que están instalados en el equipo. */
    fun installedPkgs(pm: PackageManager, t: Target): List<String> =
        t.pkgs.filter { p ->
            try { pm.getPackageInfo(p, 0); true } catch (e: Exception) { false }
        }

    fun isGroupEnabled(ctx: Context, g: Group): Boolean =
        prefs(ctx).getBoolean("g_" + g.id, g.defaultOn)

    fun setGroupEnabled(ctx: Context, g: Group, on: Boolean) {
        prefs(ctx).edit().putBoolean("g_" + g.id, on).apply()
        reload(ctx)
    }

    /** Incluye también Google Play Services en la VPN: algunos anuncios los pide Google por su cuenta. */
    fun includeGms(ctx: Context): Boolean = prefs(ctx).getBoolean("include_gms", true)
    fun setIncludeGms(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("include_gms", on).apply()

    /** Mantener la VPN lista en la pantalla de inicio, para que las radios no pidan anuncios sin filtro. */
    fun isEarlyArm(ctx: Context): Boolean = prefs(ctx).getBoolean("early_arm", false)
    fun setEarlyArm(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("early_arm", on).apply()

    fun isAdGuardEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("use_adguard", true)

    fun setAdGuardEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("use_adguard", on).apply()
        reload(ctx)
    }

    fun customDomains(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet(KEY_CUSTOM, emptySet()) ?: emptySet())

    fun addCustom(ctx: Context, raw: String): Boolean {
        val d = raw.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').trimEnd('.')
        if (!Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(d)) {
            return false
        }
        prefs(ctx).edit().putStringSet(KEY_CUSTOM, customDomains(ctx) + d).apply()
        reload(ctx)
        return true
    }

    fun reload(ctx: Context) {
        val s = HashSet<String>()
        for (g in GROUPS) if (isGroupEnabled(ctx, g)) s.addAll(g.domains)
        s.addAll(customDomains(ctx))
        all = s
        useAdGuard = isAdGuardEnabled(ctx)
    }

    fun isBlocked(name: String): Boolean {
        var d = name
        while (true) {
            if (d in all) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }
}
