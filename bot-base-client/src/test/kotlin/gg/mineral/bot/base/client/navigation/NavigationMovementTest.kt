package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.controls.Key.Type
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.EventHandler
import gg.mineral.bot.impl.controls.Keyboard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationMovementTest {
    private fun keyboard()=Keyboard(object : EventHandler {
        override fun <T : Event> callEvent(event: T)=false
    })

    @Test fun `normal movement releases a sneak held by navigation waiting or failed jumps`() {
        val keyboard=keyboard()
        repeat(3) {
            NavigationMovement.apply(keyboard,setOf(Type.KEY_LSHIFT))
            assertTrue(keyboard.isKeyDown(Type.KEY_LSHIFT))
            NavigationMovement.apply(keyboard,setOf(Type.KEY_S))
            assertFalse(keyboard.isKeyDown(Type.KEY_LSHIFT))
            assertTrue(keyboard.isKeyDown(Type.KEY_S))
        }
    }

    @Test fun `empty movement releases all navigation controls and cannot restore sneak later`() {
        val keyboard=keyboard()
        NavigationMovement.apply(keyboard,setOf(Type.KEY_LSHIFT,Type.KEY_W,Type.KEY_SPACE,Type.KEY_LCONTROL))
        NavigationMovement.apply(keyboard,emptySet())
        keyboard.onGameLoop(Long.MAX_VALUE)
        for(type in listOf(Type.KEY_LSHIFT,Type.KEY_W,Type.KEY_S,Type.KEY_SPACE,Type.KEY_LCONTROL))
            assertFalse(keyboard.isKeyDown(type))
    }
}
