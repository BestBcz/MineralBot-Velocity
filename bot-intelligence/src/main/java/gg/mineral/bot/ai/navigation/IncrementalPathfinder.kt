package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import java.util.PriorityQueue
import kotlin.math.*

enum class Traversal { WALK, STEP, JUMP, DROP, SWIM, TOWER }
data class RouteStep(val position: NavVec, val traversal: Traversal, val actions: List<BlockAction> = emptyList(),
                     val estimatedTicks: Double = 0.0, val selectionCost: Double = estimatedTicks)
enum class SearchStatus { SEARCHING, SUCCESS, PARTIAL, NO_PATH }
data class RouteResult(val status: SearchStatus, val path: List<RouteStep>, val expanded: Int,
                       val executableSegment: List<RouteStep> = path.take(NavigationTuning.EXECUTABLE_PREFIX_STEPS))
data class NavigationGoal(val position: NavVec, val range: Double = 2.5,
                          val interactionBlocks: Set<BlockPos> = emptySet(),
                          val preferredBridgeHeight: Double? = null)

class IncrementalPathfinder(private val world: NavigationWorld, start: NavigationState,
    private val goal: NavigationGoal, private val material: BuildingMaterial? = null,
    private val excluded: Set<BlockPos> = emptySet(), private val nanoTime: () -> Long = System::nanoTime,
    private val allowEdits: Boolean = true, private val actionOverheadTicks: Double = 4.0,
    private val terrainCostFactor: Double = NavigationTuning.TERRAIN_COST_FACTOR,
    private val profile: NavigationProfile = NavigationProfile.DEFAULT,
    private val failedJumps: Set<JumpEdge> = emptySet(), private val escapeFire: Boolean = false) {
    private data class Key(val x: Int,val y: Int,val z: Int,val edits: Map<BlockPos,NavBlock>,val remaining: Int)
    private data class Node(val step: RouteStep,val edits: Map<BlockPos,NavBlock>,val remaining: Int,
        val cost: Double,val heuristic: Double,val parent: Node?)
    private val geometry=NavigationGeometry(world,start.width,start.height,ignoreFire=escapeFire)
    private val safeGeometry=if(escapeFire) NavigationGeometry(world,start.width,start.height) else geometry
    private val startState=start
    private val origin=start.position
    private val bedDistance=hypot(origin.x-goal.position.x,origin.z-goal.position.z)
    private val climbStage=!escapeFire && profile==NavigationProfile.BEDFIGHT && goal.preferredBridgeHeight?.let { it>origin.y+0.6 }==true && bedDistance>6.0
    // Solve a bounded advancing climb before resuming the low bed objective.
    // A height-only heuristic toward a distant bed can otherwise prefer a long flat bridge.
    private val guidance=if(climbStage) {
        val scale=min(6.0,bedDistance-6.0)/bedDistance
        NavVec(origin.x+(goal.position.x-origin.x)*scale,goal.preferredBridgeHeight!!,
            origin.z+(goal.position.z-origin.z)*scale)
    } else goal.position
    private val horizontalEstimate=if(start.inWater && geometry.water(goal.position,emptyMap())) 10.0 else 3.5
    private val open=PriorityQueue<Node>(compareBy<Node> { it.cost+it.heuristic }.thenBy { it.heuristic })
    private val costs=HashMap<Key,Double>()
    private var best=Node(RouteStep(origin,Traversal.WALK),emptyMap(),material?.count?:0,0.0,h(origin),null)
    private var expanded=0
    private var complete: RouteResult?=null
    private val existingOpen=PriorityQueue<Node>(compareBy<Node> { it.cost+it.heuristic })
    private val existingCosts=HashMap<Key,Double>()
    private var existingExpanded=0
    private var existingChecked=escapeFire || profile!=NavigationProfile.BEDFIGHT || !allowEdits || material==null
    init {
        open.add(best); costs[key(best)]=0.0
        if(!existingChecked) { existingOpen.add(best); existingCosts[key(best)]=0.0 }
    }
    // A practical vertical estimate keeps a close elevated target from expanding every ground-level edit combination.
    private fun travelHeight(p: NavVec): Double = if(profile==NavigationProfile.BEDFIGHT &&
        hypot(p.x-goal.position.x,p.z-goal.position.z)>6.0)
        max(goal.position.y,goal.preferredBridgeHeight ?: goal.position.y) else goal.position.y
    private fun h(p: NavVec): Double {
        // Dijkstra's ordering finds the least-cost safe exit, independently of the enemy.
        if(escapeFire) return 0.0
        val distance=hypot(p.x-guidance.x,p.z-guidance.z)
        val tolerance=if(climbStage) 0.05 else 1.0
        return max(0.0,distance-if(climbStage) 0.3 else goal.range)*horizontalEstimate+
            max(0.0,abs(p.y-if(climbStage) guidance.y else travelHeight(p))-tolerance)*12.0
    }
    private fun key(n: Node)=Key(round(n.step.position.x*16).toInt(),round(n.step.position.y*16).toInt(),round(n.step.position.z*16).toInt(),n.edits,n.remaining)
    private fun path(n: Node): List<RouteStep> { val result=ArrayList<RouteStep>(); var at: Node?=n
        while(at?.parent!=null) { result.add(at.step); at=at.parent }; result.reverse(); return result }

    fun compute(maxNodes: Int = 64, budgetNanos: Long = 1_000_000): RouteResult {
        complete?.let { return it }
        val started=nanoTime(); var slice=0
        // Check nearby connected terrain before publishing a construction prefix.
        // This search may step sideways to join a bridge; it shares the same slice budget.
        while(!existingChecked && slice<maxNodes && (slice==0 || nanoTime()-started<budgetNanos)) {
            val n=existingOpen.poll()
            if(n==null || existingExpanded>=NavigationTuning.EXISTING_ROUTE_NODES) { existingChecked=true; break }
            if(n.cost>(existingCosts[key(n)] ?: Double.POSITIVE_INFINITY)+1e-6) continue
            existingExpanded++; slice++
            val choices=neighbors(n,construction=false).filter { candidate ->
                val p=candidate.step.position
                p.block !in excluded && hypot(p.x-origin.x,p.z-origin.z)<=NavigationTuning.EXISTING_ROUTE_RADIUS &&
                    (candidate.step.traversal!=Traversal.JUMP || hypot(p.x-n.step.position.x,p.z-n.step.position.z)<=1.5 &&
                        JumpEdge(n.step.position.block,p.block) !in failedJumps)
            }
            val reached=choices.firstOrNull { atGoal(it) }
            if(reached!=null) return RouteResult(SearchStatus.SUCCESS,path(reached),existingExpanded).also { complete=it }
            val progress=choices.filter { candidate ->
                val p=candidate.step.position
                val horizontal=hypot(p.x-goal.position.x,p.z-goal.position.z)
                bedDistance-horizontal>=0.75 || horizontal<=bedDistance+0.5 &&
                    abs(origin.y-travelHeight(origin))-abs(p.y-travelHeight(p))>=0.5
            }.minByOrNull { it.cost+it.heuristic }
            if(progress!=null) return RouteResult(SearchStatus.PARTIAL,path(progress),existingExpanded).also { complete=it }
            for(candidate in choices) {
                val k=key(candidate)
                if(candidate.cost<(existingCosts[k] ?: Double.POSITIVE_INFINITY)-1e-6) {
                    existingCosts[k]=candidate.cost; existingOpen.add(candidate)
                }
            }
            if(existingOpen.isEmpty() || existingExpanded>=NavigationTuning.EXISTING_ROUTE_NODES) existingChecked=true
        }
        if(!existingChecked) return RouteResult(SearchStatus.SEARCHING,emptyList(),existingExpanded)
        while(open.isNotEmpty() && expanded<8192 && slice<maxNodes && (slice==0 || nanoTime()-started<budgetNanos)) {
            val n=open.poll()
            if(n.cost>(costs[key(n)]?:Double.POSITIVE_INFINITY)+1e-6) continue
            expanded++; slice++
            if(h(n.step.position)<best.heuristic) best=n
            if(atGoal(n)) return RouteResult(SearchStatus.SUCCESS,path(n),expanded).also { complete=it }
            if(climbStage && n.parent!=null && (n.step.position.y>=guidance.y-0.05 ||
                hypot(n.step.position.x-goal.position.x,n.step.position.z-goal.position.z)<=6.0))
                return RouteResult(SearchStatus.PARTIAL,path(n),expanded).also { complete=it }
            if(profile==NavigationProfile.BEDFIGHT && n.step.traversal==Traversal.DROP && origin.y-n.step.position.y>16.0)
                return RouteResult(SearchStatus.PARTIAL,path(n),expanded).also { complete=it }
            // A bounded batch is a continuation point, not completion of the objective.
            if(n.parent!=null && n.heuristic<h(origin) &&
                (n.edits.size>=NavigationTuning.MAX_TERRAIN_EDITS ||
                    hypot(n.step.position.x-origin.x,n.step.position.z-origin.z)>=NavigationTuning.SEARCH_RADIUS-1e-6))
                return RouteResult(SearchStatus.PARTIAL,path(n),expanded).also { complete=it }
            neighbors(n).forEach { candidate ->
                val p=candidate.step.position
                if(p.block in excluded || hypot(p.x-origin.x,p.z-origin.z)>NavigationTuning.SEARCH_RADIUS ||
                    abs(p.y-origin.y)>16 && !(profile==NavigationProfile.BEDFIGHT && candidate.step.traversal==Traversal.DROP && p.y<origin.y) ||
                    candidate.edits.size>NavigationTuning.MAX_TERRAIN_EDITS) return@forEach
                if(candidate.step.traversal==Traversal.JUMP && candidate.step.actions.isEmpty() &&
                    JumpEdge(n.step.position.block,p.block) in failedJumps) return@forEach
                val key=key(candidate)
                if(candidate.cost+1e-6<(costs[key]?:Double.POSITIVE_INFINITY)) {
                    costs[key]=candidate.cost; open.add(candidate)
                    // Generated edges already passed geometry, inventory and permission checks.
                    // Expose useful construction without waiting for the whole distant route.
                    if(candidate.heuristic<best.heuristic ||
                        abs(candidate.heuristic-best.heuristic)<1e-6 && candidate.cost<best.cost) best=candidate
                }
            }
        }
        if(open.isEmpty() || expanded>=8192) {
            return RouteResult(if(best.parent==null) SearchStatus.NO_PATH else SearchStatus.PARTIAL,path(best),expanded).also { complete=it }
        }
        // Speculative sideways branches may lead back to the same edge after rebasing.
        // A detour without net goal progress waits for the complete validated route.
        return RouteResult(SearchStatus.SEARCHING,path(best),expanded)
    }

    private fun atGoal(n: Node): Boolean {
        val p=n.step.position
        if(escapeFire) return safeGeometry.safeFooting(p)
        if (goal.interactionBlocks.isNotEmpty()) return BlockInteraction.findAction(world,
            startState.copy(position=p,onGround=true,inWater=false),goal.interactionBlocks,n.edits,excluded)!=null
        if(hypot(p.x-goal.position.x,p.z-goal.position.z)>goal.range || abs(p.y-goal.position.y)>1.0) return false
        if(!geometry.water(p,n.edits) && !geometry.walk(p,goal.position,n.edits) &&
            !geometry.walk(p,NavVec(goal.position.x,p.y,goal.position.z),n.edits)) return false
        // A nearby target behind a wall is not an attack position.
        val distance=hypot(p.x-goal.position.x,p.z-goal.position.z)
        val steps=max(1,ceil(distance/0.2).toInt())
        for(i in 1 until steps) {
            val t=i.toDouble()/steps
            val point=NavVec(p.x+(goal.position.x-p.x)*t,p.y+1.4+(goal.position.y-p.y)*t,p.z+(goal.position.z-p.z)*t)
            val b=geometry.block(point.block,n.edits)?:return false
            if(b.boxes.any { point.x>it.minX && point.x<it.maxX && point.y>it.minY && point.y<it.maxY && point.z>it.minZ && point.z<it.maxZ }) return false
        }
        return true
    }

    private fun neighbors(n: Node,construction: Boolean=allowEdits && !escapeFire): List<Node> {
        val result=ArrayList<Node>(); val from=n.step.position
        val inWater=geometry.water(from,n.edits)
        // Grid nodes must also reach non-centered players and short-lived remembered positions.
        val goalDistance=hypot(from.x-goal.position.x,from.z-goal.position.z)
        if(!escapeFire && goalDistance<1.5 && abs(from.y-goal.position.y)<=1.0 && geometry.clear(goal.position,n.edits)) {
            if(inWater && geometry.swim(from,goal.position,n.edits))
                result.add(child(n,goal.position,Traversal.SWIM,emptyList(),n.edits,0,swimCost(from,goal.position,n.edits)))
            else if(!inWater && geometry.walk(from,goal.position,n.edits))
                result.add(child(n,goal.position,Traversal.WALK,emptyList(),n.edits,0,goalDistance*3.5))
        }
        var forwardStair=false
        var forwardLoaded=false
        for(dx in -1..1) for(dz in -1..1) {
            if(dx==0 && dz==0) continue
            val x=floor(from.x)+0.5+dx; val z=floor(from.z)+0.5+dz
            if(inWater) {
                val dest=NavVec(x,from.y,z)
                if(geometry.swim(from,dest,n.edits)) result.add(child(n,dest,Traversal.SWIM,emptyList(),n.edits,0,swimCost(from,dest,n.edits)))
            }
            for(drop in 0..3) {
                val dest=NavVec(x,from.y-drop,z)
                if(!geometry.water(dest,n.edits)) continue
                if(geometry.clear(dest,n.edits) && (geometry.swim(from,dest,n.edits) ||
                        (drop==0 && geometry.walkToWater(from,dest,n.edits)) ||
                        (dx*dz==0 && !inWater && geometry.enterWater(from,dest,n.edits)))) {
                    result.add(child(n,dest,Traversal.SWIM,emptyList(),n.edits,0,swimCost(from,dest,n.edits)+drop*2)); break
                }
            }
            val tops=geometry.surfaces(x,z,from.y,n.edits)
            var naturalAdvance=false
            for(top in tops) {
                val dest=NavVec(x,top,z)
                if(geometry.water(dest,n.edits)) continue
                if(geometry.clear(dest,n.edits)) {
                    val walk=geometry.walk(from,dest,n.edits)
                    if(walk || (!inWater && dx*dz==0 && geometry.jump(from,dest,n.edits)) || (inWater && dx*dz==0 && top-from.y<=1.0 && geometry.swim(from,NavVec(from.x,top,from.z),n.edits) && geometry.clearSegment(NavVec(from.x,top,from.z),dest,n.edits))) {
                        val type=if(inWater) Traversal.SWIM else if(walk) when { top<from.y-0.01 -> Traversal.DROP; top>from.y+0.01 -> Traversal.STEP; else -> Traversal.WALK } else Traversal.JUMP
                        naturalAdvance=true
                        if(top>from.y+0.6 && hypot(x-goal.position.x,z-goal.position.z)<goalDistance-0.1) forwardStair=true
                        result.add(child(n,dest,type,emptyList(),n.edits,0,if(inWater) swimCost(from,dest,n.edits) else 3.5*hypot(x-from.x,z-from.z)+(if(type==Traversal.JUMP) 6.0 else 0.0))); break
                    }
                }
                if(construction && dx*dz==0) breakObstacles(n,dest)?.let { result.add(it) }
            }
            if(!escapeFire && profile==NavigationProfile.BEDFIGHT && goal.position.y<from.y-0.6 && !inWater && dx*dz==0 && !naturalAdvance &&
                hypot(startState.velocity.x,startState.velocity.z)<=0.35) {
                val landing=geometry.landingBelow(x,z,from.y,n.edits)
                if(landing!=null && geometry.drop(from,landing,n.edits)) {
                    val ticks=3.5*hypot(x-from.x,z-from.z)+sqrt((from.y-landing.y)*25.0)
                    result.add(child(n,landing,Traversal.DROP,emptyList(),n.edits,0,ticks))
                    naturalAdvance=true
                }
            }
            if(dx*dz==0 && !inWater) {
                val toward=hypot(x-goal.position.x,z-goal.position.z)<goalDistance-0.1
                if(toward && (-1..2).all { geometry.block(BlockPos.at(x,from.y+it,z),n.edits)!=null }) forwardLoaded=true
                val foundation=if(construction && !naturalAdvance) placeSupport(n,NavVec(x,ceil(from.y),z),
                    if(ceil(from.y)>from.y)Traversal.STEP else Traversal.WALK) else null
                for(gap in 2..3) {
                    val gx=floor(from.x)+0.5+dx*gap; val gz=floor(from.z)+0.5+dz*gap
                    // Only jump gaps, not arbitrary shortcuts through obstacles.
                    if(geometry.surfaces(x,z,from.y,n.edits,0.6).isNotEmpty()) break
                    if(profile==NavigationProfile.BEDFIGHT && foundation!=null) break
                    for(top in geometry.surfaces(gx,gz,from.y,n.edits)) {
                        val dest=NavVec(gx,top,gz)
                        if(geometry.jump(from,dest,n.edits)) { result.add(child(n,dest,Traversal.JUMP,emptyList(),n.edits,0,gap*3.5+6.0)); break }
                    }
                }
                foundation?.let { result.add(it) }
                if(construction && !naturalAdvance && travelHeight(from)>from.y+0.6) {
                    if(profile!=NavigationProfile.BEDFIGHT || toward) {
                        // With existing ground, place a raised step against that support.
                        // Over void, land on a confirmed foundation before placing underfoot.
                        val stair=if(foundation!=null) placeSupport(foundation,
                            NavVec(x,ceil(from.y)+1,z),Traversal.TOWER)
                        else placeSupport(n,NavVec(x,ceil(from.y)+1,z),Traversal.JUMP)
                        stair?.let { result.add(it); if(toward) forwardStair=true }
                    }
                }
            }
        }
        if(inWater) {
            val up=NavVec(from.x,from.y+1,from.z)
            if(geometry.swim(from,up,n.edits)) result.add(child(n,up,Traversal.SWIM,emptyList(),n.edits,0,8.0))
        } else if(construction && travelHeight(from)>from.y+0.6 &&
            (goal.preferredBridgeHeight==null || goalDistance<=1.5 || forwardLoaded) &&
            (profile!=NavigationProfile.BEDFIGHT || goalDistance<=1.5 || !forwardStair))
            placeSupport(n,NavVec(from.x,from.y+1,from.z),Traversal.TOWER)?.let { result.add(it) }
        return result
    }

    private fun NavigationGeometry.walkToWater(from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): Boolean {
        for(i in 1..10) {
            val t=i/10.0; val p=NavVec(from.x+(to.x-from.x)*t,from.y,from.z+(to.z-from.z)*t)
            if(!clear(p,edits)) return false
            if(!water(p,edits) && surfaces(p.x,p.z,p.y,edits,0.6).none { abs(it-p.y)<=0.6 }) return false
        }
        return true
    }
    private fun child(n: Node,p: NavVec,type: Traversal,actions: List<BlockAction>,edits: Map<BlockPos,NavBlock>,used: Int,cost: Double,
                      selectionCost: Double = cost) =
        Node(RouteStep(p,type,actions,cost,selectionCost),edits,n.remaining-used,n.cost+selectionCost,h(p),n)

    private fun swimCost(from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): Double {
        val distance=hypot(to.x-from.x,to.z-from.z)
        val flow=geometry.block(from.block,edits)?.flow?:NavVec(0.0,0.0,0.0)
        val along=if(distance>0.01) (flow.x*(to.x-from.x)+flow.z*(to.z-from.z))/distance else 0.0
        return distance*12.0*(1.0-along*0.25).coerceIn(0.8,1.5)+abs(to.y-from.y)*4.0
    }

    private fun breakObstacles(n: Node,dest: NavVec): Node? {
        if(!allowEdits) return null
        val edits=n.edits.toMutableMap(); val actions=ArrayList<BlockAction>(); var cost=3.5
        val length=hypot(dest.x-n.step.position.x,dest.z-n.step.position.z)
        for(i in 1..max(1,ceil(length/0.15).toInt())) {
            val t=i.toDouble()/max(1,ceil(length/0.15).toInt())
            val p=NavVec(n.step.position.x+(dest.x-n.step.position.x)*t,n.step.position.y,n.step.position.z+(dest.z-n.step.position.z)*t)
            val obstacles=geometry.obstacles(p,edits)?:return null
            for(pos in obstacles) {
                val b=geometry.block(pos,edits)?:return null
                if(pos in excluded || b.hazardous || !world.canBreak(pos,b) || !b.breakTicks.isFinite() || b.breakTicks>200) return null
                // Never excavate a support or release adjacent fluid/falling blocks onto the bot.
                if(pos.y<n.step.position.y-0.001 || adjacentUnsafe(pos,edits)) return null
                actions.add(BlockAction(BlockActionKind.BREAK,pos,b.id,b.metadata,b.toolSlot))
                edits[pos]=NavBlock(0,replaceable=true); cost+=actionOverheadTicks+b.breakTicks
            }
        }
        if(actions.isEmpty() || !geometry.clear(dest,edits) || !geometry.walk(n.step.position,dest,edits)) return null
        return child(n,dest,Traversal.WALK,actions,edits.toMap(),0,cost,3.5+(cost-3.5)*terrainCostFactor)
    }
    private fun adjacentUnsafe(p: BlockPos,edits: Map<BlockPos,NavBlock>): Boolean {
        return listOf(p.offset(1,0,0),p.offset(-1,0,0),p.offset(0,0,1),p.offset(0,0,-1),p.offset(0,1,0)).any {
            val b=geometry.block(it,edits); b==null || b.water || b.hazardous || (it.y>p.y && b.id in setOf(12,13))
        }
    }
    private fun placeSupport(n: Node,dest: NavVec,type: Traversal): Node? {
        if(!allowEdits) return null
        // Returning to a low bed must still allow a flat bridge from our current height.
        if(dest.y>maxOf(origin.y,goal.position.y,goal.preferredBridgeHeight ?: goal.position.y)+1.0) return null
        val m=material?:return null
        if(n.remaining<=0 || abs(dest.y-round(dest.y))>0.01) return null
        val p=BlockPos.at(dest.x,dest.y-1,dest.z); val old=geometry.block(p,n.edits)?:return null
        if(p in excluded || !old.replaceable || old.hazardous || !world.canPlace(p)) return null
        // A block can only be placed against an existing or already planned solid face.
        if(listOf(p.offset(1,0,0),p.offset(-1,0,0),p.offset(0,0,1),p.offset(0,0,-1),p.offset(0,-1,0)).none { geometry.block(it,n.edits)?.boxes?.isNotEmpty()==true }) return null
        val placed=NavBlock(m.id,m.metadata,listOf(NavBox(p.x.toDouble(),p.y.toDouble(),p.z.toDouble(),p.x+1.0,p.y+1.0,p.z+1.0)))
        val edits=n.edits+(p to placed)
        if(!geometry.clear(dest,edits)) return null
        if(type!=Traversal.TOWER && !geometry.walk(n.step.position,dest,edits) && !geometry.jump(n.step.position,dest,edits)) return null
        if(type==Traversal.TOWER && !geometry.clear(NavVec(dest.x,dest.y+0.25,dest.z),n.edits)) return null
        val style=when(type) { Traversal.TOWER -> PlacementStyle.TOWER; Traversal.JUMP,Traversal.STEP -> PlacementStyle.STEP; else -> PlacementStyle.BRIDGE }
        val action=BlockAction(BlockActionKind.PLACE,p,m.id,m.metadata,m.slot,style)
        val execution=when(style) { PlacementStyle.TOWER -> 10.0; PlacementStyle.STEP -> 13.0; else -> NavigationTuning.BRIDGE_EXECUTION_TICKS }
        val ticks=actionOverheadTicks+execution
        return child(n,dest,type,listOf(action),edits,1,ticks,ticks*terrainCostFactor)
    }
}
