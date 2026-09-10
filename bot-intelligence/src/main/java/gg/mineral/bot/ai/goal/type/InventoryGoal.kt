package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.inv.InventoryTransactionStatus
import gg.mineral.bot.api.inv.item.ItemStack
import gg.mineral.bot.api.screen.type.InventoryScreen

abstract class InventoryGoal(clientInstance: ClientInstance) : Goal(clientInstance) {
    private data class ItemFingerprint(val itemId: Int, val durability: Int, val count: Int) {
        fun matches(itemStack: ItemStack?): Boolean {
            return itemStack != null &&
                    itemStack.item.id == itemId &&
                    itemStack.durability == durability &&
                    itemStack.count == count
        }
    }

    private data class PendingHotbarMove(
        val token: Long?,
        val expectedItem: ItemFingerprint,
        val hotbarSlot: Int,
        val startedTick: Int,
        val deadlineTick: Int,
        val fallbackReadyTick: Int,
        var timeoutReported: Boolean = false
    )

    private var pendingHotbarMove: PendingHotbarMove? = null
    private var retryHotbarMoveAfterTick = 0

    protected fun resolveHotbarSlot(inventorySlot: Int, movedSlot: Int = 8): Int {
        return if (inventorySlot in 0..8) inventorySlot else movedSlot
    }

    protected fun selectHotbarSlot(hotbarSlot: Int, delay: Int = 10) {
        val keyType =
                when (hotbarSlot.coerceIn(0, 8)) {
                    0 -> Key.Type.KEY_1
                    1 -> Key.Type.KEY_2
                    2 -> Key.Type.KEY_3
                    3 -> Key.Type.KEY_4
                    4 -> Key.Type.KEY_5
                    5 -> Key.Type.KEY_6
                    6 -> Key.Type.KEY_7
                    7 -> Key.Type.KEY_8
                    else -> Key.Type.KEY_9
                }
        pressKey(delay, keyType)
    }

    /**
     * Returns true only when no swap transaction is outstanding and the item is in the hotbar.
     * Call [moveItemToHotbar] from the failed prerequisite to advance the transaction.
     */
    protected fun isItemReadyInHotbar(index: Int, inventory: Inventory, moveIndex: Int = 8): Boolean {
        if (pendingHotbarMove != null || clientInstance.hasPendingInventoryTransaction) return false
        if (index !in 0..8) return false
        return inventory.getItemStackAt(index) != null && moveIndex in 0..8
    }

    fun moveItemToHotbar(index: Int, inventory: Inventory, moveIndex: Int = 8) {
        clientInstance.mouse.clearPendingClicks()
        pendingHotbarMove?.let { pending ->
            pollPendingHotbarMove(pending, inventory)
            return
        }

        if (clientInstance.hasPendingInventoryTransaction) {
            failCurrentMove("Another inventory transaction is awaiting confirmation or resync")
            return
        }

        if (index in 0..8) return
        if (clientInstance.currentTick < retryHotbarMoveAfterTick) {
            failCurrentMove("Hotbar move is cooling down after a failed transaction")
            return
        }

        val fakePlayer = clientInstance.fakePlayer
        val screen = clientInstance.currentScreen
        val inventoryContainer = fakePlayer.inventoryContainer
        val slot = inventoryContainer.getSlot(inventory, index) ?: run {
            pressKey(10, Key.Type.KEY_ESCAPE)
            failCurrentMove("Inventory slot $index is unavailable")
            return
        }

        if (screen is InventoryScreen) {
            val guiSlotX = screen.getSlotXScaled(slot, clientInstance.displayWidth)
            val guiSlotY = screen.getSlotYScaled(slot, clientInstance.displayHeight)
            val currentX = clientInstance.mouse.x
            val currentY = clientInstance.mouse.y

            if (currentX != guiSlotX || currentY != guiSlotY) {
                clientInstance.mouse.setCursorPosition(guiSlotX, guiSlotY)
                logger.debug("Moving mouse to slot at ($guiSlotX, $guiSlotY)")
            } else {
                val item = inventory.getItemStackAt(index) ?: run {
                    failCurrentMove("Inventory item disappeared before the hotbar swap")
                    return
                }
                val fingerprint = ItemFingerprint(item.item.id, item.durability, item.count)
                val timeoutTicks = InventoryMoveTiming.transactionTimeoutTicks(clientInstance.latency)
                val token = clientInstance.requestHotbarSwap(index, moveIndex)

                if (clientInstance.supportsTrackedInventoryTransactions && token == null) {
                    failCurrentMove("Another inventory transaction is still pending")
                    return
                }

                if (token == null) selectHotbarSlot(moveIndex)

                pendingHotbarMove =
                        PendingHotbarMove(
                                token = token,
                                expectedItem = fingerprint,
                                hotbarSlot = moveIndex,
                                startedTick = clientInstance.currentTick,
                                deadlineTick = clientInstance.currentTick + timeoutTicks,
                                fallbackReadyTick =
                                        clientInstance.currentTick +
                                                InventoryMoveTiming.legacySettleTicks(clientInstance.latency)
                        )
                pressKey(10, Key.Type.KEY_ESCAPE)
                logger.debug(
                        "Requested inventory slot {} -> hotbar {} transaction token={}",
                        index,
                        moveIndex,
                        token ?: "legacy"
                )
            }
        } else if (screen == null) {
            pressKey(10, Key.Type.KEY_E)
        } else {
            pressKey(10, Key.Type.KEY_ESCAPE)
            logger.debug("Unexpected screen {}; closing before inventory move", screen.javaClass.simpleName)
        }
    }

    private fun pollPendingHotbarMove(pending: PendingHotbarMove, inventory: Inventory) {
        if (clientInstance.currentScreen != null) pressKey(10, Key.Type.KEY_ESCAPE)

        val token = pending.token
        if (token == null) {
            if (clientInstance.currentTick < pending.fallbackReadyTick) return
            if (pending.expectedItem.matches(inventory.getItemStackAt(pending.hotbarSlot))) {
                pendingHotbarMove = null
                return
            }

            pendingHotbarMove = null
            retryHotbarMoveAfterTick = clientInstance.currentTick + 2
            failCurrentMove("Legacy hotbar swap did not produce the expected item")
            return
        }

        when (clientInstance.inventoryTransactionStatus(token)) {
            InventoryTransactionStatus.ACCEPTED -> {
                clientInstance.forgetInventoryTransaction(token)
                pendingHotbarMove = null
                if (!pending.expectedItem.matches(inventory.getItemStackAt(pending.hotbarSlot))) {
                    retryHotbarMoveAfterTick = clientInstance.currentTick + 2
                    failCurrentMove("Accepted hotbar swap has an unexpected destination item")
                }
            }

            InventoryTransactionStatus.REJECTED -> {
                clientInstance.forgetInventoryTransaction(token)
                pendingHotbarMove = null
                retryHotbarMoveAfterTick = clientInstance.currentTick + 2
                failCurrentMove("Server rejected the hotbar swap transaction")
            }

            InventoryTransactionStatus.PENDING -> {
                if (clientInstance.currentTick <= pending.deadlineTick) return
                if (pending.timeoutReported) {
                    if (this is Sporadic) finish()
                    return
                }
                pending.timeoutReported = true
                failCurrentMove(
                        "Timed out waiting for hotbar transaction after " +
                                (clientInstance.currentTick - pending.startedTick) +
                                " ticks"
                )
            }

            InventoryTransactionStatus.UNKNOWN -> {
                if (clientInstance.hasPendingInventoryTransaction) {
                    if (clientInstance.currentTick > pending.deadlineTick && this is Sporadic) finish()
                    return
                }

                pendingHotbarMove = null
                retryHotbarMoveAfterTick = clientInstance.currentTick + 2
                failCurrentMove("Hotbar transaction result was discarded before it could be consumed")
            }
        }
    }

    private fun failCurrentMove(reason: String) {
        if (clientInstance.currentScreen != null) pressKey(10, Key.Type.KEY_ESCAPE)
        logger.warn(reason)
        if (this is Sporadic) finish()
    }

}
