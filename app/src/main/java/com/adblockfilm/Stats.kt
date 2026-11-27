package com.adblockfilm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object Stats {
    val blockedCount = AtomicInteger(0)
    val allowedCount = AtomicInteger(0)

    /** Paquetes que la VPN está cubriendo ahora mismo (para el diagnóstico del panel). */
    @Volatile
    var covered: List<String> = emptyList()

    private val blocked = ArrayDeque<String>()
    private val allowed = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun addBlocked(domain: String, owner: String = "") {
        blockedCount.incrementAndGet()
        push(blocked, domain, owner)
    }

    @Synchronized
    fun addAllowed(domain: String, owner: String = "") {
        allowedCount.incrementAndGet()
        push(allowed, domain, owner)
    }

    @Synchronized
    fun recentBlocked(): List<String> = blocked.toList()

    @Synchronized
    fun recentAllowed(): List<String> = allowed.toList()

    private fun push(q: ArrayDeque<String>, d: String, owner: String) {
        val line = if (owner.isEmpty()) d else "$d [$owner]"
        if (q.isNotEmpty() && q.first().drop(9) == line) return
        q.addFirst(fmt.format(Date()) + " " + line)
        while (q.size > 60) q.removeLast()
    }
}
