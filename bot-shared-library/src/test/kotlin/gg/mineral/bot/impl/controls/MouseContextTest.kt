package gg.mineral.bot.impl.controls

import gg.mineral.bot.api.controls.MouseButton.Type
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.event.EventHandler
import gg.mineral.bot.api.event.peripherals.MouseButtonEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MouseContextTest {
    @Test fun `queued attack cannot follow the cursor into inventory`() {
        val mouse = Mouse(object : EventHandler {
            override fun <T : Event> callEvent(event: T) = false
        })
        mouse.pressButton(25, Type.LEFT_CLICK)
        mouse.unpressButton(Type.LEFT_CLICK)
        mouse.clearPendingClicks()
        mouse.setCursorPosition(100, 200)
        while (mouse.next()) assertFalse(mouse.eventButtonState)
        assertFalse(mouse.getButton(Type.LEFT_CLICK).isPressed)
        mouse.pressButton(Type.LEFT_CLICK)
        assertTrue(mouse.next())
        assertTrue(mouse.eventButtonState)
        mouse.onGameLoop(Long.MAX_VALUE)
        assertTrue(mouse.getButton(Type.LEFT_CLICK).isPressed)
    }

    @Test fun `context cleanup cannot be cancelled by an item use goal`() {
        val mouse = Mouse(object : EventHandler {
            override fun <T : Event> callEvent(event: T) = event is MouseButtonEvent && !event.pressed
        })
        mouse.pressButton(Type.RIGHT_CLICK)
        mouse.clearPendingClicks()
        assertFalse(mouse.getButton(Type.RIGHT_CLICK).isPressed)
        assertFalse(mouse.next())
    }
}
