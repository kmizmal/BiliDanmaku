package com.zmal.bilidanmaku

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@Suppress("SpellCheckingInspection")
object Utils {
    val logger = LoggerFactory.getLogger("bilidanmaku")!!
    var currentIdCode: String = ""
    private const val FIXED_APP_ID = 1760270800600
    private const val FIXED_KEY = "15TFVHJi774HSW6YAmQ4toxn"
    private const val FIXED_SECRET = "pVLu5MS0FsH5X2bfOscdcHrqQUbXEJ"
    private const val FIXED_HOST = "https://live-open.biliapi.com"

    fun sign(params: String): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000
        val nonce = Random.nextInt(1, 100000) + System.currentTimeMillis()
        val md5 = MessageDigest.getInstance("MD5").digest(params.toByteArray(Charsets.UTF_8))
        val md5data = md5.joinToString("") { "%02x".format(it) }

        val headerMap = mutableMapOf(
            "x-bili-timestamp" to ts.toString(),
            "x-bili-signature-method" to "HMAC-SHA256",
            "x-bili-signature-nonce" to nonce.toString(),
            "x-bili-accesskeyid" to FIXED_KEY,
            "x-bili-signature-version" to "1.0",
            "x-bili-content-md5" to md5data,
        )

        val headerStr = headerMap.toSortedMap().map { "${it.key}:${it.value}" }.joinToString("\n")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(FIXED_SECRET.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal(headerStr.toByteArray()).joinToString("") { "%02x".format(it) }

        val finalHeaders = mutableMapOf(
            "Authorization" to signature, "Content-Type" to "application/json", "Accept" to "application/json"
        )
        finalHeaders.putAll(headerMap)
        return finalHeaders
    }

    lateinit var cli: BiliClient

    fun initBiliClient() {
        try {
            currentIdCode = ConfigManager.getIdCode()
            if(this::cli.isInitialized)return
            cli = BiliClient(
                currentIdCode, FIXED_APP_ID,FIXED_HOST
            )
            CompletableFuture.runAsync{
                cli.run()
                logger.info("BiliClient 初始化并连接")
            }

        } catch (e: Exception) {
            logger.error("Failed to initialize BiliClient: ${e.message}", e)
        }
    }
    fun reloadClient() : Boolean{
        try {
            // 如果 cli 已经初始化并运行，可以先做一些清理
            if (this::cli.isInitialized) {
                try {
                    cli.close() // 假设你的 BiliClient 有 stop() 方法
                    logger.info("旧的 BiliClient 已停止")
                } catch (e: Exception) {
                    logger.warn("停止旧的 BiliClient 时出错: ${e.message}")
                }
            }

            // 重新获取 idCode 并初始化
            currentIdCode = ConfigManager.getIdCode()
            cli = BiliClient(currentIdCode, FIXED_APP_ID, FIXED_HOST)

            // 异步运行客户端
            CompletableFuture.runAsync {
                try {
                    cli.run()
                    logger.info("BiliClient 已重新初始化并连接")
                } catch (e: Exception) {
                    logger.error("BiliClient 运行出错: ${e.message}", e)
                }
            }
            return  true
        } catch (e: Exception) {
            logger.error("Failed to reload BiliClient: ${e.message}", e)
            return false
        }
    }

}