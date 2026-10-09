package com.example.deepseek

import com.example.deepseek.config.AppConfig
import com.example.deepseek.config.DatabaseConfig
import com.example.deepseek.config.DeepSeekConfig

/** In-memory H2 in PostgreSQL compatibility mode, one database per test class. */
internal fun testDatabaseConfig(name: String) = DatabaseConfig(
    url = "jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    user = "sa",
    password = "",
    maxPoolSize = 5,
)

/** Config for [Application.configureApp] tests; the real DB is connected in @BeforeAll. */
internal fun testAppConfig() = AppConfig(
    deepseek = DeepSeekConfig(
        baseUrl = "http://localhost:9",
        apiKey = "test-key",
        model = "deepseek-flash",
        reasoningEffort = "high",
        thinkingType = "disabled",
        systemPrompt = null,
        timeoutSeconds = 10,
    ),
    database = testDatabaseConfig("unused"),
)
