package com.example.deepseek

import com.example.deepseek.db.ConversationRepository
import com.example.deepseek.db.DatabaseFactory
import com.example.deepseek.model.ChatMessage
import com.example.deepseek.model.ChatResponse
import com.example.deepseek.model.ConversationDto
import com.example.deepseek.model.ConversationSummaryDto
import com.example.deepseek.provider.ChatProvider
import com.example.deepseek.provider.Completion
import com.example.deepseek.provider.DeepSeekException
import com.example.deepseek.provider.Usage
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatRoutesTest {

    /** Records what the server sent to the provider. */
    private class FakeChatProvider : ChatProvider {
        val receivedHistories = mutableListOf<List<ChatMessage>>()
        val receivedModels = mutableListOf<String>()

        override suspend fun complete(
            history: List<ChatMessage>,
            model: String,
            reasoningEffort: String,
        ): Completion {
            receivedHistories += history
            receivedModels += model
            return Completion("Ответ на: ${history.last().content}", Usage(1, 2, 3))
        }
    }

    private val provider = FakeChatProvider()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private lateinit var repository: ConversationRepository

        @JvmStatic
        @BeforeAll
        fun connectDatabase() {
            DatabaseFactory.connect(testDatabaseConfig("routes-test"))
            repository = ConversationRepository()
        }
    }

    @Test
    fun `chat creates conversation, calls provider with history, persists reply`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Привет, сервер"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val chat = json.decodeFromString<ChatResponse>(response.bodyAsText())
        assertEquals("Ответ на: Привет, сервер", chat.reply.content)
        assertEquals("assistant", chat.reply.role)
        assertNotNull(chat.usage)

        // provider received exactly one user message
        assertEquals(1, provider.receivedHistories.size)
        assertEquals("Привет, сервер", provider.receivedHistories[0].last().content)

        // conversation persisted with both messages
        val conversationResponse = client.get("/api/v1/conversations/${chat.conversationId}")
        assertEquals(HttpStatusCode.OK, conversationResponse.status)
        val conversation = json.decodeFromString<ConversationDto>(conversationResponse.bodyAsText())
        assertEquals("Привет, сервер", conversation.title)
        assertEquals(2, conversation.messages.size)
    }

    @Test
    fun `history accumulates across turns of the same conversation`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val first = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Первый вопрос"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstChat = json.decodeFromString<ChatResponse>(first.bodyAsText())

        val second = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"conversationId":"${firstChat.conversationId}","message":"Второй вопрос"}""")
        }
        assertEquals(HttpStatusCode.OK, second.status)

        // second call must carry the full history: user, assistant, user
        val lastHistory = provider.receivedHistories.last()
        assertEquals(
            listOf("user", "assistant", "user"),
            lastHistory.map { it.role },
        )
        assertEquals("Второй вопрос", lastHistory.last().content)
    }

    @Test
    fun `per-request model override is passed to the provider`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi","model":"deepseek-reasoner"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("deepseek-reasoner", provider.receivedModels.last())
    }

    @Test
    fun `empty message is rejected with 400`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `unknown conversation id returns 404`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val id = java.util.UUID.randomUUID()
        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"conversationId":"$id","message":"hi"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `provider error is surfaced with its status code`() = testApplication {
        val failingProvider = object : ChatProvider {
            override suspend fun complete(
                history: List<ChatMessage>,
                model: String,
                reasoningEffort: String,
            ): Completion = throw DeepSeekException(401, "Invalid API key")
        }
        application { configureApp(testAppConfig(), repository, failingProvider) }

        val response = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Invalid API key"))
    }

    @Test
    fun `delete removes the conversation`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val created = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"to delete"}""")
        }
        val chat = json.decodeFromString<ChatResponse>(created.bodyAsText())

        val deleted = client.delete("/api/v1/conversations/${chat.conversationId}")
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val gone = client.get("/api/v1/conversations/${chat.conversationId}")
        assertEquals(HttpStatusCode.NotFound, gone.status)
    }

    @Test
    fun `conversations list contains the summary`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val created = client.post("/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"список"}""")
        }
        val chat = json.decodeFromString<ChatResponse>(created.bodyAsText())

        val listResponse = client.get("/api/v1/conversations")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val list = json.decodeFromString<List<ConversationSummaryDto>>(listResponse.bodyAsText())
        assertTrue(list.any { it.id == chat.conversationId && it.messageCount == 2L })
    }

    @Test
    fun `health endpoint reports ok`() = testApplication {
        application { configureApp(testAppConfig(), repository, provider) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""))
    }
}
