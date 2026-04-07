package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.screen.type.ContainerScreen

/**
 * Goal for clicking the enchanted book (kit selector) in Practice arenas.
 * When the bot spawns in the arena, it needs to right-click the enchanted book to receive its kit.
 */
class SelectKitGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    
    private var hasSelectedKit = false
    private var clickedBook = false
    
    override fun shouldExecute(): Boolean {
        if (hasSelectedKit) return false
        
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        
        // Check if we have an enchanted book in inventory
        return inventory.contains(Item.ENCHANTED_BOOK)
    }
    
    override fun onStart() {
        clickedBook = false
    }
    
    private fun getEnchantedBookSlot(): Int {
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        
        for (i in 0..35) {
            val itemStack = inventory.getItemStackAt(i) ?: continue
            if (itemStack.item.id == Item.ENCHANTED_BOOK) {
                return i
            }
        }
        return -1
    }
    
    override fun onTick(tick: Tick) {
        val bookSlot = getEnchantedBookSlot()
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        
        tick.finishIf("No enchanted book found", bookSlot == -1)
        
        // Close any open container first
        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        
        // If book is in hotbar (slots 0-8)
        tick.prerequisite("In Hotbar", bookSlot <= 8) {
            moveItemToHotbar(bookSlot, inventory)
        }
        
        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(bookSlot)) {
            selectHotbarSlot(resolveHotbarSlot(bookSlot))
        }
        
        // Verify we're holding the enchanted book
        tick.finishIf("Not Holding Enchanted Book", inventory.heldItemStack?.item?.id != Item.ENCHANTED_BOOK)
        
        // Right click to select kit
        tick.execute {
            if (!clickedBook) {
                pressButton(50, MouseButton.Type.RIGHT_CLICK)
                clickedBook = true
            }
        }
        
        // Check if kit was selected (no more enchanted book in inventory)
        tick.finishIf("Kit Selected", !fakePlayer.inventory.contains(Item.ENCHANTED_BOOK))
        
        tick.execute {
            // If book is gone, we've selected our kit
            if (!fakePlayer.inventory.contains(Item.ENCHANTED_BOOK)) {
                hasSelectedKit = true
            }
        }
    }
    
    override fun onEnd() {
        hasSelectedKit = true
        logger.info("Kit selection completed")
    }
    
    override fun onEvent(event: Event): Boolean {
        return false
    }
    
    override fun onGameLoop() {
    }
}
