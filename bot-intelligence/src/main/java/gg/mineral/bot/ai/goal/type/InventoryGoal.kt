package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.screen.type.ContainerScreen

abstract class InventoryGoal(clientInstance: ClientInstance) : Goal(clientInstance) {
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

    fun moveItemToHotbar(index: Int, inventory: Inventory, moveIndex: Int = 8) {
        val fakePlayer = clientInstance.fakePlayer

        val screen = clientInstance.currentScreen

        val inventoryContainer = fakePlayer.inventoryContainer

        val slot = inventoryContainer.getSlot(inventory, index) ?: run {
            pressKey(10, Key.Type.KEY_ESCAPE)
            return logger.debug("Slot is null; closing inventory")
        }

        if (screen is ContainerScreen) {
            val guiSlotX = screen.getSlotXScaled(slot, clientInstance.displayWidth)
            val guiSlotY = screen.getSlotYScaled(slot, clientInstance.displayHeight)

            val currentX = clientInstance.mouse.x
            val currentY = clientInstance.mouse.y

            if (currentX != guiSlotX || currentY != guiSlotY) {
                clientInstance.mouse.setCursorPosition(guiSlotX, guiSlotY)
                logger.debug("Moving mouse to slot at ($guiSlotX, $guiSlotY)")
            } else {
                selectHotbarSlot(moveIndex)
                logger.debug("Swapped item to hotbar slot.")
            }
        } else if (screen == null) {
            pressKey(10, Key.Type.KEY_E)
        } else {
            pressKey(10, Key.Type.KEY_ESCAPE)
            logger.debug("Unexpected screen {}; closing before inventory move", screen.javaClass.simpleName)
        }
    }
}
