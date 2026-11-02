package gg.mineral.bot.ai.goal.type

import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.Keyboard
import gg.mineral.bot.api.controls.Mouse
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.ClientEntity
import gg.mineral.bot.api.entity.effect.PotionEffect
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.event.peripherals.MouseButtonEvent
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack
import gg.mineral.bot.api.inv.potion.Potion
import gg.mineral.bot.api.math.BoundingBox
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.math.floor

internal class SupplyTestFixture {
    data class Effect(override val potionID: Int, override val duration: Int, override val amplifier: Int = 0) : PotionEffect
    class Stack(id: Int, override var count: Int = 3, override val durability: Int = 0,
                override val potion: Potion? = null, override val attackDamage: Double = 0.0) : ItemStack {
        override val item = object : Item { override val id = id }
    }
    class PlayerData(var x: Double = 0.0, var consuming: Boolean = false) {
        var velocityX = 0.0
        var velocityZ = 0.0
        var yaw = -90f
        var pitch = 0f
        var hunger = 19f
        var health = 20f
        var sprinting = false
        var heldSlot = 0
        val slots = mutableMapOf<Int, Stack>()
        val effects = mutableListOf<PotionEffect>()
    }

    var tick = 1000
    var latency = 0
    var terrain = "flat"
    var occluded = false
    var leftClicks = 0
    var rightClicks = 0
    var stoppedUses = 0
    var pendingYaw: Float? = null
    val yawAtUse = mutableListOf<Float?>()
    var activeGoal: Goal? = null
    val buttons = mutableSetOf<MouseButton.Type>()
    val movement = mutableSetOf<Key.Type>()
    val inputOrder = mutableListOf<String>()
    val config = BotConfiguration(friendlyUUIDs = mutableSetOf(), pearlCooldown = 0)
    val selfData = PlayerData()
    val enemyData = PlayerData(4.0, consuming = true).apply { yaw = 90f }
    val extraData = PlayerData(20.0).apply { yaw = 90f }
    val entities = mutableListOf<ClientEntity>()

    private val world = proxy<ClientWorld> { name, args -> when (name) {
        "getEntities" -> entities
        "getBlockAt" -> {
            val x = floor((args!![0] as Number).toDouble()).toInt()
            val y = floor((args[1] as Number).toDouble()).toInt()
            val z = floor((args[2] as Number).toDouble()).toInt()
            val id = when {
                terrain == "drop" -> Block.AIR
                terrain == "wall" && y >= 64 -> Block.STONE
                terrain == "lava" && y == 64 -> Block.LAVA_STILL
                occluded && x == 1 && y in 64..66 -> Block.STONE
                y < 64 -> Block.STONE
                else -> Block.AIR
            }
            proxy<Block> { property, _ -> when (property) {
                "getId" -> id
                "getCollisionBoundingBox" -> if (id == Block.STONE) bounds(x.toDouble(), y.toDouble(), z.toDouble(), 1.0, 1.0) else null
                else -> null
            } }
        }
        else -> null
    } }
    val self: FakePlayer = player(0, selfData)
    val enemy: ClientPlayer = player(1, enemyData)
    val extra: ClientPlayer = player(2, extraData)

    private val mouse = proxy<Mouse> { name, args -> when (name) {
        "getButton" -> {
            val type = args!![0] as MouseButton.Type
            proxy<MouseButton> { property, _ -> when (property) {
                "getType" -> type
                "isPressed" -> type in buttons
                else -> null
            } }
        }
        "pressButton", "unpressButton" -> {
            val press = name == "pressButton"
            val types = args!!.filterIsInstance<Array<*>>().single()
            for (type in types.filterIsInstance<MouseButton.Type>()) {
                if ((activeGoal as? Sporadic)?.executing == true &&
                    activeGoal?.onEvent(MouseButtonEvent(type, press)) == true) continue
                if (press && buttons.add(type)) {
                    if (type == MouseButton.Type.LEFT_CLICK) { leftClicks++; inputOrder.add("attack") }
                    if (type == MouseButton.Type.RIGHT_CLICK) {
                        rightClicks++; selfData.consuming = true; inputOrder.add("use"); yawAtUse.add(pendingYaw)
                    }
                } else if (!press) buttons.remove(type)
            }
            Unit
        }
        "clearPendingClicks", "stopAll" -> { buttons.clear(); pendingYaw = null; Unit }
        "changeYaw" -> { pendingYaw = args!![0] as Float; selfData.yaw += pendingYaw!!; Unit }
        "changePitch" -> { selfData.pitch += args!![0] as Float; Unit }
        else -> null
    } }
    private val keyboard = proxy<Keyboard> { name, args -> when (name) {
        "pressKey", "unpressKey" -> {
            val types = args!!.filterIsInstance<Array<*>>().single().filterIsInstance<Key.Type>()
            for (type in types) {
                if (name == "pressKey") {
                    if (type in Key.Type.KEY_1..Key.Type.KEY_9) {
                        selfData.heldSlot = type.ordinal - Key.Type.KEY_1.ordinal
                        inputOrder.add("slot:${selfData.heldSlot}")
                    } else movement.add(type)
                } else movement.remove(type)
            }
            Unit
        }
        else -> null
    } }
    val client = proxy<ClientInstance> { name, _ -> when (name) {
        "getFakePlayer" -> self
        "getConfiguration" -> config
        "getCurrentTick" -> tick
        "getLatency" -> latency
        "getMouse" -> mouse
        "getKeyboard" -> keyboard
        "getHasActiveSporadicGoal" -> (activeGoal as? Sporadic)?.executing == true
        "getBlocksContinuousInventory" -> (activeGoal as? Sporadic)?.let { it.executing && it.blocksContinuousInventory() } ?: false
        "getBlocksContinuousAim" -> (activeGoal as? Sporadic)?.let { it.executing && it.blocksContinuousAim() } ?: false
        "getBlocksContinuousAttack" -> (activeGoal as? Sporadic)?.let { it.executing && it.blocksContinuousAttack() } ?: false
        "getBlocksContinuousMovement" -> (activeGoal as? Sporadic)?.let { it.executing && it.blocksContinuousMovement() } ?: false
        else -> null
    } }

    init { entities.add(enemy) }

    fun potion(effect: Int, variant: Int = effect, splash: Boolean = false): Stack =
        Stack(Item.POTION, durability = variant, potion = object : Potion {
            override val isSplash = splash
            override val effects = listOf(Effect(effect, 3600))
        })

    fun start(goal: Goal) {
        activeGoal = goal
        check(goal.checkExecute()) { "Goal did not start" }
    }

    fun step(goal: Goal) {
        tick++
        goal.callTick()
    }

    fun startUsing(goal: Goal) {
        start(goal)
        repeat(12) {
            if (rightClicks > 0) return
            step(goal)
        }
        error("Supply did not start: ${goal.javaClass.simpleName}")
    }

    private inline fun <reified T : ClientPlayer> player(id: Long, data: PlayerData): T {
        val inventory = proxy<Inventory> { name, args -> when (name) {
            "getItemStackAt" -> data.slots[args!![0] as Int]
            "getHeldSlot" -> data.heldSlot
            "getHeldItemStack" -> data.slots[data.heldSlot]
            else -> null
        } }
        return proxy { name, _ -> when (name) {
            "getUuid" -> UUID(0, id)
            "getEntityId" -> id.toInt()
            "getUsername" -> "Player$id"
            "getX", "getLastX" -> data.x
            "getY", "getLastY" -> 64.0
            "getZ", "getLastZ" -> 0.0
            "getMotionX" -> data.velocityX
            "getMotionZ" -> data.velocityZ
            "getYaw" -> data.yaw
            "getPitch" -> data.pitch
            "getBoundingBox" -> bounds(data.x - 0.3, 64.0, -0.3, 0.6, 1.8)
            "getEyeHeight" -> 1.62f
            "getInventory" -> inventory
            "getWorld" -> world
            "getActivePotionEffectIds" -> data.effects.map { it.potionID }.toIntArray()
            "getClientActivePotionEffects" -> data.effects
            "getHealth" -> data.health
            "getHunger" -> data.hunger
            "isEatingOrDrinking" -> data.consuming
            "isOnGround" -> true
            "isSprinting" -> data.sprinting
            "getRandom" -> java.util.Random(1)
            "stopUsingItem" -> { data.consuming = false; if (id == 0L) stoppedUses++; Unit }
            else -> null
        } }
    }

    private fun bounds(x: Double, y: Double, z: Double, width: Double, height: Double) =
        proxy<BoundingBox> { name, _ -> when (name) {
            "getMinX" -> x; "getMaxX" -> x + width
            "getMinY" -> y; "getMaxY" -> y + height
            "getMinZ" -> z; "getMaxZ" -> z + width
            else -> null
        } }

    companion object {
        private inline fun <reified T> proxy(crossinline values: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
                val value = values(method.name, args)
                if (value != null) value
                else if (method.isDefault) InvocationHandler.invokeDefault(instance, method, *(args ?: emptyArray()))
                else when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            } as T
    }
}
