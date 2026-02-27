# MineralBot Bukkit 1.7.10 兼容层说明（完整版）

本文档说明当前仓库中 `bot-bukkit` 对 1.7.10（`v1_7_R4`）的兼容设计与落地状态。

## 1) 之前为什么在 1.7.10 直接不可用

历史代码在 `CompatDSL.kt` 的 1.7.10 分支是 `TODO()`，会在以下关键节点直接抛异常：

- 构建服务端 Bot 玩家对象
- 构建 `PlayerConnection`
- 切换 `NetworkManager` 的连接状态

这会导致机器人登录流程中断。

## 2) 已完成的 1.7.10 关键兼容

### 2.1 版本分发

`CompatDSL.kt` 已接入 1.7.10：

- `newBukkitServerPlayer(...) -> v1_7_R4.NMSServerPlayer`
- `newPlayerConnection(...) -> v1_7_R4.PlayerConnection`
- `ChannelHandler.setConnectionState(...) -> v1_7_R4.EnumProtocol`

### 2.2 NMS 玩家层实现（v1_7_R4）

新增 `gg.mineral.bot.bukkit.plugin.compat.v1_7_R4.NMSServerPlayer`，提供：

- 1.7 `EntityPlayer` 创建与绑定
- `playerConnection` 注入
- 皮肤 `GameProfile#textures` 注入
- 位置/朝向同步、背包同步、登录后世界初始化
- 记分板、出生点、加入流程、资源包初始化

### 2.3 对 1.7 分叉服务端的稳定性增强

考虑 1.7 生态常见分叉（CraftBukkit/Spigot/Fork）之间方法差异，增加了容错：

- `onJoin()` 对 `onPlayerJoin(...)` 增加反射兜底，避免方法签名差异导致硬崩。
- `resetPlayerSampleUpdateTimer()` 先尝试 `I()`，再回退 `aH()`（反射），兼容不同映射。
- 资源包字段读取改为 `runCatching`，避免字段缺失导致初始化失败。

## 3) 插件元数据对齐

`plugin.yml` 的 `api` 由 `1.8.9` 调整到 `1.7.10`，避免在老服中出现版本误判/拒载场景。

## 4) 与 1.8+ 的已知能力差异

这是 Minecraft/NMS 代际差异，非逻辑遗漏：

- 1.7 资源包仅 URL，不含 hash 强校验。
- reduced debug info、difficulty lock 在 1.7 不可用，按兼容默认值处理。

## 5) 你上线时建议重点验收

1. 单 Bot 登录（握手 -> 登录 -> 进入世界）
2. 批量 Bot 创建与重连（是否存在偶发反射/方法签名错误）
3. ViaVersion + ViaRewind + ViaBackwards 组合链路下的稳定性
4. 你自定义 Practice 逻辑里的事件/战斗流程是否完整触发

> 结论：当前代码已从“1.7.10 会直接崩”推进为“1.7.10 可运行并含分叉兜底”。
> 若你要“1.7 PvP 手感完全一致”，下一步应继续做击退/阻挡/实体元数据时序级别对齐。

## 6) 直接可用部署调整（针对“放进服务器就能用”）

为降低你直接丢进 1.7.10 服的失败概率，本次做了两项关键上线向兼容：

- `plugin.yml` 中将 `ViaVersion / ViaRewind / ViaBackwards` 从强依赖改为 `softdepend`，仅保留 `packetevents` 为强依赖。
- Netty 登录桥接不再强依赖 Via 的 `UserConnection`：
  - `PreViaHandler` 在无 Via 时默认按 `LOGIN` 流程继续，确保机器人可进入登录逻辑。
  - `ServerBotImpl` 对 pipeline 锚点 (`splitter`/`decoder`) 增加降级挂载策略，避免不同核心 pipeline 名称差异导致注入失败。
  - `PostViaHandler` 在未记录连接状态时默认 `PLAY`，避免空状态直接异常。

