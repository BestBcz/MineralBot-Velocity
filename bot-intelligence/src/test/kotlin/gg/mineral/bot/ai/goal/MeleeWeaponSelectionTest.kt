package gg.mineral.bot.ai.goal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MeleeWeaponSelectionTest {
    @Test
    fun `empty inventory selects no weapon for bare handed combat`() {
        assertNull(findBestMeleeWeaponSlot { null })
    }

    @Test
    fun `items without attack damage still select no weapon`() {
        assertNull(findBestMeleeWeaponSlot(slotCount = 3) { 0.0 })
    }

    @Test
    fun `highest damage weapon is selected`() {
        val damageBySlot = mapOf(2 to 4.0, 7 to 7.0, 19 to 6.0)

        assertEquals(7, findBestMeleeWeaponSlot { damageBySlot[it] })
    }
}
