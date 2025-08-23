package com.zmal.bilidanmaku

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.PacketSender
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.network.ServerPlayNetworkHandler
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture


@Suppress("SpellCheckingInspection")
object BiliDanmaku : ModInitializer {
    private val logger = LoggerFactory.getLogger("bilidanmaku")
    private lateinit var cli: BiliClient
    private var currentIdCode: String = ""
    private var server: MinecraftServer? = null

    private const val FIXED_APP_ID = 1760270800600
    private const val FIXED_KEY = "15TFVHJi774HSW6YAmQ4toxn"
    private const val FIXED_SECRET = "pVLu5MS0FsH5X2bfOscdcHrqQUbXEJ"
    private const val FIXED_HOST = "https://live-open.biliapi.com"

    override fun onInitialize() {
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                if (::cli.isInitialized) {
                    cli.close()
                }
            } catch (e: Exception) {
                logger.error("Error during shutdown: ${e.message}", e)
            }
        })

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("bilidanmaku")
                    .then(
                        CommandManager.literal("reload")
                            .executes { context ->
                                server = context.source.server
                                val newConfig = ConfigManager.forceReload()
                                if (currentIdCode != newConfig.idCode) {
                                    if (::cli.isInitialized) cli.close()
                                    initBiliClient()
                                    sendFeedback("BiliClient 配置已重载并重新连接")
                                } else {
                                    sendFeedback("BiliClient 配置已重载（无变化）")
                                }
                                1
                            }
                    )
                    .then(
                        CommandManager.literal("status")
                            .executes { context ->
                                server = context.source.server
                                if (::cli.isInitialized) {
                                    sendFeedback(cli.getStatus())
                                } else {
                                    sendFeedback("BiliClient 未初始化")
                                }
                                1
                            }
                    )
                    .then(
                        CommandManager.literal("reconnect")
                            .executes { context ->
                                server = context.source.server
                                if (::cli.isInitialized) {
                                    cli.close()
                                    cli.run()
                                    sendFeedback("BiliClient 正在重新连接")
                                } else {
                                    initBiliClient()
                                    sendFeedback("BiliClient 已初始化并连接")
                                }
                                1
                            }
                    )
                    .then(
                        CommandManager.literal("setid")
                            .then(
                                CommandManager.argument("idCode", StringArgumentType.string())
                                    .executes { context ->
                                        val idCode = StringArgumentType.getString(context, "idCode")
                                        server = context.source.server
                                        if (ConfigManager.updateIdCode(idCode)) {
                                            currentIdCode = idCode
                                            if (::cli.isInitialized) cli.close()
                                            initBiliClient()
                                            sendFeedback("ID Code 已更新并重新连接")
                                        } else {
                                            sendFeedback("ID Code 更新失败")
                                        }
                                        1
                                    }
                            )
                    )
            )
        }

        ServerPlayConnectionEvents.JOIN.register { _: ServerPlayNetworkHandler?, _: PacketSender?, minecraftServer: MinecraftServer? ->
            minecraftServer?.let {
                server = it
                initBiliClient()
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { _: ServerPlayNetworkHandler?, _: MinecraftServer? ->
            if (::cli.isInitialized) cli.close()
        }
    }

    fun sendFeedback(message: String) {
        server?.execute {
            for (player: ServerPlayerEntity in server!!.playerManager.playerList) {
                player.sendMessage(Text.literal("[BiliDanmaku] $message"), false)
            }
        }
        logger.info(message)
    }
    fun sendFeedback(msg: Text){
        server?.execute {
            for (player: ServerPlayerEntity in server!!.playerManager.playerList) {
                player.sendMessage(msg, false)
            }
        }
        logger.info(msg.string)
    }

    private fun initBiliClient() {
        try {
            currentIdCode = ConfigManager.getIdCode()

            if (currentIdCode.isEmpty()) {
                logger.warn("ID Code is empty, BiliClient will not be initialized")
                return
            }

            cli = BiliClient(
                currentIdCode,
                FIXED_APP_ID,
                FIXED_KEY,
                FIXED_SECRET,
                FIXED_HOST
            )

            CompletableFuture.runAsync {
                try {
                    cli.run()
                    if (!cli.isConnected()) {
                        logger.warn("Initial connection failed, attempting reconnect...")
                        Thread.sleep(2000)
                        cli.reload()
                    }
                    if (cli.isConnected()) {
                        logger.info("BiliClient connected successfully")
                    } else {
                        logger.error("BiliClient failed to connect after retry")
                    }

                } catch (e: Exception) {
                    logger.error("Error during BiliClient initialization: ${e.message}", e)
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to initialize BiliClient: ${e.message}", e)
        }
    }
}