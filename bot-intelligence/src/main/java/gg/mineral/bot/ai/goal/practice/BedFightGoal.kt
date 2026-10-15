package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.MeleeCombatGoal
import gg.mineral.bot.ai.perception.CombatPerception
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.navigation.*
import kotlin.math.pow

/** Bed objectives replace melee inputs, rather than competing as a second continuous goal. */
class BedFightGoal(clientInstance: ClientInstance) : MeleeCombatGoal(clientInstance), GoalDebugState {
    private val policy=BedFightPolicy()
    private var mode=BedFightPolicy.Mode.FALLBACK
    private var selectedTarget: java.util.UUID?=null
    private var lastLogTick=-200
    override fun debugSummary()="mode=$mode target=$selectedTarget navigation=${navigator?.diagnostic}"
    override val preferMatchTarget get()=false
    override val navigationProfile get()=gg.mineral.bot.ai.navigation.NavigationProfile.BEDFIGHT

    override fun chooseCombatTarget(snapshot: CombatPerception.Snapshot,range: Double): CombatPerception.PlayerState? {
        if(mode==BedFightPolicy.Mode.FALLBACK) return super.chooseCombatTarget(snapshot,range)
        return snapshot.enemies.firstOrNull { it.uuid==selectedTarget && it.distance3D<=range }
    }

    override fun updateObjective(): Boolean {
        val state=clientInstance.bedFightState
        val snapshot=perception.snapshot()
        val decision=policy.update(clientInstance.currentTick,state,
            snapshot.enemies.map { BedFightPolicy.Threat(it.uuid,it.distance3D) },combatProtected)
        if(decision.mode!=mode) navigator?.reset()
        mode=decision.mode; selectedTarget=decision.target
        if(logger.isDebugEnabled && clientInstance.currentTick-lastLogTick>=200) {
            lastLogTick=clientInstance.currentTick; logger.debug("BedFight: {}",debugSummary())
        }
        if(decision.clearCombat) clearCombatMemory()

        if(mode==BedFightPolicy.Mode.WAIT) {
            navigator?.pause(); clientInstance.navigationContext?.move(emptySet())
            clientInstance.mouse.clearPendingClicks()
            clientInstance.mouse.unpressButton(MouseButton.Type.LEFT_CLICK,MouseButton.Type.RIGHT_CLICK)
            return true
        }
        if(mode==BedFightPolicy.Mode.FALLBACK) return false
        if(mode==BedFightPolicy.Mode.COMBAT || mode==BedFightPolicy.Mode.HUNT) {
            setCombatTarget(chooseCombatTarget(snapshot,clientInstance.configuration.targetSearchRange.toDouble()))
            return false
        }
        if(clientInstance.hasActiveSporadicGoal) { navigator?.pause(); return true }
        val context=clientInstance.navigationContext ?: return false
        val navigator=navigator ?: return false
        val here=context.state().position
        val bed=state!!.beds.filter { it.alive }.minByOrNull { b -> b.blocks.minOf { p ->
            (p.x+0.5-here.x).pow(2)+(p.y+0.5-here.y).pow(2)+(p.z+0.5-here.z).pow(2) } } ?: return false
        val closest=bed.blocks.minBy { p -> (p.x+0.5-here.x).pow(2)+(p.y+0.5-here.y).pow(2)+(p.z+0.5-here.z).pow(2) }
        navigator.update(clientInstance.currentTick,NavVec(closest.x+0.5,closest.y+0.5,closest.z+0.5),4.5,false,
            (!clientInstance.blocksContinuousInventory || navigator.ownsInteraction) && !clientInstance.blocksContinuousAim,
            interactionBlocks=bed.blocks,profile=navigationProfile)
        if(!navigator.ownsInteraction) navigator.steeringYaw?.let { setMouseYaw(it); setMousePitch(12f) }
        return true
    }
}
