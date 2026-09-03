package gg.mineral.bot.base.client.inventory

import gg.mineral.bot.api.inv.InventoryTransactionStatus
import java.util.concurrent.atomic.AtomicLong

/**
 * Correlates the client's locally-predicted window click with the server acknowledgement.
 * Only one tracked bot inventory transaction is allowed in flight at a time.
 */
internal class InventoryTransactionTracker<T>(
    private val retainedCompletedTransactions: Int = 32
) {
    internal data class Entry<T>(
        val token: Long,
        val windowId: Int,
        val actionNumber: Short,
        val sourceContainerSlot: Int,
        val hotbarContainerSlot: Int,
        val hotbarInventorySlot: Int,
        val sourceAfter: T?,
        val hotbarAfter: T?,
        var status: InventoryTransactionStatus = InventoryTransactionStatus.PENDING,
        val protectedContainerSlots: MutableSet<Int> = linkedSetOf()
    )

    private val nextToken = AtomicLong(1L)
    private val entries = LinkedHashMap<Long, Entry<T>>()
    private var pendingToken: Long? = null
    private var awaitingResyncWindowId: Int? = null

    @Synchronized
    fun register(
        windowId: Int,
        actionNumber: Short,
        sourceContainerSlot: Int,
        hotbarContainerSlot: Int,
        hotbarInventorySlot: Int,
        sourceAfter: T?,
        hotbarAfter: T?
    ): Entry<T>? {
        if (hasPending()) return null

        val entry =
            Entry(
                token = nextToken.getAndIncrement(),
                windowId = windowId,
                actionNumber = actionNumber,
                sourceContainerSlot = sourceContainerSlot,
                hotbarContainerSlot = hotbarContainerSlot,
                hotbarInventorySlot = hotbarInventorySlot,
                sourceAfter = sourceAfter,
                hotbarAfter = hotbarAfter
            )
        entries[entry.token] = entry
        pendingToken = entry.token
        pruneCompleted()
        return entry
    }

    @Synchronized
    fun complete(windowId: Int, actionNumber: Short, accepted: Boolean): Entry<T>? {
        val token = pendingToken ?: return null
        val entry = entries[token] ?: return null
        if (entry.windowId != windowId || entry.actionNumber != actionNumber) return null

        entry.status =
            if (accepted) InventoryTransactionStatus.ACCEPTED
            else InventoryTransactionStatus.REJECTED
        if (accepted) {
            entry.protectedContainerSlots += entry.sourceContainerSlot
            entry.protectedContainerSlots += entry.hotbarContainerSlot
        }
        pendingToken = null
        if (!accepted) awaitingResyncWindowId = windowId
        return entry
    }

    @Synchronized
    fun markWindowSynchronized(windowId: Int) {
        if (awaitingResyncWindowId == windowId) awaitingResyncWindowId = null
    }

    @Synchronized
    fun consumeAcceptedForSlot(windowId: Int, containerSlot: Int): List<Entry<T>> =
        entries.values.filter {
            it.windowId == windowId &&
                    it.status == InventoryTransactionStatus.ACCEPTED &&
                    it.protectedContainerSlots.remove(containerSlot)
        }

    @Synchronized
    fun consumeAcceptedForWindow(windowId: Int): List<Entry<T>> {
        val accepted =
                entries.values.filter {
                    it.windowId == windowId &&
                            it.status == InventoryTransactionStatus.ACCEPTED &&
                            it.protectedContainerSlots.isNotEmpty()
                }
        accepted.forEach { it.protectedContainerSlots.clear() }
        return accepted
    }

    @Synchronized
    fun status(token: Long): InventoryTransactionStatus =
        entries[token]?.status ?: InventoryTransactionStatus.UNKNOWN

    @Synchronized
    fun forget(token: Long) {
        if (pendingToken == token) return
        entries.remove(token)
    }

    @Synchronized
    fun hasPending(): Boolean = pendingToken != null || awaitingResyncWindowId != null

    private fun pruneCompleted() {
        if (entries.size <= retainedCompletedTransactions + 1) return

        val iterator = entries.entries.iterator()
        while (entries.size > retainedCompletedTransactions + 1 && iterator.hasNext()) {
            val candidate = iterator.next().value
            if (candidate.status != InventoryTransactionStatus.PENDING) iterator.remove()
        }
    }
}
