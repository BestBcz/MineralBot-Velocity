package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import kotlin.math.ceil
import kotlin.math.max

/**
 * Goal for clicking the enchanted book (kit selector) in Practice arenas.
 * When the bot spawns in the arena, it needs to right-click the enchanted book to receive its kit.
 */
class SelectKitGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound {
    override var executing: Boolean = false
    override var startTime: Long = 0
    override val maxDuration: Long = 100
    
    private var lastClickTick: Int? = null
    private var retryAfterTick = 0

    override fun blocksContinuousAim() = true
    override fun blocksContinuousAttack() = true
    override fun blocksContinuousMovement() = true
    
    override fun shouldExecute(): Boolean {
        if (clientInstance.currentTick < retryAfterTick) return false
        
        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        
        // Check if we have an enchanted book in inventory
        return inventory.contains(Item.ENCHANTED_BOOK)
    }
    
    override fun onStart() {
        lastClickTick = null
        clientInstance.mouse.clearPendingClicks()
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
        
        // If book is in hotbar (slots 0-8)
        tick.prerequisite("In Hotbar", isItemReadyInHotbar(bookSlot, inventory)) {
            moveItemToHotbar(bookSlot, inventory)
        }

        tick.prerequisite("Inventory Closed", clientInstance.currentScreen == null) {
            pressKey(10, Key.Type.KEY_ESCAPE)
        }
        
        tick.prerequisite("Correct Hotbar Slot Selected", inventory.heldSlot == resolveHotbarSlot(bookSlot)) {
            selectHotbarSlot(resolveHotbarSlot(bookSlot))
        }
        
        // Verify we're holding the enchanted book
        tick.prerequisite("Holding Enchanted Book", inventory.heldItemStack?.item?.id == Item.ENCHANTED_BOOK) {}
        
        // Right click to select kit
        tick.execute {
            val interval=max(10,ceil(clientInstance.latency.coerceAtLeast(0)/50.0).toInt()*2+4)
            if (lastClickTick?.let { clientInstance.currentTick-it>=interval } != false) {
                pressButton(50, MouseButton.Type.RIGHT_CLICK)
                lastClickTick = clientInstance.currentTick
            }
        }
        
        // Check if kit was selected (no more enchanted book in inventory)
        tick.finishIf("Kit Selected", !fakePlayer.inventory.contains(Item.ENCHANTED_BOOK))
        
    }
    
    override fun onEnd() {
        clientInstance.mouse.clearPendingClicks()
        val selected=!clientInstance.fakePlayer.inventory.contains(Item.ENCHANTED_BOOK)
        retryAfterTick=clientInstance.currentTick+if(selected) 0 else 10
        logger.debug("Kit selection ended: selected={}",selected)
    }
    
    override fun onEvent(event: Event): Boolean {
        return false
    }
    
    override fun onGameLoop() {
    }
}
