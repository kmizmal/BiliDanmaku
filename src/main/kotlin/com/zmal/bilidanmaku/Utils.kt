package com.zmal.bilidanmaku

//import org.slf4j.LoggerFactory
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object Utils {
//    val logger = LoggerFactory.getLogger("bilidanmaku")!!
    fun sign(params: String,key:String,secret:String): Map<String, String> {
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
}