package com.example.deepseek

import com.example.deepseek.config.AppConfig
import com.example.deepseek.db.ConversationRepository
import com.example.deepseek.db.DatabaseFactory
import com.example.deepseek.model.ErrorBody
import com.example.deepseek.model.ErrorResponse
import com.example.deepseek.provider.ChatProvider
import com.example.deepseek.provider.DeepSeekChatProvider
import com.example.deepseek.provider.DeepSeekException
import com.example.deepseek.routes.configureRouting
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    EngineMain.main(args)
}

/** Production wiring: reads application.conf, connects the DB, builds real dependencies. */
fun Application.module() {
    val config = AppConfig.from(environment.config)
    DatabaseFactory.connect(config.database)
    configureApp(config, ConversationRepository(), DeepSeekChatProvider(config.deepseek))
}

/**
 * Pluggable application setup; tests reuse it with an H2 database and a fake provider.
 * Note: the caller is responsible for calling [DatabaseFactory.connect] beforehand.
 */
fun Application.configureApp(
    config: AppConfig,
    repository: ConversationRepository,
    provider: ChatProvider,
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    install(CallLogging)

    install(StatusPages) {
        exception<DeepSeekException> { call, cause ->
            call.respond(
                HttpStatusCode(cause.statusCode, cause.message ?: ""),
                ErrorResponse(ErrorBody("deepseek_error", cause.message ?: "DeepSeek API error")),
            )
        }
        exception<NotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(ErrorBody("not_found", cause.message ?: "Not found")),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorBody("bad_request", cause.message ?: "Bad request")),
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorBody("invalid_json", "Request body is not valid JSON")),
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody("internal_error", "Internal server error")),
            )
        }
    }

    configureRouting(config, repository, provider)
}
