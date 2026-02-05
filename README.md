# IMPORTANT: READ THE LICENSE

#### Fork of Mineral-Bot
## Made for MiCet.cc BotDuel Fight

# MineralBot-Velocity
The most realistic bots in existence, based off an actual Minecraft Client.

```
###The original plugin was only a partial Bukkit plugin (for versions 1.8 
and above, and seemingly dependent on Mineral-Spigot), 
and the bot-intelligence was incomplete. 
Based on the original plugin, 
I created a Velocity version of bot-mineral that supports Velocity 3.x. 
You can deploy it on Velocity and link it to your 1.7.10 Practice server, 
breaking the version restriction and allowing bots to enter your 1.7.10 
Spigot server and obtain full BotDuel functionality
```

## Build
`./gradlew :bot-velocity:shadowJar`

## How does it work?

This features a modified 1.7.10 Client with the static fields removed. This allows multiple client instances to be created under one JVM instance. Everything can be loaded as a Bukkit Plugin. Nothing is standalone.
It is also exceptionally light weight. It can handle 1500 bots fighting on a $20 dedicated server.

## How to use this plugin into your server
```
- Link Velocity to your server
- code your server's practice plugin to link with MineralBot-Velocity
- send BotDuelMatchStart to plugin,then bot will connect to your server
- enjoy

 ##This tutorial is poorly written; you still need to do some research on your own
 （my libs:potpvpsi and mspigot)
```

#### Disclaimer: This plugin strictly complies with the content of the License and is solely for learning and reference purposes