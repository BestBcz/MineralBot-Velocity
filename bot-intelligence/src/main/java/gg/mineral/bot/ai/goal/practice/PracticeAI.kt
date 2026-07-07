package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.*
import gg.mineral.bot.api.configuration.BotDifficulty
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.api.instance.ClientInstance

/**
 * Practice AI Manager - Configures bot goals based on kit type.
 *
 * Supported kit types:
 * - NoDebuff: Splash potions, ender pearls, W-tap, strafe
 * - Debuff: Includes poison/slowness pots
 * - Gapple: Golden apples, sword combat
 * - Sumo: Knockback-focused, platform control
 * - BuildUHC: Blocks, lava, fishing rod, golden heads
 * - Soup: Soup healing instead of potions
 * - Boxing: Pure melee, no items
 * - Axe: Axe combat (high damage)
 * - Combo: Fast-paced combo trades
 * - Classic: Basic sword + bow
 */
object PracticeAI {

    /**
     * Get appropriate goals for the given kit type. Goals are ordered by priority (highest priority
     * first).
     */
    fun getGoalsForKit(clientInstance: ClientInstance, kitType: String): Array<Goal> {
        val normalizedKit = kitType.lowercase().replace(" ", "").replace("_", "")

        return when {
            normalizedKit.contains("nodebuff") || normalizedKit.contains("nomisplace") ->
                    getNoDebuffGoals(clientInstance)
            normalizedKit.contains("debuff") -> getDebuffGoals(clientInstance)
            normalizedKit.contains("sumo") -> getSumoGoals(clientInstance)
            normalizedKit.contains("builduhc") || normalizedKit.contains("uhc") ->
                    getBuildUHCGoals(clientInstance)
            normalizedKit.contains("soup") || normalizedKit.contains("kitmap") ->
                    getSoupGoals(clientInstance)
            normalizedKit.contains("gapple") || normalizedKit.contains("diamond") ->
                    getGappleGoals(clientInstance)
            normalizedKit.contains("box") || normalizedKit.contains("boxing") ->
                    getBoxingGoals(clientInstance)
            normalizedKit.contains("axe") || normalizedKit.contains("axes") ->
                    getAxeGoals(clientInstance)
            normalizedKit.contains("combo") || normalizedKit.contains("nodelay") ->
                    getComboGoals(clientInstance)
            normalizedKit.contains("classic") || normalizedKit.contains("archer") ->
                    getClassicGoals(clientInstance)
            normalizedKit.contains("spleef") -> getSpleefGoals(clientInstance)
            normalizedKit.contains("diamond") -> getDiamondGoals(clientInstance)
            else -> getDefaultGoals(clientInstance)
        }
    }

    /**
     * NoDebuff - The most popular Practice kit type. Focus: Health potions, ender pearls for gap
     * closing, W-tap combat.
     */
    private fun getNoDebuffGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance), // First: Select kit
                ReplaceArmorGoal(clientInstance), // Equip armor
                ThrowHealthPotGoal(clientInstance), // Priority: Heal when low
                DrinkPotionGoal(clientInstance), // Drink speed/strength
                ThrowPearlGoal(clientInstance), // Pearl to chase/escape
                SafeEatGoal(clientInstance), // Eat food safely
                MeleeCombatGoal(clientInstance) // Base combat
        )
    }

    /** Debuff - Like NoDebuff but includes debuff potions. */
    private fun getDebuffGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                ThrowHealthPotGoal(clientInstance),
                ThrowDebuffPotGoal(clientInstance), // Throw slowness/poison
                DrinkPotionGoal(clientInstance),
                ThrowPearlGoal(clientInstance),
                SafeEatGoal(clientInstance),
                MeleeCombatGoal(clientInstance)
        )
    }

    /** Sumo - Knockback-focused, no items, platform control. */
    private fun getSumoGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                SumoCombatGoal(clientInstance) // Special sumo combat
        )
    }

    /** BuildUHC - Strategic item usage, golden heads, fishing rod. */
    private fun getBuildUHCGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                FishingRodGoal(clientInstance), // Rod pressure should stay short-lived
                BuildUHCCombatGoal(clientInstance), // BuildUHC-specific heads/gapples/lava/water
                MeleeCombatGoal(clientInstance)
        )
    }

    private fun getDiamondGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
            SelectKitGoal(clientInstance),
            EatGappleGoal(clientInstance), // Eat gapple for regen
            FishingRodGoal(clientInstance), // Rod for knockback
            SafeEatGoal(clientInstance),
            MeleeCombatGoal(clientInstance)
        )
    }

    /** Soup - Soup healing instead of potions. */
    private fun getSoupGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                HealSoupGoal(clientInstance), // Eat soup for instant heal
                DropEmptyBowlGoal(clientInstance), // Drop empty bowls
                SafeEatGoal(clientInstance),
                MeleeCombatGoal(clientInstance)
        )
    }

    /** Gapple - Golden apple based combat. */
    private fun getGappleGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                DrinkPotionGoal(clientInstance),
                DrinkStrengthPotionGoal(clientInstance),
                EatEnchantedGappleGoal(clientInstance),
                SafeEatGoal(clientInstance),
                MeleeCombatGoal(clientInstance)
        )
    }

    /** Boxing - Pure melee, 4-heart health, hit counting. */
    private fun getBoxingGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                MeleeCombatGoal(clientInstance) // Just combat, no items
        )
    }

    /** Axe - Axe-based combat, high damage per hit. */
    private fun getAxeGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                SafeEatGoal(clientInstance),
                MeleeCombatGoal(clientInstance) // Uses best weapon (axe)
        )
    }

    /** Combo - Speed effects, fast-paced trading. */
    private fun getComboGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                DrinkPotionGoal(clientInstance), // Speed pots
                EatEnchantedGappleGoal(clientInstance),
                SafeEatGoal(clientInstance),
                MeleeCombatGoal(clientInstance)
        )
    }

    /** Classic - Sword + Bow combat. */
    private fun getClassicGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                EatGappleGoal(clientInstance),
                SafeEatGoal(clientInstance),
                FishingRodGoal(clientInstance), // Rod for spacing
                MeleeCombatGoal(clientInstance)
        )
    }

    /** Spleef - Break blocks under enemy (different gameplay). */
    private fun getSpleefGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                MeleeCombatGoal(clientInstance) // Basic targeting for now
        )
    }

    /** Default goals for unknown kit types. */
    private fun getDefaultGoals(clientInstance: ClientInstance): Array<Goal> {
        return arrayOf(
                SelectKitGoal(clientInstance),
                ReplaceArmorGoal(clientInstance),
                ThrowHealthPotGoal(clientInstance),
                EatGappleGoal(clientInstance),
                SafeEatGoal(clientInstance),
                MeleeCombatGoal(clientInstance)
        )
    }

    /** Configure bot for specific kit type and start all goals. */
    fun configureBotForKit(clientInstance: ClientInstance, kitType: String) {
        configureBotForKit(clientInstance, kitType, BotDifficulty.NORMAL)
    }

    fun configureBotForKit(
        clientInstance: ClientInstance,
        kitType: String,
        difficulty: BotDifficulty
    ) {
        difficulty.applyTo(clientInstance.configuration)
        val goals = getGoalsForKit(clientInstance, kitType)
        clientInstance.startGoals(*goals)
    }
}
