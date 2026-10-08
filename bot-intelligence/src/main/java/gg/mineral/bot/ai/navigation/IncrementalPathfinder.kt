package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import java.util.PriorityQueue
import kotlin.math.*

enum class Traversal { WALK, STEP, JUMP, DROP, SWIM, TOWER }
data class RouteStep(val position: NavVec, val traversal: Traversal, val actions: List<BlockAction> = emptyList(),
                     val estimatedTicks: Double = 0.0)
enum class SearchStatus { SEARCHING, SUCCESS, PARTIAL, NO_PATH }
data class RouteResult(val status: SearchStatus, val path: List<RouteStep>, val expanded: Int)
data class NavigationGoal(val position: NavVec, val range: Double = 2.5)

class IncrementalPathfinder(private val world: NavigationWorld, start: NavigationState,
    private val goal: NavigationGoal, private val material: BuildingMaterial? = null,
    private val excluded: Set<BlockPos> = emptySet(), private val nanoTime: () -> Long = System::nanoTime,
    private val allowEdits: Boolean = true, private val actionOverheadTicks: Double = 4.0) {
    private data class Key(val x: Int,val y: Int,val z: Int,val edits: Map<BlockPos,NavBlock>,val remaining: Int)
    private data class Node(val step: RouteStep,val edits: Map<BlockPos,NavBlock>,val remaining: Int,
        val cost: Double,val heuristic: Double,val parent: Node?)
    private val geometry=NavigationGeometry(world,start.width,start.height)
    private val origin=start.position
    private val horizontalEstimate=if(start.inWater && geometry.water(goal.position,emptyMap())) 10.0 else 3.5
    private val open=PriorityQueue<Node>(compareBy<Node> { it.cost+it.heuristic }.thenBy { it.heuristic })
    private val costs=HashMap<Key,Double>()
    private var best=Node(RouteStep(origin,Traversal.WALK),emptyMap(),material?.count?:0,0.0,h(origin),null)
    private var expanded=0
    private var complete: RouteResult?=null
    init { open.add(best); costs[key(best)]=0.0 }
    // A practical vertical estimate keeps a close elevated target from expanding every ground-level edit combination.
    private fun h(p: NavVec)=max(0.0,hypot(p.x-goal.position.x,p.z-goal.position.z)-goal.range)*horizontalEstimate+max(0.0,abs(p.y-goal.position.y)-1.0)*12.0
    private fun key(n: Node)=Key(round(n.step.position.x*16).toInt(),round(n.step.position.y*16).toInt(),round(n.step.position.z*16).toInt(),n.edits,n.remaining)
    private fun path(n: Node): List<RouteStep> { val result=ArrayList<RouteStep>(); var at: Node?=n
        while(at?.parent!=null) { result.add(at.step); at=at.parent }; result.reverse(); return result }

    fun compute(maxNodes: Int = 64, budgetNanos: Long = 1_000_000): RouteResult {
        complete?.let { return it }
        val started=nanoTime(); var slice=0
        while(open.isNotEmpty() && expanded<8192 && slice<maxNodes && (slice==0 || nanoTime()-started<budgetNanos)) {
            val n=open.poll()
            if(n.cost>(costs[key(n)]?:Double.POSITIVE_INFINITY)+1e-6) continue
            expanded++; slice++
            if(h(n.step.position)<best.heuristic) best=n
            if(atGoal(n)) return RouteResult(SearchStatus.SUCCESS,path(n),expanded).also { complete=it }
            neighbors(n).forEach { candidate ->
                val p=candidate.step.position
                if(p.block in excluded || hypot(p.x-origin.x,p.z-origin.z)>32 || abs(p.y-origin.y)>16 || candidate.edits.size>12) return@forEach
                val key=key(candidate)
                if(candidate.cost+1e-6<(costs[key]?:Double.POSITIVE_INFINITY)) {
                    costs[key]=candidate.cost; open.add(candidate)
                }
            }
        }
        if(open.isEmpty() || expanded>=8192) {
            return RouteResult(if(best.parent==null) SearchStatus.NO_PATH else SearchStatus.PARTIAL,path(best),expanded).also { complete=it }
        }
        // Prefer actual goal progress, avoiding sideways fluid branches. A frontier fallback permits U-shaped escapes.
        val preview = if(best.parent!=null) best else open.peek()?.takeIf { it.parent!=null }?:best
        return RouteResult(SearchStatus.SEARCHING,path(preview),expanded)
    }

    private fun atGoal(n: Node): Boolean {
        val p=n.step.position
        if(hypot(p.x-goal.position.x,p.z-goal.position.z)>goal.range || abs(p.y-goal.position.y)>1.0) return false
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

    private fun neighbors(n: Node): List<Node> {
        val result=ArrayList<Node>(); val from=n.step.position
        val inWater=geometry.water(from,n.edits)
        // Grid nodes must also reach non-centered players and short-lived remembered positions.
        val goalDistance=hypot(from.x-goal.position.x,from.z-goal.position.z)
        if(goalDistance<1.5 && abs(from.y-goal.position.y)<=1.0 && geometry.clear(goal.position,n.edits)) {
            if(inWater && geometry.swim(from,goal.position,n.edits))
                result.add(child(n,goal.position,Traversal.SWIM,emptyList(),n.edits,0,swimCost(from,goal.position,n.edits)))
            else if(!inWater && geometry.walk(from,goal.position,n.edits))
                result.add(child(n,goal.position,Traversal.WALK,emptyList(),n.edits,0,goalDistance*3.5))
        }
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
            for(top in tops) {
                val dest=NavVec(x,top,z)
                if(geometry.water(dest,n.edits)) continue
                if(geometry.clear(dest,n.edits)) {
                    val walk=geometry.walk(from,dest,n.edits)
                    if(walk || (!inWater && dx*dz==0 && geometry.jump(from,dest,n.edits)) || (inWater && dx*dz==0 && top-from.y<=1.0 && geometry.swim(from,NavVec(from.x,top,from.z),n.edits) && geometry.clearSegment(NavVec(from.x,top,from.z),dest,n.edits))) {
                        val type=if(inWater) Traversal.SWIM else if(walk) when { top<from.y-0.6 -> Traversal.DROP; top>from.y+0.01 -> Traversal.STEP; else -> Traversal.WALK } else Traversal.JUMP
                        result.add(child(n,dest,type,emptyList(),n.edits,0,if(inWater) swimCost(from,dest,n.edits) else 3.5*hypot(x-from.x,z-from.z)+(if(type==Traversal.JUMP) 6.0 else 0.0))); break
                    }
                }
                if(dx*dz==0) breakObstacles(n,dest)?.let { result.add(it) }
            }
            if(dx*dz==0 && !inWater) {
                for(gap in 2..3) {
                    val gx=floor(from.x)+0.5+dx*gap; val gz=floor(from.z)+0.5+dz*gap
                    // Only jump gaps, not arbitrary shortcuts through obstacles.
                    if(geometry.surfaces(x,z,from.y,n.edits,0.6).isNotEmpty()) break
                    for(top in geometry.surfaces(gx,gz,from.y,n.edits)) {
                        val dest=NavVec(gx,top,gz)
                        if(geometry.jump(from,dest,n.edits)) { result.add(child(n,dest,Traversal.JUMP,emptyList(),n.edits,0,gap*3.5+6.0)); break }
                    }
                }
                placeSupport(n,NavVec(x,ceil(from.y),z),if(ceil(from.y)>from.y)Traversal.STEP else Traversal.WALK)?.let { result.add(it) }
                if(goal.position.y>from.y+0.6) placeSupport(n,NavVec(x,ceil(from.y)+1,z),Traversal.JUMP)?.let { result.add(it) }
            }
        }
        if(inWater) {
            val up=NavVec(from.x,from.y+1,from.z)
            if(geometry.swim(from,up,n.edits)) result.add(child(n,up,Traversal.SWIM,emptyList(),n.edits,0,8.0))
        } else if(goal.position.y>from.y+0.6) placeSupport(n,NavVec(from.x,from.y+1,from.z),Traversal.TOWER)?.let { result.add(it) }
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
    private fun child(n: Node,p: NavVec,type: Traversal,actions: List<BlockAction>,edits: Map<BlockPos,NavBlock>,used: Int,cost: Double) =
        Node(RouteStep(p,type,actions,cost),edits,n.remaining-used,n.cost+cost,h(p),n)

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
        return child(n,dest,Traversal.WALK,actions,edits.toMap(),0,cost)
    }
    private fun adjacentUnsafe(p: BlockPos,edits: Map<BlockPos,NavBlock>): Boolean {
        return listOf(p.offset(1,0,0),p.offset(-1,0,0),p.offset(0,0,1),p.offset(0,0,-1),p.offset(0,1,0)).any {
            val b=geometry.block(it,edits); b==null || b.water || b.hazardous || (it.y>p.y && b.id in setOf(12,13))
        }
    }
    private fun placeSupport(n: Node,dest: NavVec,type: Traversal): Node? {
        if(!allowEdits) return null
        if(dest.y>goal.position.y+1.0) return null
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
        val execution=when(style) { PlacementStyle.TOWER -> 10.0; PlacementStyle.STEP -> 13.0; else -> 7.0 }
        return child(n,dest,type,listOf(action),edits,1,actionOverheadTicks+execution)
    }
}
