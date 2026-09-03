package gg.mineral.bot.api.inv

/**
 * State of an inventory transaction initiated by a bot goal.
 *
 * Implementations which do not support tracked inventory transactions return [UNKNOWN].
 */
enum class InventoryTransactionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    UNKNOWN
}
