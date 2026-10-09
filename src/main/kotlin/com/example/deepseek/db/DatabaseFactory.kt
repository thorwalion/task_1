package com.example.deepseek.db

import com.example.deepseek.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object DatabaseFactory {

    lateinit var database: Database
        private set

    /**
     * Creates the connection pool, applies Flyway migrations and registers the
     * database with Exposed. Throws on first connection failure.
     */
    fun connect(config: DatabaseConfig): DataSource {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                poolName = "deepseek-server-pool"
                // Fail fast instead of retrying forever if the DB is down.
                initializationFailTimeout = 10_000
            },
        )

        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        database = Database.connect(dataSource)
        return dataSource
    }

    /** Lightweight connectivity check used by the health endpoint. */
    fun ping(): Boolean = runCatching {
        check(DatabaseFactory::database.isInitialized) { "Database is not connected" }
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) {
            exec("SELECT 1")
        }
        true
    }.getOrDefault(false)
}
