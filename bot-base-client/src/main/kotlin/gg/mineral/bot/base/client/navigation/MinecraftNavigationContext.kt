package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.inv.InventoryTransactionStatus
import gg.mineral.bot.api.navigation.*
import gg.mineral.bot.api.screen.type.InventoryScreen
import gg.mineral.bot.base.client.instance.ClientInstance
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.block.material.Material
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.item.ItemBlock
import net.minecraft.network.play.client.C17PacketCustomPayload
import net.minecraft.potion.Potion
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

class MinecraftNavigationContext(private val instance: ClientInstance) : NavigationContext, NavigationWorld {
    override val world: NavigationWorld get()=this
    override val searchBudget get()=gg.mineral.bot.ai.navigation.SharedNavigationBudget.PROCESS
    override val actionOverheadTicks get()=4.0+instance.latency.coerceAtLeast(0)/50.0*2
    private enum class Phase { AUTHORIZE, SLOT, POSITION, AIM, EXECUTE, CONFIRM, LAND }
    private var phase=Phase.AUTHORIZE
    private var phaseTick=0
    private var failureReason=""
    override val actionDiagnostic get()=if(status==ActionStatus.FAILED) failureReason else phase.name
    private var tower: TowerPlacement?=null
    private var supportPos: BlockPos?=null
    private var supportFace=-1
    private var expectedDigTicks=0
    private var buoyancyHeld=false
    private val toolCache=HashMap<Pair<Int,Float>,Pair<Int,Double>>()
    private var toolSignature=0
    private val permissions=NavigationPermissionStore()
    private val payloads=ConcurrentLinkedQueue<ByteArray>()
    private val pendingBinding=AtomicReference<String?>()
    private var blockRevision=0L
    private var cachedTick=-1
    private val blocks=HashMap<BlockPos,NavBlock?>()
    private var nativeWorld: Any?=null
    private var action: BlockAction?=null
    private var status=ActionStatus.IDLE
    private var actionId=0L
    private var startedTick=0
    private var lastActionTick=-1
    private var swapToken: Long?=null
    private var selectedSlot=-1
    private var clicked=false
    private var ownsSneak=false
    private var ownsJump=false
    private var ownsMouse=false
    private var ownsAim=false
    private val movementTypes=setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D,Key.Type.KEY_SPACE,Key.Type.KEY_LCONTROL)
    private val orphanedSwaps=mutableSetOf<Long>()
    override val revision: Long get()=blockRevision

    fun bind(token: String) { pendingBinding.set(token) }
    fun receive(payload: ByteArray) { if(payload.size<=32767 && payloads.size<4096) payloads.add(payload.copyOf()) }
    fun changed() { blocks.clear(); blockRevision++ }
    fun pump() {
        pendingBinding.getAndSet(null)?.let { token ->
            cancelAction(); permissions.bind(token); blocks.clear(); blockRevision++
        }
        orphanedSwaps.removeIf { token ->
            if(instance.inventoryTransactionStatus(token)!=InventoryTransactionStatus.PENDING) { instance.forgetInventoryTransaction(token); true } else false
        }
        if(nativeWorld!==instance.theWorld) {
            val previousWorld=nativeWorld
            nativeWorld=instance.theWorld; cancelAction(); blocks.clear(); blockRevision++
            // New connections are explicitly bound by BotDuelStarted.
            if(nativeWorld==null) { permissions.bind(""); payloads.clear() }
            else if(previousWorld!=null) { permissions.bind(permissions.token); payloads.clear() }
        }
        val old=permissions.permissions
        while(true) permissions.receive(payloads.poll()?:break)
        if(old?.copy(revision=0)!=permissions.permissions?.copy(revision=0)) { blocks.clear(); blockRevision++ }
        if(cachedTick!=instance.currentTick) {
            cachedTick=instance.currentTick
            val p=instance.thePlayer
            var signature=1
            if(p!=null) {
                for(slot in 0..35) {
                    val stack=p.vanillaInventory.getStackInSlot(slot)
                    signature=31*signature+(stack?.let { System.identityHashCode(it.item)+it.itemDamage*31+it.stackSize*997+(it.tagCompound?.hashCode()?:0) }?:0)
                }
                signature=31*signature+if(p.isInWater) 1 else 0
                signature=31*signature+if(p.onGround) 1 else 0
                signature=31*signature+(p.getActivePotionEffect(Potion.digSpeed)?.amplifier?:-1)
                signature=31*signature+(p.getActivePotionEffect(Potion.digSlowdown)?.amplifier?:-1)
            }
            if(signature!=toolSignature) { toolSignature=signature; toolCache.clear() }
        }
    }
    override fun state(): NavigationState {
        pump(); val p=instance.thePlayer ?: return NavigationState(NavVec(0.0,0.0,0.0),0f,false,false,300)
        val b=p.boundingBox
        return NavigationState(NavVec(p.posX,b.minY,p.posZ),p.rotationYaw,p.onGround,p.isInWater,p.air,
            b.maxX-b.minX,b.maxY-b.minY,NavVec(p.motionX,p.motionY,p.motionZ))
    }
    override fun block(pos: BlockPos): NavBlock? {
        if(blocks.containsKey(pos)) return withTool(pos,blocks[pos])
        val w=instance.theWorld ?: return null
        if(!w.blockExists(pos.x,pos.y,pos.z)) return null
        val b=w.getBlock(pos.x,pos.y,pos.z); val id=Block.getIdFromBlock(b)
        val meta=w.getBlockMetadata(pos.x,pos.y,pos.z)
        val shapes=ArrayList<AxisAlignedBB>()
        synchronized(b) {
            b.setBlockBoundsBasedOnState(w,pos.x,pos.y,pos.z)
            b.addCollisionBoxesToList(w,pos.x,pos.y,pos.z,
                AxisAlignedBB.getBoundingBox(pos.x.toDouble()-0.01,pos.y.toDouble()-0.01,pos.z.toDouble()-0.01,pos.x+1.01,pos.y+1.51,pos.z+1.01),shapes,null)
        }
        val boxes=shapes.map { NavBox(it.minX,it.minY,it.minZ,it.maxX,it.maxY,it.maxZ) }
        val fluid=b.material==Material.water
        val flow=if(fluid && b is BlockLiquid) {
            val v=Vec3.createVectorHelper(0.0,0.0,0.0)
            b.velocityToAddToEntity(w,pos.x,pos.y,pos.z,instance.thePlayer,v)
            NavVec(v.xCoord,v.yCoord,v.zCoord)
        } else NavVec(0.0,0.0,0.0)
        val result=NavBlock(id,meta,boxes,fluid,id in setOf(10,11,51,81),b.material.isReplaceable || id==0 || fluid,flow)
        if(blocks.size>=16384) blocks.clear()
        blocks[pos]=result
        return withTool(pos,result)
    }
    private fun withTool(pos: BlockPos,block: NavBlock?): NavBlock? {
        if(block==null || block.boxes.isEmpty() || permissions.permissions?.canBreak(pos,block.id)!=true) return block
        val b=instance.theWorld?.getBlock(pos.x,pos.y,pos.z)?:return block
        val key=block.id to b.getBlockHardness(instance.theWorld,pos.x,pos.y,pos.z)
        val result=toolCache.getOrPut(key) { tool(pos,b) }
        return block.copy(breakTicks=result.second,toolSlot=result.first)
    }
    override fun maintainBuoyancy(inWater: Boolean) {
        if(inWater) {
            instance.keyboard.pressKey(Key.Type.KEY_SPACE)
            instance.keyboard.unpressKey(Key.Type.KEY_LCONTROL)
            instance.thePlayer?.setSprinting(false)
        } else if(buoyancyHeld) instance.keyboard.unpressKey(Key.Type.KEY_SPACE)
        buoyancyHeld=inWater
    }
    private fun tool(pos: BlockPos,b: Block): Pair<Int,Double> {
        val p=instance.thePlayer ?: return -1 to Double.POSITIVE_INFINITY
        val w=instance.theWorld ?: return -1 to Double.POSITIVE_INFINITY
        val hardness=b.getBlockHardness(w,pos.x,pos.y,pos.z)
        if(hardness<0) return -1 to Double.POSITIVE_INFINITY
        var bestSlot=-1; var bestTicks=Double.POSITIVE_INFINITY
        for(slot in 0..35) {
            val stack=p.vanillaInventory.getStackInSlot(slot)
            var strength=stack?.func_150997_a(b)?.toDouble()?:1.0
            if(strength>1 && stack!=null) {
                val level=EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId,stack)
                if(level>0) strength+=(level*level+1)*if(stack.func_150998_b(b)) 1.0 else 0.08
            }
            if(p.isPotionActive(Potion.digSpeed)) strength*=1+(p.getActivePotionEffect(Potion.digSpeed).amplifier+1)*0.2
            if(p.isPotionActive(Potion.digSlowdown)) strength*=max(0.0,1-(p.getActivePotionEffect(Potion.digSlowdown).amplifier+1)*0.2)
            if(p.isInWater && !EnchantmentHelper.getAquaAffinityModifier(p)) strength/=5
            if(!p.onGround) strength/=5
            val ticks=if(hardness==0f) 1.0 else ceil(hardness*(if(b.material.isToolNotRequired || stack?.func_150998_b(b)==true) 30.0 else 100.0) / strength.coerceAtLeast(0.001))
            if(ticks<bestTicks) { bestTicks=ticks; bestSlot=slot }
        }
        return bestSlot to bestTicks
    }
    override fun canBreak(pos: BlockPos,block: NavBlock)=permissions.permissions?.canBreak(pos,block.id)==true
    override fun canPlace(pos: BlockPos)=permissions.permissions?.canPlace(pos)==true
    override fun material(): BuildingMaterial? {
        pump(); if(permissions.permissions?.building!=true) return null
        val p=instance.thePlayer?:return null
        for(slot in 0..35) {
            val stack=p.vanillaInventory.getStackInSlot(slot)?:continue
            if(stack.item !is ItemBlock || stack.stackSize<=0) continue
            val b=Block.getBlockFromItem(stack.item)
            // Stable, full cubes only; never use containers, gravity blocks or explosives as scaffolding.
            val id=Block.getIdFromBlock(b)
            if(id !in setOf(1,3,4,5,20,24,35,45,48,49,80,87,98,121,159,172)) continue
            val count=(0..35).sumOf { i -> p.vanillaInventory.getStackInSlot(i)?.let { if(it.item===stack.item && it.itemDamage==stack.itemDamage) it.stackSize else 0 }?:0 }
            return BuildingMaterial(id,stack.itemDamage,slot,count)
        }
        return null
    }
    override fun requestAction(action: BlockAction): ActionStatus {
        pump()
        if(this.action==action && status !in setOf(ActionStatus.IDLE,ActionStatus.FAILED)) return status
        cancelAction(); this.action=action; startedTick=instance.currentTick; actionId++
        phase=Phase.AUTHORIZE; phaseTick=startedTick; failureReason=""; lastActionTick=-1
        expectedDigTicks=if(action.kind==BlockActionKind.BREAK) block(action.pos)?.breakTicks?.takeIf { it.isFinite() }?.toInt()?.coerceIn(0,200)?:0 else 0
        val p=instance.thePlayer
        if(action.kind==BlockActionKind.PLACE && (action.placement==PlacementStyle.TOWER ||
                action.placement==PlacementStyle.AUTO && p!=null && abs(action.pos.x+0.5-p.posX)<0.5 && abs(action.pos.z+0.5-p.posZ)<0.5 && action.pos.y+1>p.boundingBox.minY+0.05))
            tower=TowerPlacement(action.pos.y+1.0)
        val payload=permissions.check(actionId,action) ?: return ActionStatus.FAILED.also { fail("permissions unavailable") }
        instance.netHandler?.addToSendQueue(C17PacketCustomPayload("MineralBot",payload)) ?: return ActionStatus.FAILED.also { fail("not connected") }
        status=ActionStatus.WAITING; return status
    }
    override fun actionStatus()=status
    override fun move(keys: Set<Key.Type>) {
        (movementTypes-keys).forEach { instance.keyboard.unpressKey(it) }
        keys.forEach { instance.keyboard.pressKey(it) }
        if(instance.thePlayer?.isInWater==true) instance.thePlayer?.setSprinting(false)
    }
    private fun prepareSlot(a: BlockAction): Boolean {
        val p=instance.thePlayer?:return false
        var slot=selectedSlot.takeIf { it>=0 }?:a.slot
        if(a.kind==BlockActionKind.PLACE && swapToken==null) {
            fun matches(i: Int): Boolean = p.vanillaInventory.getStackInSlot(i)?.let {
                it.item is ItemBlock && Block.getIdFromBlock(Block.getBlockFromItem(it.item))==a.blockId && it.itemDamage==a.metadata && it.stackSize>0
            }==true
            if(slot !in 0..35 || !matches(slot)) {
                slot=(0..35).firstOrNull { matches(it) }?:-1
                selectedSlot=slot
            }
        }
        if(slot !in 0..35) { fail("no matching inventory slot"); return false }
        swapToken?.let { token ->
            when(instance.inventoryTransactionStatus(token)) {
                InventoryTransactionStatus.PENDING -> return false
                InventoryTransactionStatus.ACCEPTED -> { instance.forgetInventoryTransaction(token); swapToken=null; selectedSlot=8; slot=8 }
                else -> { instance.forgetInventoryTransaction(token); swapToken=null; fail("inventory swap rejected"); return false }
            }
        }
        if(slot>8) {
            if(instance.currentScreen is InventoryScreen) swapToken=instance.requestHotbarSwap(slot,8)
            else if(instance.currentScreen==null) instance.keyboard.pressKey(10,Key.Type.KEY_E)
            return false
        }
        if(instance.currentScreen!=null) { instance.keyboard.pressKey(10,Key.Type.KEY_ESCAPE); return false }
        if(p.vanillaInventory.currentItem!=slot) {
            instance.mouse.clearPendingClicks()
            instance.keyboard.pressKey(10,Key.Type.entries.first { it.name=="KEY_${slot+1}" }); return false
        }
        val stack=p.vanillaInventory.getStackInSlot(slot)
        if(a.kind==BlockActionKind.PLACE && (stack==null || Block.getIdFromBlock(Block.getBlockFromItem(stack.item))!=a.blockId || stack.itemDamage!=a.metadata || stack.stackSize<=0)) {
            fail("building material changed"); return false
        }
        return true
    }
    private fun phase(next: Phase) {
        if(phase!=next) { phase=next; phaseTick=instance.currentTick }
    }
    private fun faceCenter(pos: BlockPos,face: Int): Vec3 {
        val box=block(pos)?.boxes?.maxByOrNull { (it.maxX-it.minX)*(it.maxY-it.minY)*(it.maxZ-it.minZ) }
        val center=Vec3.createVectorHelper(box?.let { (it.minX+it.maxX)/2 }?:pos.x+0.5,
            box?.let { (it.minY+it.maxY)/2 }?:pos.y+0.5,box?.let { (it.minZ+it.maxZ)/2 }?:pos.z+0.5)
        when(face) {
            1 -> center.yCoord=box?.maxY?:pos.y+1.0
            5 -> center.xCoord=box?.maxX?:pos.x+1.0
            4 -> center.xCoord=box?.minX?:pos.x.toDouble()
            3 -> center.zCoord=box?.maxZ?:pos.z+1.0
            2 -> center.zCoord=box?.minZ?:pos.z.toDouble()
        }
        return center
    }
    private fun steerTo(x: Double,z: Double) {
        val p=instance.thePlayer?:return
        val angle=atan2(-(x-p.posX),z-p.posZ)-Math.toRadians(p.rotationYaw.toDouble())
        val keys=mutableSetOf<Key.Type>()
        if(cos(angle)>0.38)keys.add(Key.Type.KEY_W)
        if(cos(angle)<-0.38)keys.add(Key.Type.KEY_S)
        if(sin(angle)<-0.38)keys.add(Key.Type.KEY_A)
        if(sin(angle)>0.38)keys.add(Key.Type.KEY_D)
        move(keys)
    }
    override fun tickAction() {
        pump()
        if(lastActionTick==instance.currentTick) return
        lastActionTick=instance.currentTick
        val a=action?:return
        if(status !in setOf(ActionStatus.WAITING,ActionStatus.EXECUTING)) return
        val lag=ceil(instance.latency.coerceAtLeast(0)/50.0).toInt()*2
        val phaseLimit=40+lag+if(phase==Phase.EXECUTE && a.kind==BlockActionKind.BREAK) expectedDigTicks else 0
        if(permissions.permissions?.building!=true) { fail("permissions unavailable"); return }
        if(instance.currentTick-startedTick>300+lag || instance.currentTick-phaseTick>phaseLimit) {
            fail("timeout in "+phase.name); return
        }
        val reply=permissions.reply?.takeIf { it.first==actionId }?.second
        if(reply==0) { fail("server rejected operation"); return }
        val p=instance.thePlayer?:return
        val w=instance.theWorld?:return
        if(reply==2) {
            val b=block(a.pos)
            val matches=b!=null && if(a.kind==BlockActionKind.BREAK) b.id==0 || b.replaceable
                                  else b.id==a.blockId && b.metadata==a.metadata
            if(matches) {
                release(); move(emptySet())
                if(tower?.update(state(),clicked,true)==TowerPlacement.Motion.LANDING) { phase(Phase.LAND); return }
                status=ActionStatus.CONFIRMED
            }
            return
        }
        if(reply!=1) return
        if(phase==Phase.AUTHORIZE) phase(Phase.SLOT)
        // Advance flight even if a ray or inventory preparation is temporarily unavailable.
        val towerMotion=if(tower!=null && phase==Phase.EXECUTE && !clicked) tower!!.update(state(),false,false) else null
        if(towerMotion==TowerPlacement.Motion.FAILED) { fail("missed tower placement window"); return }
        if(ownsJump && towerMotion!=null && towerMotion!=TowerPlacement.Motion.TAKEOFF) {
            instance.keyboard.unpressKey(Key.Type.KEY_SPACE); ownsJump=false
        }
        // A click is issued once. Confirmation waits must never re-enter jump or edge positioning.
        if(clicked) { phase(Phase.CONFIRM); return }
        if(!prepareSlot(a)) return
        if(phase==Phase.SLOT) phase(Phase.POSITION)

        val pos=a.pos
        var support=pos
        var face=-1
        if(a.kind==BlockActionKind.PLACE) {
            if(supportPos==null) {
                val candidates=listOf(pos.offset(0,-1,0) to 1,pos.offset(-1,0,0) to 5,
                    pos.offset(1,0,0) to 4,pos.offset(0,0,-1) to 3,pos.offset(0,0,1) to 2)
                val candidate=candidates.filter { block(it.first)?.boxes?.isNotEmpty()==true }
                    .minByOrNull { val center=faceCenter(it.first,it.second)
                        (center.xCoord-p.posX).pow(2)+(center.yCoord-p.posY).pow(2)+(center.zCoord-p.posZ).pow(2) }
                    ?:run { fail("no support face"); return }
                supportPos=candidate.first; supportFace=candidate.second
            }
            support=supportPos!!; face=supportFace
            if(block(support)?.boxes?.isNotEmpty()!=true) { fail("support disappeared"); return }
            if(tower!=null && phase==Phase.POSITION) {
                if(!p.onGround) return
                if(hypot(pos.x+0.5-p.posX,pos.z+0.5-p.posZ)>0.07) {
                    instance.keyboard.pressKey(Key.Type.KEY_LSHIFT); ownsSneak=true
                    steerTo(pos.x+0.5,pos.z+0.5); return
                }
                if(ownsSneak) instance.keyboard.unpressKey(Key.Type.KEY_LSHIFT)
                ownsSneak=false; move(emptySet()); phase(Phase.AIM)
            } else if(tower==null) {
                instance.keyboard.pressKey(Key.Type.KEY_LSHIFT); ownsSneak=true
                if(phase==Phase.POSITION && pos.y+1<=p.boundingBox.minY+0.05 && support.y==pos.y) {
                    val x=support.x+0.5+(pos.x-support.x)*0.65
                    val z=support.z+0.5+(pos.z-support.z)*0.65
                    if(hypot(x-p.posX,z-p.posZ)>0.09) { steerTo(x,z); return }
                }
                move(emptySet()); phase(Phase.AIM)
            }
        } else {
            val live=w.getBlock(pos.x,pos.y,pos.z)
            if(Block.getIdFromBlock(live)!=a.blockId) {
                if(ownsMouse) { instance.mouse.unpressButton(MouseButton.Type.LEFT_CLICK); instance.playerController?.resetBlockRemoving() }
                phase(Phase.CONFIRM); return
            }
            if(phase==Phase.POSITION) phase(Phase.AIM)
        }

        val center=faceCenter(support,face)
        // posY already includes the legacy client's eye offset.
        val dx=center.xCoord-p.posX; val dy=center.yCoord-p.posY; val dz=center.zCoord-p.posZ
        if(sqrt(dx*dx+dy*dy+dz*dz)>4.5) { fail("support outside reach"); return }
        val yaw=Math.toDegrees(atan2(-dx,dz)).toFloat()
        val pitch=-Math.toDegrees(atan2(dy,hypot(dx,dz))).toFloat()
        instance.mouse.changeYaw(wrap(yaw-p.rotationYaw)); instance.mouse.changePitch(pitch-p.rotationPitch)
        ownsAim=true
        // Check the actual applied rotation, not a stale render hit or a queued mouse delta.
        instance.entityRenderer.getMouseOver(1.0f)
        val hit=instance.objectMouseOver
        val aimed=abs(wrap(yaw-p.rotationYaw))<1.5 && abs(pitch-p.rotationPitch)<1.5 &&
            hit?.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK &&
            hit.blockX==support.x && hit.blockY==support.y && hit.blockZ==support.z &&
            (face<0 || hit.sideHit==face)
        if(!aimed) {
            if(a.kind==BlockActionKind.BREAK && ownsMouse) instance.mouse.unpressButton(MouseButton.Type.LEFT_CLICK)
            return
        }
        if(tower!=null) {
            phase(Phase.EXECUTE)
            when(towerMotion?:tower!!.update(state(),false,false)) {
                TowerPlacement.Motion.TAKEOFF -> {
                    instance.keyboard.pressKey(Key.Type.KEY_SPACE); ownsJump=true; return
                }
                TowerPlacement.Motion.FAILED -> { fail("missed tower placement window"); return }
                TowerPlacement.Motion.RISING -> {
                    if(ownsJump) instance.keyboard.unpressKey(Key.Type.KEY_SPACE)
                    ownsJump=false; return
                }
                TowerPlacement.Motion.PLACE -> {
                    if(ownsJump) instance.keyboard.unpressKey(Key.Type.KEY_SPACE)
                    ownsJump=false
                }
                else -> return
            }
        }
        phase(Phase.EXECUTE); status=ActionStatus.EXECUTING
        if(a.kind==BlockActionKind.BREAK) {
            instance.mouse.pressButton(MouseButton.Type.LEFT_CLICK); ownsMouse=true
        } else {
            instance.mouse.pressButton(60,MouseButton.Type.RIGHT_CLICK); ownsMouse=true; clicked=true
            phase(Phase.CONFIRM)
        }
    }
    private fun wrap(v: Float): Float { var x=v%360; if(x>=180)x-=360; if(x< -180)x+=360; return x }
    private fun release() {
        if(ownsMouse || ownsAim) instance.mouse.clearPendingClicks()
        if(ownsMouse) { instance.mouse.unpressButton(MouseButton.Type.LEFT_CLICK,MouseButton.Type.RIGHT_CLICK); instance.playerController?.resetBlockRemoving() }
        if(ownsSneak) instance.keyboard.unpressKey(Key.Type.KEY_LSHIFT)
        if(ownsJump) instance.keyboard.unpressKey(Key.Type.KEY_SPACE)
        ownsMouse=false; ownsAim=false; ownsSneak=false; ownsJump=false
    }
    private fun fail(reason: String="inventory preparation failed") { move(emptySet()); release(); failureReason=reason; status=ActionStatus.FAILED }
    override fun cancelAction() {
        val previous=action
        if(previous!=null && status !in setOf(ActionStatus.IDLE,ActionStatus.CONFIRMED)) {
            permissions.check(actionId,previous,NavigationPermissionStore.CANCEL)?.let {
                instance.netHandler?.addToSendQueue(C17PacketCustomPayload("MineralBot",it))
            }
        }
        move(emptySet())
        release(); swapToken?.let { if(instance.inventoryTransactionStatus(it)!=InventoryTransactionStatus.PENDING) instance.forgetInventoryTransaction(it) else orphanedSwaps.add(it) }
        swapToken=null; selectedSlot=-1; clicked=false; action=null; status=ActionStatus.IDLE
        tower=null; supportPos=null; supportFace=-1; buoyancyHeld=false
    }
    override fun reset() { cancelAction(); pendingBinding.set(null); permissions.bind(""); payloads.clear(); blocks.clear(); blockRevision++ }
}
