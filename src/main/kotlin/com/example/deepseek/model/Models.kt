package com.example.deepseek.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A chat message in the form the provider API expects. */
data class ChatMessage(val role: String, val content: String)

/** kotlinx.serialization has no built-in java.util.UUID serializer; encode as string. */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

// --- API DTOs (camelCase JSON, exposed to clients) ---

@Serializable
data class ChatRequest(
    @Serializable(with = UUIDSerializer::class) val conversationId: UUID? = null,
    val message: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
)

@Serializable
data class MessageDto(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: String,
)

@Serializable
data class ConversationSummaryDto(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val title: String?,
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Long,
)

@Serializable
data class ConversationDto(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val title: String?,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<MessageDto>,
)

@Serializable
data class UsageDto(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
)

@Serializable
data class ChatResponse(
    @Serializable(with = UUIDSerializer::class) val conversationId: UUID,
    val reply: MessageDto,
    val usage: UsageDto? = null,
)

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

/** Normalizes whatever JDBC driver returned for a TIMESTAMP WITH TIME ZONE column. */
internal fun toInstant(value: Any?): Instant = when (value) {
    is Instant -> value
    is OffsetDateTime -> value.toInstant()
    is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
    is java.sql.Timestamp -> value.toInstant()
    else -> throw IllegalStateException("Unexpected timestamp value: $value")
}

internal fun Instant.toIsoString(): String = DateTimeFormatter.ISO_INSTANT.format(this)
