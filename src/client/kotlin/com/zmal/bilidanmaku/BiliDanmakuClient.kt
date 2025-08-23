package com.zmal.bilidanmaku
//
//import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
//import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
//import net.fabricmc.fabric.api.networking.v1.PacketSender
//import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
//import net.minecraft.client.MinecraftClient
//import net.minecraft.server.MinecraftServer
//import net.minecraft.server.network.ServerPlayNetworkHandler
//import net.minecraft.text.Text
//import org.slf4j.LoggerFactory
//import java.util.concurrent.CompletableFuture
//
//@Suppress("SpellCheckingInspection")
object BiliDanmakuClient : ClientModInitializer {
//    private val logger = LoggerFactory.getLogger("bilidanmaku")
//    private lateinit var cli: BiliClient
//    private var currentIdCode: String = ""
//
//    // 固定参数
//    private const val FIXED_APP_ID = 1760270800600
//
//    private const val FIXED_KEY = "15TFVHJi774HSW6YAmQ4toxn"
//    private const val FIXED_SECRET = "pVLu5MS0FsH5X2bfOscdcHrqQUbXEJ"
//    private const val FIXED_HOST = "https://live-open.biliapi.com"
//
    override fun onInitializeClient() {
//
//        Runtime.getRuntime().addShutdownHook(Thread {
//            try {
//                if (::cli.isInitialized) {
//                    cli.close()
//                }
//            } catch (e: Exception) {
//                logger.error("Error during shutdown: ${e.message}")
//                e.printStackTrace()
//            }
//        })
//
//        // 注册客户端命令
//        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
//            dispatcher.register(
//                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("bilidanmaku")
//                    .then(
//                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("reload")
//                            .executes { _ ->
//                                val newConfig = ConfigManager.forceReload()
//
//                                if (currentIdCode != newConfig.idCode) {
//                                    // 重新初始化客户端
//                                    if (::cli.isInitialized) {
//                                        cli.close()
//                                    }
//                                    initBiliClient()
//                                    sendFeedback("BiliClient 配置已重载并重新连接")
//                                } else {
//                                    sendFeedback("BiliClient 配置已重载（无变化）")
//                                }
//                                1
//                            }
//                    )
//                    .then(
//                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("status")
//                            .executes { _ ->
//                                if (::cli.isInitialized) {
//                                    val status = if (cli.isConnected()) "已连接" else "未连接"
//                                    sendFeedback("BiliClient 状态: $status, ID Code: ${ConfigManager.getIdCode()}")
//                                } else {
//                                    sendFeedback("BiliClient 未初始化")
//                                }
//                                1
//                            }
//                    )
//                    .then(
//                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("reconnect")
//                            .executes { _ ->
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
//                    .then(
//                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("setid")
//                            .then(
//                                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument(
//                                    "idCode",
//                                    StringArgumentType.string()
//                                )
//                                    .executes { context ->
//                                        val idCode = StringArgumentType.getString(context, "idCode")
//
//                                        if (ConfigManager.updateIdCode(idCode)) {
//                                            currentIdCode = idCode // 更新当前ID记录
//
//                                            // 重新初始化客户端
//                                            if (::cli.isInitialized) {
//                                                cli.close()
//                                            }
//                                            initBiliClient()
//                                            sendFeedback("ID Code 已更新并重新连接")
//                                        } else {
//                                            sendFeedback("ID Code 更新失败")
//                                        }
//                                        1
//                                    }
//                            )
//                    )
//            )
//            dispatcher.register(
//                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("bdm")
//                    .redirect(dispatcher.getRoot().getChild("bilidanmaku"))
//            )
//        }
//
//        ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { _: ServerPlayNetworkHandler?, _: PacketSender?, _: MinecraftServer? ->
//            initBiliClient()
//        })
//        ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { _: ServerPlayNetworkHandler?, _: MinecraftServer? ->
//            cli.close()
//        })
//
//    }
//
//    fun sendFeedback(message: String) {
//        val player = MinecraftClient.getInstance().player
//        player?.sendMessage(Text.literal("[BiliDanmaku] $message"), false)
//            ?: logger.info(message)
//    }
//
//    private fun initBiliClient() {
//        try {
//            val config = ConfigManager.getBilibiliConfig()
//            currentIdCode = config.idCode // 记录当前ID
//
//            if (config.idCode.isEmpty()) {
//                logger.warn("ID Code is empty, BiliClient will not be initialized")
//                return
//            }
//
//            cli = BiliClient(
//                config.idCode,
//                FIXED_APP_ID,
//                FIXED_KEY,
//                FIXED_SECRET,
//                FIXED_HOST
//            )
//
//            CompletableFuture.runAsync {
//                try {
//                    cli.run()
//
//                    if (!cli.isConnected()) {
//                        logger.warn("Initial connection failed, attempting reconnect...")
//                        Thread.sleep(2000)
//                        cli.reconnect()
//                    }
//
//                    if (cli.isConnected()) {
//                        logger.info("BiliClient connected successfully")
//                    } else {
//                        logger.error("BiliClient failed to connect after retry")
//                    }
//                } catch (e: Exception) {
//                    logger.error("Error during BiliClient initialization: ${e.message}")
//                    e.printStackTrace()
//                }
//            }
//        } catch (e: Exception) {
//            logger.error("Failed to initialize BiliClient: ${e.message}")
//            e.printStackTrace()
//        }
    }

}