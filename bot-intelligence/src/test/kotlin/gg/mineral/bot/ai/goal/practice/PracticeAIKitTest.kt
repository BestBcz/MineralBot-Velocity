package gg.mineral.bot.ai.goal.practice

import gg.mineral.bot.ai.goal.MeleeCombatGoal
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.instance.ClientInstance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PracticeAIKitTest {
    private val config=BotConfiguration(friendlyUUIDs=mutableSetOf())
    private val client=Proxy.newProxyInstance(ClientInstance::class.java.classLoader,
        arrayOf(ClientInstance::class.java)) { _, method, _ ->
        when(method.name) {
            "getConfiguration" -> config
            "getNavigationContext" -> null
            else -> error("Unexpected client access: ${method.name}")
        }
    } as ClientInstance

    @Test fun `SG aliases keep their supplies and use the same rod goal as UHC`() {
        val original=PracticeAI.getGoalsForKit(client,"unknown").map { it.javaClass }
        val uhc=PracticeAI.getGoalsForKit(client,"BuildUHC")
        val rodClass=uhc.single { it is FishingRodGoal }.javaClass
        for(kit in listOf("SG","sg","S G","s_g","SurvivalGames","Survival Games","survival_games")) {
            val goals=PracticeAI.getGoalsForKit(client,kit)
            assertEquals(original,goals.filterNot { it is FishingRodGoal }.map { it.javaClass },kit)
            assertEquals(rodClass,goals.single { it is FishingRodGoal }.javaClass,kit)
            assertEquals(goals.lastIndex-1,goals.indexOfFirst { it is FishingRodGoal },kit)
            assertTrue(goals.last() is MeleeCombatGoal,kit)
            assertFalse(goals.any { it is BuildUHCCombatGoal },kit)
        }
    }

    @Test fun `other kit registrations retain their rod behavior`() {
        for(kit in listOf("UHC","BuildUHC","Classic")) {
            assertEquals(1,PracticeAI.getGoalsForKit(client,kit).count { it is FishingRodGoal },kit)
        }
        for(kit in listOf("unknown","NoDebuff","Sumo","Boxing")) {
            assertFalse(PracticeAI.getGoalsForKit(client,kit).any { it is FishingRodGoal },kit)
        }
    }
}
