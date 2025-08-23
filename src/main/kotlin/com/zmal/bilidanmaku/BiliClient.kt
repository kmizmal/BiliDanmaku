package com.zmal.bilidanmaku

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.zmal.bilidanmaku.BiliDanmaku.sendFeedback
import kotlinx.coroutines.*
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ByteString
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class BiliClient(
    private val idCode: String,
    private val appId: Long,
    private val key: String,
    private val secret: String,
    private val host: String
) {
    private var gameId: String = ""
    private val client = OkHttpClient()
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var heartbeatTask: ScheduledFuture<*>? = null

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isConnected = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)
    private var isManuallyClosed = false
    private val reconnectAttempts = AtomicInteger(0)
    private val maxReconnectAttempts = 10 // Increased from 5

    private val baseReconnectDelay = 2000L
    private val maxReconnectDelay = 60000L

    private var lastWebSocketUrl: String? = null
    private var lastAuthBody: String? = null

    private var lastHeartbeatTime = AtomicLong(System.currentTimeMillis())
    private var heartbeatJob: Job? = null
    private var appHeartbeatJob: Job? = null
    private var healthCheckJob: Job? = null
    private var lastHeartbeatReplyTime: AtomicLong = AtomicLong(0)

    private var lastTimestamp: Long = 0
    private var isLive = false

    private val logger = LoggerFactory.getLogger("bilidanmaku")

    private fun sign(params: String): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000
        val nonce = Random.nextInt(1, 100000) + System.currentTimeMillis()
        val md5 = MessageDigest.getInstance("MD5").digest(params.toByteArray(Charsets.UTF_8))
        val md5data = md5.joinToString("") { "%02x".format(it) }

        val headerMap = mutableMapOf(
            "x-bili-timestamp" to ts.toString(),
            "x-bili-signature-method" to "HMAC-SHA256",
            "x-bili-signature-nonce" to nonce.toString(),
            "x-bili-accesskeyid" to key,
            "x-bili-signature-version" to "1.0",
            "x-bili-content-md5" to md5data,
        )

        val headerStr = headerMap.toSortedMap().map { "${it.key}:${it.value}" }.joinToString("\n")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal(headerStr.toByteArray()).joinToString("") { "%02x".format(it) }

        val finalHeaders = mutableMapOf(
            "Authorization" to signature, "Content-Type" to "application/json", "Accept" to "application/json"
        )
        finalHeaders.putAll(headerMap)
        return finalHeaders
    }

    private suspend fun getWebsocketInfo(retryCount: Int = 3): Pair<String, String> {
        repeat(retryCount) { attempt ->
            try {
                return getWebsocketInfoInternal()
            } catch (e: Exception) {
                logger.warn("Failed to get websocket info (attempt ${attempt + 1}/$retryCount): ${e.message}")
                if (attempt < retryCount - 1) {
                    delay(1000L * (attempt + 1)) // Progressive delay
                }
            }
        }
        throw RuntimeException("Failed to get websocket info after $retryCount attempts")
    }

    private fun getWebsocketInfoInternal(): Pair<String, String> {
        val postUrl = "$host/v2/app/start"
        val params = """{"code":"$idCode","app_id":$appId}"""
        val headers = sign(params)

        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.write(params.toByteArray(Charsets.UTF_8))
            }
        }

        val request = Request.Builder().url(postUrl).apply {
            headers.forEach { (key, value) ->
                addHeader(key, value)
            }
        }.post(requestBody).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Request failed: ${response.code} ${response.message}")
            }
            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            logger.debug("body: $body")

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
            val addr = (wsInfo["wss_link"] as List<*>)[0].toString()
            val authBody = wsInfo["auth_body"].toString()
            return addr to authBody
        }
    }

    fun run() {
        if (isShuttingDown.get()) {
            logger.warn("Cannot start - client is shutting down")
            return
        }

        scope.launch {
            try {
                val (addr, authBody) = getWebsocketInfo()
                lastWebSocketUrl = addr
                lastAuthBody = authBody
                connectWebSocket(addr, authBody)
            } catch (e: Exception) {
                logger.error("Failed to initialize connection: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun connectWebSocket(addr: String, authBody: String) {
        if (isShuttingDown.get()) {
            logger.info("Skipping connection - client is shutting down")
            return
        }

        val request = Request.Builder().url(addr).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("WebSocket connected")
                sendFeedback("连接成功")
                isConnected.set(true)
                reconnectAttempts.set(0)
                lastHeartbeatTime.set(System.currentTimeMillis())
                lastHeartbeatReplyTime.set(System.currentTimeMillis())


                auth(webSocket, authBody)
                startHeartbeats()
                startHealthCheck()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                logger.info("recv text: $text")
                lastHeartbeatTime.set(System.currentTimeMillis())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastHeartbeatTime.set(System.currentTimeMillis())
                val proto = Proto()
                proto.unpack(bytes.toByteArray())
                val body = proto.getBodyAsString()

                if (proto.op == 3) lastHeartbeatReplyTime.set(System.currentTimeMillis())
                else logger.info("recv op=${proto.op} body=$body")

                exBody(body)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.error("WebSocket failure", t)
                heartbeatTask?.cancel(true)
                heartbeatTask = null
                if (!isShuttingDown.get() && !isManuallyClosed) {
                    handleConnectionLoss()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.warn("WebSocket closed: code=$code reason=$reason")
                heartbeatTask?.cancel(true)
                heartbeatTask = null
                if (!isShuttingDown.get() && !isManuallyClosed) {
                    handleConnectionLoss()
                }
            }
        })
    }

    private fun exBody(body: String) {
        try {
            if (body.trim().startsWith("{")) {
                val json = JsonParser.parseString(body).asJsonObject
                if (json.has("cmd")) {
                    when (val cmd = json["cmd"].asString) {
                        "LIVE_OPEN_PLATFORM_DM" -> {
                            val data = json["data"].asJsonObject
                            val uname = data["uname"].asString
                            val msg = data["msg"].asString
                            logger.info("弹幕 [$uname] $msg")
                            val msgs: Text? = Text.literal("[${uname}] ")
                                .styled { it.withColor(Formatting.LIGHT_PURPLE).withBold(true) }
                                .append(Text.literal(msg).styled { it.withColor(Formatting.WHITE) })
                            msgs?.let { sendFeedback(it) }
                            //                                    sendAck(webSocket, json["id"].asString) // 添加确认
                        }

                        "LIVE_OPEN_PLATFORM_SEND_GIFT" -> {
                            val data = json["data"].asJsonObject
                            val uname = data["uname"].asString
                            val gift = data["gift_name"].asString
                            val num = data["gift_num"].asInt
                            logger.info("礼物 [$uname] 送出 $num 个 $gift")

                            val msg = Text.literal("[")
                                .append(Text.literal(uname).styled { it.withColor(Formatting.GREEN) })
                                .append(Text.literal("] 送出 "))
                                .append(Text.literal(num.toString()).styled { it.withColor(Formatting.BLUE) })
                                .append(Text.literal(" "))
                                .append(Text.literal(gift).styled { it.withColor(Formatting.WHITE) })
                            msg?.let { sendFeedback(it) }
                        }

                        "LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER" -> {
                            val data = json["data"].asJsonObject
                            val uname = data["uname"].asString
                            logger.info("$uname 进入了直播间")
                            val msg = Text.literal("$uname 进入了直播间").styled {
                                it.withColor(Formatting.GOLD).withItalic(true).withBold(true)
                            }
                            msg?.let { sendFeedback(it) }
                        }

                        "LIVE_OPEN_PLATFORM_LIVE_START" -> {
                            val data = json["data"].asJsonObject
                            val title = data["title"].asString
                            val areaName = data["area_name"].asString
                            val timestamp = data["timestamp"].asLong
                            isLive = true
                            lastTimestamp = timestamp
                            val msg = Text.literal("主播开播了 ").append(Text.literal("[${title}]"))
                                .append(Text.literal("(${areaName})"))
                            msg?.let { sendFeedback(it) }
                        }

                        "LIVE_OPEN_PLATFORM_LIVE_END" -> {
                            val data = json["data"].asJsonObject
                            val title = data["title"].asString
                            val areaName = data["area_name"].asString
                            val endTimestamp = data["timestamp"].asLong

                            isLive = false
                            val alive = endTimestamp - lastTimestamp
                            val hours = alive / 3600
                            val minutes = (alive % 3600) / 60
                            val seconds = alive % 60
                            val formatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)

                            val msg = Text.literal("主播下播了：")
                                .append(Text.literal(title).formatted(Formatting.AQUA))
                                .append(Text.literal("（$areaName）"))
                                .append(Text.literal(" 本次直播时长 $formatted"))
                            sendFeedback(msg)
                        }

                        else -> {
                            logger.info("其他消息: $cmd $json")
                        }
                    }
                } else {
                    logger.info("无 cmd 消息: $json")
                }
            } else {
                logger.debug("收到非 JSON 消息: $body")
            }
        } catch (e: Exception) {
            logger.error("解析消息失败: ${e.message}", e)
        }
    }
//  文档与demo中均未提及，不启用
//    private fun sendAck(webSocket: WebSocket, messageId: String) {
//        val ackJson = """{"message_id":"$messageId"}"""
//        val proto = Proto().apply {
//            op = 8 // 确认操作码
//            setBody(ackJson)
//        }
//        webSocket.send(ByteString.of(*proto.pack()))
//    }

    private fun handleConnectionLoss() {
        isConnected.set(false)
        stopHeartbeats()

        if (!isShuttingDown.get() && !isReconnecting.get()) {
            scheduleReconnect()
        }
    }

    private fun calculateReconnectDelay(): Long {
        val attempt = reconnectAttempts.get()
        val exponentialDelay = minOf(baseReconnectDelay * (1L shl attempt), maxReconnectDelay)
        val jitter = (exponentialDelay * 0.1 * Random.nextDouble()).toLong()
        return exponentialDelay + jitter
    }

    private fun scheduleReconnect() {
        if (isShuttingDown.get()) {
            logger.info("Not scheduling reconnect - shutting down")
            return
        }

        if (isReconnecting.compareAndSet(false, true)) {
            scope.launch {
                val attempts = reconnectAttempts.incrementAndGet()
                if (attempts <= maxReconnectAttempts) {
                    val delay = calculateReconnectDelay()
                    logger.info("Scheduling reconnection attempt $attempts/$maxReconnectAttempts in ${delay}ms")
                    sendFeedback("连接断开，${delay / 1000}秒后重连 (${attempts}/${maxReconnectAttempts})")

                    delay(delay)

                    if (!isShuttingDown.get()) {
                        reconnect()
                    }
                } else {
                    logger.error("Max reconnection attempts ($maxReconnectAttempts) reached. Stopping reconnection.")
                    sendFeedback("连接失败，已达到最大重连次数")
                    isReconnecting.set(false)
                }
            }
        }
    }

    private suspend fun reconnect() {
        try {
            logger.info("Attempting to reconnect...")

            // Close existing connection
//            webSocket?.close(1000, "reconnecting")
            sendFeedback("重连前关闭")
            stopHeartbeats()

            val addr = lastWebSocketUrl
            val authBody = lastAuthBody

            if (addr != null && authBody != null) {
                try {
                    connectWebSocket(addr, authBody)
                } catch (e: Exception) {
                    logger.warn("Failed to reconnect with cached info, fetching new info: ${e.message}")
                    val (newAddr, newAuthBody) = getWebsocketInfo()
                    lastWebSocketUrl = newAddr
                    lastAuthBody = newAuthBody
                    connectWebSocket(newAddr, newAuthBody)
                }
            } else {
                logger.info("No cached connection info, getting fresh info")
                val (newAddr, newAuthBody) = getWebsocketInfo()
                lastWebSocketUrl = newAddr
                lastAuthBody = newAuthBody
                connectWebSocket(newAddr, newAuthBody)
            }
        } catch (e: Exception) {
            logger.error("Reconnection failed: ${e.message}")
            scheduleReconnect()
        } finally {
            isReconnecting.set(false)
        }
    }

    fun reload() {
        scope.launch {
            try {
                logger.info("Reloading connection...")
                sendFeedback("正在重新加载连接...")

                webSocket?.close(1000, "reloading")
                sendFeedback("重载前关闭")
                stopHeartbeats()
                isConnected.set(false)

                isReconnecting.set(false)
                reconnectAttempts.set(0)

                val (addr, authBody) = getWebsocketInfo()
                lastWebSocketUrl = addr
                lastAuthBody = authBody

                connectWebSocket(addr, authBody)

                logger.info("Reload completed successfully")
            } catch (e: Exception) {
                logger.error("Reload failed: ${e.message}")
                sendFeedback("重新加载失败: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun startHealthCheck() {
        heartbeatJob = scope.launch {
            delay(30000) // 30秒后开始健康检查

            while (isActive && isConnected.get()) {
                delay(20000) // 20秒心跳

                // 检查上次心跳回复时间
                val timeSinceLastReply = System.currentTimeMillis() - lastHeartbeatReplyTime.get()
                if (timeSinceLastReply > 60000) { // 60秒无回复认为连接异常
                    logger.warn("No heartbeat reply for ${timeSinceLastReply}ms, reconnecting")
                    webSocket?.close(1001, "heartbeat timeout")
                    break
                }

                // 发送心跳
                val hb = Proto().apply { op = 2 } // OP_HEARTBEAT
                webSocket?.send(ByteString.of(*hb.pack()))
            }

        }
    }

    fun getStatus(): String {
        val sb = StringBuilder()
        val connected = isConnected.get()
        val reconnecting = isReconnecting.get()
        val attempts = reconnectAttempts.get()

        sb.append("弹幕姬状态: ")
        when {
            connected -> sb.append("已连接")
            reconnecting -> sb.append("重连中 ($attempts/$maxReconnectAttempts)")
            else -> sb.append("未连接")
        }

        if (!connected) {
            return sb.toString()
        }

        sb.append("；直播间状态: ").append(if (isLive) "已开播" else "未开播")

        if (!isLive && lastTimestamp > 0) {
            val date = Date(lastTimestamp * 1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val formatted = sdf.format(date)
            sb.append("；上一次开播时间: ").append(formatted)
        }

        return sb.toString()
    }

    fun isConnected(): Boolean = isConnected.get()

    private fun auth(webSocket: WebSocket, rawBody: String) {
        try {
            val bodyJson = JsonParser.parseString(rawBody).asJsonObject
            logger.debug("Auth body={}", bodyJson)

            val proto = Proto().apply {
                op = 7
                setBody(bodyJson.toString())
            }
            webSocket.send(ByteString.of(*proto.pack()))
            logger.info("Auth packet sent")
        } catch (e: Exception) {
            logger.error("Auth body is not valid JSON: $rawBody", e)
        }
    }

    private fun startHeartbeats() {
        stopHeartbeats()

        heartbeatJob = scope.launch {
            while (isActive && isConnected.get() && !isShuttingDown.get()) {
                delay(20_000)
                if (isConnected.get()) {
                    try {
                        val hb = Proto().apply { op = 2 }
                        webSocket?.send(ByteString.of(*hb.pack()))
                        logger.debug("send websocket heartbeat")
                    } catch (e: Exception) {
                        logger.error("Failed to send websocket heartbeat: ${e.message}")
                        break
                    }
                }
            }
        }

        appHeartbeatJob = scope.launch {
            while (isActive && isConnected.get() && !isShuttingDown.get()) {
                delay(20_000)
                if (isConnected.get() && gameId.isNotEmpty()) {
                    try {
                        val postUrl = "$host/v2/app/heartbeat"
                        val params = """{"game_id":"$gameId"}"""
                        val headers = sign(params)
                        val request = Request.Builder().url(postUrl).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.post(params.toRequestBody("application/json".toMediaType())).build()

                        client.newCall(request).execute().use {
                            if (it.isSuccessful) {
                                logger.debug("send appHeartBeat success")
                            } else {
                                logger.warn("App heartbeat failed: ${it.code}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("App heartbeat error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopHeartbeats() {
        heartbeatJob?.cancel()
        appHeartbeatJob?.cancel()
        healthCheckJob?.cancel()
        heartbeatJob = null
        appHeartbeatJob = null
        healthCheckJob = null
    }

    fun close() {
        try {
            isShuttingDown.set(true)
            isReconnecting.set(false)
            isConnected.set(false)

            stopHeartbeats()

            if (gameId.isNotEmpty()) {
                try {
                    val postUrl = "$host/v2/app/end"
                    val params = """{"game_id":"$gameId","app_id":$appId}"""
                    val headers = sign(params)

                    val request = Request.Builder().url(postUrl).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.post(params.toRequestBody("application/json".toMediaType())).build()

                    client.newCall(request).execute().use {
                        logger.info("end app success: $params")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to send end app request: ${e.message}")
                }
            }

            webSocket?.close(1000, "bye")
            sendFeedback("手动关闭")
            scope.cancel()
        } catch (e: Exception) {
            logger.error("Error during close: ${e.message}")
        }
    }
}