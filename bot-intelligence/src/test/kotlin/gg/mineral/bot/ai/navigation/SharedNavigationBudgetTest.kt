package gg.mineral.bot.ai.navigation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SharedNavigationBudgetTest {
    @Test fun `fifty callers get fair slices and unused reservations return to the window`() {
        var now = 0L
        val budget = SharedNavigationBudget({ now })
        val owners = List(50) { Any() }
        val served = mutableSetOf<Int>()
        repeat(7) {
            var used = 0L
            owners.forEachIndexed { index,owner ->
                val grant = budget.acquire(owner)
                if(grant>0) { used+=grant; served.add(index); budget.finish(owner,grant) }
            }
            assertTrue(used<=8_000_000)
            now+=50_000_000
        }
        assertEquals(50,served.size)
        val owner = Any()
        owners.forEach { budget.cancel(it) }
        repeat(15) { assertEquals(1_000_000,budget.acquire(owner)); budget.finish(owner,500_000) }
        assertEquals(500_000,budget.acquire(owner)); budget.finish(owner,500_000)
        assertEquals(0,budget.acquire(owner))
    }

    @Test fun `cancelled and inactive owners do not block other bots`() {
        var now = 0L
        val budget = SharedNavigationBudget({ now },limitNanos=1)
        val first = Any(); val waiting = Any(); val last = Any()
        assertEquals(1,budget.acquire(first)); budget.finish(first,1)
        assertEquals(0,budget.acquire(waiting))
        now=50_000_000
        assertEquals(0,budget.acquire(last))
        budget.cancel(waiting)
        assertEquals(1,budget.acquire(last)); budget.finish(last,1)
        budget.acquire(waiting)
        now=1_100_000_000
        assertEquals(1,budget.acquire(last))
    }
}
