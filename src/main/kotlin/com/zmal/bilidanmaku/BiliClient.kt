package com.zmal.bilidanmaku

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.zmal.bilidanmaku.BiliDanmaku.sendFeedback
import com.zmal.bilidanmaku.Utils.logger
import com.zmal.bilidanmaku.Utils.sign
import kotlinx.coroutines.*
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/**
 * Bilibili Live Open Platform Client
 * Refactored to match Python demo structure
 */
class BiliClient(
    private val idCode: String, private val appId: Long, private val host: String
) {
    private var gameId: String = ""
    private val client = OkHttpClient()
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var appHeartbeatJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    /**
     * Main entry point - starts the client event loop
     */
    fun run() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("BiliClient is already running")
            return
        }

        try {
            runBlocking {
                val (wsUrl, authBody) = getWebsocketInfo()

                // Connect to websocket
//                val websocket =
                webSocket = connect(wsUrl, authBody)
                // Start concurrent tasks
                // Start concurrent tasks and store job references
                heartbeatJob = launch { webSocket?.let { heartBeat(it) } }
                appHeartbeatJob = launch { appHeartBeat() }

                // Wait for all tasks to complete
                listOf(heartbeatJob!!, appHeartbeatJob!!).joinAll()
            }
        } catch (e: Exception) {
            logger.error("Error in run loop", e)
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * Get WebSocket connection information
     */
    private suspend fun getWebsocketInfo(): Pair<String, String> = withContext(Dispatchers.IO) {
        val postUrl = "$host/v2/app/start"
        val params = """{"code":"$idCode","app_id":$appId}"""
        val headers = sign(params)

//        logger.info("[BiliClient] start app with headers: $headers")

        val request = Request.Builder().url(postUrl).apply { headers.forEach { (key, value) -> addHeader(key, value) } }
            .post(params.toRequestBody()).build()

        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                throw RuntimeException("Request failed: ${it.code} ${it.message}")
            }

            val body = it.body?.string() ?: throw RuntimeException("Empty response")
            val data = gson.fromJson(body, Map::class.java)

            val code = when (val codeValue = data["code"]) {
                is Number -> codeValue.toInt()
                is String -> codeValue.toIntOrNull() ?: -1
                else -> -1
            }

            if (code != 0) {
                throw RuntimeException("API error: ${data["message"]}")
            }

            val dataMap = data["data"] as Map<*, *>
            val gameInfo = dataMap["game_info"] as Map<*, *>
            gameId = gameInfo["game_id"].toString()

            val wsInfo = dataMap["websocket_info"] as Map<*, *>
            val wsUrl = (wsInfo["wss_link"] as List<*>)[0].toString()
            val authBody = wsInfo["auth_body"].toString()

//            logger.debug("WebSocket URL: $wsUrl")
            logger.debug("Auth body received")

            return@withContext wsUrl to authBody
        }
    }

    /**
     * Establish WebSocket connection and authenticate
     */
    private suspend fun connect(wsUrl: String, authBody: String): WebSocket =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(wsUrl).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    logger.debug("WebSocket connected ${this.hashCode()}")

//                    sendFeedback("连接成功",)

                    try {
                        auth(webSocket, authBody)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        val proto = Proto()
                        proto.unpack(bytes.toByteArray())

                        when (proto.op) {
                            8 -> { // OP_AUTH_REPLY
                                logger.info("鉴权成功")
                                if (cont.isActive) {
                                    cont.resume(webSocket) { _, _, _ -> }
                                }
                            }
                            3 -> logger.debug("服务器收到心跳包的回复")
                            5 -> processLiveMessage(proto.getBodyAsString())
                            else -> logger.info("其他 op=${proto.op}")
                        }
                    } catch (e: Exception) {
                        logger.error("Error handling message", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            })
        }


    /**
     * Send authentication packet
     */
    private fun auth(ws: WebSocket, authBody: String) {
        try {
            val proto = Proto().apply {
                setBody(authBody)
                op = 7
            }
            ws.send(ByteString.of(*proto.pack()))
            webSocket=ws
//            logger.info("Authentication sent successfully ${this.hashCode()}")
        } catch (e: Exception) {
            logger.error("Authentication failed", e)
            throw e
        }
    }

    /**
     * WebSocket heartbeat loop
     */
    private suspend fun heartBeat(ws: WebSocket) {
        while (!isShuttingDown.get()) {
            delay(20_000) // 20 seconds

            try {
//                val proto = Proto().apply { op = 2 }
//                webSocket?.send(ByteString.of(*proto.pack()))
//                logger.info("[BiliClient] send heartBeat success")
                val proto = Proto().apply { op = 2 }
                val data = proto.pack()
//                val ok =
                    ws.send(ByteString.of(*data))
//                logger.info("heartBeat send=${ok}, length=${data.size}  hashCode${this.hashCode()}")
//                logger.info("heartBeat raw hex: ${ByteString.of(*data).hex()}")


            } catch (e: Exception) {
                logger.error("Failed to send heartbeat", e)
                break
            }
        }
    }

    /**
     * App heartbeat loop
     */
    private suspend fun appHeartBeat() {
        while (!isShuttingDown.get()) {
            delay(20_000) // 20 seconds

            if (gameId.isNotEmpty()) {
                try {
                    val postUrl = "$host/v2/app/heartbeat"
                    val params = """{"game_id":"$gameId"}"""
                    val headers = sign(params)

                    val request = Request.Builder().url(postUrl)
                        .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                        .post(params.toRequestBody()).build()

                    withContext(Dispatchers.IO) {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                logger.debug("send appheartBeat success")
                            } else {
                                logger.warn("App heartbeat failed: ${response.code}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("App heartbeat error", e)
                }
            }
        }
    }

//    private fun recvLoop(webSocket: WebSocket) {
//        logger.info("[BiliClient] run recv...")
//        // In actual implementation, this would be handled by WebSocketListener.onMessage
//        // This is just a placeholder to match the Python structure
//    }

    /**
     * Handle incoming WebSocket messages
     */

    /**
     * Process live platform messages
     */
    private fun processLiveMessage(body: String) {
        try {
            if (!body.trim().startsWith("{")) {
                logger.debug("Non-JSON message: $body")
                return
            }

            val json = JsonParser.parseString(body).asJsonObject
            if (!json.has("cmd")) {
                logger.debug("Message without cmd: {}", json)
                return
            }

            when (val cmd = json["cmd"].asString) {
                "LIVE_OPEN_PLATFORM_DM" -> {
                    val data = json["data"].asJsonObject
                    val uname = data["uname"].asString
                    val msg = data["msg"].asString
                    logger.info("弹幕 [$uname] $msg")

                    val formattedMsg =
                        Text.literal("[$uname] ").styled { it.withColor(Formatting.LIGHT_PURPLE).withBold(true) }
                            .append(Text.literal(msg).styled { it.withColor(Formatting.WHITE) })
                    sendFeedback(formattedMsg)
                }

                "LIVE_OPEN_PLATFORM_SEND_GIFT" -> {
                    val data = json["data"].asJsonObject
                    val uname = data["uname"].asString
                    val gift = data["gift_name"].asString
                    val num = data["gift_num"].asInt
                    logger.info("礼物 [$uname] 送出 $num 个 $gift")

                    val msg = Text.literal("[$uname] 送出 $num $gift").styled { it.withColor(Formatting.GREEN) }
                    sendFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER" -> {
                    val data = json["data"].asJsonObject
                    val uname = data["uname"].asString
                    logger.info("$uname 进入了直播间")

                    val msg =
                        Text.literal("$uname 进入了直播间").styled { it.withColor(Formatting.GOLD).withItalic(true) }
                    sendFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIVE_START" -> {
                    val data = json["data"].asJsonObject
                    val title = data["title"].asString
                    val areaName = data["area_name"].asString

                    val msg = Text.literal("主播开播了: $title ($areaName)").styled { it.withColor(Formatting.AQUA) }
                    sendFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIVE_END" -> {
                    val data = json["data"].asJsonObject
                    val title = data["title"].asString
                    val areaName = data["area_name"].asString

                    val msg = Text.literal("主播下播了: $title ($areaName)").styled { it.withColor(Formatting.GRAY) }
                    sendFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_INTERACTION_END" -> reconnect()

                else -> {
                    logger.info("其他消息: $cmd")
                }
            }
        } catch (e: Exception) {
            logger.error("解析消息失败", e)
        }
    }

    /**
     * Close the client and cleanup resources
     */
    fun close() {
        try {
            isShuttingDown.set(true)

            // End the app session
            if (gameId.isNotEmpty()) {
                runBlocking {
                    try {
                        val postUrl = "$host/v2/app/end"
                        val params = """{"game_id":"$gameId","app_id":$appId}"""
                        val headers = sign(params)

                        val request = Request.Builder().url(postUrl)
                            .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                            .post(params.toRequestBody("application/json".toMediaType())).build()

                        withContext(Dispatchers.IO) {
                            client.newCall(request).execute().use {
                                logger.info("[BiliClient] end app success: $params")
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to send end app request", e)
                    }
                }
            }

            // Close WebSocket
            webSocket?.close(1000, "Client shutdown")
            logger.info("客户端已关闭")

            // Cancel coroutine scope
            scope.cancel()

        } catch (e: Exception) {
            logger.error("Error during close", e)
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * Get current connection status
     */
    fun getStatus(): String {
        return when {
            isRunning.get() -> "运行中 - gameId: $gameId"
            isShuttingDown.get() -> "关闭中"
            else -> "未运行"
        }
    }

    fun reconnect() {
        heartbeatJob?.cancel()
        appHeartbeatJob?.cancel()
        if (isShuttingDown.get()) return

        sendFeedback(Text.literal("停止推送，尝试重连"))

        scope.launch {
            try {
                // 先关闭旧连接
                webSocket?.close(1000, "Reconnect")
                delay(5000) // 等 5 秒再重连
                logger.info("LIVE_OPEN_PLATFORM_INTERACTION_END尝试重新连接 WebSocket...")
                val (wsUrl, authBody) = getWebsocketInfo()
                webSocket = connect(wsUrl, authBody)
                // Start new jobs
                heartbeatJob = launch { webSocket?.let { heartBeat(it) } }
                appHeartbeatJob = launch { appHeartBeat() }
            } catch (e: Exception) {
                logger.error("重连失败", e)
                // 可以再次触发重连或延迟更长时间重试
            }
        }
    }

}