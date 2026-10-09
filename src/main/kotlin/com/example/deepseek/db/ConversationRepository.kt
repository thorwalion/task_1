package com.example.deepseek.db

import com.example.deepseek.model.ChatMessage
import com.example.deepseek.model.ConversationDto
import com.example.deepseek.model.ConversationSummaryDto
import com.example.deepseek.model.MessageDto
import com.example.deepseek.model.toInstant
import com.example.deepseek.model.toIsoString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ConversationRepository {

    /** Creates a conversation whose title is derived from the first user message. */
    suspend fun createConversation(firstUserMessage: String): UUID = dbQuery {
        val id = UUID.randomUUID()
        Conversations.insert {
            it[Conversations.id] = id
            it[title] = firstUserMessage.take(200)
            it[createdAt] = now()
            it[updatedAt] = now()
        }
        id
    }

    suspend fun addMessage(conversationId: UUID, role: String, content: String): Long = dbQuery {
        val id = Messages.insert {
            it[Messages.conversationId] = conversationId
            it[Messages.role] = role
            it[Messages.content] = content
            it[createdAt] = now()
        }.get(Messages.id)

        Conversations.update({ Conversations.id eq conversationId }) {
            it[updatedAt] = now()
        }
        id
    }

    /** Full message history of a conversation, oldest first. */
    suspend fun getHistory(conversationId: UUID): List<ChatMessage> = dbQuery {
        Messages.selectAll()
            .where { Messages.conversationId eq conversationId }
            .orderBy(Messages.id, SortOrder.ASC)
            .map { ChatMessage(role = it[Messages.role], content = it[Messages.content]) }
    }

    suspend fun listConversations(): List<ConversationSummaryDto> = dbQuery {
        val countExpr = Messages.id.count()
        val messageCounts: Map<UUID, Long> = Messages
            .select(Messages.conversationId, countExpr)
            .groupBy(Messages.conversationId)
            .associate { row ->
                row[Messages.conversationId] to row[countExpr] as Long
            }

        Conversations.selectAll()
            .orderBy(Conversations.updatedAt, SortOrder.DESC)
            .map {
                ConversationSummaryDto(
                    id = it[Conversations.id],
                    title = it[Conversations.title],
                    createdAt = toInstant(it[Conversations.createdAt]).toIsoString(),
                    updatedAt = toInstant(it[Conversations.updatedAt]).toIsoString(),
                    messageCount = messageCounts[it[Conversations.id]] ?: 0,
                )
            }
    }

    suspend fun findConversation(id: UUID): ConversationDto? = dbQuery {
        val row = Conversations.selectAll()
            .where { Conversations.id eq id }
            .singleOrNull() ?: return@dbQuery null

        val messages = Messages.selectAll()
            .where { Messages.conversationId eq id }
            .orderBy(Messages.id, SortOrder.ASC)
            .map {
                MessageDto(
                    id = it[Messages.id],
                    role = it[Messages.role],
                    content = it[Messages.content],
                    createdAt = toInstant(it[Messages.createdAt]).toIsoString(),
                )
            }

        ConversationDto(
            id = row[Conversations.id],
            title = row[Conversations.title],
            createdAt = toInstant(row[Conversations.createdAt]).toIsoString(),
            updatedAt = toInstant(row[Conversations.updatedAt]).toIsoString(),
            messages = messages,
        )
    }

    /** Returns true if the conversation existed and was deleted. */
    suspend fun deleteConversation(id: UUID): Boolean = dbQuery {
        Conversations.deleteWhere { Conversations.id eq id } > 0
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    private suspend fun <T> dbQuery(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.database) { block() }
    }
}
