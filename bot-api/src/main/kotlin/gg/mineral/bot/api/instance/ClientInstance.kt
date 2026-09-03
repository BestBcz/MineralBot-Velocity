package gg.mineral.bot.api.instance

import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.controls.Keyboard
import gg.mineral.bot.api.controls.Mouse
import gg.mineral.bot.api.entity.living.player.FakePlayer
import gg.mineral.bot.api.event.EventHandler
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.inv.InventoryTransactionStatus
import gg.mineral.bot.api.screen.Screen
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.UUID

interface ClientInstance : EventHandler {
    /**
     * Sets the player's goals.
     *
     * @param goals the goals
     */
    fun <T : Goal> startGoals(vararg goals: T)

    /**
     * Gets the current latency.
     *
     * @return the current latency
     */
    val latency: Int

    /**
     * Gets the current tick.
     *
     * @return the current tick
     */
    val currentTick: Int

    /**
     * Returns the current screen that the fake player is viewing.
     *
     * @return the current screen of the fake player
     */
    val currentScreen: Screen?

    /**
     * Gets the bot configuration.
     *
     * @return the bot configuration
     */
    val configuration: BotConfiguration

    /** Target currently recommended by an authoritative external combat guide. */
    val guidedTargetUuid: UUID?
        get() = null

    /**
     * Gets the keyboard.
     *
     * @return the keyboard
     */
    val keyboard: Keyboard

    /**
     * Gets the mouse.
     *
     * @return the mouse
     */
    val mouse: Mouse

    /**
     * Gets the fake player.
     *
     * @return the fake player
     */
    val fakePlayer: FakePlayer

    /** Whether this client can correlate inventory clicks with server acknowledgements. */
    val supportsTrackedInventoryTransactions: Boolean
        get() = false

    /** True while a click acknowledgement or rejection resynchronization is outstanding. */
    val hasPendingInventoryTransaction: Boolean
        get() = false

    /**
     * Requests an inventory hotbar-swap transaction and returns an opaque transaction token.
     *
     * A null result means that the implementation cannot start a tracked transaction right now.
     */
    fun requestHotbarSwap(inventorySlot: Int, hotbarSlot: Int): Long? = null

    /** Returns the latest state for a token returned by [requestHotbarSwap]. */
    fun inventoryTransactionStatus(token: Long): InventoryTransactionStatus =
        InventoryTransactionStatus.UNKNOWN

    /** Releases a completed transaction token once the caller has consumed its result. */
    fun forgetInventoryTransaction(token: Long) {}

    /**
     * Returns whether a sporadic foreground goal is currently executing.
     */
    val hasActiveSporadicGoal: Boolean
        get() = false

    /**
     * Returns whether the active foreground goal is holding inventory control.
     */
    val blocksContinuousInventory: Boolean
        get() = false

    /**
     * Returns whether the active foreground goal is holding aim control.
     */
    val blocksContinuousAim: Boolean
        get() = false

    /**
     * Returns whether the active foreground goal is holding attack control.
     */
    val blocksContinuousAttack: Boolean
        get() = false

    /**
     * Returns whether the active foreground goal is holding movement control.
     */
    val blocksContinuousMovement: Boolean
        get() = false

    /**
     * Checks if the client is running.
     *
     * @return true if the client is running
     */
    val isRunning: Boolean

    /**
     * Gets the time in milliseconds.
     *
     * @return the time in milliseconds
     */
    fun timeMillis(): Long

    /**
     * Gets the game loop executor.
     *
     * @return the game loop executor
     */
    val gameLoopExecutor: ScheduledExecutorService

    /**
     * Gets the async executor.
     *
     * @return the async executor
     */
    val asyncExecutor: ExecutorService

    /**
     * Schedules a task to run after a delay.
     *
     * @param runnable the task to run
     * @param delay    the delay in milliseconds
     * @return true if the task was executed immediately
     */
    fun schedule(runnable: Runnable, delay: Long): Boolean

    /**
     * Gets the player's session.
     *
     * @return the player's session
     */
    val session: Session

    /**
     * Shuts down the instance.
     */
    fun shutdown()

    /**
     * Create a new mouse.
     *
     * @return the new mouse
     */
    fun newMouse(): Mouse

    /**
     * Create a new keyboard.
     *
     * @return the new keyboard
     */
    fun newKeyboard(): Keyboard

    /**
     * Gets the display height.
     *
     * @return the display height
     */
    val displayHeight: Int

    /**
     * Gets the display width.
     *
     * @return the display width
     */
    val displayWidth: Int
}
