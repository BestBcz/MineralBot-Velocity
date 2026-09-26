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
        val released = mutableSetOf<Type>()
        while (mouse.next()) {
            assertFalse(mouse.eventButtonState)
            assertTrue(mouse.eventContextReset)
            mouse.eventButtonType?.let { released.add(it) }
        }
        assertTrue(Type.RIGHT_CLICK in released)
    }

    @Test fun `old timed release cannot cancel a new right click hold`() {
        val mouse = Mouse(object : EventHandler {
            override fun <T : Event> callEvent(event: T) = false
        })
        mouse.pressButton(100, Type.RIGHT_CLICK)
        mouse.unpressButton(Type.RIGHT_CLICK)
        mouse.pressButton(Type.RIGHT_CLICK)
        mouse.onGameLoop(Long.MAX_VALUE)
        assertTrue(mouse.getButton(Type.RIGHT_CLICK).isPressed)
    }

    @Test fun `cleanup releases an already expired click still held by Minecraft`() {
        val mouse = Mouse(object : EventHandler {
            override fun <T : Event> callEvent(event: T) = false
        })
        mouse.pressButton(5, Type.RIGHT_CLICK)
        assertTrue(mouse.next())
        mouse.onGameLoop(Long.MAX_VALUE)
        mouse.clearPendingClicks()
        var releasedRight = false
        while (mouse.next()) {
            assertFalse(mouse.eventButtonState)
            if (mouse.eventButtonType == Type.RIGHT_CLICK) releasedRight = true
        }
        assertTrue(releasedRight)
    }
}
