package com.zmal.bilidanmaku

import com.mojang.brigadier.arguments.StringArgumentType
import com.zmal.bilidanmaku.Utils.cli
import com.zmal.bilidanmaku.Utils.initBiliClient
import com.zmal.bilidanmaku.Utils.logger
import com.zmal.bilidanmaku.Utils.reloadClient
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.text.Text


@Suppress("SpellCheckingInspection")
object BiliDanmakuClient : ClientModInitializer {
    private var currentIdCode: String = ""

    override fun onInitializeClient() {

        // 注册客户端命令
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("bilidanmaku").then(literal("reload").executes {
                    reloadClient(true)
                    sendClientFeedback("BiliClient 配置已重载并重新连接")
                    1
                }).then(literal("status").executes {
                    sendClientFeedback(cli.getStatus())
                    1
                }).then(
                    literal("setid").then(
                        argument("idCode", StringArgumentType.string()).executes { context ->
                            val idCode = StringArgumentType.getString(context, "idCode")
                            if (ConfigManager.updateIdCode(idCode)) {
                                currentIdCode = idCode
                                reloadClient(true)
                                sendClientFeedback("ID Code 已更新并重新连接")
                            } else {
                                sendClientFeedback("ID Code 更新失败")
                            }
                            1
                        })
                )
            )
            dispatcher.register(
                literal("bdm").redirect(dispatcher.getRoot().getChild("bilidanmaku"))
            )
        }
        ClientEntityEvents.ENTITY_LOAD.register(ClientEntityEvents.Load { entity: Entity?, _: ClientWorld? ->
            if (entity is PlayerEntity) {
                initBiliClient(true)
                logger.info("注册服务端实例ClientEntityEvents")
            }
        })
        // 断线或客户端退出时关闭连接
        ServerPlayConnectionEvents.DISCONNECT.register { _, _ -> cli.close() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { cli.close() }
    }

    fun sendClientFeedback(msg: Text) {
        val mc = MinecraftClient.getInstance()
        mc.execute {
            mc.inGameHud.chatHud.addMessage(msg)
        }
    }

    fun sendClientFeedback(message: String) {
        val mc = MinecraftClient.getInstance()
        mc.execute {
            mc.inGameHud.chatHud.addMessage(Text.literal("[BiliDanmakucli] $message"))
        }
    }
}
