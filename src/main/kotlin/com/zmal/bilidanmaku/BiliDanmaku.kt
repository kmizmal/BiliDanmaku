package com.zmal.bilidanmaku

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.zmal.bilidanmaku.Utils.cli
import com.zmal.bilidanmaku.Utils.currentIdCode
import com.zmal.bilidanmaku.Utils.initBiliClient
import com.zmal.bilidanmaku.Utils.reloadClient
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.slf4j.LoggerFactory


@Suppress("SpellCheckingInspection")
object BiliDanmaku : ModInitializer {
    private val logger = LoggerFactory.getLogger("bilidanmaku")
    private var server: MinecraftServer? = null

    override fun onInitialize() {

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("bilidanmaku")
                    .then(
                        CommandManager.literal("reload")
                            .executes { context ->
                                server = context.source.server
                                if (reloadClient()) sendFeedback("BiliClient 配置已重载并重新连接",context)

                                1
                            }
                    )
                    .then(
                        CommandManager.literal("").executes { context ->
                                server = context.source.server
                                sendFeedback(cli.getStatus(),context)
                                1
                            })
//                    .then(
//                        CommandManager.literal("reconnect")
//                            .executes { context ->
//                                server = context.source.server
//                                if (::cli.isInitialized) {
//                                    cli.close()
//                                    cli.run()
//                                    sendFeedback("BiliClient 正在重新连接")
//                                } else {
//                                    initBiliClient()
//                                    sendFeedback("BiliClient 已初始化并连接")
//                                }
//                                1
//                            }
//                    )
                    .then(
                        CommandManager.literal("setid").then(
                            CommandManager.argument("idCode", StringArgumentType.string()).executes { context ->
                                    val idCode = StringArgumentType.getString(context, "idCode")
                                    server = context.source.server
                                    if (ConfigManager.updateIdCode(idCode)) {
                                        currentIdCode = idCode
                                        initBiliClient()
                                        sendFeedback("ID Code 已更新并重新连接",context)
                                    } else {
                                        sendFeedback("ID Code 更新失败",context)
                                    }
                                    1
                                })))
            dispatcher.register(
                CommandManager.literal("bdm").redirect(dispatcher.getRoot().getChild("bilidanmaku"))
            )
        }

        // 服务器启动完成
        ServerLifecycleEvents.SERVER_STARTED.register { minecraftServer ->
            server = minecraftServer
            initBiliClient()
            logger.info("注册服务端实例ServerLifecycleEvents")
        }

        // 服务器关闭时
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            cli.close()
        }
    }

    fun sendFeedback(message: String, ctx: CommandContext<ServerCommandSource>) {
        val text = Text.literal("[BiliDanmaku] $message")
        ctx.getSource().sendFeedback({ text }, false)
    }

    fun sendFeedback(msg: Text) {
        val mcServer = server ?: return
        mcServer.execute {
            val text = Text.literal("[BiliDanmaku] $msg")
            mcServer.playerManager.broadcast(text, false)
        }
        logger.info(msg.string)
    }


}