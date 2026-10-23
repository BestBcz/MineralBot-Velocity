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
    private val failedJumps = mutableMapOf<JumpEdge, Int>()
    private var jump: JumpTraversal? = null
    private var drop: DropTraversal? = null
    private var lastDropTick=-1
    private var profile = NavigationProfile.DEFAULT
    private var progressDestination: NavVec? = null
    private var bestRemaining = Double.POSITIVE_INFINITY
    private val budgetOwner = Any()
    var ownsMovement = false; private set
    var steeringYaw: Float? = null; private set
    var preparingJump = false; private set
    val ownsInteraction get() = action != null
    val ownsAim get() = ownsInteraction || context.ownsAim
    var diagnostic = "idle"; private set
    var searchesStarted = 0; private set
    var searchNanos = 0L; private set
    var firstMovementDelayTicks = -1; private set
    var firstRouteDelayTicks = -1; private set
    var failedActions = 0; private set
    var lastActionFailure = ""; private set
    private var navigationStartedTick = -1
    private var blockedTicks = 0
    private var lastBlockedTick = -1
    private var interactionTargets = emptySet<BlockPos>()
    private var confirmedBridge: BlockPos? = null
    private var escapingFire = false

    fun pause() {
        if (ownsMovement || ownsAim) context.cancelAction()
        context.maintainBuoyancy(false)
        context.searchBudget.cancel(budgetOwner)
        search = null; route.clear(); action = null; preview = false
        ownsMovement = false; steeringYaw = null; preparingJump = false
        lastPosition = null; localDestination = null; lastTick = -1
        goal = null; plannedGoal = null; retryTick = 0; failures = 0
        navigationStartedTick = -1
        blockedTicks = 0; lastBlockedTick = -1; interactionTargets = emptySet()
        confirmedBridge=null
        escapingFire=false
        jump=null; drop=null; progressDestination=null; bestRemaining=Double.POSITIVE_INFINITY
    }
    fun reset() { pause(); targetId = null; excluded.clear(); failedJumps.clear() }

    private fun combat(state: NavigationState, resetDelay: Boolean = true): Boolean {
        if (ownsMovement || ownsAim) context.cancelAction()
        context.searchBudget.cancel(budgetOwner)
        search = null; route.clear(); action = null; preview = false; localDestination = null
        confirmedBridge=null
        jump=null; drop=null; progressDestination=null
        ownsMovement = false; failures = 0; retryTick = 0; navigationStartedTick = -1
        context.maintainBuoyancy(state.inWater)
        diagnostic = "combat"
        if (resetDelay) { blockedTicks = 0; lastBlockedTick = -1 }
        return false
    }

    fun update(tick: Int, target: NavVec?, range: Double, lineOfSight: Boolean, canUseItems: Boolean,
               combatState: NavigationCombatState = NavigationCombatState(),
               interactionBlocks: Set<BlockPos> = emptySet(),
               profile: NavigationProfile = NavigationProfile.DEFAULT,
               preferredBridgeHeight: Double? = null): Boolean {
        if (tick == lastTick) return ownsMovement
        lastTick = tick
        steeringYaw = null; preparingJump = false
        val state = context.state()
        val here = state.position
        val safeGeometry = NavigationGeometry(context.world, state.width, state.height)
        val needsEscape = safeGeometry.touchingFire(here) || escapingFire &&
            !(safeGeometry.safeFooting(here) && (state.onGround || state.inWater))
        if (needsEscape != escapingFire) {
            // Discard the combat route and its retry delay on entry, and the fire
            // exemption on exit. Utility goals still retain their normal priority.
            reset()
            lastTick = tick
            escapingFire = needsEscape
        }
        val geometry = if (escapingFire) NavigationGeometry(context.world, state.width, state.height, ignoreFire=true) else safeGeometry
        val destination = if (escapingFire) here else target
        val editsAllowed = canUseItems && !escapingFire
        if (!state.inWater) context.maintainBuoyancy(false)
        if (combatState.targetId != targetId || interactionTargets != interactionBlocks || this.profile!=profile) {
            context.searchBudget.cancel(budgetOwner)
            if (ownsMovement || action != null) context.cancelAction()
            search = null; route.clear(); action = null; preview = false; localDestination = null
            goal = null; plannedGoal = null; failures = 0; retryTick = 0
            targetId = combatState.targetId
            interactionTargets = interactionBlocks.toSet(); blockedTicks = 0; lastBlockedTick = -1
            this.profile=profile; jump=null; progressDestination=null
        }
        lastPosition?.let { previous ->
            if (hypot(here.x-previous.x, here.z-previous.z)>3.0 ||
                abs(here.y-previous.y)>3.0 && !(drop!=null && here.y<previous.y)) {
                context.searchBudget.cancel(budgetOwner)
                context.cancelAction(); action = null; search = null; route.clear(); preview = false
                failures = 0; retryTick = 0; localDestination = null
                lastPosition = here; progressTick = tick
                jump=null; drop=null; progressDestination=null
            }
        }
        if(lastPosition==null) progressTick=tick
        lastPosition=here
        if (state.inWater && state.air<80 && geometry.clear(NavVec(here.x,here.y+0.4,here.z),emptyMap())) {
            context.searchBudget.cancel(budgetOwner)
            if (action != null) context.cancelAction()
            action = null; search = null; route.clear(); preview = false
            context.move(setOf(Key.Type.KEY_SPACE)); ownsMovement = true
            diagnostic = "surfacing"; progressTick = tick
            return true
        }
        if (destination == null) {
            if (ownsMovement || action != null) context.cancelAction()
            action = null; search = null; route.clear(); goal = null; plannedGoal = null
            jump=null; progressDestination=null
            context.searchBudget.cancel(budgetOwner)
            ownsMovement = state.inWater
            if (state.inWater) context.move(setOf(Key.Type.KEY_SPACE))
            diagnostic = if (state.inWater) "floating" else "idle"
            blockedTicks = 0; lastBlockedTick = -1
            return ownsMovement
        }
        if(drop!=null && !validLanding(drop!!.to,geometry)) {
            drop=null; route.clear(); search=null; localDestination=null
            context.searchBudget.cancel(budgetOwner)
        }
        val distance = sqrt((here.x-destination.x).pow(2)+(here.y-destination.y).pow(2)+(here.z-destination.z).pow(2))
        val entityPursuit = interactionBlocks.isEmpty() && !escapingFire
        if(entityPursuit && !state.inWater && (combatState.protected || combatState.preserveOpenMovement) &&
            hypot(here.x-destination.x,here.z-destination.z)<=NavigationTuning.COMBAT_TERRAIN_RANGE) {
            // Compare the ground below both players, rather than their transient jump/knockback heights.
            fun footing(p: NavVec)=geometry.surfaces(p.x,p.z,p.y,emptyMap(),0.05).firstOrNull {
                p.y-it in -0.05..1.5 && geometry.clear(p.copy(y=it),emptyMap())
            }?.let { p.copy(y=it) }
            val selfGround=footing(here); val targetGround=footing(destination)
            if(selfGround!=null && targetGround!=null && (geometry.walk(selfGround,targetGround,emptyMap()) ||
                hypot(here.x-destination.x,here.z-destination.z)<=1.5 && geometry.jump(selfGround,targetGround,emptyMap()))) return combat(state)
            val plannedTakeoff=jump!=null || action?.placement in setOf(PlacementStyle.TOWER,PlacementStyle.STEP)
            if(!state.onGround && !plannedTakeoff) return combat(state)
        }
        if (entityPursuit && lineOfSight && distance<=max(NavigationTuning.COMBAT_MIN_RANGE,range) &&
            abs(here.y-destination.y)<=NavigationTuning.COMBAT_HEIGHT &&
            geometry.clearSegment(here,destination,emptyMap()) &&
            (geometry.walk(here,destination,emptyMap()) ||
                abs(here.y-destination.y)<=0.6 && geometry.walk(here,NavVec(destination.x,here.y,destination.z),emptyMap()))) return combat(state)
        if (!state.inWater && !state.onGround && action == null && !ownsMovement &&
            entityPursuit && (combatState.protected || combatState.preserveOpenMovement)) return combat(state)
        if(!state.inWater && !state.onGround && action==null && jump==null && drop==null) {
            context.continueBridge(null); context.move(emptySet()); ownsMovement=true
            diagnostic="landing"; return true
        }
        // Open combat pursuit checks a short supported corridor, including targets currently airborne.
        val scale = (2.0/hypot(destination.x-here.x,destination.z-here.z).coerceAtLeast(0.01)).coerceAtMost(1.0)
        val pursuitEnd = if(combatState.preserveOpenMovement && lineOfSight)
            NavVec(here.x+(destination.x-here.x)*scale,here.y,here.z+(destination.z-here.z)*scale) else destination
        val openPursuit = entityPursuit && !state.inWater && destination.y>=here.y-0.05 && abs(here.y-destination.y)<=1.25 &&
            geometry.clear(destination,emptyMap()) && geometry.walk(here,pursuitEnd,emptyMap())
        if (openPursuit && (combatState.preserveOpenMovement || combatState.protected)) return combat(state)

        if (entityPursuit && !state.inWater && !ownsMovement && action == null &&
            (combatState.protected || combatState.preserveOpenMovement)) {
            blockedTicks = if (lastBlockedTick == tick-1) blockedTicks+1 else 1
            lastBlockedTick = tick
            if (blockedTicks<NavigationTuning.TAKEOVER_TICKS) return combat(state,resetDelay=false)
        }

        if (navigationStartedTick<0) { navigationStartedTick = tick; firstMovementDelayTicks = -1; firstRouteDelayTicks = -1 }
        excluded.entries.removeIf { tick>=it.value }
        failedJumps.entries.removeIf { tick>=it.value }
        goal = NavigationGoal(destination,if(entityPursuit) range.coerceIn(0.3,3.0) else BlockInteraction.REACH,
            if(escapingFire) emptySet() else interactionBlocks,preferredBridgeHeight)
        val changedGoal = !escapingFire && (plannedGoal?.let {
            hypot(it.position.x-destination.x,it.position.z-destination.z)>2.0 || abs(it.position.y-destination.y)>2.0 ||
                it.interactionBlocks!=interactionBlocks
        } ?: false)
        val changedHeight=plannedGoal?.let {
            val previous=it.preferredBridgeHeight
            previous!=preferredBridgeHeight && (previous==null || preferredBridgeHeight==null || abs(previous-preferredBridgeHeight)>=1.0)
        } ?: false
        if((changedGoal || changedHeight && action==null) && tick-lastPlanTick>=4) {
            context.searchBudget.cancel(budgetOwner)
            if(action!=null) context.cancelAction()
            action=null; route.clear(); search=null; jump=null; localDestination=null; progressDestination=null
        }
        if (allowEdits != editsAllowed) {
            context.searchBudget.cancel(budgetOwner)
            if (action != null) { context.cancelAction(); action = null }
            search = null
            if (!editsAllowed && route.any { it.actions.isNotEmpty() }) route.clear()
            jump=null
            allowEdits = editsAllowed; retryTick = 0
        }
        if (lastRevision != context.world.revision) {
            // Frontier nodes carry virtual edits and cannot survive arbitrary support changes.
            search=null; context.searchBudget.cancel(budgetOwner)
            localDestination = null; retryTick = 0; lastRevision = context.world.revision
            if (escapingFire) {
                // The chosen exit may itself have caught fire since it was planned.
                route.clear(); jump=null; drop=null; progressDestination=null
            }
        }
        if (action != null) {
            context.tickAction()
            when (context.actionStatus()) {
                ActionStatus.CONFIRMED -> {
                    val completed = action!!
                    confirmedBridge=completed.pos.takeIf { completed.kind==BlockActionKind.PLACE && completed.placement==PlacementStyle.BRIDGE }
                    context.completeAction(); action = null; search = null
                    val step = route.removeFirstOrNull()
                    if (step != null) route.addFirst(step.copy(actions = step.actions.filterNot { it == completed }))
                    progressTick = tick; lastPosition = here; failures = 0
                    if(changedHeight && tick-lastPlanTick>=4) {
                        context.searchBudget.cancel(budgetOwner)
                        route.clear(); search=null; preview=false; jump=null; localDestination=null; progressDestination=null
                    }
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
        if (!escapingFire && !entityPursuit && editsAllowed && tick>=retryTick) {
            val next = BlockInteraction.findAction(context.world,state,interactionBlocks,excluded=excluded.keys)
            if (next!=null) {
                context.searchBudget.cancel(budgetOwner)
                search=null; route.clear(); preview=false; localDestination=null
                route.addFirst(RouteStep(here,Traversal.WALK,listOf(next)))
                context.move(emptySet()); action=next
                context.requestAction(next); context.tickAction()
                ownsMovement=true; diagnostic="bed action: "+context.actionDiagnostic
                return true
            }
        }
        if(drop!=null && lastDropTick==tick-1 && validLanding(drop!!.to,geometry)) {
            driveDrop(tick,state,drop!!.to,geometry)
            return true
        }
        if (openPursuit) {
            context.searchBudget.cancel(budgetOwner)
            route.clear(); search = null
            jump=null
            drive(tick,state,destination,false,false); diagnostic = "direct"
            checkProgress(tick,state)
            return true
        }
        consumeReached(tick,state)
        // Finish the committed segment before replacing it or rebasing the next search.
        if (tick>=retryTick && route.isEmpty()) {
            advanceSearch(tick,state,geometry,editsAllowed)
            consumeReached(tick,state)
        }
        if (route.isEmpty()) {
            localMove(tick,state,destination,geometry)
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
                context.world.canPlace(nextAction.pos) && live?.replaceable == true &&
                    (context.material()?.let { it.count>0 && it.id==nextAction.blockId && it.metadata==nextAction.metadata }==true) &&
                    listOf(nextAction.pos.offset(1,0,0),nextAction.pos.offset(-1,0,0),nextAction.pos.offset(0,0,1),
                        nextAction.pos.offset(0,0,-1),nextAction.pos.offset(0,-1,0)).any { context.world.block(it)?.boxes?.isNotEmpty()==true } &&
                    placementClear(state,step,nextAction,geometry)
            else live != null && live.id == nextAction.blockId && context.world.canBreak(nextAction.pos,live)
            if (!editsAllowed || !permitted) {
                context.searchBudget.cancel(budgetOwner)
                route.clear(); search = null; localDestination = null
                localMove(tick,state,destination,geometry)
                return true
            }
            context.move(emptySet()); action = nextAction
            context.requestAction(nextAction); context.tickAction()
            ownsMovement = true; diagnostic = "block action: "+context.actionDiagnostic
            return true
        }
        if (!geometry.clear(step.position,emptyMap()) ||
            step.traversal in setOf(Traversal.WALK,Traversal.STEP,Traversal.DROP) && state.onGround &&
            !geometry.walk(here,step.position,emptyMap()) &&
            !(step.traversal==Traversal.DROP && profile==NavigationProfile.BEDFIGHT && geometry.drop(here,step.position,emptyMap()))) {
            context.searchBudget.cancel(budgetOwner)
            route.clear(); search = null; localDestination = null
            localMove(tick,state,destination,geometry)
            return true
        }
        if(step.traversal==Traversal.JUMP) {
            driveJump(tick,state,step.position)
            return true
        }
        if(step.traversal==Traversal.DROP) {
            driveDrop(tick,state,step.position,geometry)
            return true
        }
        drop=null
        jump=null
        drive(tick,state,lookAhead(state,geometry),false,step.traversal==Traversal.SWIM)
        diagnostic = if(escapingFire) "escaping fire: "+step.traversal.name else step.traversal.name
        checkProgress(tick,state)
        return true
    }

    private fun placementClear(state: NavigationState,step: RouteStep,action: BlockAction,geometry: NavigationGeometry): Boolean {
        val p=action.pos
        val placed=mapOf(p to NavBlock(action.blockId,action.metadata,listOf(
            NavBox(p.x.toDouble(),p.y.toDouble(),p.z.toDouble(),p.x+1.0,p.y+1.0,p.z+1.0))))
        if(!geometry.clear(step.position,placed)) return false
        return when {
            action.placement==PlacementStyle.TOWER -> geometry.clear(step.position.copy(y=step.position.y+0.25),emptyMap())
            step.traversal==Traversal.JUMP -> geometry.jump(state.position,step.position,placed)
            else -> geometry.walk(state.position,step.position,placed)
        }
    }

    private fun consumeReached(tick: Int,state: NavigationState) {
        while (route.isNotEmpty()) {
            val next = route.first()
            if (next.actions.isNotEmpty() || next.traversal in setOf(Traversal.TOWER,Traversal.JUMP,Traversal.DROP) && !state.onGround) break
            val threshold=if(escapingFire) 0.1 else if(next.traversal==Traversal.DROP) 0.65 else
                if(confirmedBridge?.let { next.position==NavVec(it.x+0.5,it.y+1.0,it.z+0.5) }==true) 0.6 else 0.4
            if (hypot(state.position.x-next.position.x,state.position.z-next.position.z)>threshold ||
                abs(state.position.y-next.position.y)>0.3 &&
                !(next.traversal==Traversal.SWIM && state.position.y>=next.position.y-0.3)) break
            route.removeFirst(); confirmedBridge=null; progressTick = tick; lastPosition = state.position; failures = 0
            jump=null; progressDestination=null
        }
        if(route.isEmpty() && preview) { search=null; context.searchBudget.cancel(budgetOwner); preview=false }
    }

    private fun advanceSearch(tick: Int,state: NavigationState,geometry: NavigationGeometry,canUseItems: Boolean) {
        if (search == null) {
            search = IncrementalPathfinder(context.world,state,goal!!,
                if (canUseItems) context.material() else null,excluded.keys.toSet(),allowEdits=canUseItems,
                actionOverheadTicks=context.actionOverheadTicks,profile=if(escapingFire) NavigationProfile.DEFAULT else profile,
                failedJumps=failedJumps.keys.toSet(),escapeFire=escapingFire)
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
        val remaining = splice(state,if(result.status==SearchStatus.SEARCHING) result.executableSegment else result.path,geometry)
        if (result.status != SearchStatus.SEARCHING) {
            search = null; context.searchBudget.cancel(budgetOwner)
            val mustReplace = route.isEmpty() || preview || route.last().position.let {
                hypot(it.x-goal!!.position.x,it.z-goal!!.position.z)>goal!!.range || abs(it.y-goal!!.position.y)>1.0
            }
            if (remaining != null && remaining.isNotEmpty() &&
                (mustReplace || remaining.sumOf { it.selectionCost }<route.sumOf { it.selectionCost }*0.8)) {
                route.clear(); route.addAll(remaining); preview = false; localDestination = null
                if (firstRouteDelayTicks<0) firstRouteDelayTicks = tick-navigationStartedTick
            } else if (route.isEmpty()) retry(tick)
        } else if (route.isEmpty() && remaining != null) {
            val safePrefix = remaining.take(NavigationTuning.EXECUTABLE_PREFIX_STEPS)
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
            if (distance<=(if(escapingFire) 0.1 else 0.4) && abs(state.position.y-step.position.y)<0.3) { index = i+1; continue }
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
            Traversal.WALK,Traversal.STEP -> geometry.walk(state.position,step.position,emptyMap())
            Traversal.DROP -> geometry.walk(state.position,step.position,emptyMap()) ||
                profile==NavigationProfile.BEDFIGHT && geometry.drop(state.position,step.position,emptyMap())
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
        if (escapingFire) {
            // A speculative step could head deeper into fire. Wait for a complete exit route.
            waitForRoute(state,geometry)
            return
        }
        val here = state.position
        val cached = localDestination
        if (cached != null && hypot(here.x-cached.x,here.z-cached.z)>0.3 &&
            safeSegment(state,RouteStep(cached,if(state.inWater) Traversal.SWIM else Traversal.WALK),geometry)) {
            drive(tick,state,cached,false,state.inWater); return
        }
        val heading = atan2(target.z-here.z,target.x-here.x)
        // Search chooses detours; local waiting only advances toward the objective.
        val ahead=NavVec(here.x+cos(heading)*0.8,here.y,here.z+sin(heading)*0.8)
        val supportedAhead=if(state.onGround && !state.inWater) geometry.surfaces(ahead.x,ahead.z,here.y,emptyMap(),0.05)
            .map { ahead.copy(y=it) }.firstOrNull { geometry.walk(here,it,emptyMap()) } else null
        localDestination = if (!state.onGround && !state.inWater ||
            hypot(here.x-target.x,here.z-target.z)<1.2 && target.y>here.y+1.25) null else
            if(state.inWater) ahead.takeIf { geometry.swim(here,it,emptyMap()) } else supportedAhead
        localDestination?.let {
            if(it.y<here.y-0.01) driveDrop(tick,state,it,geometry) else drive(tick,state,it,false,state.inWater)
        } ?: run {
            waitForRoute(state,geometry)
        }
    }

    private fun validLanding(dest: NavVec,geometry: NavigationGeometry)=geometry.clear(dest,emptyMap()) &&
        !geometry.water(dest,emptyMap()) && geometry.surfaces(dest.x,dest.z,dest.y,emptyMap(),0.05).any { abs(it-dest.y)<0.06 }

    private fun driveDrop(tick: Int,state: NavigationState,dest: NavVec,geometry: NavigationGeometry) {
        context.continueBridge(null)
        val attempt=drop?.takeIf { it.to==dest && lastDropTick>=tick-1 } ?: DropTraversal(state.position,dest,tick).also { drop=it }
        lastDropTick=tick; ownsMovement=true; preparingJump=false; steeringYaw=attempt.yaw
        val phase=attempt.update(tick,state)
        if(phase==DropTraversal.Phase.FAILED || !validLanding(dest,geometry) ||
            attempt.from.y-dest.y>3.0 && !geometry.supportedFootprint(dest)) {
            route.clear(); search=null; localDestination=null; drop=null
            context.searchBudget.cancel(budgetOwner); context.move(emptySet()); steeringYaw=null
            diagnostic="drop interrupted"; progressTick=tick; return
        }
        if(phase==DropTraversal.Phase.LANDED) {
            consumeReached(tick,state); context.move(emptySet()); drop=null; localDestination=null; progressTick=tick; return
        }
        // Use movement keys to brake in the air while preserving the original descent yaw.
        val dx=dest.x-state.position.x-state.velocity.x*2
        val dz=dest.z-state.position.z-state.velocity.z*2
        val angle=atan2(-dx,dz)-Math.toRadians(state.yaw.toDouble())
        val keys=mutableSetOf<Key.Type>()
        if(hypot(dx,dz)>0.12) {
            if(cos(angle)>0.38) keys.add(Key.Type.KEY_W)
            if(cos(angle)< -0.38) keys.add(Key.Type.KEY_S)
            if(sin(angle)< -0.38) keys.add(Key.Type.KEY_A)
            if(sin(angle)>0.38) keys.add(Key.Type.KEY_D)
        }
        context.move(keys); diagnostic="drop "+phase.name
    }

    private fun waitForRoute(state: NavigationState,geometry: NavigationGeometry) {
        context.continueBridge(state.position)
        val predicted=state.position.copy(x=state.position.x+state.velocity.x*2,z=state.position.z+state.velocity.z*2)
        val edge=state.onGround && (!geometry.supportedFootprint(state.position) || !geometry.supportedFootprint(predicted))
        context.move(when { state.inWater -> setOf(Key.Type.KEY_SPACE); edge -> setOf(Key.Type.KEY_LSHIFT); else -> emptySet() })
        ownsMovement=true
    }

    private fun retry(tick: Int) {
        failures++
        retryTick = tick+when(failures) { 1 -> 5; 2 -> 10; else -> 20 }
    }

    private fun driveJump(tick: Int,state: NavigationState,dest: NavVec) {
        context.continueBridge(null)
        val attempt=jump?.takeIf { it.to==dest } ?: JumpTraversal(state.position,dest,tick).also { jump=it }
        val yaw=Math.toDegrees(atan2(-(dest.x-state.position.x),dest.z-state.position.z)).toFloat()
        val error=Math.toRadians((yaw-state.yaw).toDouble())
        val phase=attempt.update(tick,state,abs(atan2(sin(error),cos(error)))<=Math.toRadians(20.0))
        preparingJump=true; steeringYaw=yaw; ownsMovement=true
        when(phase) {
            JumpTraversal.Phase.FAILED -> {
                failedJumps[JumpEdge(attempt.from.block,dest.block)]=tick+NavigationTuning.FAILED_JUMP_TICKS
                context.searchBudget.cancel(budgetOwner)
                route.clear(); search=null; jump=null; localDestination=null; progressDestination=null
                preparingJump=false; steeringYaw=null
                waitForRoute(state,NavigationGeometry(context.world,state.width,state.height))
                diagnostic="jump failed"; progressTick=tick
            }
            JumpTraversal.Phase.PREPARING -> { context.move(emptySet()); diagnostic="jump preparing" }
            JumpTraversal.Phase.LANDED -> { consumeReached(tick,state); context.move(emptySet()) }
            else -> {
                drive(tick,state,dest,false,false)
                if(phase==JumpTraversal.Phase.TAKEOFF) context.move(setOf(Key.Type.KEY_W,Key.Type.KEY_LCONTROL,Key.Type.KEY_SPACE))
                preparingJump=true; diagnostic="jump "+phase.name
            }
        }
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
        val backwardBridge=context.continueBridge(if(jump || swim || state.inWater) null else dest)
        val angle = atan2(-dx,dz)-Math.toRadians(state.yaw.toDouble())
        steeringYaw = if(backwardBridge) null else Math.toDegrees(atan2(-dx,dz)).toFloat(); preparingJump = jump
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
        if (!backwardBridge && !state.inWater && !swim && Key.Type.KEY_W in keys) keys.add(Key.Type.KEY_LCONTROL)
        if (jump && state.onGround || state.inWater || swim && dest.y>state.position.y+0.15) keys.add(Key.Type.KEY_SPACE)
        context.move(keys); ownsMovement = true
        if (firstMovementDelayTicks<0 && keys.any { it in setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D) })
            firstMovementDelayTicks = tick-navigationStartedTick
    }

    private fun checkProgress(tick: Int,state: NavigationState) {
        if (tick<retryTick) { progressTick = tick; return }
        if (search!=null && route.isEmpty()) { progressTick = tick; return }
        if (jump!=null || drop!=null) return // Bounded independently, including preparation and airborne time.
        val here = state.position
        val destination=route.firstOrNull()?.position ?: localDestination ?: goal?.position ?: return
        val remaining=hypot(here.x-destination.x,here.z-destination.z)+abs(here.y-destination.y)
        if(progressDestination!=destination) {
            progressDestination=destination; bestRemaining=remaining; progressTick=tick
        } else if(remaining<bestRemaining-(if(state.inWater) 0.12 else 0.2)) {
            bestRemaining=remaining; progressTick=tick
        }
        lastPosition=here
        if (tick-progressTick<(if(state.inWater) 20 else 12)) return
        route.firstOrNull()?.let { excluded[it.position.block] = tick+60 }
        context.searchBudget.cancel(budgetOwner)
        route.clear(); search = null; localDestination = null
        progressDestination=null
        retry(tick); progressTick = tick
    }
}
