package com.example.deepseek.provider

import com.example.deepseek.model.ChatMessage

data class Usage(val promptTokens: Long, val completionTokens: Long, val totalTokens: Long)

data class Completion(val content: String, val usage: Usage?)

/** Abstraction over the LLM backend so routes can be tested with a fake provider. */
interface ChatProvider {
    suspend fun complete(history: List<ChatMessage>, model: String, reasoningEffort: String): Completion
}

/** Raised when the upstream provider is unreachable or returns an error. */
class DeepSeekException(val statusCode: Int, message: String) : RuntimeException(message)
