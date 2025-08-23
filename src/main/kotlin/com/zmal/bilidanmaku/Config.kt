package com.zmal.bilidanmaku

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object ConfigManager {

    private var cachedConfig: Config? = null
    private var lastModifiedTime: Long = 0

    data class Config(
        var idCode: String = ""
    )

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    private val configFolder: Path
        // 使用 FabricLoader 提供的 config 目录
        get() = FabricLoader.getInstance().configDir.resolve("bilidanmaku")

    private val configPath: Path
        get() = configFolder.resolve("bilibili_config.json")

    fun getBilibiliConfig(): Config {
        val file = configPath.toFile()
        if (cachedConfig == null || !Files.exists(configPath) || file.lastModified() != lastModifiedTime) {
            ensureConfigFolder()

            cachedConfig = if (Files.exists(configPath)) {
                try {
                    val jsonString = Files.readString(configPath, StandardCharsets.UTF_8)
//                    println("Loaded config: $jsonString")

                    if (jsonString.trim().isEmpty() || jsonString.trim() == "{}") {
                        println("Empty config file, creating default config")
                        val defaultConfig = Config()
                        saveBilibiliConfig(defaultConfig)
                        defaultConfig
                    } else {
                        val config = gson.fromJson(jsonString, Config::class.java)
                        config ?: Config().also { saveBilibiliConfig(it) }
                    }
                } catch (e: Exception) {
                    println("Error loading config: ${e.message}")
                    Config().also { saveBilibiliConfig(it) }
                }
            } else {
                println("Config file not found, creating default config")
                val defaultConfig = Config()
                saveBilibiliConfig(defaultConfig)
                defaultConfig
            }

            lastModifiedTime = if (Files.exists(configPath)) file.lastModified() else 0
        }

        return cachedConfig ?: Config()
    }

    fun saveBilibiliConfig(config: Config): Boolean {
        ensureConfigFolder()
        return try {
            val jsonString = gson.toJson(config)
            Files.writeString(
                configPath,
                jsonString,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            cachedConfig = config
            lastModifiedTime = configPath.toFile().lastModified()
            println("Config saved successfully")
            true
        } catch (e: Exception) {
            println("Failed to save config: ${e.message}")
            false
        }
    }

    fun forceReload(): Config {
        cachedConfig = null
        lastModifiedTime = 0
        return getBilibiliConfig()
    }

    private fun ensureConfigFolder(): Boolean {
        return if (!Files.exists(configFolder)) {
            try {
                Files.createDirectories(configFolder)
                println("Created config directory: $configFolder")
                true
            } catch (e: IOException) {
                println("Failed to create config directory: ${e.message}")
                false
            }
        } else {
            true
        }
    }

    fun updateIdCode(newIdCode: String): Boolean {
        val config = getBilibiliConfig()
        config.idCode = newIdCode
        return saveBilibiliConfig(config)
    }

    fun getIdCode(): String = getBilibiliConfig().idCode
}
