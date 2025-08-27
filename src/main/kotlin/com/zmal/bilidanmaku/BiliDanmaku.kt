package com.zmal.bilidanmaku

import com.mojang.brigadier.arguments.StringArgumentType
import com.zmal.bilidanmaku.Utils.cli
import com.zmal.bilidanmaku.Utils.currentIdCode
import com.zmal.bilidanmaku.Utils.initBiliClient
import com.zmal.bilidanmaku.Utils.logger
import com.zmal.bilidanmaku.Utils.reloadClient
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.text.Text


@Suppress("SpellCheckingInspection")
object BiliDanmaku : ModInitializer {
    private var server: MinecraftServer? = null

    override fun onInitialize() {

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("bilidanmaku").then(
                    literal("reload").executes { context ->
                        server = context.source.server
                        if (reloadClient(false)) sendServerFeedback("BiliClient 配置已重载并重新连接")

                        1
                    }).then(
                    literal("").executes { context ->
                        server = context.source.server
                        sendServerFeedback(cli.getStatus())
                        1
                    })
                    .then(
                        literal("setid").then(
                            CommandManager.argument("idCode", StringArgumentType.string()).executes { context ->
                                val idCode = StringArgumentType.getString(context, "idCode")
                                server = context.source.server
                                if (ConfigManager.updateIdCode(idCode)) {
                                    currentIdCode = idCode
                                    initBiliClient(false)
                                    sendServerFeedback("ID Code 已更新并重新连接")
                                } else {
                                    sendServerFeedback("ID Code 更新失败")
                                }
                                1
                            })
                    )
            )
            dispatcher.register(
                literal("bdm").redirect(dispatcher.getRoot().getChild("bilidanmaku"))
            )
        }

        // 服务器启动完成
        ServerLifecycleEvents.SERVER_STARTED.register { minecraftServer ->
            server = minecraftServer
            initBiliClient(false)
            logger.info("注册服务端实例ServerLifecycleEvents")
        }

        // 服务器关闭时
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            cli.close()
        }
    }

    fun sendServerFeedback(message: String) {
        val text = Text.literal("[BiliDanmaku] $message")
        server?.playerManager?.broadcast(text, false)
    }

    fun sendServerFeedback(msg: Text) {
        val mcServer = server ?: return
        mcServer.execute {
            mcServer.playerManager.broadcast(msg, false)
        }
        logger.info(msg.string)
    }


}