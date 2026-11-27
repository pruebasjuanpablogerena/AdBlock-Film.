package com.adblockfilm

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Recuerda si el pantallazo de inicio ya se mostró en este arranque de la app. */
private object SplashState {
    @Volatile
    var shown = false
}

private object P {
    val bgTop = Color.parseColor("#0B1230")
    val bgBottom = Color.parseColor("#04060F")
    val card = Color.parseColor("#121A38")
    val cardStroke = Color.parseColor("#222E5A")
    val inner = Color.parseColor("#0C1330")
    val accent = Color.parseColor("#3B82F6")
    val accent2 = Color.parseColor("#22D3EE")
    val on = Color.parseColor("#22C55E")
    val off = Color.parseColor("#64748B")
    val warn = Color.parseColor("#F59E0B")
    val text = Color.parseColor("#F1F5F9")
    val sub = Color.parseColor("#94A3B8")
}

class MainActivity : Activity() {

    private class AppRow(val t: Blocklist.Target, val icon: ImageView, val status: TextView)
    private class PermRow(val chip: TextView, val action: TextView)

    companion object {
        /** La pantalla de los iconos «(sin anuncios)» la usa para pedir el permiso de VPN con explicación. */
        const val EXTRA_ASK_VPN = "ask_vpn"
    }

    private lateinit var permVpn: PermRow
    private lateinit var permUsage: PermRow
    private lateinit var permNotif: PermRow
    private var pendingAfterNotif: (() -> Unit)? = null

    private lateinit var powerBtn: FrameLayout
    private lateinit var powerIcon: ImageView
    private lateinit var stateTitle: TextView
    private lateinit var stateSub: TextView
    private lateinit var blockedNum: TextView
    private lateinit var allowedNum: TextView
    private lateinit var autoChip: TextView
    private lateinit var autoBanner: View
    private lateinit var logView: TextView
    private lateinit var domainInput: EditText
    private lateinit var splash: ImageView
    private var splashShown = true
    private val rows = ArrayList<AppRow>()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- helpers de diseño

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun rowBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.parseColor("#2A3B82F6"), 12, P.accent2, 2))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, 12))
    }

    private fun pillBg(fill: Int, stroke: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(fill, 24, Color.WHITE, 2))
        addState(intArrayOf(), rounded(fill, 24, stroke, 1))
    }

    private fun tint(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(P.card, 20, P.cardStroke, 1)
        setPadding(dp(18), dp(14), dp(18), dp(14))
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(P.cardStroke) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun section(title: String): TextView =
        label(title.uppercase(), 12f, P.sub, true).apply { letterSpacing = 0.1f }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else P.accent2)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pillBg(if (filled) P.accent else Color.TRANSPARENT, if (filled) P.accent else P.accent2)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun switchWidget(checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            isChecked = checked
            thumbTintList = tint(P.on, Color.parseColor("#CBD5E1"))
            trackTintList = tint(Color.parseColor("#1D6B3A"), Color.parseColor("#334155"))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    /** Fila con título, descripción y interruptor; toda la fila es tocable (también con el control remoto). */
    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = rowBg()
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, P.text, true))
        if (desc.isNotEmpty()) texts.addView(label(desc, 12f, P.sub))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = switchWidget(checked, onChange).apply {
            isClickable = false
            isFocusable = false
        }
        row.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        row.setOnClickListener { sw.toggle() }
        return row
    }

    /** Tarjeta plegable: solo muestra el título hasta que se toca. */
    private fun collapsible(title: String, subtitle: String, content: View): View {
        val box = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = rowBg()
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, P.text, true))
        if (subtitle.isNotEmpty()) texts.addView(label(subtitle, 12f, P.sub))
        val arrow = label("Ver", 13f, P.accent2, true)
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(arrow)
        content.visibility = View.GONE
        header.setOnClickListener {
            val open = content.visibility != View.VISIBLE
            content.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "Ocultar" else "Ver"
        }
        box.addView(header)
        box.addView(content, lp(8))
        return box
    }

    // ---------------------------------------------------------------- bloques de pantalla

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(label("AdBlock Film", 22f, P.text, true))
        texts.addView(label("Sin anuncios, solo en tus apps", 12f, P.sub))
        row.addView(texts)
        return row
    }

    private fun buildHero(): View {
        val c = card()
        c.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#18244E"), Color.parseColor("#0E1634"))).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), P.cardStroke)
        }
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(18), dp(24), dp(18), dp(18))

        powerBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { toggle() }
            setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.07f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
        powerIcon = ImageView(this).apply { setImageResource(R.drawable.ic_power) }
        powerBtn.addView(powerIcon, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        c.addView(powerBtn, LinearLayout.LayoutParams(dp(148), dp(148)))

        stateTitle = label("", 22f, P.text, true).apply { gravity = Gravity.CENTER }
        c.addView(stateTitle, lp(16))
        stateSub = label("", 13f, P.sub).apply { gravity = Gravity.CENTER }
        c.addView(stateSub, lp(2))
        autoChip = label("", 12f, P.sub, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        c.addView(autoChip, lp(12, ViewGroup.LayoutParams.WRAP_CONTENT))

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(P.inner, 16)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        fun statCol(caption: String, color: Int): TextView {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val num = label("0", 24f, color, true)
            col.addView(num)
            col.addView(label(caption, 11f, P.sub))
            stats.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return num
        }
        blockedNum = statCol("Anuncios bloqueados", P.accent2)
        allowedNum = statCol("Conexiones normales", P.text)
        c.addView(stats, lp(16))
        return c
    }

    private fun buildPermissionBanner(): View {
        val c = card()
        c.background = rounded(Color.parseColor("#2A2110"), 20, P.warn, 1)
        c.addView(label("Falta un permiso para el modo automático", 15f, P.warn, true))
        c.addView(label("Activa «Acceso de uso» para que la VPN se encienda sola al abrir tus apps " +
            "y se apague al salir. Sin esto, abre las apps con el botón «Abrir» de aquí.", 12f, P.text), lp(4))
        c.addView(pill("Activar ahora", true) { enableAuto() }, lp(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        return c
    }

    private fun buildApps(): View {
        val box = card()
        rows.clear()
        Blocklist.TARGETS.forEachIndexed { i, t ->
            if (i > 0) divider(box)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
                clipToOutline = true
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            texts.addView(label(t.title, 15f, P.text, true))
            val status = label("", 12f, P.sub)
            texts.addView(status)
            if (t.note.isNotEmpty()) texts.addView(label(t.note, 11f, P.warn), lp(2))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(pill("Abrir", true) { openApp(t) })
            val sw = switchWidget(Blocklist.isTargetEnabled(this, t.pkg)) { on ->
                Blocklist.setTargetEnabled(this, t.pkg, on)
                // El túnel se arma con las apps marcadas: si está activo, se apaga y se rearma solo.
                if (AdBlockVpnService.running) AutoStart.stopVpn(this)
            }
            row.addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
            box.addView(row)
            rows.add(AppRow(t, icon, status))
        }
        return box
    }

    private fun buildSettings(): View {
        val box = card()
        box.addView(switchRow("Modo automático",
            "Enciende la VPN al abrir tus apps y la apaga al salir",
            AutoStart.isEnabled(this)) { on ->
            AutoStart.setEnabled(this, on)
            if (on && !AutoStart.hasUsageAccess(this)) enableAuto()
        })
        divider(box)
        box.addView(switchRow("DNS de AdGuard",
            "Filtro extra de anuncios y rastreadores (recomendado)",
            Blocklist.isAdGuardEnabled(this)) { on -> Blocklist.setAdGuardEnabled(this, on) })
        divider(box)
        box.addView(switchRow("VPN lista en la pantalla de inicio",
            "Evita anuncios al abrir las radios; se apaga al abrir otras apps",
            Blocklist.isEarlyArm(this)) { on -> Blocklist.setEarlyArm(this, on) })
        divider(box)
        box.addView(switchRow("Incluir Google Play Services",
            "Recomendado: bloquea anuncios que Google pide por su cuenta",
            Blocklist.includeGms(this)) { on ->
            Blocklist.setIncludeGms(this, on)
            if (AdBlockVpnService.running) AutoStart.stopVpn(this) // se rearma al abrir la app
        })
        return box
    }

    private fun permRow(box: LinearLayout, title: String, desc: String, onGrant: () -> Unit): PermRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(title, 15f, P.text, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = label("", 12f, P.sub, true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) }
        head.addView(chip)
        row.addView(head)
        row.addView(label(desc, 12f, P.sub), lp(4))
        val action = pill("Activar", true) { onGrant() }
        row.addView(action, lp(8, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(row)
        return PermRow(chip, action)
    }

    private fun buildPermissions(): View {
        val box = card()
        permVpn = permRow(box, "VPN local",
            "Crea la conexión en tu propio equipo que filtra los anuncios, solo en las apps marcadas. El contenido de tus apps no pasa por aquí.") {
            val p = VpnService.prepare(this)
            if (p == null) {
                Toast.makeText(this, "El permiso de VPN ya está concedido", Toast.LENGTH_SHORT).show()
            } else {
                ensureNotif { requestVpn(p) }
            }
        }
        divider(box)
        permUsage = permRow(box, "Acceso de uso",
            "Permite saber qué app está abierta para encender la VPN solo al abrir tus apps y apagarla al salir. No lee mensajes ni contenido.") {
            enableAuto()
        }
        divider(box)
        permNotif = permRow(box, "Notificaciones",
            "Muestra el aviso «Bloqueo de anuncios activo» con el botón Desactivar.") {
            if (Build.VERSION.SDK_INT >= 33 && !notifAsked()) ensureNotif { } else openNotifSettings()
        }
        box.addView(label("Android también concede, sin preguntar: Internet (consultar el DNS), " +
            "servicio en primer plano (mantener el bloqueo activo) e inicio al encender (volver a vigilar tras reiniciar).",
            11f, P.sub), lp(12))
        return box
    }

    private fun setPerm(p: PermRow, ok: Boolean) {
        p.chip.text = if (ok) "Concedido" else "Falta"
        p.chip.setTextColor(if (ok) P.on else P.warn)
        p.chip.background = rounded(if (ok) Color.parseColor("#0F2A1B") else Color.parseColor("#2A2110"), 12)
        p.action.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun buildAdvanced(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Accesos directos «(sin anuncios)»
        val icons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (t in Blocklist.TARGETS) {
            icons.addView(switchRow(t.title, "Mostrar el icono «(sin anuncios)» en el menú", iconVisible(t)) { on ->
                packageManager.setComponentEnabledSetting(
                    ComponentName(this, t.launcher),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
            })
        }
        col.addView(collapsible("Accesos directos «(sin anuncios)»",
            "Abren la app con la VPN ya encendida", icons))

        // Redes de anuncios
        val groups = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (g in Blocklist.GROUPS) {
            groups.addView(switchRow(g.title, "", Blocklist.isGroupEnabled(this, g)) { on ->
                Blocklist.setGroupEnabled(this, g, on)
            })
        }
        col.addView(collapsible("Redes de anuncios", "Elige qué redes se bloquean (activado = bloqueada)", groups),
            lp(12))

        // Dominios personalizados
        val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        domainInput = EditText(this).apply {
            hint = "ej. ads.ejemplo.com"
            textSize = 15f
            setTextColor(P.text)
            setHintTextColor(P.sub)
            setSingleLine()
            background = rounded(P.inner, 12, P.cardStroke, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        custom.addView(domainInput)
        custom.addView(pill("Añadir dominio", false) { addDomain() },
            lp(10, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(collapsible("Dominios personalizados", "Bloquea un dominio extra a mano", custom), lp(12))

        // Actividad en vivo
        logView = label("", 11f, P.sub).apply { typeface = Typeface.MONOSPACE }
        col.addView(collapsible("Actividad en vivo",
            "Qué se bloquea y qué pasa, con hora y app", logView), lp(12))
        return col
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = P.bgTop
        window.navigationBarColor = P.bgBottom

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(30))
        }
        content.addView(buildHeader())
        content.addView(buildHero(), lp(20))
        autoBanner = buildPermissionBanner()
        content.addView(autoBanner, lp(14))
        content.addView(section("Tus apps"), lp(24))
        content.addView(buildApps(), lp(8))
        content.addView(section("Ajustes"), lp(24))
        content.addView(buildSettings(), lp(8))
        content.addView(section("Permisos y para qué sirven"), lp(24))
        content.addView(buildPermissions(), lp(8))
        content.addView(section("Avanzado"), lp(24))
        content.addView(buildAdvanced(), lp(8))
        content.addView(label("Solo las apps marcadas pasan por la VPN", 11f, P.sub)
            .apply { gravity = Gravity.CENTER }, lp(26))

        // En pantallas grandes (TV) el contenido se limita a un ancho cómodo y se centra.
        val maxW = dp(640)
        val width = minOf(resources.displayMetrics.widthPixels, maxW)
        val wrap = FrameLayout(this)
        wrap.addView(content, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(wrap)
        }

        val bg = FrameLayout(this)
        bg.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(P.bgTop, P.bgBottom))
        bg.addView(scroll)

        splash = ImageView(this).apply {
            setImageResource(R.drawable.splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#01030A"))
            isClickable = true
            setOnClickListener { dismissSplash() }
        }
        bg.addView(splash, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // El pantallazo solo sale cuando la app arranca desde cero. Si ya estaba abierta
        // (icono, aviso del panel de notificaciones, recientes), se entra directo al contenido.
        val showSplash = !SplashState.shown
        SplashState.shown = true
        splashShown = showSplash
        if (!showSplash) splash.visibility = View.GONE
        setContentView(bg)
        powerBtn.requestFocus()
        if (showSplash) handler.postDelayed({ dismissSplash() }, 2500)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Si venimos de un icono «(sin anuncios)» sin permiso de VPN, se explica y se pide aquí. */
    private fun handleIntent(i: Intent?) {
        if (i != null && i.getBooleanExtra(EXTRA_ASK_VPN, false)) {
            i.removeExtra(EXTRA_ASK_VPN)
            ensureNotif {
                val prepare = VpnService.prepare(this)
                if (prepare != null) requestVpn(prepare) else startVpn()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val next = pendingAfterNotif
            pendingAfterNotif = null
            next?.invoke()
        }
    }

    private fun dismissSplash() {
        if (!splashShown) return
        splashShown = false
        splash.animate().alpha(0f).setDuration(300)
            .withEndAction { splash.visibility = View.GONE }.start()
    }

    // Mientras se ve la imagen de inicio, cualquier tecla del control la cierra
    // sin activar los botones que hay debajo.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (splashShown) {
            if (event.action == KeyEvent.ACTION_DOWN) dismissSplash()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        AutoStart.startMonitor(this)
        loadIcons()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- acciones

    private fun toggle() {
        if (AdBlockVpnService.running) {
            startService(Intent(this, AdBlockVpnService::class.java)
                .setAction(AdBlockVpnService.ACTION_STOP))
            return
        }
        ensureNotif {
            val prepare = VpnService.prepare(this)
            if (prepare != null) requestVpn(prepare) else startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, AdBlockVpnService::class.java)
            .setAction(AdBlockVpnService.ACTION_START))
    }

    // ---- Permisos: antes de cada uno se explica para qué sirve ----

    private fun prefsUi() = getSharedPreferences("ui", MODE_PRIVATE)
    private fun notifAsked(): Boolean = prefsUi().getBoolean("notif_asked", false)
    private fun markNotifAsked() = prefsUi().edit().putBoolean("notif_asked", true).apply()
    private fun vpnFlag(): Boolean = prefsUi().getBoolean("vpn_ok", false)
    private fun setVpnFlag() = prefsUi().edit().putBoolean("vpn_ok", true).apply()

    private fun notifGranted(): Boolean = try {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    } catch (e: Exception) {
        true
    }

    /** Ventana propia con el motivo del permiso; solo después se abre el aviso de Android. */
    private fun explainThen(title: String, why: String, extra: String, okText: String,
                            onNo: () -> Unit = {}, onOk: () -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(P.card, 24, P.cardStroke, 1)
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        body.addView(label(title, 19f, P.text, true))
        body.addView(label(why, 14f, P.text), lp(10))
        if (extra.isNotEmpty()) body.addView(label(extra, 12f, P.sub), lp(10))
        val dlg = AlertDialog.Builder(this).setView(body).create()
        dlg.setOnCancelListener { onNo() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(pill("Ahora no", false) { dlg.cancel() })
        val ok = pill(okText, true) {
            dlg.dismiss()
            onOk()
        }
        buttons.addView(ok, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        body.addView(buttons, lp(18))
        dlg.show()
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(480)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ok.requestFocus()
    }

    /** Notificaciones (Android 13+): se explica una vez y luego se continúa con el siguiente paso. */
    private fun ensureNotif(next: () -> Unit) {
        val needs = Build.VERSION.SDK_INT >= 33 && !notifGranted() && !notifAsked()
        if (!needs) {
            next()
            return
        }
        explainThen("Permiso de notificaciones",
            "Sirve para mostrar el aviso «Bloqueo de anuncios activo» con el botón Desactivar mientras la protección está encendida.",
            "Si no lo permites, el bloqueo funciona igual, pero no verás el aviso ni podrás apagarlo desde el panel de notificaciones.",
            "Continuar",
            onNo = {
                markNotifAsked()
                next()
            }) {
            markNotifAsked()
            pendingAfterNotif = next
            try {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
            } catch (e: Exception) {
                pendingAfterNotif = null
                next()
            }
        }
    }

    private fun requestVpn(prepare: Intent) {
        explainThen("Permiso de VPN",
            "Android lo pide para que AdBlock Film cree una conexión local en tu equipo que filtra los anuncios, solo en las apps que marcaste (Film App y las radios).",
            "El contenido de tus apps no pasa por aquí: solo se revisan los nombres de los sitios que piden (DNS) para cortar los de publicidad, y esos nombres se consultan al DNS de AdGuard. " +
                "Enseguida Android mostrará su propio aviso de conexión VPN: pulsa Aceptar.",
            "Continuar") {
            try {
                startActivityForResult(prepare, 1)
            } catch (e: Exception) {
                Toast.makeText(this, "Este dispositivo no permite pedir el permiso de VPN",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enableAuto() {
        AutoStart.setEnabled(this, true)
        if (AutoStart.hasUsageAccess(this)) {
            AutoStart.startMonitor(this)
            Toast.makeText(this, "Inicio automático activado", Toast.LENGTH_SHORT).show()
            return
        }
        ensureNotif {
            explainThen("Permiso de Acceso de uso",
                "Sirve para saber qué app tienes abierta. Así la VPN se enciende sola cuando abres Film App, miRadio o YouTube, y se apaga cuando sales, sin afectar a las demás apps.",
                "No lee tus mensajes ni el contenido de otras apps: solo el nombre de la app que está en pantalla. En la siguiente pantalla busca «AdBlock Film» y actívalo.",
                "Abrir ajustes") { openUsageSettings() }
        }
    }

    private fun openUsageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this,
                    "Esta TV no abre Acceso de uso. Usa el comando adb del README",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNotifSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
            }
        }
    }

    private fun iconVisible(t: Blocklist.Target): Boolean =
        when (packageManager.getComponentEnabledSetting(ComponentName(this, t.launcher))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> t.iconDefault
        }

    private fun openApp(t: Blocklist.Target) {
        Blocklist.setTargetEnabled(this, t.pkg, true) // abrirla desde aquí implica filtrarla
        // Con el permiso ya concedido, el bloqueo queda listo antes de que la app haga consultas.
        AutoStart.startVpnIfReady(this, restart = AdBlockVpnService.running)
        val target = Blocklist.installedPkgs(packageManager, t).firstOrNull() ?: t.pkg
        val i = packageManager.getLeanbackLaunchIntentForPackage(target)
            ?: packageManager.getLaunchIntentForPackage(target)
        if (i != null) startActivity(i)
        else Toast.makeText(this, "${t.title} no está instalada", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            setVpnFlag()
            startVpn()
        }
    }

    private fun addDomain() {
        val ok = Blocklist.addCustom(this, domainInput.text.toString())
        Toast.makeText(this, if (ok) "Dominio añadido" else "Dominio no válido",
            Toast.LENGTH_SHORT).show()
        if (ok) domainInput.setText("")
    }

    // ---------------------------------------------------------------- estado en pantalla

    private fun loadIcons() {
        for (r in rows) {
            val pkg = Blocklist.installedPkgs(packageManager, r.t).firstOrNull()
            if (pkg != null) {
                try {
                    r.icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                    r.icon.clearColorFilter()
                    r.icon.setPadding(0, 0, 0, 0)
                    r.icon.background = null
                    continue
                } catch (_: Exception) {
                }
            }
            r.icon.setImageResource(R.drawable.ic_stat_shield)
            r.icon.setColorFilter(P.off)
            r.icon.setPadding(dp(11), dp(11), dp(11), dp(11))
            r.icon.background = rounded(P.inner, 12)
        }
    }

    private fun refresh() {
        val on = AdBlockVpnService.running
        val usage = AutoStart.hasUsageAccess(this)
        val autoOn = usage && AutoStart.isEnabled(this)

        val ring = if (on) P.on else P.off
        powerBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(P.inner)
            setStroke(dp(5), ring)
        }
        powerIcon.setColorFilter(ring)

        stateTitle.text = if (on) "Protección activa" else "Protección apagada"
        stateTitle.setTextColor(if (on) P.on else P.text)
        stateSub.text = if (on) "Filtrando ${Stats.covered.size} app(s) marcadas"
            else if (autoOn) "Se enciende sola al abrir una app marcada"
            else "Toca el botón para encenderla"

        autoChip.text = if (autoOn) "●  Modo automático activo" else "●  Modo automático: falta el permiso"
        autoChip.setTextColor(if (autoOn) P.on else P.warn)
        autoChip.background = rounded(
            if (autoOn) Color.parseColor("#0F2A1B") else Color.parseColor("#2A2110"), 14)
        autoBanner.visibility = if (autoOn) View.GONE else View.VISIBLE

        if (on && !vpnFlag()) setVpnFlag() // si la VPN está activa, el permiso está concedido
        setPerm(permVpn, on || vpnFlag())
        setPerm(permUsage, usage)
        setPerm(permNotif, notifGranted())

        blockedNum.text = Stats.blockedCount.get().toString()
        allowedNum.text = Stats.allowedCount.get().toString()

        for (r in rows) {
            val inst = Blocklist.installedPkgs(packageManager, r.t).isNotEmpty()
            val prot = Blocklist.isTargetEnabled(this, r.t.pkg)
            r.status.text = if (!inst) "No instalada" else if (prot) "Instalada · protegida" else "Instalada · sin filtrar"
            r.status.setTextColor(if (!inst) P.off else if (prot) P.on else P.warn)
        }

        logView.text = "BLOQUEADOS  (hora · dominio · [app])\n" +
            Stats.recentBlocked().joinToString("\n") { "✕ $it" } +
            "\n\nPERMITIDOS\n" +
            Stats.recentAllowed().joinToString("\n") { "✓ $it" }
    }
}
