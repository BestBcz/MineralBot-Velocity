package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import kotlin.math.*

/** One navigation update per physics tick; utility goals always have priority. */
class CombatNavigator(private val context: NavigationContext) {
    private var search: IncrementalPathfinder?=null
    private var goal: NavigationGoal?=null
    private var route=ArrayDeque<RouteStep>()
    private var action: BlockAction?=null
    private var lastTick=-1
    private var lastRevision=-1L
    private var lastPosition: NavVec?=null
    private var progressTick=0
    private var failures=0
    private var held=false
    private var recoveryUntil=0
    private var recoveryKeys=emptySet<Key.Type>()
    private var allowEdits=true
    private val excluded=mutableMapOf<BlockPos,Int>()
    var ownsMovement=false; private set
    var steeringYaw: Float?=null; private set
    var preparingJump=false; private set
    val ownsInteraction get()=action!=null
    var diagnostic="idle"; private set

    fun pause() {
        if(ownsMovement || action!=null) context.cancelAction()
        search=null; route.clear(); action=null; ownsMovement=false; steeringYaw=null; preparingJump=false; lastPosition=null; lastTick=-1
    }
    fun reset() { pause(); goal=null; failures=0; held=false; excluded.clear() }

    fun update(tick: Int,target: NavVec?,range: Double,lineOfSight: Boolean,canUseItems: Boolean): Boolean {
        if(tick==lastTick) return ownsMovement
        lastTick=tick
        steeringYaw=null; preparingJump=false
        val state=context.state(); val here=state.position
        val geometry=NavigationGeometry(context.world,state.width,state.height)
        lastPosition?.let { previous ->
            if(hypot(here.x-previous.x,here.z-previous.z)>3.0 || abs(here.y-previous.y)>3.0) {
                context.cancelAction(); action=null; search=null; route.clear(); held=false; failures=0
                lastPosition=here; progressTick=tick
            }
        }
        if(state.inWater && state.air<80 && geometry.clear(NavVec(here.x,here.y+0.4,here.z),emptyMap())) {
            context.cancelAction(); action=null; search=null; route.clear()
            context.move(setOf(Key.Type.KEY_SPACE)); ownsMovement=true; diagnostic="surfacing"; progressTick=tick
            return true
        }
        excluded.entries.removeIf { tick>=it.value }
        val newGoal=target?.let { NavigationGoal(it,range.coerceIn(0.3,3.0)) }
        val movedGoal=goal==null || newGoal==null || goal!!.position.block!=newGoal.position.block
        if(movedGoal || allowEdits!=canUseItems) {
            context.cancelAction(); action=null; search=null; route.clear(); goal=newGoal; held=false; failures=0
            progressTick=tick; lastPosition=here; allowEdits=canUseItems
        }
        if(target==null) {
            ownsMovement=state.inWater
            context.move(if(state.inWater) setOf(Key.Type.KEY_SPACE) else emptySet())
            diagnostic=if(state.inWater) "floating" else "idle"
            return ownsMovement
        }
        if(action!=null) {
            if(!canUseItems) { context.cancelAction(); action=null; route.clear(); search=null }
            else {
                context.tickAction()
                when(context.actionStatus()) {
                    ActionStatus.CONFIRMED -> { context.cancelAction(); action=null; route.clear(); search=null; progressTick=tick; lastPosition=here; failures=0 }
                    ActionStatus.FAILED,ActionStatus.IDLE -> { excluded[action!!.pos]=tick+100; context.cancelAction(); action=null; route.clear(); search=null; progressTick=tick }
                    else -> { ownsMovement=true; diagnostic="block action"; return true }
                }
            }
        }
        if(!state.inWater && lineOfSight && hypot(here.x-target.x,here.z-target.z)<=range && abs(here.y-target.y)<1.0) {
            context.move(emptySet()); search=null; route.clear(); ownsMovement=false; failures=0; diagnostic="combat"; return false
        }
        if(held) {
            val positionChanged=lastPosition?.let { hypot(here.x-it.x,here.z-it.z)>0.2 || abs(here.y-it.y)>0.3 }==true
            if(lastRevision==context.world.revision && !positionChanged) { context.move(if(state.inWater)setOf(Key.Type.KEY_SPACE) else emptySet()); ownsMovement=true; diagnostic="waiting for terrain"; return true }
            held=false; failures=0; search=null; progressTick=tick; lastPosition=here
        }
        if(tick<recoveryUntil) {
            context.move(recoveryKeys); ownsMovement=true; diagnostic="recovering"; return true
        }
        if(lastRevision!=context.world.revision) {
            val next=route.firstOrNull()
            if(next!=null && (next.actions.isNotEmpty() || !geometry.clear(next.position,emptyMap()))) route.clear()
            lastRevision=context.world.revision
        }
        // Direct movement uses swept body/support checks, rather than an eye-level ray.
        if(!state.inWater && abs(here.y-target.y)<=1.25 && geometry.clear(target,emptyMap()) && geometry.walk(here,target,emptyMap())) {
            route.clear(); search=null
            drive(state,target,false,false)
            ownsMovement=true; diagnostic="direct"
            checkProgress(tick,state,geometry)
            return true
        }
        while(route.isNotEmpty()) {
            val next=route.first()
            if(next.actions.isNotEmpty()) break
            if(hypot(here.x-next.position.x,here.z-next.position.z)>0.28 ||
                (abs(here.y-next.position.y)>0.3 && !(next.traversal==Traversal.SWIM && here.y>=next.position.y-0.3))) break
            route.removeFirst(); progressTick=tick; lastPosition=here; failures=0
        }
        if(route.isEmpty()) {
            if(search==null) search=IncrementalPathfinder(context.world,state,goal!!,
                if(canUseItems) context.material() else null,excluded.keys.toSet(),allowEdits=canUseItems)
            val result=search!!.compute()
            diagnostic="${result.status} (${result.expanded})"
            if(result.status!=SearchStatus.SEARCHING) {
                route.addAll(result.path); search=null
                if(route.isEmpty()) {
                    failures++; held=failures>=3; progressTick=tick
                    context.move(if(state.inWater)setOf(Key.Type.KEY_SPACE) else emptySet()); ownsMovement=true
                    if(!held) { recoveryUntil=tick+5; recoveryKeys=emptySet() }
                    return true
                }
            } else {
                context.move(if(state.inWater)setOf(Key.Type.KEY_SPACE) else emptySet()); ownsMovement=true; return true
            }
        }
        val step=route.first()
        if(step.actions.isNotEmpty()) {
            context.move(emptySet()); action=step.actions.first()
            context.requestAction(action!!); context.tickAction()
            ownsMovement=true; diagnostic="block action"; return true
        }
        // A successful search is still checked at execution time after block updates/knockback.
        if(!geometry.clear(step.position,emptyMap())) { route.clear(); search=null; context.move(emptySet()); ownsMovement=true; return true }
        if(step.traversal in setOf(Traversal.WALK,Traversal.STEP,Traversal.DROP) && state.onGround &&
            !geometry.walk(here,step.position,emptyMap())) {
            route.clear(); search=null; context.move(emptySet()); ownsMovement=true; return true
        }
        val jump=step.traversal==Traversal.JUMP || step.traversal==Traversal.TOWER
        drive(state,step.position,jump,step.traversal==Traversal.SWIM)
        ownsMovement=true; diagnostic=step.traversal.name
        checkProgress(tick,state,geometry)
        return true
    }
    private fun drive(state: NavigationState,dest: NavVec,jump: Boolean,swim: Boolean) {
        var dx=dest.x-state.position.x; var dz=dest.z-state.position.z
        if(state.inWater) {
            val flow=context.world.block(state.position.block)?.flow?:NavVec(0.0,0.0,0.0)
            dx-=flow.x*0.6+state.velocity.x*0.4; dz-=flow.z*0.6+state.velocity.z*0.4
        }
        val angle=atan2(-dx,dz)-Math.toRadians(state.yaw.toDouble())
        steeringYaw=Math.toDegrees(atan2(-dx,dz)).toFloat()
        preparingJump=jump
        if(jump && state.onGround && abs(atan2(sin(angle),cos(angle)))>Math.toRadians(20.0)) {
            context.move(emptySet())
            return
        }
        val keys=mutableSetOf<Key.Type>()
        if(hypot(dx,dz)>0.10) {
            if(cos(angle)>0.38)keys.add(Key.Type.KEY_W)
            if(cos(angle)< -0.38)keys.add(Key.Type.KEY_S)
            if(sin(angle)< -0.38)keys.add(Key.Type.KEY_A)
            if(sin(angle)>0.38)keys.add(Key.Type.KEY_D)
        }
        if(!state.inWater && !swim && Key.Type.KEY_W in keys)keys.add(Key.Type.KEY_LCONTROL)
        if((jump && state.onGround) || state.inWater || (swim && dest.y>state.position.y+0.15))keys.add(Key.Type.KEY_SPACE)
        context.move(keys)
    }
    private fun checkProgress(tick: Int,state: NavigationState,geometry: NavigationGeometry) {
        if(preparingJump && state.onGround && steeringYaw?.let {
                val angle=Math.toRadians((it-state.yaw).toDouble()); abs(atan2(sin(angle),cos(angle)))>Math.toRadians(20.0)
            }==true) { progressTick=tick; return }
        val here=state.position; val previous=lastPosition
        if(previous==null || hypot(here.x-previous.x,here.z-previous.z)>(if(state.inWater)0.12 else 0.2) || abs(here.y-previous.y)>0.3) {
            lastPosition=here; progressTick=tick; return
        }
        if(!state.onGround && !state.inWater) { progressTick=tick; return } // knockback/airborne is not stuck
        if(tick-progressTick<if(state.inWater)40 else 20) return
        failures++; route.firstOrNull()?.let { excluded[it.position.block]=tick+60 }; route.clear(); search=null
        held=failures>=3; progressTick=tick; lastRevision=context.world.revision
        val yaw=Math.toRadians(state.yaw.toDouble())
        val options=listOf(Key.Type.KEY_A to NavVec(here.x+cos(yaw)*0.65,here.y,here.z+sin(yaw)*0.65),
            Key.Type.KEY_D to NavVec(here.x-cos(yaw)*0.65,here.y,here.z-sin(yaw)*0.65),
            Key.Type.KEY_S to NavVec(here.x+sin(yaw)*0.65,here.y,here.z-cos(yaw)*0.65))
        val recovery=options.firstOrNull { geometry.walk(here,it.second,emptyMap()) }
        recoveryKeys=if(recovery!=null && !held)setOf(recovery.first) else if(state.inWater)setOf(Key.Type.KEY_SPACE) else emptySet()
        recoveryUntil=tick+5; context.move(recoveryKeys)
    }
}
