package gg.mineral.bot.ai.navigation

import gg.mineral.bot.api.navigation.*
import kotlin.math.*

/** All positions are feet positions. Collision shapes stay in world coordinates. */
internal class NavigationGeometry(val world: NavigationWorld, val width: Double, val height: Double,
                                  private val ignoreFire: Boolean = false) {
    private var cachedRevision = world.revision
    private val blocks = HashMap<BlockPos, NavBlock?>()
    fun block(p: BlockPos, edits: Map<BlockPos, NavBlock>): NavBlock? {
        edits[p]?.let { return traversableBlock(it) }
        if (cachedRevision != world.revision) { blocks.clear(); cachedRevision = world.revision }
        if (!blocks.containsKey(p)) blocks[p] = world.block(p)
        return traversableBlock(blocks[p])
    }
    // Fire has no solid collision shape. Only an active escape may cross it.
    private fun traversableBlock(block: NavBlock?): NavBlock? =
        if (ignoreFire && block?.id == 51 && block.hazardous) block.copy(hazardous = false) else block

    fun touchingFire(p: NavVec): Boolean {
        val box = body(p)
        for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
            for (y in max(0, floor(box.minY).toInt())..floor(box.maxY).toInt())
                for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                    if (block(BlockPos(x,y,z), emptyMap())?.id == 51 &&
                        NavBox(x.toDouble(),y.toDouble(),z.toDouble(),x+1.0,y+1.0,z+1.0).intersects(box)) return true
                }
        return false
    }

    fun safeFooting(p: NavVec): Boolean = clear(p, emptyMap()) &&
        (water(p, emptyMap()) || supportedFootprint(p))
    fun body(p: NavVec): NavBox {
        val r = width / 2 - 0.001
        return NavBox(p.x-r, p.y+0.001, p.z-r, p.x+r, p.y+height, p.z+r)
    }
    fun obstacles(p: NavVec, edits: Map<BlockPos, NavBlock>): Set<BlockPos>? {
        val box = body(p); val result = mutableSetOf<BlockPos>()
        // Include the cell below: fences extend beyond their block's unit cube.
        for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
            for (y in max(0, floor(box.minY).toInt()-1)..floor(box.maxY).toInt())
                for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                    val pos = BlockPos(x,y,z); val b = block(pos, edits) ?: return null
                    if (b.hazardous || b.boxes.any { it.intersects(box) }) result.add(pos)
                }
        return result
    }
    fun clear(p: NavVec, edits: Map<BlockPos, NavBlock>): Boolean {
        val box = body(p)
        for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
            for (y in max(0, floor(box.minY).toInt()-1)..floor(box.maxY).toInt())
                for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                    val b = block(BlockPos(x,y,z), edits) ?: return false
                    if (b.hazardous || b.boxes.any { it.intersects(box) }) return false
                }
        return true
    }
    fun water(p: NavVec, edits: Map<BlockPos, NavBlock>) = block(p.block, edits)?.water == true ||
        block(BlockPos.at(p.x,p.y+0.4,p.z), edits)?.water == true ||
        block(BlockPos.at(p.x,p.y-0.2,p.z), edits)?.water == true

    fun supportedFootprint(p: NavVec,edits: Map<BlockPos,NavBlock> = emptyMap()): Boolean {
        val radius=width/2+0.015
        for(dx in listOf(-radius,0.0,radius)) for(dz in listOf(-radius,0.0,radius)) {
            val x=p.x+dx; val z=p.z+dz
            val b=block(BlockPos.at(x,p.y-0.02,z),edits) ?: return false
            if(b.hazardous || b.boxes.none { abs(it.maxY-p.y)<0.06 && x>=it.minX && x<=it.maxX && z>=it.minZ && z<=it.maxZ }) return false
        }
        return true
    }

    /** A solid, loaded landing column; air or unknown terrain never counts as a landing. */
    fun landingBelow(x: Double,z: Double,feetY: Double,edits: Map<BlockPos,NavBlock>): NavVec? {
        for(y in min(255,floor(feetY-0.01).toInt()) downTo 0) {
            val b=block(BlockPos.at(x,y.toDouble(),z),edits) ?: return null
            if(b.hazardous || b.water) return null
            if(b.boxes.isEmpty()) continue
            val top=b.boxes.maxOf { it.maxY }
            if(top>feetY+0.05) return null
            val landing=NavVec(x,top,z)
            return landing.takeIf { clear(it,edits) && supportedFootprint(it,edits) }
        }
        return null
    }

    fun drop(from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): Boolean {
        if(from.y-to.y<=3.0 || hypot(to.x-from.x,to.z-from.z)>1.5 || water(to,edits) || !supportedFootprint(to,edits)) return false
        val above=to.copy(y=from.y)
        if(!clearSegment(from,above,edits)) return false
        val column=body(to).copy(maxY=from.y+height)
        for(x in floor(column.minX).toInt()..floor(column.maxX).toInt())
            for(z in floor(column.minZ).toInt()..floor(column.maxZ).toInt())
                for(y in max(0,floor(column.minY).toInt()-1)..min(255,floor(column.maxY).toInt())) {
                    val b=block(BlockPos(x,y,z),edits) ?: return false
                    if(b.hazardous || b.water || b.boxes.any { it.intersects(column) }) return false
                }
        return true
    }

    fun clearSegment(from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): Boolean {
        val steps=max(1,ceil(max(hypot(to.x-from.x,to.z-from.z),abs(to.y-from.y))/0.12).toInt())
        return (1..steps).all { i ->
            val t=i.toDouble()/steps
            clear(NavVec(from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t,from.z+(to.z-from.z)*t),edits)
        }
    }

    fun enterWater(from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): Boolean {
        if(!water(to,edits) || from.y-to.y !in 0.0..3.0) return false
        val edge=NavVec(to.x,from.y,to.z)
        return clearSegment(from,edge,edits) && clearSegment(edge,to,edits)
    }

    fun surfaces(x: Double, z: Double, y: Double, edits: Map<BlockPos, NavBlock>, rise: Double = 1.25): List<Double> {
        val r = width / 2 - 0.01
        val box = NavBox(x-r,y-3.01,z-r,x+r,y+rise+0.01,z+r)
        val tops = mutableSetOf<Double>()
        for (bx in floor(box.minX).toInt()..floor(box.maxX).toInt())
            for (by in max(0,floor(box.minY).toInt()-1)..min(255,floor(box.maxY).toInt()))
                for (bz in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                    val b = block(BlockPos(bx,by,bz),edits) ?: continue
                    if (b.hazardous) continue
                    for (shape in b.boxes) {
                        if (shape.maxX > box.minX && shape.minX < box.maxX && shape.maxZ > box.minZ && shape.minZ < box.maxZ &&
                            shape.maxY <= y+rise+0.001 && shape.maxY >= y-3.001) tops.add(shape.maxY)
                    }
        }
        return tops.sortedDescending()
    }

    fun walk(from: NavVec, to: NavVec, edits: Map<BlockPos, NavBlock>): Boolean {
        if(abs(to.x-from.x)>0.05 && abs(to.z-from.z)>0.05 && abs(to.y-from.y)<0.06) {
            // Keep both corners supported when joining a narrow bridge/island.
            // Sampling only the diagonal can miss a very short unsupported corner.
            for(corner in listOf(NavVec(from.x,to.y,to.z),NavVec(to.x,to.y,from.z))) {
                if(!clear(corner,edits) || surfaces(corner.x,corner.z,corner.y,edits,0.05).none { abs(it-corner.y)<0.06 }) return false
            }
        }
        val length = hypot(to.x-from.x,to.z-from.z)
        val steps = max(1,ceil(length/0.12).toInt()); var y = from.y
        for (i in 1..steps) {
            val t = i.toDouble()/steps; val x = from.x+(to.x-from.x)*t; val z=from.z+(to.z-from.z)*t
            if (water(NavVec(x,y,z),edits)) return false
            val top = surfaces(x,z,y,edits,0.6).firstOrNull { clear(NavVec(x,it,z),edits) } ?: return false
            if (y-top > 3.001) return false
            // Check the vertical step space before moving horizontally.
            if (top > y+0.001 && !clear(NavVec(from.x+(to.x-from.x)*(i-1)/steps,top,from.z+(to.z-from.z)*(i-1)/steps),edits)) return false
            y = top
        }
        return abs(y-to.y)<0.06
    }

    /** Vanilla 1.7 jump impulse, gravity and drag; horizontal motion clips at walls. */
    fun jump(from: NavVec, to: NavVec, edits: Map<BlockPos, NavBlock>): Boolean {
        if (to.y-from.y > 1.25 || from.y-to.y > 3.0) return false
        val distance = hypot(to.x-from.x,to.z-from.z)
        if (distance<0.01 || distance>3.05) return false
        var y=from.y; var vy=0.42; var traveled=0.0
        for (tick in 1..24) {
            val nextY=y+vy
            val step=min(distance-traveled, if(distance>1.5) min(0.36,tick*0.12) else 0.22)
            val nextTraveled=traveled+step
            val here=NavVec(from.x+(to.x-from.x)*traveled/distance,nextY,from.z+(to.z-from.z)*traveled/distance)
            if (vy<0 && nextY<=to.y+0.001 && traveled>=distance-0.25 && clear(to,edits)) return true
            if (!clear(here,edits)) return false
            val next=NavVec(from.x+(to.x-from.x)*nextTraveled/distance,nextY,from.z+(to.z-from.z)*nextTraveled/distance)
            if(clear(next,edits)) traveled=nextTraveled
            y=nextY; vy=(vy-0.08)*0.98
        }
        return false
    }

    fun swim(from: NavVec,to: NavVec,edits: Map<BlockPos,NavBlock>): Boolean {
        val count=max(1,ceil(max(hypot(to.x-from.x,to.z-from.z),abs(to.y-from.y))/0.15).toInt())
        for(i in 1..count) {
            val t=i.toDouble()/count
            val p=NavVec(from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t,from.z+(to.z-from.z)*t)
            if(!clear(p,edits) || (!water(p,edits) && i<count)) return false
        }
        return true
    }
}
