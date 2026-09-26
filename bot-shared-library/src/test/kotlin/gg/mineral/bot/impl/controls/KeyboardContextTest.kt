package gg.mineral.bot.impl.controls

import gg.mineral.bot.api.controls.Key.Type
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.EventHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyboardContextTest {
    private fun keyboard() = Keyboard(object : EventHandler {
        override fun <T : Event> callEvent(event: T) = false
    })
    @Test fun `old delayed release cannot stop a new movement press`() {
        val keyboard = keyboard()
        keyboard.pressKey(100, Type.KEY_W)
        keyboard.unpressKey(Type.KEY_W)
        keyboard.pressKey(Type.KEY_W)
        keyboard.onGameLoop(Long.MAX_VALUE)
        assertTrue(keyboard.isKeyDown(Type.KEY_W))
    }
    @Test fun `old delayed resume cannot restore movement after explicit input`() {
        val keyboard = keyboard()
        keyboard.pressKey(Type.KEY_W)
        keyboard.unpressKey(100, Type.KEY_W)
        keyboard.pressKey(Type.KEY_W)
        keyboard.unpressKey(Type.KEY_W)
        keyboard.onGameLoop(Long.MAX_VALUE)
        assertFalse(keyboard.isKeyDown(Type.KEY_W))
    }
    @Test fun `stop discards queued drops and cancels delayed movement`() {
        val keyboard = keyboard()
        keyboard.pressKey(10, Type.KEY_Q)
        keyboard.pressKey(Type.KEY_W)
        keyboard.unpressKey(100, Type.KEY_W)
        keyboard.stopAll()
        keyboard.onGameLoop(Long.MAX_VALUE)
        while (keyboard.next()) assertFalse(keyboard.eventKeyState)
        assertFalse(keyboard.isKeyDown(Type.KEY_Q))
        assertFalse(keyboard.isKeyDown(Type.KEY_W))
    }
}
