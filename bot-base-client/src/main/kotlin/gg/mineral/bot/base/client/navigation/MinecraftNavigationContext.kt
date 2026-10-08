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
        if(cachedTick!=instance.currentTick) { blocks.clear(); cachedTick=instance.currentTick }
    }
    override fun state(): NavigationState {
        pump(); val p=instance.thePlayer ?: return NavigationState(NavVec(0.0,0.0,0.0),0f,false,false,300)
        val b=p.boundingBox
        return NavigationState(NavVec(p.posX,b.minY,p.posZ),p.rotationYaw,p.onGround,p.isInWater,p.air,
            b.maxX-b.minX,b.maxY-b.minY,NavVec(p.motionX,p.motionY,p.motionZ))
    }
    override fun block(pos: BlockPos): NavBlock? {
        if(blocks.containsKey(pos)) return blocks[pos]
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
        val tool=if(permissions.permissions?.canBreak(pos,id)==true && boxes.isNotEmpty()) tool(pos,b) else (-1 to Double.POSITIVE_INFINITY)
        return NavBlock(id,meta,boxes,fluid,id in setOf(10,11,51,81),b.material.isReplaceable || id==0 || fluid,flow,tool.second,tool.first).also { blocks[pos]=it }
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
        val payload=permissions.check(actionId,action) ?: return ActionStatus.FAILED.also { status=it }
        instance.netHandler?.addToSendQueue(C17PacketCustomPayload("MineralBot",payload)) ?: return ActionStatus.FAILED.also { status=it }
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
        if(slot !in 0..35) { status=ActionStatus.FAILED; return false }
        swapToken?.let { token ->
            when(instance.inventoryTransactionStatus(token)) {
                InventoryTransactionStatus.PENDING -> return false
                InventoryTransactionStatus.ACCEPTED -> { instance.forgetInventoryTransaction(token); swapToken=null; selectedSlot=8; slot=8 }
                else -> { instance.forgetInventoryTransaction(token); swapToken=null; status=ActionStatus.FAILED; return false }
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
            status=ActionStatus.FAILED; return false
        }
        return true
    }
    override fun tickAction() {
        pump(); if(lastActionTick==instance.currentTick) return; lastActionTick=instance.currentTick
        val a=action?:return
        if(status !in setOf(ActionStatus.WAITING,ActionStatus.EXECUTING)) return
        if(permissions.permissions?.building!=true || instance.currentTick-startedTick>300+instance.latency/50) { fail(); return }
        val reply=permissions.reply?.takeIf { it.first==actionId }?.second
        if(reply==0) { fail(); return }
        if(reply==2) {
            val b=block(a.pos)
            if(b!=null && (if(a.kind==BlockActionKind.BREAK) b.id==0 || b.replaceable else b.id==a.blockId && b.metadata==a.metadata)) {
                release(); status=ActionStatus.CONFIRMED
            }
            return
        }
        if(reply!=1 || !prepareSlot(a)) return
        val p=instance.thePlayer?:return
        val w=instance.theWorld?:return
        val pos=a.pos
        var support=pos; var face=-1
        if(a.kind==BlockActionKind.PLACE) {
            val candidates=listOf(pos.offset(0,-1,0) to 1,pos.offset(-1,0,0) to 5,pos.offset(1,0,0) to 4,pos.offset(0,0,-1) to 3,pos.offset(0,0,1) to 2)
            val candidate=candidates.firstOrNull { block(it.first)?.boxes?.isNotEmpty()==true }?:run { fail(); return }
            support=candidate.first; face=candidate.second
            if(pos.y+1>p.boundingBox.minY+0.05 && abs(pos.x+0.5-p.posX)<0.5 && abs(pos.z+0.5-p.posZ)<0.5) {
                instance.keyboard.pressKey(Key.Type.KEY_SPACE); ownsJump=true; return
            }
            instance.keyboard.unpressKey(Key.Type.KEY_SPACE); ownsJump=false
            instance.keyboard.pressKey(Key.Type.KEY_LSHIFT); ownsSneak=true
            if(pos.y+1<=p.boundingBox.minY+0.05 && support.y==pos.y && !clicked) {
                // Sneak to the lip so the outside support face becomes visible for bridging.
                val sx=support.x+0.5+(pos.x-support.x)*0.65
                val sz=support.z+0.5+(pos.z-support.z)*0.65
                if(hypot(sx-p.posX,sz-p.posZ)>0.09) {
                    val angle=atan2(-(sx-p.posX),sz-p.posZ)-Math.toRadians(p.rotationYaw.toDouble())
                    val keys=mutableSetOf<Key.Type>()
                    if(cos(angle)>0.38)keys.add(Key.Type.KEY_W)
                    if(cos(angle)< -0.38)keys.add(Key.Type.KEY_S)
                    if(sin(angle)< -0.38)keys.add(Key.Type.KEY_A)
                    if(sin(angle)>0.38)keys.add(Key.Type.KEY_D)
                    move(keys); return
                }
            }
            move(emptySet())
        }
        val center=Vec3.createVectorHelper(support.x+0.5,support.y+0.5,support.z+0.5)
        if(face==1) center.yCoord=support.y+1.0
        if(face==5) center.xCoord=support.x+1.0
        if(face==4) center.xCoord=support.x.toDouble()
        if(face==3) center.zCoord=support.z+1.0
        if(face==2) center.zCoord=support.z.toDouble()
        // The legacy client's render ray starts at posY (already includes its eye offset).
        val eyeY=p.posY
        val dx=center.xCoord-p.posX; val dy=center.yCoord-eyeY; val dz=center.zCoord-p.posZ
        if(sqrt(dx*dx+dy*dy+dz*dz)>4.5) { fail(); return }
        val yaw=Math.toDegrees(atan2(-dx,dz)).toFloat(); val pitch=-Math.toDegrees(atan2(dy,hypot(dx,dz))).toFloat()
        instance.mouse.changeYaw(wrap(yaw-p.rotationYaw)); instance.mouse.changePitch(pitch-p.rotationPitch)
        val hit=instance.objectMouseOver
        if(hit?.typeOfHit!=MovingObjectPosition.MovingObjectType.BLOCK || hit.blockX!=support.x || hit.blockY!=support.y || hit.blockZ!=support.z || (face>=0 && hit.sideHit!=face)) return
        status=ActionStatus.EXECUTING
        if(a.kind==BlockActionKind.BREAK) {
            val live=w.getBlock(pos.x,pos.y,pos.z)
            if(Block.getIdFromBlock(live)!=a.blockId) return
            instance.mouse.pressButton(MouseButton.Type.LEFT_CLICK); ownsMouse=true
        } else if(!clicked) {
            instance.mouse.pressButton(25,MouseButton.Type.RIGHT_CLICK); ownsMouse=true; clicked=true
        }
    }
    private fun wrap(v: Float): Float { var x=v%360; if(x>=180)x-=360; if(x< -180)x+=360; return x }
    private fun release() {
        if(ownsMouse) { instance.mouse.clearPendingClicks(); instance.mouse.unpressButton(MouseButton.Type.LEFT_CLICK,MouseButton.Type.RIGHT_CLICK); instance.playerController?.resetBlockRemoving() }
        if(ownsSneak) instance.keyboard.unpressKey(Key.Type.KEY_LSHIFT)
        if(ownsJump) instance.keyboard.unpressKey(Key.Type.KEY_SPACE)
        ownsMouse=false; ownsSneak=false; ownsJump=false
    }
    private fun fail() { release(); status=ActionStatus.FAILED }
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
    }
    override fun reset() { cancelAction(); pendingBinding.set(null); permissions.bind(""); payloads.clear(); blocks.clear(); blockRevision++ }
}
