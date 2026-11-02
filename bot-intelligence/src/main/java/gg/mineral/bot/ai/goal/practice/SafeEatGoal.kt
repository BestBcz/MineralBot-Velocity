package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.ai.goal.type.SupplyController
import gg.mineral.bot.ai.goal.type.SupplyKind
import gg.mineral.bot.ai.goal.type.SupplyState
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.goal.Timebound
import gg.mineral.bot.api.instance.ClientInstance

class SafeEatGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance), Sporadic, Timebound, GoalDebugState {
    override var executing = false
    override var startTime = 0L
    override val maxDuration = 120L
    private val supply = SupplyController(clientInstance, SupplyKind.FOOD,
        { isItemReadyInHotbar(it, clientInstance.fakePlayer.inventory) },
        { moveItemToHotbar(it, clientInstance.fakePlayer.inventory) },
        { selectHotbarSlot(it) }, { finish() })

    internal fun shareSupplyState(state: SupplyState) { supply.sharedState = state }
    override fun shouldExecute() = supply.shouldExecute()
    override fun onStart() = supply.onStart()
    override fun onTick(tick: Tick) = supply.onTick(tick)
    override fun onEnd() = supply.onEnd()
    override fun onEvent(event: Event) = supply.onEvent(event)
    override fun onGameLoop() = supply.onGameLoop()
    override fun blocksContinuousAim() = true
    override fun blocksContinuousAttack() = true
    override fun blocksContinuousMovement() = true
    override fun debugSummary() = supply.debugSummary()
}
