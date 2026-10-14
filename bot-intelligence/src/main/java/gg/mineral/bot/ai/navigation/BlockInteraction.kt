package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import kotlin.math.*

/** Finds an exposed bed half or the first removable defence on a ray to that half. */
internal object BlockInteraction {
    const val REACH = 4.5

    fun findAction(world: NavigationWorld, state: NavigationState, targets: Set<BlockPos>,
                   edits: Map<BlockPos,NavBlock> = emptyMap(), excluded: Set<BlockPos> = emptySet()): BlockAction? {
        if (!state.onGround || state.inWater) return null
        if (targets.none { abs(it.x+0.5-state.position.x)<=REACH+0.5 && abs(it.z+0.5-state.position.z)<=REACH+0.5 }) return null
        val geometry=NavigationGeometry(world,state.width,state.height)
        val feet=state.position
        if (!geometry.clear(feet,edits) || geometry.surfaces(feet.x,feet.z,feet.y,edits,0.05).none { abs(it-feet.y)<0.06 }) return null
        // Destroying either half removes the whole bed; never stand on the other half.
        if (targets.any { pos -> geometry.block(pos,edits)?.boxes?.any { b ->
                abs(b.maxY-feet.y)<0.06 && feet.x+state.width/2>b.minX && feet.x-state.width/2<b.maxX &&
                    feet.z+state.width/2>b.minZ && feet.z-state.width/2<b.maxZ }==true }) return null
        val eye=NavVec(feet.x,feet.y+state.eyeHeight,feet.z)
        val choices=ArrayList<Pair<BlockAction,Double>>()
        for (target in targets) {
            val bed=geometry.block(target,edits) ?: continue
            if (bed.id!=26) continue
            for (box in bed.boxes) for (point in points(box)) {
                val distance=distance(eye,point)
                if (distance>REACH) continue
                val hit=firstHit(geometry,eye,point,edits) ?: continue
                val block=geometry.block(hit,edits) ?: continue
                if (hit in excluded || !safeToBreak(world,state,hit,block,edits)) continue
                choices.add(BlockAction(BlockActionKind.BREAK,hit,block.id,block.metadata,block.toolSlot,aimPoint=point) to
                    ((if(hit in targets) 0.0 else 1_000.0)+block.breakTicks+distance))
            }
        }
        return choices.minByOrNull { it.second }?.first
    }

    private fun safeToBreak(world: NavigationWorld,state: NavigationState,pos: BlockPos,b: NavBlock,
                            edits: Map<BlockPos,NavBlock>): Boolean {
        if (pos.y<state.position.y-0.001 || b.hazardous || !b.breakTicks.isFinite() || b.breakTicks>200 || !world.canBreak(pos,b)) return false
        return listOf(pos.offset(1,0,0),pos.offset(-1,0,0),pos.offset(0,0,1),pos.offset(0,0,-1),pos.offset(0,1,0)).none { p ->
            val adjacent=edits[p] ?: world.block(p)
            adjacent==null || adjacent.water || adjacent.hazardous || p.y>pos.y && adjacent.id in setOf(12,13)
        }
    }

    private fun points(b: NavBox): List<NavVec> {
        val x=(b.minX+b.maxX)/2; val y=(b.minY+b.maxY)/2; val z=(b.minZ+b.maxZ)/2
        return listOf(NavVec(x,b.maxY-0.001,z),NavVec(b.minX+0.001,y,z),NavVec(b.maxX-0.001,y,z),
            NavVec(x,y,b.minZ+0.001),NavVec(x,y,b.maxZ-0.001))
    }

    private fun distance(a: NavVec,b: NavVec)=sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)+(a.z-b.z).pow(2))

    private fun firstHit(geometry: NavigationGeometry,from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): BlockPos? {
        val cells=linkedSetOf<BlockPos>()
        val steps=ceil(distance(from,to)/0.08).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t=i.toDouble()/steps
            val pos=BlockPos.at(from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t,from.z+(to.z-from.z)*t)
            cells.add(pos); if(pos.y>0) cells.add(pos.offset(0,-1,0))
        }
        var nearest=Double.POSITIVE_INFINITY; var hit: BlockPos?=null
        for (pos in cells) {
            val block=geometry.block(pos,edits) ?: return null
            for (box in block.boxes) {
                val t=intersection(from,to,box) ?: continue
                if(t<nearest) { nearest=t; hit=pos }
            }
        }
        return hit
    }

    private fun intersection(a: NavVec,b: NavVec,box: NavBox): Double? {
        var lo=0.0; var hi=1.0
        for ((start,end,bounds) in listOf(Triple(a.x,b.x,box.minX to box.maxX),
                Triple(a.y,b.y,box.minY to box.maxY),Triple(a.z,b.z,box.minZ to box.maxZ))) {
            val d=end-start
            if(abs(d)<1e-9) { if(start<bounds.first || start>bounds.second) return null }
            else {
                val t1=(bounds.first-start)/d; val t2=(bounds.second-start)/d
                lo=max(lo,min(t1,t2)); hi=min(hi,max(t1,t2)); if(lo>hi) return null
            }
        }
        return lo
    }
}
