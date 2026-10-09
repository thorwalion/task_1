package com.example.deepseek.config

import io.ktor.server.config.ApplicationConfig

data class DeepSeekConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String,
    val thinkingType: String,
    val systemPrompt: String?,
    val timeoutSeconds: Long,
)

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

data class AppConfig(
    val deepseek: DeepSeekConfig,
    val database: DatabaseConfig,
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val ds = config.config("deepseek")
            val apiKey = ds.propertyOrNull("apiKey")?.getString()
                ?: throw IllegalStateException(
                    "DeepSeek API key is not configured. " +
                        "Set the DEEPSEEK_API_KEY environment variable and restart the server."
                )

            val database = config.config("database")

            return AppConfig(
                deepseek = DeepSeekConfig(
                    baseUrl = ds.property("baseUrl").getString().trimEnd('/'),
                    apiKey = apiKey,
                    model = ds.property("model").getString(),
                    reasoningEffort = ds.property("reasoningEffort").getString(),
                    thinkingType = ds.property("thinkingType").getString(),
                    systemPrompt = ds.propertyOrNull("systemPrompt")?.getString()?.takeIf { it.isNotBlank() },
                    timeoutSeconds = ds.property("timeoutSeconds").getString().toLong(),
                ),
                database = DatabaseConfig(
                    url = database.property("url").getString(),
                    user = database.property("user").getString(),
                    password = database.property("password").getString(),
                    maxPoolSize = database.property("maxPoolSize").getString().toInt(),
                ),
            )
        }
    }
}
