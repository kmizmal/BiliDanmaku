package com.zmal.bilidanmaku

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.zmal.bilidanmaku.BiliDanmaku.sendServerFeedback
import com.zmal.bilidanmaku.Utils.logger
import com.zmal.bilidanmaku.Utils.sign
import kotlinx.coroutines.*
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class BiliClient(
    private val idCode: String, private val appId: Long, private val host: String, val isClient: Boolean
) {
    private var gameId: String = ""
    private var startT: Long = 0
    private val client = OkHttpClient()
    private val gson = Gson()
    private var websocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var appHeartbeatJob: Job? = null

    private val isRunning = AtomicBoolean(false)

    /**
     * Main entry point - starts the client event loop
     */
    fun run() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("BiliClient is already running")
            return
        }

        scope.launch {
            try {
                val (wsUrl, authBody) = getWebsocketInfo()
                websocket = connect(wsUrl, authBody)
                heartbeatJob = launch {
                    while (isActive) {
                        heartBeat()
                        delay(20_000)
                    }
                }
                appHeartbeatJob = launch {
                    while (isActive) {
                        appHeartBeat()
                        delay(20_000)
                    }
                }
                listOf(heartbeatJob!!, appHeartbeatJob!!).joinAll()
            } catch (e: CancellationException) {
                logger.info("Run loop cancelled normally")
                throw e
            } catch (e: Exception) {
                logger.error("Error in run loop", e)
            } finally {
                isRunning.set(false)
            }
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
    private suspend fun connect(wsUrl: String, authBody: String): WebSocket = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(wsUrl).build()

        websocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("WebSocket connected ${System.identityHashCode(webSocket)}")
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
//                    logger.info("onMessage ${System.identityHashCode(webSocket)}")
                    when (proto.op) {
                        8 -> { // OP_AUTH_REPLY
                            logger.info("鉴权成功")
                            websocket = webSocket
                            if (cont.isActive) {
                                cont.resume(webSocket) { cause, _, _ ->
                                    logger.warn("WebSocket continuation cancelled", cause)
                                    webSocket.close(1000, "Cancelled")
                                }
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

    private fun auth(ws: WebSocket, authBody: String) {
        try {
            val proto = Proto().apply {
                setBody(authBody)
                op = 7
            }
            ws.send(ByteString.of(*proto.pack()))
            websocket = ws
            logger.info("Authentication sent successfully ${System.identityHashCode(ws)}")
        } catch (e: Exception) {
            logger.error("Authentication failed", e)
            throw e
        }
    }

    /**
     * WebSocket heartbeat loop
     */
    private fun heartBeat() {
        try {
            val proto = Proto().apply { op = 2 }
            val data = proto.pack()
//                val ok =
            websocket?.send(ByteString.of(*data))
//            logger.info("heartBeat, wsId=${System.identityHashCode(websocket)} length=${data.size}")
//                logger.info("heartBeat raw hex: ${ByteString.of(*data).hex()}")
        } catch (e: Exception) {
            logger.error("Failed to send heartbeat", e)
            reconnect()
        }
    }

    /**
     * App heartbeat loop
     */
    private suspend fun appHeartBeat() {

        if (gameId.isNotEmpty()) {

            val postUrl = "$host/v2/app/heartbeat"
            val params = gson.toJson(mapOf("game_id" to gameId))
            val headers = sign(params)

            val request =
                Request.Builder().url(postUrl).apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                    .post(params.toRequestBody()).build()
            try {
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) logger.warn("App heartbeat failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.error("App heartbeat error", e)
            } catch (e: SocketTimeoutException) {
                logger.warn("App heartbeat timeout", e)
            }

        }
    }

//    private fun recvLoop(webSocket: WebSocket) {
//        logger.info("[BiliClient] run recv...")
//        // In actual implementation, this would be handled by WebSocketListener.onMessage
//        // This is just a placeholder to match the Python structure
//    }

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
                    val data = json.getAsJsonObject("data") ?: return
                    val uname = data["uname"]?.asString ?: "Unknown"
                    val msg = data["msg"]?.asString ?: ""
                    val dmType = data["dm_type"]?.asInt ?: 0
                    val guardLevel = data["guard_level"]?.asInt ?: 0
                    val isAdmin = data["is_admin"]?.asInt ?: 0
//                    val gloryLevel = data["glory_level"]?.asInt ?: 0

                    // Check for fan medal info
                    val medalInfo = if (data["fans_medal_wearing_status"]?.asBoolean == true) {
                        val medalName = data["fans_medal_name"]?.asString ?: ""
                        val medalLevel = data["fans_medal_level"]?.asInt ?: 0
                        " [$medalName$medalLevel]"
                    } else ""

                    // Build user title/badge info
                    val badges = mutableListOf<String>()
                    if (isAdmin == 1) badges.add("房管")
                    when (guardLevel) {
                        1 -> badges.add("总督")
                        2 -> badges.add("提督")
                        3 -> badges.add("舰长")
                    }
//                    if (gloryLevel > 0) badges.add("荣耀$gloryLevel")

                    val badgeText = if (badges.isNotEmpty()) " ${badges.joinToString("/")}" else ""

                    // Handle different message types
                    val messageText = when (dmType) {
                        1 -> { // Emoji message
                            val emojiUrl = data["emoji_img_url"]?.asString
                            if (!emojiUrl.isNullOrEmpty()) "[表情包: $msg]" else msg
                        }

                        else -> msg
                    }

                    // Check for reply
                    val replyInfo = data["reply_uname"]?.asString?.let { replyUname ->
                        if (replyUname.isNotEmpty()) " @$replyUname " else ""
                    } ?: ""

                    val formattedMsg = Text.literal("[$uname$medalInfo$badgeText] $replyInfo")
                        .styled { it.withColor(Formatting.LIGHT_PURPLE).withBold(true) }
                        .append(Text.literal(messageText).styled { it.withColor(Formatting.WHITE) })
                    sendServerFeedback(formattedMsg)
                }

                "LIVE_OPEN_PLATFORM_SEND_GIFT" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val uname = data["uname"]?.asString ?: "Unknown"
                    val giftName = data["gift_name"]?.asString ?: "Unknown Gift"
                    val giftNum = data["gift_num"]?.asLong ?: 0L
//                    val price = data["price"]?.asLong ?: 0L
//                    val paid = data["paid"]?.asBoolean ?: false
                    val guardLevel = data["guard_level"]?.asInt ?: 0

                    // Check for combo gift
                    val isCombo = data["combo_gift"]?.asBoolean ?: false
                    val comboInfo = if (isCombo) {
                        val comboData = data["combo_info"]?.asJsonObject
                        val comboBaseNum = comboData?.get("combo_base_num")?.asLong ?: 0L
                        val comboCount = comboData?.get("combo_count")?.asLong ?: 0L
                        if (comboBaseNum > 0 && comboCount > 0) {
                            " (连击: ${comboBaseNum}x$comboCount)"
                        } else ""
                    } else ""

                    // Check for blind gift (mystery box)
                    val blindInfo = data["blind_gift"]?.asJsonObject?.let { blindGift ->
                        if (blindGift["status"]?.asBoolean == true) {
                            val blindGiftId = blindGift["blind_gift_id"]?.asLong ?: 0L
                            " [盲盒:$blindGiftId]"
                        } else ""
                    } ?: ""

                    // Check for fan medal info
                    val medalInfo = if (data["fans_medal_wearing_status"]?.asBoolean == true) {
                        val medalName = data["fans_medal_name"]?.asString ?: ""
                        val medalLevel = data["fans_medal_level"]?.asInt ?: 0
                        " [$medalName$medalLevel]"
                    } else ""

                    val guardText = when (guardLevel) {
                        1 -> " [总督]"
                        2 -> " [提督]"
                        3 -> " [舰长]"
                        else -> ""
                    }


                    val msg =
                        Text.literal("[$uname$medalInfo$guardText] 送出 $giftNum 个 $giftName$comboInfo$blindInfo")
                            .styled { it.withColor(Formatting.GREEN) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val uname = data["uname"]?.asString ?: "Unknown"
//                    val timestamp = data["timestamp"]?.asLong ?: 0L

                    val msg = Text.literal("$uname 进入了直播间")
                        .styled { it.withColor(Formatting.GOLD).withItalic(true) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIVE_START" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val title = data["title"]?.asString ?: "Unknown Title"
                    val areaName = data["area_name"]?.asString ?: "Unknown Area"
//                    val roomId = data["room_id"]?.asLong ?: 0L
                    val timestamp = data["timestamp"]?.asLong ?: 0L
                    startT = timestamp
                    val msg = Text.literal("主播开播了: $title ($areaName)")
                        .styled { it.withColor(Formatting.AQUA).withBold(true) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIVE_END" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val title = data["title"]?.asString ?: "Unknown Title"
                    val areaName = data["area_name"]?.asString ?: "Unknown Area"
//                    val roomId = data["room_id"]?.asLong ?: 0L
                    val timestamp = data["timestamp"]?.asLong ?: 0L
                    val durationSeconds = timestamp - startT
                    val hours = durationSeconds / 3600
                    val minutes = (durationSeconds % 3600) / 60
                    val seconds = durationSeconds % 60
                    val durationStr = "%02d:%02d:%02d".format(hours, minutes, seconds)

                    val msg = Text.literal("主播下播了: $title ($areaName) [本次开播时长：$durationStr]")
                        .styled { it.withColor(Formatting.GRAY).withItalic(true) }
                    sendServerFeedback(msg)

                }

                "LIVE_OPEN_PLATFORM_SUPER_CHAT" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val uname = data["uname"]?.asString ?: "Unknown"
                    val message = data["message"]?.asString ?: ""
                    val rmb = data["rmb"]?.asLong ?: 0L
                    val startTime = data["start_time"]?.asLong ?: 0L
                    val endTime = data["end_time"]?.asLong ?: 0L
                    val duration = if (startTime in 1..<endTime) {
                        " (${(endTime - startTime)}秒)"
                    } else ""

                    // Check for fan medal info
                    val medalInfo = if (data["fans_medal_wearing_status"]?.asBoolean == true) {
                        val medalName = data["fans_medal_name"]?.asString ?: ""
                        val medalLevel = data["fans_medal_level"]?.asInt ?: 0
                        " [$medalName$medalLevel]"
                    } else ""

                    val msg = Text.literal("[$uname$medalInfo] 付费留言(¥$rmb$duration): $message")
                        .styled { it.withColor(Formatting.YELLOW).withBold(true) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_SUPER_CHAT_DEL" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val messageIds = data["message_ids"]?.asJsonArray?.map { it.asLong } ?: emptyList()
                    val roomId = data["room_id"]?.asLong ?: 0L

                    val msg =
                        Text.literal("付费留言已下线 (房间: $roomId, 消息ID: ${messageIds.joinToString(", ")})")
                            .styled { it.withColor(Formatting.RED).withItalic(true) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_GUARD" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val userInfo = data.getAsJsonObject("user_info") ?: return
                    val uname = userInfo["uname"]?.asString ?: "Unknown"
                    val guardLevel = data["guard_level"]?.asInt ?: 0
                    val guardNum = data["guard_num"]?.asLong ?: 1L
                    val guardUnit = data["guard_unit"]?.asString ?: "月"
                    val price = data["price"]?.asLong ?: 0L

                    val guardLevelText = when (guardLevel) {
                        1 -> "总督"
                        2 -> "提督"
                        3 -> "舰长"
                        else -> "大航海"
                    }

                    // Check for fan medal info
                    val medalInfo = if (data["fans_medal_wearing_status"]?.asBoolean == true) {
                        val medalName = data["fans_medal_name"]?.asString ?: ""
                        val medalLevel = data["fans_medal_level"]?.asInt ?: 0
                        " [$medalName$medalLevel]"
                    } else ""

                    val priceText = if (price > 0) " (¥${price / 1000})" else ""
                    val durationText = if (guardUnit != "月" || guardNum != 1L) " ${guardNum}${guardUnit}" else ""

                    val msg = Text.literal("[$uname$medalInfo] 成为了 $guardLevelText$durationText$priceText")
                        .styled { it.withColor(Formatting.GOLD).withBold(true) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_LIKE" -> {
                    val data = json.getAsJsonObject("data") ?: return
                    val uname = data["uname"]?.asString ?: "Unknown"
                    val likeCount = data["like_count"]?.asLong ?: 1L
                    val likeText = data["like_text"]?.asString ?: "点赞了"

                    // Check for fan medal info
                    val medalInfo = if (data["fans_medal_wearing_status"]?.asBoolean == true) {
                        val medalName = data["fans_medal_name"]?.asString ?: ""
                        val medalLevel = data["fans_medal_level"]?.asInt ?: 0
                        " [$medalName$medalLevel]"
                    } else ""

                    val msg = Text.literal("[$uname$medalInfo] $likeText ($likeCount 次)")
                        .styled { it.withColor(Formatting.LIGHT_PURPLE) }
                    sendServerFeedback(msg)
                }

                "LIVE_OPEN_PLATFORM_INTERACTION_END" -> reconnect()

                else -> {
                    logger.info("其他消息: $cmd")
                }
            }
        } catch (e: Exception) {
            logger.error("解析消息失败", e)
            // Log the raw message for debugging
            logger.debug("Failed to parse message body: $body")
        }
    }

//    private fun sendFeedback(msg: String) {
//        if (isClient) {
////            sendClientFeedback(msg)
//        }
//    }
    /***
     * Externally Called Methods
     */

    /**
     * Close the client and cleanup resources
     */
    fun close() {
        try {

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
            websocket?.close(1000, "Client shutdown")
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
     * Get status
     */
    fun getStatus(): String {
        return when {
            isRunning.get() -> "运行中 - gameId: $gameId"
            websocket != null -> "ws ${websocket.hashCode()}"
            else -> "未运行"
        }
    }

    fun reconnect() {
        if (!isRunning.get()) return

        heartbeatJob?.cancel()
        appHeartbeatJob?.cancel()

        sendServerFeedback(Text.literal("停止推送，尝试重连"))

        scope.launch {
            try {
                // 先关闭旧连接
                websocket?.close(1000, "Reconnect")
                websocket = null
                delay(5000) // 等 5 秒再重连
                logger.info("LIVE_OPEN_PLATFORM_INTERACTION_END尝试重新连接 WebSocket...")
                val (wsUrl, authBody) = getWebsocketInfo()
                websocket = connect(wsUrl, authBody)
                // Start new jobs
                heartbeatJob = launch {
                    while (isActive) {
                        heartBeat()
                        delay(20_000)
                    }
                }
                appHeartbeatJob = launch {
                    while (isActive) {
                        appHeartBeat()
                        delay(20_000)
                    }
                }
            } catch (e: Exception) {
                logger.error("重连失败", e)
                // 可以再次触发重连或延迟更长时间重试
            }
        }
    }

}