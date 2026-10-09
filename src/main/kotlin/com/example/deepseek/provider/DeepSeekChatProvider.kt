package com.example.deepseek.provider

import com.example.deepseek.config.DeepSeekConfig
import com.example.deepseek.model.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Calls the DeepSeek chat completions endpoint (OpenAI-compatible protocol).
 * Request/response fields use snake_case, hence the dedicated Json instance.
 */
class DeepSeekChatProvider(private val config: DeepSeekConfig) : ChatProvider {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(snakeJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = config.timeoutSeconds * 1000
        }
    }

    override suspend fun complete(
        history: List<ChatMessage>,
        model: String,
        reasoningEffort: String,
    ): Completion {
        val request = DeepSeekRequest(
            model = model,
            messages = history.map { DeepSeekMessage(role = it.role, content = it.content) },
            thinking = Thinking(type = config.thinkingType),
            reasoningEffort = reasoningEffort,
        )

        val response = try {
            client.post("${config.baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: Exception) {
            throw DeepSeekException(502, "DeepSeek API is unreachable: ${e.message}")
        }

        if (!response.status.isSuccess()) {
            val error = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<DeepSeekErrorResponse>(response.bodyAsText())
            }.getOrNull()
            val message = error?.error?.message ?: "DeepSeek API returned ${response.status}"
            throw DeepSeekException(response.status.value, message)
        }

        val body = response.body<DeepSeekResponse>()
        val content = body.choices.firstOrNull()?.message?.content
            ?: throw DeepSeekException(502, "DeepSeek returned an empty response")

        return Completion(
            content = content,
            usage = body.usage?.let { Usage(it.promptTokens, it.completionTokens, it.totalTokens) },
        )
    }

    @Serializable
    data class DeepSeekRequest(
        val model: String,
        val messages: List<DeepSeekMessage>,
        val thinking: Thinking,
        val reasoningEffort: String,
        val stream: Boolean = false,
    )

    @Serializable
    data class Thinking(val type: String)

    @Serializable
    data class DeepSeekMessage(val role: String, val content: String)

    @Serializable
    data class DeepSeekResponse(
        val choices: List<Choice> = emptyList(),
        val usage: DeepSeekUsage? = null,
    )

    @Serializable
    data class Choice(val message: DeepSeekMessage? = null)

    @Serializable
    data class DeepSeekUsage(
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val totalTokens: Long = 0,
    )

    @Serializable
    data class DeepSeekErrorResponse(val error: DeepSeekError? = null)

    @Serializable
    data class DeepSeekError(val message: String? = null)

    private companion object {
        val snakeJson = Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
            ignoreUnknownKeys = true
        }
    }
}
