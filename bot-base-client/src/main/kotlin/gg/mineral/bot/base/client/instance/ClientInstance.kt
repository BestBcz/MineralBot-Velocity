package gg.mineral.bot.base.client.instance

import com.google.common.collect.Multimap
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.controls.Keyboard
import gg.mineral.bot.api.controls.Mouse
import gg.mineral.bot.api.entity.ClientEntity
import gg.mineral.bot.api.entity.living.ClientLivingEntity
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.goal.GoalDebugState
import gg.mineral.bot.api.goal.Sporadic
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.Inventory
import gg.mineral.bot.api.inv.InventoryContainer
import gg.mineral.bot.api.inv.InventoryTransactionStatus
import gg.mineral.bot.api.inv.Slot
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.inv.item.ItemStack
import gg.mineral.bot.api.math.BoundingBox
import gg.mineral.bot.api.math.simulation.PlayerMotionSimulator
import gg.mineral.bot.api.screen.Screen
import gg.mineral.bot.api.screen.type.ContainerScreen
import gg.mineral.bot.api.screen.type.InventoryScreen
import gg.mineral.bot.api.world.ClientWorld
import gg.mineral.bot.api.world.block.Block
import gg.mineral.bot.base.client.inventory.InventoryTransactionTracker
import gg.mineral.bot.base.client.manager.InstanceManager
import gg.mineral.bot.impl.thread.ThreadManager
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet
import java.io.File
import java.net.Proxy
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.settings.KeyBinding
import net.minecraft.network.play.client.C0EPacketClickWindow
import net.minecraft.util.Session
import org.apache.logging.log4j.LogManager

open class ClientInstance(
        override val configuration: BotConfiguration,
        width: Int,
        height: Int,
        fullscreen: Boolean,
        demo: Boolean,
        gameDir: File,
        assetsDir: File,
        resourcePackDir: File,
        proxy: Proxy,
        version: String,
        userProperties: Multimap<*, *>,
        assetIndex: String
) :
        Minecraft(
                Session(configuration.fullUsername, configuration.uuid.toString(), "0", "legacy"),
                width,
                height,
                fullscreen,
                demo,
                gameDir,
                assetsDir,
                resourcePackDir,
                proxy,
                version,
                userProperties,
                assetIndex
        ),
        ClientInstance {

    @Volatile var backendControlListener: java.util.function.Consumer<ByteArray>? = null
    var bungeeGuardForwarding: BungeeGuardForwarding? = null
    @Volatile var directConnectionStage: String = "DIRECT_RESOLVE_TARGET"

    // Active goals.
    private val goals = ObjectLinkedOpenHashSet<Goal>()

    // Delayed tasks queue.
    private val delayedTasks = ConcurrentLinkedQueue<DelayedTask>()

    // Guide-created ghost targets so we can clean them up when real server entities exist.
    private val guideTargetEntityIds = mutableMapOf<UUID, Int>()
    private val pendingGuideUpdate = AtomicReference<GuideUpdate?>()
    var packetDiagnosticsListener: PacketDiagnosticsListener? = null
    var timingDiagnosticsListener: TimingDiagnosticsListener? = null

    override var latency: Int = 0

    @Volatile
    override var guidedTargetUuid: UUID? = null

    override var currentTick: Int = 0
    private var foregroundGoalName: String? = null
    private var foregroundGoalStartTick = -1
    private var lastForegroundGoalWarnTick = -200
    private var previousScreenOpen = false
    private val inventoryTransactions =
            InventoryTransactionTracker<net.minecraft.item.ItemStack>()

    override val keyboard: Keyboard
        get() = super.keyboard
    override val mouse: Mouse
        get() = super.mouse
    override val supportsTrackedInventoryTransactions: Boolean
        get() = true
    override val hasPendingInventoryTransaction: Boolean
        get() = inventoryTransactions.hasPending()
    override fun requestHotbarSwap(inventorySlot: Int, hotbarSlot: Int): Long? {
        if (inventorySlot !in 9..35 || hotbarSlot !in 0..8) return null
        if (currentScreen !is InventoryScreen || inventoryTransactions.hasPending()) return null

        val player = thePlayer ?: return null
        val handler = netHandler ?: return null
        val inventory = player.vanillaInventory
        val container = player.vanillaInventoryContainer
        if (player.vanillaOpenContainer !== container) return null

        val sourceSlot = container.getSlotFromInventory(inventory, inventorySlot) ?: return null
        val hotbarContainerSlot =
                container.getSlotFromInventory(inventory, hotbarSlot) ?: return null
        if (!sourceSlot.hasStack) return null

        val actionNumber = container.getNextTransactionID(inventory)
        val clickedItem =
                container.slotClick(sourceSlot.slotNumber, hotbarSlot, 2, player)
        val entry =
                inventoryTransactions.register(
                        windowId = container.windowId,
                        actionNumber = actionNumber,
                        sourceContainerSlot = sourceSlot.slotNumber,
                        hotbarContainerSlot = hotbarContainerSlot.slotNumber,
                        hotbarInventorySlot = hotbarSlot,
                        sourceAfter = sourceSlot.stack?.copy(),
                        hotbarAfter = hotbarContainerSlot.stack?.copy()
                ) ?: return null

        handler.addToSendQueue(
                C0EPacketClickWindow(
                        container.windowId,
                        sourceSlot.slotNumber,
                        hotbarSlot,
                        2,
                        clickedItem,
                        actionNumber
                )
        )
        return entry.token
    }

    override fun inventoryTransactionStatus(token: Long): InventoryTransactionStatus =
            inventoryTransactions.status(token)

    override fun forgetInventoryTransaction(token: Long) {
        inventoryTransactions.forget(token)
    }

    /** Called by the play packet handler when the server resolves a window click. */
    fun recordInventoryTransactionResult(windowId: Int, actionNumber: Short, accepted: Boolean) {
        val entry = inventoryTransactions.complete(windowId, actionNumber, accepted) ?: return
        if (!accepted) return

        restoreAcceptedInventoryPrediction(entry)
    }

    /** Called after a full server inventory snapshot has been applied. */
    fun recordInventoryWindowSync(windowId: Int) {
        inventoryTransactions.markWindowSynchronized(windowId)
        inventoryTransactions.consumeAcceptedForWindow(windowId).forEach {
            restoreAcceptedInventoryPrediction(it)
        }
    }

    /** Reapplies accepted predictions after an incremental server slot update. */
    fun preserveAcceptedInventoryPredictions(windowId: Int, containerSlot: Int) {
        inventoryTransactions.consumeAcceptedForSlot(windowId, containerSlot).forEach {
            restoreAcceptedInventoryPrediction(it)
        }
    }

    private fun restoreAcceptedInventoryPrediction(
        entry: InventoryTransactionTracker.Entry<net.minecraft.item.ItemStack>
    ) {
        val player = thePlayer ?: return
        val inventory = player.vanillaInventory
        val container = player.vanillaInventoryContainer
        if (container.windowId != entry.windowId) return

        // The server accepted the exact predicted result carried by C0E. Reapply that result so an
        // older SET_SLOT/WINDOW_ITEMS packet cannot leave the local view behind the server state.
        container.getSlot(entry.sourceContainerSlot).putStack(entry.sourceAfter?.copy())
        container
                .getSlotFromInventory(inventory, entry.hotbarInventorySlot)
                ?.putStack(entry.hotbarAfter?.copy())
    }

    override val hasActiveSporadicGoal: Boolean
        get() = activeSporadicGoal() != null
    override val blocksContinuousInventory: Boolean
        get() = inventoryTransactions.hasPending() || activeSporadic()?.blocksContinuousInventory() == true
    override val blocksContinuousAim: Boolean
        get() = activeSporadic()?.blocksContinuousAim() == true
    override val blocksContinuousAttack: Boolean
        get() = inventoryTransactions.hasPending() || activeSporadic()?.blocksContinuousAttack() == true
    override val blocksContinuousMovement: Boolean
        get() = activeSporadic()?.blocksContinuousMovement() == true

    init {
        mainThread = null
    }

    /** Internal data class to track delayed tasks. */
    internal data class DelayedTask(val runnable: Runnable, val sendTime: Long) {
        fun canSend(currentTime: Long): Boolean = currentTime >= sendTime
    }

    private data class GuideUpdate(
            val bX: Double,
            val bY: Double,
            val bZ: Double,
            val bYaw: Float,
            val bPitch: Float,
            val bHealth: Float,
            val bFood: Int,
            val bSat: Float,
            val targetUuid: UUID,
            val tX: Double,
            val tY: Double,
            val tZ: Double,
            val tYaw: Float,
            val tPitch: Float,
            val tVelX: Double,
            val tVelY: Double,
            val tVelZ: Double,
            val tHealth: Float,
            val tBlocking: Boolean
    )

    @JvmOverloads
    fun recordClientboundPacket(packetKey: String, entityId: Int = Int.MIN_VALUE, entityUuid: UUID? = null) {
        packetDiagnosticsListener?.onClientboundPacket(packetKey, entityId, entityUuid)
    }

    fun recordTimingEvent(
        event: String,
        queuedNanos: Long,
        velocityX: Double,
        velocityY: Double,
        velocityZ: Double
    ) {
        timingDiagnosticsListener?.onTimingEvent(event, queuedNanos, velocityX, velocityY, velocityZ)
    }

    /**
     * Schedules a task to run after a delay. If called on the main thread with zero delay and no
     * queued tasks, the task executes immediately.
     */
    fun scheduleTask(runnable: Runnable, delay: Long): Boolean {
        val currentTime = getSystemTime()
        if (isMainThread() && delay <= 0 && delayedTasks.isEmpty()) {
            runnable.run()
            return true
        }
        delayedTasks.add(DelayedTask(runnable, currentTime + delay))
        return false
    }

    /** Returns true if the current thread is the main game thread. */
    override fun isMainThread(): Boolean = Thread.currentThread() == mainThread

    override val gameLoopExecutor: ScheduledExecutorService
        get() = ThreadManager.gameLoopExecutor

    override val asyncExecutor: ExecutorService
        get() = ThreadManager.asyncExecutor

    private fun activeSporadicGoal(): Goal? {
        for (goal in goals) {
            if (goal is Sporadic && goal.executing) {
                return goal
            }
        }
        return null
    }

    private fun activeSporadic(): Sporadic? = activeSporadicGoal() as? Sporadic

    private fun foregroundGoal(): Goal? {
        val activeGoal = activeSporadicGoal()
        if (activeGoal != null) {
            return activeGoal
        }

        for (goal in goals) {
            if (goal is Sporadic && goal.checkExecute()) {
                return goal
            }
        }
        return null
    }

    private fun observeForegroundGoalDiagnostics() {
        // Normal actions such as drinking also hold controls for several ticks.
        if (!logger.isDebugEnabled) return

        val goal = activeSporadicGoal()
        val goalName = goal?.javaClass?.simpleName

        if (goalName != foregroundGoalName) {
            foregroundGoalName = goalName
            foregroundGoalStartTick = if (goal != null) currentTick else -1
            lastForegroundGoalWarnTick = -200
        }

        if (goal == null || foregroundGoalStartTick == -1) {
            return
        }

        val blocksControl = blocksContinuousAim || blocksContinuousAttack || blocksContinuousMovement
        val activeTicks = currentTick - foregroundGoalStartTick
        if (!blocksControl || activeTicks < FOREGROUND_GOAL_WARN_TICKS) {
            return
        }

        if (currentTick - lastForegroundGoalWarnTick < FOREGROUND_GOAL_WARN_INTERVAL_TICKS) {
            return
        }
        lastForegroundGoalWarnTick = currentTick

        val fakePlayer = fakePlayer
        val inventory = fakePlayer.inventory
        val heldItem = inventory.heldItemStack?.let { "${it.item.id}:${it.durability}x${it.count}" } ?: "empty"
        val pressedKeys = pressedKeySummary()
        val pressedButtons = pressedButtonSummary()
        val debugSummary = (goal as? GoalDebugState)?.debugSummary() ?: "n/a"
        val nearestEnemy = nearestEnemyDistance()?.let { formatDecimal(it) } ?: "none"

        logger.debug(
                "Foreground goal stall: goal={} activeTicks={} blocks=[inv:{},aim:{},atk:{},move:{}] health={} hunger={} held={} screen={} keys={} buttons={} nearestEnemy={} debug={}",
                goalName,
                activeTicks,
                blocksContinuousInventory,
                blocksContinuousAim,
                blocksContinuousAttack,
                blocksContinuousMovement,
                formatDecimal(fakePlayer.health.toDouble()),
                fakePlayer.hunger,
                heldItem,
                currentScreen?.javaClass?.simpleName ?: "none",
                pressedKeys,
                pressedButtons,
                nearestEnemy,
                debugSummary
        )
    }

    private fun nearestEnemyDistance(): Double? {
        val fakePlayer = fakePlayer
        return fakePlayer.world.entities
                .filterIsInstance<ClientLivingEntity>()
                .filter {
                    it.uuid != fakePlayer.uuid &&
                            !configuration.friendlyUUIDs.contains(it.uuid)
                }
                .minOfOrNull { fakePlayer.distance3DTo(it) }
    }

    private fun pressedKeySummary(): String {
        val keys =
                listOf(
                                gg.mineral.bot.api.controls.Key.Type.KEY_W,
                                gg.mineral.bot.api.controls.Key.Type.KEY_A,
                                gg.mineral.bot.api.controls.Key.Type.KEY_S,
                                gg.mineral.bot.api.controls.Key.Type.KEY_D,
                                gg.mineral.bot.api.controls.Key.Type.KEY_SPACE,
                                gg.mineral.bot.api.controls.Key.Type.KEY_LCONTROL,
                                gg.mineral.bot.api.controls.Key.Type.KEY_LSHIFT
                        )
                        .filter { keyboard.getKey(it)?.isPressed == true }
                        .map { it.name.removePrefix("KEY_") }
        return if (keys.isEmpty()) "none" else keys.joinToString(",")
    }

    private fun pressedButtonSummary(): String {
        val buttons =
                listOf(
                                gg.mineral.bot.api.controls.MouseButton.Type.LEFT_CLICK,
                                gg.mineral.bot.api.controls.MouseButton.Type.RIGHT_CLICK,
                                gg.mineral.bot.api.controls.MouseButton.Type.MIDDLE_CLICK
                        )
                        .filter { mouse.getButton(it)?.isPressed == true }
                        .map { it.name.removeSuffix("_CLICK") }
        return if (buttons.isEmpty()) "none" else buttons.joinToString(",")
    }

    private fun formatDecimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun recoverFromUnexpectedForegroundScreen() {
        val goal = activeSporadicGoal() ?: return
        val screen = currentScreen ?: return
        if (screen is ContainerScreen || theWorld == null) {
            return
        }

        val blocksControl = blocksContinuousAim || blocksContinuousAttack || blocksContinuousMovement
        if (!blocksControl) {
            return
        }

        logger.warn(
                "Closing unexpected screen during foreground goal: goal={} screen={} keys={} buttons={}",
                goal.javaClass.simpleName,
                screen.javaClass.simpleName,
                pressedKeySummary(),
                pressedButtonSummary()
        )
        displayGuiScreen(null)
    }

    private fun resyncHeldInputsAfterScreenClose() {
        if (theWorld == null) {
            return
        }

        gg.mineral.bot.api.controls.Key.Type.entries
                .filter { keyboard.getKey(it)?.isPressed == true }
                .forEach { KeyBinding.setKeyBindState(this, it.keyCode, true) }

        gg.mineral.bot.api.controls.MouseButton.Type.entries
                .filter { it.keyCode >= 0 && mouse.getButton(it)?.isPressed == true }
                .forEach { KeyBinding.setKeyBindState(this, it.keyCode - 100, true) }
    }

    private inline fun forEachContinuousGoal(action: (Goal) -> Unit) {
        for (goal in goals) {
            if (goal !is Sporadic && goal.checkExecute()) {
                action(goal)
            }
        }
    }

    override fun runGameLoop() {
        if (!running) return
        mainThread = Thread.currentThread()

        foregroundGoal()?.callGameLoop()
        forEachContinuousGoal { goal ->
            goal.callGameLoop()
        }

        val currentTime = getSystemTime()
        while (delayedTasks.isNotEmpty()) {
            val task = delayedTasks.peek()
            if (task.canSend(currentTime)) {
                task.runnable.run()
                delayedTasks.poll()
            } else {
                break
            }
        }

        // Call game-loop updates for keyboard and mouse.
        super.keyboard.onGameLoop(getSystemTime())
        super.mouse.onGameLoop(getSystemTime())

        super.runGameLoop()
    }

    override fun schedule(runnable: Runnable, delay: Long): Boolean = scheduleTask(runnable, delay)
    override val session: gg.mineral.bot.api.instance.Session
        get() = super.getSession()

    override fun <T : Event> callEvent(event: T): Boolean {
        if (event is gg.mineral.bot.api.event.peripherals.MouseButtonEvent && event.pressed) {
            if (currentScreen is InventoryScreen || hasPendingInventoryTransaction) return true
            if (event.type == gg.mineral.bot.api.controls.MouseButton.Type.LEFT_CLICK &&
                    blocksContinuousAttack) return true
        }
        var cancelled = false

        activeSporadicGoal()?.let { goal ->
            cancelled = goal.onEvent(event) || cancelled
        }

        forEachContinuousGoal { goal ->
            cancelled = goal.onEvent(event) || cancelled
        }

        return cancelled
    }

    override fun runTick() {
        applyPendingGuideUpdate()
        super.runTick()
        currentTick++
        recoverFromUnexpectedForegroundScreen()
        if (previousScreenOpen && currentScreen == null) {
            resyncHeldInputsAfterScreenClose()
        }
        previousScreenOpen = currentScreen != null

        // Update latency using a Gaussian distribution from the fake player's random.
        val fp = fakePlayer
        latency =
                fp.random
                        .nextGaussian(
                                configuration.latency.toDouble(),
                                configuration.latencyDeviation.toDouble()
                        )
                        .toInt()

        foregroundGoal()?.callTick()
        forEachContinuousGoal { goal ->
            goal.callTick()
        }
        observeForegroundGoalDiagnostics()
    }

    @SafeVarargs
    override fun <T : Goal> startGoals(vararg goals: T) {
        for (goal in goals) {
            if (this.goals.add(goal)) {
                logger.debug("Added goal: {}", goal.javaClass.simpleName)
            } else {
                logger.debug("Failed to add goal: {}", goal.javaClass.simpleName)
            }
        }
    }

    override fun shutdownMinecraftApplet() {
        if (InstanceManager.instances.remove(configuration.uuid) != null)
                logger.debug("Removed instance: {}", configuration.uuid)
        if (InstanceManager.pendingInstances.remove(configuration.uuid) != null)
                logger.debug("Removed pending instance: {}", configuration.uuid)
        goals.clear()
        running = false
        logger.debug("Stopping!")
        try {
            loadWorld(null as WorldClient?)
        } catch (e: Throwable) {
            // Ignore any errors during shutdown.
        }
        mcSoundHandler?.func_147685_d()
    }

    override val isRunning: Boolean
        get() = running

    override fun timeMillis(): Long = getSystemTime()

    override var currentScreen: Screen?
        get() = super.currentScreen
        set(value) {
            super.currentScreen = value as GuiScreen?
        }

    override val fakePlayer: FakePlayer
        get() {
            val player = thePlayer
            if (player is FakePlayer) {
                return player
            }
            // If the player is not an instance of FakePlayer, return a default FakePlayer.
            return object : FakePlayer {
                override val lastReportedX: Double = 0.0
                override val lastReportedY: Double = 0.0
                override val lastReportedZ: Double = 0.0
                override val inventory: Inventory =
                        object : Inventory {
                            override val heldItemStack: ItemStack? = null
                            override val heldSlot: Int = 0

                            override fun getItemStackAt(slot: Int): ItemStack? {
                                return null
                            }

                            override val helmet = null
                            override val chestplate = null
                            override val leggings = null
                            override val boots = null

                            override fun findSlot(item: Item): Int {
                                return -1
                            }

                            override fun findSlot(id: Int): Int {
                                return -1
                            }

                            override val items: Array<ItemStack?>
                                get() = emptyArray()
                        }
                override val inventoryContainer =
                        object : InventoryContainer {
                            override fun getSlot(inventory: Inventory, slot: Int): Slot? {
                                return null
                            }
                        }

                override val eyeHeight: Float = 0f
                override val username = configuration.fullUsername
                override val hunger = 20f
                override val isEatingOrDrinking = false
                override val headY = 0.0
                override val activePotionEffectIds = intArrayOf()
                override val clientActivePotionEffects = emptyList<gg.mineral.bot.api.entity.effect.PotionEffect>()
                override fun isPotionActive(potionId: Int): Boolean = false
                override val health = 0f
                override val uuid = configuration.uuid
                override val collidingBoundingBox: BoundingBox?
                    get() = null
                override val entityId = 0
                override val x = 0.0
                override val y = 0.0
                override val z = 0.0
                override val yaw = 0f
                override val pitch = 0f
                override val isOnGround = false
                override val lastX = 0.0
                override val lastY = 0.0
                override val lastZ = 0.0
                override var motionX = 0.0
                override var motionY = 0.0
                override var motionZ = 0.0
                override val world: ClientWorld
                    get() =
                            object : ClientWorld {
                                override val entities: Collection<ClientEntity> = emptyList()

                                override fun getEntityByID(entityId: Int): ClientEntity? {
                                    return null
                                }

                                override fun getBlockAt(x: Int, y: Int, z: Int): Block {
                                    return object : Block {
                                        override val id: Int = 0

                                        override fun getCollisionBoundingBox(
                                                world: ClientWorld,
                                                xTile: Int,
                                                yTile: Int,
                                                zTile: Int
                                        ): BoundingBox? {
                                            return null
                                        }
                                    }
                                }

                                override fun getBlockAt(x: Double, y: Double, z: Double): Block {
                                    return object : Block {
                                        override val id: Int = 0

                                        override fun getCollisionBoundingBox(
                                                world: ClientWorld,
                                                xTile: Int,
                                                yTile: Int,
                                                zTile: Int
                                        ): BoundingBox? {
                                            return null
                                        }
                                    }
                                }

                                override fun getBlockMetadataAt(x: Int, y: Int, z: Int): Int = 0
                            }
                override val random: Random
                    get() = Random()
                override var boundingBox: BoundingBox =
                        object : BoundingBox {
                            override var minX: Double = 0.0
                            override var minY: Double = 0.0
                            override var minZ: Double = 0.0
                            override var maxX: Double = 0.0
                            override var maxY: Double = 0.0
                            override var maxZ: Double = 0.0
                        }

                override val isSprinting = false
                override val clientInstance = this@ClientInstance
                override fun motionSimulator(world: ClientWorld): PlayerMotionSimulator {
                    return gg.mineral.bot.base.client.math.simulation.PlayerMotionSimulator(
                            this@ClientInstance,
                            this,
                            world
                    )
                }
            }
        }

    override fun newMouse(): Mouse = gg.mineral.bot.lwjgl.input.Mouse(this)

    override fun newKeyboard(): Keyboard = gg.mineral.bot.lwjgl.input.Keyboard(this)

    override var displayHeight: Int
        get() = super.displayHeight
        set(value) {
            super.displayHeight = value
        }

    override var displayWidth: Int
        get() = super.displayWidth
        set(value) {
            super.displayWidth = value
        }

    private fun applyPendingGuideUpdate() {
        val update = pendingGuideUpdate.getAndSet(null) ?: return
        applyGuideUpdate(update)
    }

    companion object {
        private const val FOREGROUND_GOAL_WARN_TICKS = 20
        private const val FOREGROUND_GOAL_WARN_INTERVAL_TICKS = 20
        private val logger = LogManager.getLogger(ClientInstance::class.java)
    }

    /** Updates the bot's state and target entity from external guide data. */
    fun updateFromGuide(
            bX: Double,
            bY: Double,
            bZ: Double,
            bYaw: Float,
            bPitch: Float,
            bHealth: Float,
            bFood: Int,
            bSat: Float,
            targetUuid: UUID,
            tX: Double,
            tY: Double,
            tZ: Double,
            tYaw: Float,
            tPitch: Float,
            tVelX: Double,
            tVelY: Double,
            tVelZ: Double,
            tHealth: Float,
            tBlocking: Boolean
    ) {
        val update =
                GuideUpdate(
                        bX,
                        bY,
                        bZ,
                        bYaw,
                        bPitch,
                        bHealth,
                        bFood,
                        bSat,
                        targetUuid,
                        tX,
                        tY,
                        tZ,
                        tYaw,
                        tPitch,
                        tVelX,
                        tVelY,
                        tVelZ,
                        tHealth,
                        tBlocking
                )

        if (!isMainThread()) {
            pendingGuideUpdate.set(update)
            return
        }

        applyGuideUpdate(update)
    }

    private fun applyGuideUpdate(update: GuideUpdate) {
        guidedTargetUuid = update.targetUuid

        val player = this.thePlayer
        if (player != null) {
            player.setHealth(update.bHealth)
            // player.foodStats.foodLevel = bFood // Accessor might vary
            // player.foodStats.saturationLevel = bSat
        }

        val world = this.theWorld
        if (world != null) {
            var targetEntity = world.playerEntities.firstOrNull { it.gameProfile.id == update.targetUuid }
            val guideEid = update.targetUuid.hashCode() or Int.MIN_VALUE

            if (targetEntity != null) {
                guideTargetEntityIds.remove(update.targetUuid)?.let { world.removeEntityFromWorld(it) }
            } else {
                val existing = world.getEntityByID(guideEid)
                targetEntity = if (existing is net.minecraft.client.entity.EntityOtherPlayerMP) {
                    existing
                } else {
                    val profile = com.mojang.authlib.GameProfile(update.targetUuid, "Target")
                    net.minecraft.client.entity.EntityOtherPlayerMP(this, world, profile).also {
                        // This guide-only entity is for aim info and must not affect collisions/knockback.
                        it.noClip = true
                        world.addEntityToWorld(guideEid, it)
                        guideTargetEntityIds[update.targetUuid] = guideEid
                    }
                }
            }

            // Update target state - Target MUST be exact as we don't simulate it
            targetEntity.setPositionAndRotation(update.tX, update.tY, update.tZ, update.tYaw, update.tPitch)
            if (targetEntity is net.minecraft.entity.EntityLivingBase) {
                targetEntity.setHealth(update.tHealth)
            }
            targetEntity.motionX = update.tVelX
            targetEntity.motionY = update.tVelY
            targetEntity.motionZ = update.tVelZ
        }
    }

    fun updateKnockbackProfile(profile: gg.mineral.bot.base.client.profile.KnockbackProfile) {
        val player = this.thePlayer
        if (player != null) {
            player.setKnockbackProfile(profile)
            if (logger.isDebugEnabled) logger.debug("Updated knockback profile for {}", player.commandSenderName)
        }
    }
}
