package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.navigation.*
import kotlin.math.*

/** Plans once per physics tick; combat interrupts before pending actions advance. */
class CombatNavigator(private val context: NavigationContext) {
    private var search: IncrementalPathfinder? = null
    private var goal: NavigationGoal? = null
    private var plannedGoal: NavigationGoal? = null
    private var targetId: java.util.UUID? = null
    private val route = ArrayDeque<RouteStep>()
    private var preview = false
    private var action: BlockAction? = null
    private var lastTick = -1
    private var lastPlanTick = -100
    private var lastRevision = -1L
    private var lastPosition: NavVec? = null
    private var progressTick = 0
    private var failures = 0
    private var retryTick = 0
    private var allowEdits = true
    private var localDestination: NavVec? = null
    private val excluded = mutableMapOf<BlockPos, Int>()
    private val budgetOwner = Any()
    var ownsMovement = false; private set
    var steeringYaw: Float? = null; private set
    var preparingJump = false; private set
    val ownsInteraction get() = action != null
    var diagnostic = "idle"; private set
    var searchesStarted = 0; private set
    var searchNanos = 0L; private set
    var firstMovementDelayTicks = -1; private set
    var firstRouteDelayTicks = -1; private set
    var failedActions = 0; private set
    var lastActionFailure = ""; private set
    private var navigationStartedTick = -1

    fun pause() {
        if (ownsMovement || action != null) context.cancelAction()
        context.maintainBuoyancy(false)
        context.searchBudget.cancel(budgetOwner)
        search = null; route.clear(); action = null; preview = false
        ownsMovement = false; steeringYaw = null; preparingJump = false
        lastPosition = null; localDestination = null; lastTick = -1
        goal = null; plannedGoal = null; retryTick = 0; failures = 0
        navigationStartedTick = -1
    }
    fun reset() { pause(); targetId = null; excluded.clear() }

    private fun combat(state: NavigationState): Boolean {
        if (ownsMovement || action != null) context.cancelAction()
        context.searchBudget.cancel(budgetOwner)
        search = null; route.clear(); action = null; preview = false; localDestination = null
        ownsMovement = false; failures = 0; retryTick = 0; navigationStartedTick = -1
        context.maintainBuoyancy(state.inWater)
        diagnostic = "combat"
        return false
    }

    fun update(tick: Int, target: NavVec?, range: Double, lineOfSight: Boolean, canUseItems: Boolean,
               combatState: NavigationCombatState = NavigationCombatState()): Boolean {
        if (tick == lastTick) return ownsMovement
        lastTick = tick
        steeringYaw = null; preparingJump = false
        val state = context.state()
        val here = state.position
        val geometry = NavigationGeometry(context.world, state.width, state.height)
        if (!state.inWater) context.maintainBuoyancy(false)
        if (combatState.targetId != targetId) {
            context.searchBudget.cancel(budgetOwner)
            if (ownsMovement || action != null) context.cancelAction()
            search = null; route.clear(); action = null; preview = false; localDestination = null
            goal = null; plannedGoal = null; failures = 0; retryTick = 0
            targetId = combatState.targetId
        }
        lastPosition?.let { previous ->
            if (hypot(here.x-previous.x, here.z-previous.z)>3.0 || abs(here.y-previous.y)>3.0) {
                context.searchBudget.cancel(budgetOwner)
                context.cancelAction(); action = null; search = null; route.clear(); preview = false
                failures = 0; retryTick = 0; localDestination = null
                lastPosition = here; progressTick = tick
            }
        }
        if (state.inWater && state.air<80 && geometry.clear(NavVec(here.x,here.y+0.4,here.z),emptyMap())) {
            context.searchBudget.cancel(budgetOwner)
            if (action != null) context.cancelAction()
            action = null; search = null; route.clear(); preview = false
            context.move(setOf(Key.Type.KEY_SPACE)); ownsMovement = true
            diagnostic = "surfacing"; progressTick = tick
            return true
        }
        if (target == null) {
            if (ownsMovement || action != null) context.cancelAction()
            action = null; search = null; route.clear(); goal = null; plannedGoal = null
            context.searchBudget.cancel(budgetOwner)
            ownsMovement = state.inWater
            if (state.inWater) context.move(setOf(Key.Type.KEY_SPACE))
            diagnostic = if (state.inWater) "floating" else "idle"
            return ownsMovement
        }
        val distance = sqrt((here.x-target.x).pow(2)+(here.y-target.y).pow(2)+(here.z-target.z).pow(2))
        if (lineOfSight && distance<=max(3.0,range) && abs(here.y-target.y)<1.0) return combat(state)
        if (!state.inWater && !state.onGround && action == null && !ownsMovement &&
            (combatState.protected || combatState.preserveOpenMovement)) return combat(state)
        // Open combat pursuit checks a short supported corridor, including targets currently airborne.
        val scale = (2.0/hypot(target.x-here.x,target.z-here.z).coerceAtLeast(0.01)).coerceAtMost(1.0)
        val pursuitEnd = if(combatState.preserveOpenMovement && lineOfSight)
            NavVec(here.x+(target.x-here.x)*scale,here.y,here.z+(target.z-here.z)*scale) else target
        val openPursuit = !state.inWater && abs(here.y-target.y)<=1.25 &&
            geometry.clear(target,emptyMap()) && geometry.walk(here,pursuitEnd,emptyMap())
        if (openPursuit && (combatState.preserveOpenMovement || combatState.protected)) return combat(state)

        if (navigationStartedTick<0) { navigationStartedTick = tick; firstMovementDelayTicks = -1; firstRouteDelayTicks = -1 }
        if (lastPosition == null) { lastPosition = here; progressTick = tick }
        excluded.entries.removeIf { tick>=it.value }
        goal = NavigationGoal(target,range.coerceIn(0.3,3.0))
        if (allowEdits != canUseItems) {
            context.searchBudget.cancel(budgetOwner)
            if (action != null) { context.cancelAction(); action = null }
            search = null
            if (!canUseItems && route.any { it.actions.isNotEmpty() }) route.clear()
            allowEdits = canUseItems; retryTick = 0
        }
        if (lastRevision != context.world.revision) {
            localDestination = null; retryTick = 0; lastRevision = context.world.revision
        }
        if (action != null) {
            context.tickAction()
            when (context.actionStatus()) {
                ActionStatus.CONFIRMED -> {
                    val completed = action!!
                    context.cancelAction(); action = null; search = null
                    val step = route.removeFirstOrNull()
                    if (step != null) route.addFirst(step.copy(actions = step.actions.filterNot { it == completed }))
                    progressTick = tick; lastPosition = here; failures = 0
                }
                ActionStatus.FAILED,ActionStatus.IDLE -> {
                    val reason = context.actionDiagnostic
                    failedActions++; lastActionFailure = reason
                    excluded[action!!.pos] = tick+100
                    context.cancelAction(); action = null; route.clear(); search = null
                    diagnostic = "action failed: "+reason; retry(tick)
                }
                else -> { ownsMovement = true; diagnostic = "block action: "+context.actionDiagnostic; return true }
            }
        }
        if (openPursuit) {
            context.searchBudget.cancel(budgetOwner)
            route.clear(); search = null
            drive(tick,state,target,false,false); diagnostic = "direct"
            checkProgress(tick,state)
            return true
        }
        consumeReached(tick,state)
        val changedGoal = plannedGoal?.let {
            hypot(it.position.x-target.x,it.position.z-target.z)>2.0 || abs(it.position.y-target.y)>2.0
        } ?: true
        if (changedGoal && tick-lastPlanTick>=4) search = null
        if (tick>=retryTick && (search != null || route.isEmpty() || changedGoal && tick-lastPlanTick>=4)) {
            advanceSearch(tick,state,geometry,canUseItems)
            consumeReached(tick,state)
        }
        if (route.isEmpty()) {
            localMove(tick,state,target,geometry)
            if (tick<retryTick) diagnostic = "retrying in "+(retryTick-tick)+" ticks"
            checkProgress(tick,state)
            return true
        }
        val step = route.first()
        if (step.actions.isNotEmpty()) {
            if (!state.onGround && !state.inWater) {
                context.move(emptySet()); ownsMovement = true; diagnostic = "landing"; return true
            }
            val nextAction = step.actions.first()
            val live = context.world.block(nextAction.pos)
            val permitted = if (nextAction.kind == BlockActionKind.PLACE)
                context.world.canPlace(nextAction.pos) && live?.replaceable == true
            else live != null && live.id == nextAction.blockId && context.world.canBreak(nextAction.pos,live)
            if (!canUseItems || !permitted) {
                context.searchBudget.cancel(budgetOwner)
                route.clear(); search = null; localDestination = null
                localMove(tick,state,target,geometry)
                return true
            }
            context.move(emptySet()); action = nextAction
            context.requestAction(nextAction); context.tickAction()
            ownsMovement = true; diagnostic = "block action: "+context.actionDiagnostic
            return true
        }
        if (!geometry.clear(step.position,emptyMap()) ||
            step.traversal in setOf(Traversal.WALK,Traversal.STEP,Traversal.DROP) && state.onGround &&
            !geometry.walk(here,step.position,emptyMap())) {
            context.searchBudget.cancel(budgetOwner)
            route.clear(); search = null; localDestination = null
            localMove(tick,state,target,geometry)
            return true
        }
        drive(tick,state,lookAhead(state,geometry),step.traversal==Traversal.JUMP,step.traversal==Traversal.SWIM)
        diagnostic = step.traversal.name
        checkProgress(tick,state)
        return true
    }

    private fun consumeReached(tick: Int,state: NavigationState) {
        while (route.isNotEmpty()) {
            val next = route.first()
            if (next.actions.isNotEmpty() || next.traversal==Traversal.TOWER && !state.onGround) break
            if (hypot(state.position.x-next.position.x,state.position.z-next.position.z)>0.4 ||
                abs(state.position.y-next.position.y)>0.3 &&
                !(next.traversal==Traversal.SWIM && state.position.y>=next.position.y-0.3)) break
            route.removeFirst(); progressTick = tick; lastPosition = state.position; failures = 0
        }
    }

    private fun advanceSearch(tick: Int,state: NavigationState,geometry: NavigationGeometry,canUseItems: Boolean) {
        if (search == null) {
            search = IncrementalPathfinder(context.world,state,goal!!,
                if (canUseItems) context.material() else null,excluded.keys.toSet(),allowEdits=canUseItems,
                actionOverheadTicks=context.actionOverheadTicks)
            plannedGoal = goal; lastPlanTick = tick; searchesStarted++
        }
        val budget = context.searchBudget.acquire(budgetOwner)
        if (budget<=0) { diagnostic = "search budget"; return }
        val start = System.nanoTime()
        val result = try { search!!.compute(budgetNanos=budget) } finally {
            val elapsed = System.nanoTime()-start
            searchNanos += elapsed; context.searchBudget.finish(budgetOwner,elapsed)
        }
        diagnostic = result.status.toString()+" ("+result.expanded+")"
        val remaining = splice(state,result.path,geometry)
        if (result.status != SearchStatus.SEARCHING) {
            search = null; context.searchBudget.cancel(budgetOwner)
            val mustReplace = route.isEmpty() || preview || route.last().position.let {
                hypot(it.x-goal!!.position.x,it.z-goal!!.position.z)>goal!!.range || abs(it.y-goal!!.position.y)>1.0
            }
            if (remaining != null && remaining.isNotEmpty() &&
                (mustReplace || remaining.sumOf { it.estimatedTicks }<route.sumOf { it.estimatedTicks }*0.8)) {
                route.clear(); route.addAll(remaining); preview = false; localDestination = null
                if (firstRouteDelayTicks<0) firstRouteDelayTicks = tick-navigationStartedTick
            } else if (route.isEmpty()) retry(tick)
        } else if (route.isEmpty() && remaining != null) {
            val closeElevatedTarget = canUseItems && goal!!.position.y>state.position.y+12.0 &&
                hypot(goal!!.position.x-state.position.x,goal!!.position.z-state.position.z)<=1.5
            // A useful, collision-checked tower prefix can start before a very high target's full batch is solved.
            val safePrefix = remaining.takeWhile { step -> step.actions.isEmpty() || closeElevatedTarget &&
                step.traversal==Traversal.TOWER && step.actions.all { it.placement==PlacementStyle.TOWER } }.take(3)
            val useful = !state.inWater || hypot(goal!!.position.x-state.position.x,goal!!.position.z-state.position.z)<1.0 ||
                safePrefix.any { hypot(it.position.x-state.position.x,it.position.z-state.position.z)>0.1 }
            if (safePrefix.isNotEmpty() && useful) {
                route.addAll(safePrefix); preview = true; localDestination = null
                if (firstRouteDelayTicks<0) firstRouteDelayTicks = tick-navigationStartedTick
            }
        }
    }

    /** A search starts before movement; skip only prefixes safely reachable from the live position. */
    private fun splice(state: NavigationState,path: List<RouteStep>,geometry: NavigationGeometry): List<RouteStep>? {
        if (path.isEmpty()) return path
        var index = 0
        for (i in path.indices) {
            val step = path[i]
            if (step.actions.isNotEmpty()) break
            val distance = hypot(state.position.x-step.position.x,state.position.z-step.position.z)
            if (distance<=0.4 && abs(state.position.y-step.position.y)<0.3) { index = i+1; continue }
            if (distance<=2.25 && safeSegment(state,step,geometry)) index = i
        }
        val result = path.drop(index)
        val first = result.firstOrNull() ?: return result
        if (first.actions.isNotEmpty()) return result.takeIf {
            hypot(state.position.x-first.position.x,state.position.z-first.position.z)<=1.8 &&
                abs(state.position.y-first.position.y)<=1.3
        }
        return result.takeIf { hypot(state.position.x-first.position.x,state.position.z-first.position.z)<=3.1 && (safeSegment(state,first,geometry) ||
            first.traversal==Traversal.JUMP && geometry.jump(state.position,first.position,emptyMap())) }
    }

    private fun safeSegment(state: NavigationState,step: RouteStep,geometry: NavigationGeometry): Boolean =
        when (step.traversal) {
            Traversal.WALK,Traversal.STEP,Traversal.DROP -> geometry.walk(state.position,step.position,emptyMap())
            Traversal.SWIM -> geometry.swim(state.position,step.position,emptyMap()) ||
                !state.inWater && geometry.enterWater(state.position,step.position,emptyMap())
            Traversal.TOWER -> hypot(state.position.x-step.position.x,state.position.z-step.position.z)<0.4 &&
                abs(state.position.y-step.position.y)<0.3 && geometry.clear(step.position,emptyMap())
            else -> false
        }

    private fun lookAhead(state: NavigationState,geometry: NavigationGeometry): NavVec {
        val first = route.first()
        var dest = first.position
        if (first.traversal !in setOf(Traversal.WALK,Traversal.SWIM)) return dest
        for (step in route.drop(1).take(3)) {
            if (step.actions.isNotEmpty() || step.traversal != first.traversal ||
                hypot(step.position.x-state.position.x,step.position.z-state.position.z)>2.5 ||
                abs(step.position.y-first.position.y)>(if(first.traversal==Traversal.SWIM) 1.25 else 0.3) || !safeSegment(state,step,geometry)) break
            route.removeFirst()
            dest = step.position
        }
        return dest
    }

    private fun localMove(tick: Int,state: NavigationState,target: NavVec,geometry: NavigationGeometry) {
        val here = state.position
        val cached = localDestination
        if (cached != null && hypot(here.x-cached.x,here.z-cached.z)>0.3 &&
            safeSegment(state,RouteStep(cached,if(state.inWater) Traversal.SWIM else Traversal.WALK),geometry)) {
            drive(tick,state,cached,false,state.inWater); return
        }
        val heading = atan2(target.z-here.z,target.x-here.x)
        localDestination = if (!state.onGround && !state.inWater ||
            hypot(here.x-target.x,here.z-target.z)<1.2 && target.y>here.y+1.25) null else
            listOf(0.0,PI/4,-PI/4,PI/2,-PI/2,PI).map { angle ->
                NavVec(here.x+cos(heading+angle)*0.8,here.y,here.z+sin(heading+angle)*0.8)
            }.firstOrNull { dest -> if(state.inWater) geometry.swim(here,dest,emptyMap()) else geometry.walk(here,dest,emptyMap()) }
        localDestination?.let { drive(tick,state,it,false,state.inWater) } ?: run {
            context.move(if(state.inWater) setOf(Key.Type.KEY_SPACE) else emptySet()); ownsMovement = true
        }
    }

    private fun retry(tick: Int) {
        failures++
        retryTick = tick+when(failures) { 1 -> 5; 2 -> 10; else -> 20 }
    }

    private fun drive(tick: Int,state: NavigationState,dest: NavVec,jump: Boolean,swim: Boolean) {
        var dx = dest.x-state.position.x; var dz = dest.z-state.position.z
        if (state.inWater) {
            val distance = hypot(dx,dz).coerceAtLeast(0.01)
            val flow = context.world.block(state.position.block)?.flow ?: NavVec(0.0,0.0,0.0)
            val ux = dx/distance; val uz = dz/distance
            // Cancel sideways drift without reversing a short upstream segment.
            val sideways = (flow.x*0.35+state.velocity.x*1.5)*(-uz)+(flow.z*0.35+state.velocity.z*1.5)*ux
            dx += uz*sideways; dz -= ux*sideways
        }
        val angle = atan2(-dx,dz)-Math.toRadians(state.yaw.toDouble())
        steeringYaw = Math.toDegrees(atan2(-dx,dz)).toFloat(); preparingJump = jump
        if (jump && state.onGround && abs(atan2(sin(angle),cos(angle)))>Math.toRadians(20.0)) {
            context.move(emptySet()); ownsMovement = true; return
        }
        val keys = mutableSetOf<Key.Type>()
        if (hypot(dx,dz)>0.10) {
            if (cos(angle)>0.38) keys.add(Key.Type.KEY_W)
            if (cos(angle)<-0.38) keys.add(Key.Type.KEY_S)
            if (sin(angle)<-0.38) keys.add(Key.Type.KEY_A)
            if (sin(angle)>0.38) keys.add(Key.Type.KEY_D)
        }
        if (!state.inWater && !swim && Key.Type.KEY_W in keys) keys.add(Key.Type.KEY_LCONTROL)
        if (jump && state.onGround || state.inWater || swim && dest.y>state.position.y+0.15) keys.add(Key.Type.KEY_SPACE)
        context.move(keys); ownsMovement = true
        if (firstMovementDelayTicks<0 && keys.any { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) })
            firstMovementDelayTicks = tick-navigationStartedTick
    }

    private fun checkProgress(tick: Int,state: NavigationState) {
        if (tick<retryTick) { progressTick = tick; return }
        if (search!=null && route.isEmpty()) { progressTick = tick; return }
        if (preparingJump || !state.onGround && !state.inWater) { progressTick = tick; return }
        val here = state.position
        if (lastPosition?.let {
                hypot(here.x-it.x,here.z-it.z)>(if(state.inWater) 0.12 else 0.2) || abs(here.y-it.y)>0.3
            } != false) { lastPosition = here; progressTick = tick; return }
        if (tick-progressTick<(if(state.inWater) 20 else 12)) return
        route.firstOrNull()?.let { excluded[it.position.block] = tick+60 }
        context.searchBudget.cancel(budgetOwner)
        route.clear(); search = null; localDestination = null
        retry(tick); progressTick = tick
    }
}
