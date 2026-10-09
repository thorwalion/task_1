package com.example.deepseek.routes

import com.example.deepseek.config.AppConfig
import com.example.deepseek.db.ConversationRepository
import com.example.deepseek.db.DatabaseFactory
import com.example.deepseek.model.ChatMessage
import com.example.deepseek.model.ChatRequest
import com.example.deepseek.model.ChatResponse
import com.example.deepseek.model.ErrorBody
import com.example.deepseek.model.ErrorResponse
import com.example.deepseek.model.UsageDto
import com.example.deepseek.provider.ChatProvider
import com.example.deepseek.provider.DeepSeekException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

private const val MAX_MESSAGE_LENGTH = 100_000

fun Application.configureRouting(
    config: AppConfig,
    repository: ConversationRepository,
    provider: ChatProvider,
) {
    routing {
        get("/health") {
            if (DatabaseFactory.ping()) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(ErrorBody("db_unavailable", "Database is not reachable")),
                )
            }
        }

        route("/api/v1") {
            post("/chat") {
                val request = call.receive<ChatRequest>()
                val message = request.message.trim()
                if (message.isEmpty()) throw BadRequestException("Message must not be empty")
                if (message.length > MAX_MESSAGE_LENGTH) {
                    throw BadRequestException("Message is too long (max $MAX_MESSAGE_LENGTH characters)")
                }

                val conversationId = request.conversationId ?: repository.createConversation(message)
                if (request.conversationId != null && repository.findConversation(conversationId) == null) {
                    throw NotFoundException("Conversation $conversationId not found")
                }

                // Persist the user message, then send the full history to the provider.
                repository.addMessage(conversationId, role = "user", content = message)
                val history = repository.getHistory(conversationId)

                val systemPrompt = config.deepseek.systemPrompt
                val providerHistory = if (systemPrompt != null) {
                    listOf(ChatMessage(role = "system", content = systemPrompt)) + history
                } else {
                    history
                }

                val completion = try {
                    provider.complete(
                        history = providerHistory,
                        model = request.model ?: config.deepseek.model,
                        reasoningEffort = request.reasoningEffort ?: config.deepseek.reasoningEffort,
                    )
                } catch (e: DeepSeekException) {
                    throw e
                } catch (e: Exception) {
                    throw DeepSeekException(502, "Chat provider failed: ${e.message}")
                }

                repository.addMessage(conversationId, role = "assistant", content = completion.content)

                val conversation = repository.findConversation(conversationId)
                    ?: throw IllegalStateException("Conversation $conversationId disappeared")

                call.respond(
                    HttpStatusCode.OK,
                    ChatResponse(
                        conversationId = conversationId,
                        reply = conversation.messages.last(),
                        usage = completion.usage?.let {
                            UsageDto(it.promptTokens, it.completionTokens, it.totalTokens)
                        },
                    ),
                )
            }

            get("/conversations") {
                call.respond(repository.listConversations())
            }

            get("/conversations/{id}") {
                val id = call.uuidParam("id")
                call.respond(repository.findConversation(id) ?: throw NotFoundException("Conversation $id not found"))
            }

            delete("/conversations/{id}") {
                val id = call.uuidParam("id")
                if (repository.deleteConversation(id)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    throw NotFoundException("Conversation $id not found")
                }
            }
        }
    }
}

private fun ApplicationCall.uuidParam(name: String): UUID {
    val raw = parameters[name]
    val id = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw BadRequestException("Path parameter '$name' must be a valid UUID")
    return id
}
