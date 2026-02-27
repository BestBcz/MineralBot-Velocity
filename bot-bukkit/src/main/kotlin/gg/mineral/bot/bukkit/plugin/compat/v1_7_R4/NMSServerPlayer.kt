package gg.mineral.bot.bukkit.plugin.compat.v1_7_R4

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import gg.mineral.bot.api.world.ServerWorld
import gg.mineral.bot.bukkit.plugin.impl.player.BukkitServerPlayer
import net.minecraft.server.v1_7_R4.*
import org.bukkit.craftbukkit.v1_7_R4.CraftWorld
import org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer
import java.util.*

class NMSServerPlayer(
    world: ServerWorld<*>,
    uuid: UUID,
    name: String,
    vararg skinData: String,
    disableEntityCollisions: Boolean = true
) : BukkitServerPlayer<EntityPlayer>() {
    private val gameProfile: GameProfile = GameProfile(uuid, name)
    private val worldHandle = getHandle(world.handle!!)

    override val abilities: WrapperPlayServerPlayerAbilities
        get() {
            val abilities = entityPlayer.abilities
            return WrapperPlayServerPlayerAbilities(
                abilities.isInvulnerable,
                abilities.isFlying,
                abilities.canFly,
                bukkitPlayer.gameMode == org.bukkit.GameMode.CREATIVE,
                abilities.flySpeed,
                abilities.walkSpeed
            )
        }

    override val entityPlayer: EntityPlayer = object : EntityPlayer(
        MinecraftServer.getServer(), worldHandle as WorldServer, gameProfile,
        PlayerInteractManager(worldHandle)
    )

    override var playerConnection: Any
        set(value) {
            entityPlayer.playerConnection = value as PlayerConnection?
        }
        get() = entityPlayer.playerConnection

    override val bukkitPlayer: CraftPlayer = entityPlayer.bukkitEntity

    init {
        if (skinData.size == 2) {
            gameProfile.properties.put("textures", Property("textures", skinData[0], skinData[1]))
        }
        MinecraftServer.getServer().userCache.a(gameProfile)
    }

    override fun syncInventory() {
        entityPlayer.syncInventory()
    }

    override fun sendSupportedChannels() {
        runCatching { bukkitPlayer.sendSupportedChannels() }
    }

    override fun setPosition(x: Double, y: Double, z: Double) {
        entityPlayer.setPosition(x, y, z)
    }

    override fun setResourcePack(resourcePack: String, resourcePackHash: String) {
        entityPlayer.setResourcePack(resourcePack)
    }

    override fun setYawPitch(yaw: Float, pitch: Float) {
        entityPlayer.setYawPitch(yaw, pitch)
    }

    override val itemInHandIndex: Int
        get() = entityPlayer.inventory.itemInHandIndex

    override fun spawnInWorld(world: ServerWorld<*>) {
        entityPlayer.world = getHandle(world.handle!!)
        entityPlayer.dimension = (entityPlayer.world as WorldServer).dimension
        entityPlayer.spawnWorld = entityPlayer.world.worldData.name
        entityPlayer.spawnIn(entityPlayer.world)
        entityPlayer.playerInteractManager.a(entityPlayer.world as WorldServer)
    }

    override fun onJoin() {
        val playerList = MinecraftServer.getServer().playerList
        val joinMessage = "§e" + LocaleI18n.a("multiplayer.player.joined", this.getName())

        // Different 1.7 forks occasionally rename this method;
        // keep a reflective fallback to avoid hard-crashing bots on login.
        runCatching {
            playerList.onPlayerJoin(entityPlayer, joinMessage)
        }.getOrElse {
            val method = playerList.javaClass.methods.firstOrNull {
                it.name == "onPlayerJoin" && it.parameterTypes.size == 2
            } ?: return@getOrElse
            method.invoke(playerList, entityPlayer, joinMessage)
        }
    }

    override val gameModeId: Int
        get() = entityPlayer.playerInteractManager.gameMode.id

    override fun initializeGameMode() {
        MinecraftServer.getServer().playerList.a(entityPlayer, null, entityPlayer.getWorld())
    }

    override val isWorldHardcore: Boolean
        get() = entityPlayer.getWorld().getWorldData().isHardcore

    override val dimensionId: Int
        get() = entityPlayer.getWorld().worldProvider.dimension

    override val difficultyId: Int
        get() = entityPlayer.getWorld().difficulty.a()

    override val maxPlayers: Int
        get() = MinecraftServer.getServer().playerList.maxPlayers

    override val worldTypeName: String
        get() = entityPlayer.getWorld().getWorldData().type.name()

    override val isReducedDebugInfo: Boolean
        get() = false

    override val sprinting: Boolean
        get() = entityPlayer.isSprinting

    override val serverModName: String
        get() = MinecraftServer.getServer().serverModName

    override fun sendScoreboard() {
        MinecraftServer.getServer().playerList.sendScoreboard(
            entityPlayer.getWorld().getScoreboard() as ScoreboardServer,
            entityPlayer
        )
    }

    override fun resetPlayerSampleUpdateTimer() {
        val server = MinecraftServer.getServer()
        runCatching { server.I() }
            .recoverCatching {
                val method = server.javaClass.methods.firstOrNull {
                    it.name == "aH" && it.parameterTypes.isEmpty()
                } ?: error("Unable to find ping sample timer reset method")
                method.invoke(server)
            }
    }

    override val isDifficultyLocked: Boolean
        get() = false

    override val worldSpawn: IntArray
        get() = intArrayOf(
            entityPlayer.getWorld().getWorldData().c(),
            entityPlayer.getWorld().getWorldData().d(),
            entityPlayer.getWorld().getWorldData().e()
        )

    override fun sendLocationToClient() {
        entityPlayer.playerConnection.a(
            entityPlayer.locX, entityPlayer.locY, entityPlayer.locZ,
            entityPlayer.yaw, entityPlayer.pitch
        )
    }

    override fun initWorld() {
        MinecraftServer.getServer().playerList.b(entityPlayer, entityPlayer.u())
    }

    override fun initResourcePack() {
        val server = MinecraftServer.getServer()
        val resourcePack = runCatching { server.resourcePack }.getOrDefault("")
        val resourcePackHash = runCatching { server.resourcePackHash }.getOrDefault("")

        if (resourcePack.isNotEmpty()) {
            setResourcePack(resourcePack, resourcePackHash)
        }
    }

    override fun getId(): Int = entityPlayer.id

    override fun getName(): String = entityPlayer.name

    companion object {
        fun getHandle(handle: Any): World {
            if (handle is World) return handle
            if (handle is CraftWorld) return handle.handle
            throw IllegalArgumentException("Invalid world type: " + handle.javaClass.name)
        }
    }
}
