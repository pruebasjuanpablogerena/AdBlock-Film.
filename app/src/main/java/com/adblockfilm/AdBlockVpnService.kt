package com.adblockfilm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.net.ConnectivityManager
import android.net.VpnService
import android.system.OsConstants
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VPN local que solo captura las consultas DNS de Film App.
 * Las consultas a dominios de publicidad reciben una respuesta local (127.0.0.1); el resto se
 * reenvía al DNS upstream. El tráfico que no es DNS no pasa por el túnel.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.adblockfilm.START"
        const val ACTION_STOP = "com.adblockfilm.STOP"
        const val EXTRA_RESTART = "restart"
        /** Apagado pedido por la persona (botón del aviso): el vigilante no lo reenciende al instante. */
        const val ACTION_STOP_USER = "com.adblockfilm.STOP_USER"

        private const val CHANNEL = "vpn_control"
        private const val NOTIF_ID = 8

        /** true si se apagó a mano mientras la app protegida sigue abierta. */
        @Volatile
        var manualOff = false

        @Volatile
        var running = false

        // Sin el vigilante de "Acceso de uso", la VPN se apaga sola tras este tiempo sin consultas DNS
        // de Film App o al apagarse la pantalla.
        private const val IDLE_MS = 10 * 60 * 1000L
        private const val IDLE_CHECK_MS = 30_000L

        private const val VPN_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"
        // AdGuard DNS (modo "Default": bloquea anuncios y rastreadores) y, si falla, Cloudflare.
        private val ADGUARD_DNS = listOf("94.140.14.14", "94.140.15.15", "1.1.1.1")
        private val PLAIN_DNS = listOf("1.1.1.1", "8.8.8.8")
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null
    private val writeLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastDns = 0L
    private var screenReceiver: BroadcastReceiver? = null

    private fun monitorActive() = FilmMonitorService.running

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!running) return
            if (!monitorActive() && SystemClock.elapsedRealtime() - lastDns > IDLE_MS) {
                stopVpn(); stopSelf()
                return
            }
            handler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        if (act == ACTION_STOP || act == ACTION_STOP_USER) {
            if (act == ACTION_STOP_USER) manualOff = true
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            startVpn()
        } else if (intent?.getBooleanExtra(EXTRA_RESTART, false) == true) {
            // Rearma el túnel con la lista actual de apps marcadas.
            stopVpn()
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        manualOff = false
        Blocklist.reload(this)
        val builder = Builder()
            .setSession("AdBlock Film")
            .addAddress(VPN_ADDRESS, 24)
            .addRoute(FAKE_DNS, 32)
            .addDnsServer(FAKE_DNS)
            .setBlocking(true)
        // Solo las apps marcadas e instaladas pasan por el túnel. Si no hay ninguna, NO se enciende:
        // una VPN sin apps permitidas capturaría todo el dispositivo.
        var added = 0
        val covered = ArrayList<String>()
        for (t in Blocklist.TARGETS) {
            if (!Blocklist.isTargetEnabled(this, t.pkg)) continue
            for (p in t.pkgs) {
                try {
                    builder.addAllowedApplication(p)
                    added++
                    covered.add(p)
                } catch (e: PackageManager.NameNotFoundException) {
                }
            }
        }
        if (Blocklist.includeGms(this)) {
            try {
                builder.addAllowedApplication("com.google.android.gms")
                covered.add("com.google.android.gms")
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        Stats.covered = covered
        if (added == 0) {
            stopSelf()
            return
        }
        val fd = builder.establish()
        if (fd == null) {
            stopSelf()
            return
        }
        tun = fd
        running = true
        showNotification()
        lastDns = SystemClock.elapsedRealtime()
        handler.postDelayed(idleCheck, IDLE_CHECK_MS)
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!monitorActive()) { stopVpn(); stopSelf() }
            }
        }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        pool = Executors.newFixedThreadPool(6)
        worker = Thread { loop(fd) }.also { it.start() }
    }

    private fun stopVpn() {
        running = false
        hideNotification()
        handler.removeCallbacks(idleCheck)
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
        pool?.shutdownNow()
        pool = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        worker?.interrupt()
        worker = null
    }

    /** Aviso fijo en el panel de notificaciones con botón para desactivar el bloqueo. */
    private fun showNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Control del bloqueo", NotificationManager.IMPORTANCE_LOW))
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val stop = PendingIntent.getService(this, 1,
                Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP_USER), flags)
            val open = PendingIntent.getActivity(this, 2,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            val apps = Blocklist.TARGETS.filter { Blocklist.isTargetEnabled(this, it.pkg) }
                .joinToString(", ") { it.title }
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                          else Notification.Builder(this)
            val n = builder
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle("Bloqueo de anuncios activo")
                .setContentText(apps)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Desactivar", stop).build())
                .build()
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            } catch (e: Exception) {
                nm.notify(NOTIF_ID, n)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun hideNotification() {
        try { stopForeground(true) } catch (_: Exception) {}
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) handlePacket(buf.copyOf(n), output)
            }
        } catch (_: IOException) {
        }
    }

    /** App que hizo la consulta DNS (Android 10+). Sirve para saber quién pide los anuncios. */
    private fun owner(p: ByteArray, ihl: Int): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        return try {
            val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
            val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))
            val sport = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP, InetSocketAddress(src, sport), InetSocketAddress(dst, 53))
            if (uid < 0) {
                "?"
            } else {
                val n = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid$uid"
                n.removePrefix("moldesbrothers.").removePrefix("com.")
            }
        } catch (e: Exception) {
            "?"
        }
    }

    private fun handlePacket(p: ByteArray, out: FileOutputStream) {
        if (p.size < 28) return
        if ((p[0].toInt() shr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return // solo UDP
        val dstPort = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        lastDns = SystemClock.elapsedRealtime()
        val dns = p.copyOfRange(ihl + 8, p.size)
        val q = parseQuestion(dns) ?: return
        val name = q.first

        val who = owner(p, ihl)
        if (Blocklist.isBlocked(name)) {
            Stats.addBlocked(name, who)
            write(out, buildPacket(p, ihl, sinkhole(dns, q.second)))
        } else {
            Stats.addAllowed(name, who)
            pool?.execute { forward(p, ihl, dns, out) }
        }
    }

    private fun forward(p: ByteArray, ihl: Int, dns: ByteArray, out: FileOutputStream) {
        for (server in (if (Blocklist.useAdGuard) ADGUARD_DNS else PLAIN_DNS)) {
            try {
                DatagramSocket().use { s ->
                    protect(s) // evita que la consulta vuelva a entrar al túnel
                    s.soTimeout = 2500
                    s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(server), 53))
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    s.receive(resp)
                    write(out, buildPacket(p, ihl, buf.copyOf(resp.length)))
                }
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun write(out: FileOutputStream, pkt: ByteArray) {
        synchronized(writeLock) {
            try { out.write(pkt) } catch (_: IOException) {}
        }
    }

    /** Devuelve (nombre en minúsculas, offset del final de la sección Question). */
    private fun parseQuestion(d: ByteArray): Pair<String, Int>? {
        if (d.size < 17) return null
        val qd = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
        if (qd < 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= d.size) return null
            val len = d[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if ((len and 0xC0) != 0 || pos + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, pos + 1, len, Charsets.ISO_8859_1))
            pos += 1 + len
        }
        if (pos + 4 > d.size) return null
        return Pair(sb.toString().lowercase(), pos + 4)
    }

    /** Responde 127.0.0.1 (A) / ::1 (AAAA) para que la conexión falle al instante. */
    private fun sinkhole(q: ByteArray, qEnd: Int): ByteArray {
        val qtype = ((q[qEnd - 4].toInt() and 0xFF) shl 8) or (q[qEnd - 3].toInt() and 0xFF)
        val rdata: ByteArray? = when (qtype) {
            1 -> byteArrayOf(127, 0, 0, 1)
            28 -> ByteArray(16).also { it[15] = 1 }
            else -> null
        }
        val extra = if (rdata != null) 12 + rdata.size else 0
        val r = ByteArray(qEnd + extra)
        System.arraycopy(q, 0, r, 0, qEnd)
        r[2] = 0x81.toByte() // QR=1, RD=1
        r[3] = 0x80.toByte() // RA=1, RCODE=0
        r[4] = 0; r[5] = 1
        r[6] = 0; r[7] = (if (rdata != null) 1 else 0).toByte()
        r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        if (rdata != null) {
            var o = qEnd
            r[o++] = 0xC0.toByte(); r[o++] = 0x0C        // puntero al nombre
            r[o++] = (qtype shr 8).toByte(); r[o++] = qtype.toByte()
            r[o++] = 0; r[o++] = 1                        // clase IN
            r[o++] = 0; r[o++] = 0; r[o++] = 0; r[o++] = 5 // TTL 5 s
            r[o++] = 0; r[o++] = rdata.size.toByte()
            System.arraycopy(rdata, 0, r, o, rdata.size)
        }
        return r
    }

    /** Construye un paquete IPv4/UDP de respuesta a partir del paquete de consulta. */
    private fun buildPacket(req: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val total = ihl + udpLen
        val o = ByteArray(total)
        System.arraycopy(req, 0, o, 0, ihl)
        System.arraycopy(req, 16, o, 12, 4) // src = dst original
        System.arraycopy(req, 12, o, 16, 4) // dst = src original
        o[2] = (total shr 8).toByte(); o[3] = total.toByte()
        o[6] = 0; o[7] = 0
        o[8] = 64
        o[10] = 0; o[11] = 0
        val cs = checksum(o, 0, ihl)
        o[10] = (cs shr 8).toByte(); o[11] = cs.toByte()
        o[ihl] = req[ihl + 2]; o[ihl + 1] = req[ihl + 3]
        o[ihl + 2] = req[ihl]; o[ihl + 3] = req[ihl + 1]
        o[ihl + 4] = (udpLen shr 8).toByte(); o[ihl + 5] = udpLen.toByte()
        o[ihl + 6] = 0; o[ihl + 7] = 0 // checksum UDP opcional en IPv4
        System.arraycopy(dns, 0, o, ihl + 8, dns.size)
        return o
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
