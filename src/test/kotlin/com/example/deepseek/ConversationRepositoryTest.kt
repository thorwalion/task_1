package com.example.deepseek

import com.example.deepseek.db.ConversationRepository
import com.example.deepseek.db.DatabaseFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationRepositoryTest {

    private val repository = ConversationRepository()

    companion object {
        @JvmStatic
        @BeforeAll
        fun connectDatabase() {
            DatabaseFactory.connect(testDatabaseConfig("repo-test"))
        }
    }

    @Test
    fun `round-trip a conversation with messages`() = runBlocking {
        val id = repository.createConversation("Первое сообщение")
        repository.addMessage(id, "user", "Первое сообщение")
        repository.addMessage(id, "assistant", "Ответ")

        val conversation = repository.findConversation(id)!!
        assertEquals("Первое сообщение", conversation.title)
        assertEquals(2, conversation.messages.size)
        assertEquals("user", conversation.messages[0].role)
        assertEquals("Первое сообщение", conversation.messages[0].content)
        assertEquals("assistant", conversation.messages[1].role)
        assertEquals("Ответ", conversation.messages[1].content)
    }

    @Test
    fun `history is returned oldest first`() = runBlocking {
        val id = repository.createConversation("hi")
        repeat(3) { repository.addMessage(id, "user", "message-$it") }

        val history = repository.getHistory(id)
        assertEquals(listOf("message-0", "message-1", "message-2"), history.map { it.content })
    }

    @Test
    fun `list returns summaries ordered by last update`() = runBlocking {
        val first = repository.createConversation("A")
        repository.addMessage(first, "user", "A")
        delay(15) // ensure a distinct updated_at (H2 stores microseconds)

        val second = repository.createConversation("B")
        repository.addMessage(second, "user", "B1")
        repository.addMessage(second, "assistant", "B2")

        // The H2 database is shared with other tests in this class, so only
        // the relative order of our own conversations is asserted.
        val summaries = repository.listConversations()
        val ids = summaries.map { it.id }
        assertTrue(ids.indexOf(second) < ids.indexOf(first))

        val byId = summaries.associateBy { it.id }
        assertEquals(1, byId[first]!!.messageCount)
        assertEquals(2, byId[second]!!.messageCount)
    }

    @Test
    fun `delete removes the conversation and its messages`() = runBlocking {
        val id = repository.createConversation("x")
        repository.addMessage(id, "user", "x")

        assertTrue(repository.deleteConversation(id))
        assertNull(repository.findConversation(id))
        assertTrue(repository.getHistory(id).isEmpty())
        assertTrue(!repository.deleteConversation(id))
    }

    @Test
    fun `find missing conversation returns null`() = runBlocking {
        assertNull(repository.findConversation(java.util.UUID.randomUUID()))
    }
}
