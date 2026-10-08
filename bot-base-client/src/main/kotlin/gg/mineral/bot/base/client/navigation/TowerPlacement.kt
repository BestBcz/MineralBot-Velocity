package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.navigation.NavigationState

/** Exactly one takeoff per operation. A missed window fails rather than bouncing indefinitely. */
internal class TowerPlacement(private val top: Double) {
    enum class Motion { TAKEOFF, RISING, PLACE, WAITING, LANDING, COMPLETE, FAILED }
    private var jumped = false
    private var airborne = false

    fun update(state: NavigationState, clicked: Boolean, confirmed: Boolean): Motion {
        if (confirmed) return if (state.onGround && state.position.y>=top-0.05) Motion.COMPLETE else Motion.LANDING
        if (clicked) return Motion.WAITING
        if (!jumped) {
            if (!state.onGround) return Motion.RISING
            jumped = true
            return Motion.TAKEOFF
        }
        if (!state.onGround) airborne = true
        if (airborne && state.onGround) return Motion.FAILED
        if (state.position.y>=top+0.002) return Motion.PLACE
        return if (!airborne) Motion.TAKEOFF else Motion.RISING
    }
}
