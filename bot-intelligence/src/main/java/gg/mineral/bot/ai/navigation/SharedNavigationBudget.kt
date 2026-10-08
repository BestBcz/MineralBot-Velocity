package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.NavigationSearchBudget

/** Leases are reserved under a lock; world reads and searches remain on each bot's game loop. */
class SharedNavigationBudget(private val nanoTime: () -> Long = System::nanoTime,
                             private val windowNanos: Long = 50_000_000,
                             private val limitNanos: Long = 8_000_000) : NavigationSearchBudget {
    private data class Lease(val window: Long, val reserved: Long)
    private val waiting = LinkedHashMap<Any, Long>()
    private val leases = HashMap<Any, Lease>()
    private var window = Long.MIN_VALUE
    private var remaining = limitNanos

    @Synchronized override fun acquire(owner: Any): Long {
        val now = nanoTime()
        val nextWindow = now / windowNanos
        if (nextWindow != window) { window = nextWindow; remaining = limitNanos }
        waiting.entries.removeIf { now - it.value > 1_000_000_000 }
        waiting.putIfAbsent(owner, now)
        if (waiting.keys.first() !== owner || remaining <= 0 || owner in leases) return 0
        waiting.remove(owner)
        val amount = minOf(1_000_000L, remaining)
        remaining -= amount
        leases[owner] = Lease(window, amount)
        return amount
    }

    @Synchronized override fun finish(owner: Any, elapsedNanos: Long) {
        val lease = leases.remove(owner) ?: return
        if (lease.window == window) remaining += lease.reserved - elapsedNanos.coerceAtLeast(0)
    }

    @Synchronized override fun cancel(owner: Any) { waiting.remove(owner) }

    companion object { val PROCESS = SharedNavigationBudget() }
}
