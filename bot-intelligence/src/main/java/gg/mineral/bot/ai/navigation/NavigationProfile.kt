package gg.mineral.bot.ai.navigation

/** Local route preferences; permissions and the server protocol are shared by both profiles. */
enum class NavigationProfile { DEFAULT, BEDFIGHT }

/** A failed takeoff excludes only this directed jump, never a building position. */
data class JumpEdge(val from: gg.mineral.bot.api.navigation.BlockPos,
                    val to: gg.mineral.bot.api.navigation.BlockPos)
