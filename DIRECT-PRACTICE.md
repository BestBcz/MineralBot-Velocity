# Practice 直连部署

Bot 的游戏连接现在直接使用 BotDuel `serverName` 对应的 Velocity RegisteredServer 地址。
主 Velocity 仅承载 MineralBot 控制插件；不再使用 `bot-connect-host` / `bot-connect-port`，
旧配置中的这两项会自动移除。普通玩家的 VeloAuth 流程保持原样。

## 配置

在主 Velocity 的 `plugins/bot-velocity/config.properties` 中设置：

```properties
bungeeguard-secret-file=../../forwarding.secret
bot-forwarded-ip=127.0.0.1
guide-enabled=false
```

secret 文件路径相对于 **MineralBot 插件数据目录**；默认路径指向 Velocity 工作目录的
`forwarding.secret`。如主代理使用其他 secret 文件，请引用同一个文件，Windows 路径使用 `/`。
不需要复制 token 到插件配置。文件不可读、为空或格式错误时，创建请求失败，不回退到普通握手。
secret 仅启动时读取，轮换后重启主 Velocity。已有 `guide-enabled=false` 配置会保留。

跨机器时可将 `bot-forwarded-ip` 改为 Bot 宿主机的内网 IP；它是转发给插件看的身份地址，
不是 socket 绑定地址，也不会自动改变防火墙规则。

Practice 配置必须满足：

- `spigot.yml` 的 `settings.bungeecord=true`，后端 `server.properties` 的 `online-mode=false`。
- BungeeGuard 保持启用，`allowed-tokens` 包含上述主 Velocity secret。
- Practice 的 `config.yml` 中的 `ServerName`（默认 `micet`） 必须与主 Velocity 注册的服务器名一致；请求目标必须是发送请求的后端。
- 后端只允许可信宿主机经内网访问；保持现有网络隔离。

## 生命周期兼容

已核对 Micet-PotPvP 的 BotDuelHandler、BotDuelListener 和 BotGuideTask。
Practice 按现有请求 token 对应的 Bot 名称匹配入服者，实际 UUID 来自转发握手。
BotRequestAccepted / BotRequestCancelled / BotDisconnect 可能通过任意在线玩家发送。
因此客户端接收到 MineralBot 控制消息时，会调用同一个内部管理器；通过真人玩家连接到达的
消息仍由 Velocity 处理。`guide-enabled=false` 会继续忽略 BotGuide。BotKnockback 保持客户端原有处理。
没有修改 Practice、VeloAuth 或 BungeeGuard 的认证逻辑。

## 验证与上线

本地测试覆盖握手字节编码、稳定 UUID、JSON 转义、皮肤属性保留、token 唯一性、
secret 校验及握手长度限制。它们不替代真实 Practice 入服测试。

1. 将构建的 `bot-velocity/build/libs/*-all.jar` 放到主 Velocity，设置 secret 文件路径并重启。
2. 确认日志出现 `BungeeGuard secret loaded`，从 Practice 发起一次 BotDuel。
3. 检查日志目标为 Practice 注册地址，观察 `DIRECT_LOGIN_SUCCESS` / `DIRECT_JOIN_GAME` 阶段，
   以及 BotRequestAccepted、BotDuelStarted 和正常对战；Bot 不应出现在主 Velocity 玩家列表。
4. 检查结束对战、取消请求、真人离开、后端踢出/重启后的连接与临时目录清理。
5. 在隔离测试环境验证错误 token 和普通直连仍被拒绝，再逐级测试并发及 connection-throttle。
6. 真实入服和生命周期验证通过后再停用 Bot 专用 Velocity。

当前仓库不包含运行中的 Practice 服务或其生产配置；尚不能据本地测试声称真实 Join Game、
50+ Bot 并发、网络防火墙及普通玩家登录回归已通过。

## 协议依据

- [Velocity PlayerDataForwarding](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/PlayerDataForwarding.java)：无横线 UUID、NUL 分隔字段、追加 token 属性及空 signature。
- [Velocity HandshakePacket](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/HandshakePacket.java)：协议号、UTF-8 字符串、端口及登录状态编码。
- [BungeeGuard BungeeCordHandshake](https://github.com/lucko/BungeeGuard/blob/master/bungeeguard-spigot/src/main/java/me/lucko/bungeeguard/spigot/BungeeCordHandshake.java)：2500 字符上限、唯一 token 校验及移除 token 后再交给后端。

参考的是项目依赖 3.5.0-SNAPSHOT 对应的 Velocity 开发分支及 BungeeGuard 上游；
生产服务的具体构建号需在部署验收时确认。
