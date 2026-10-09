package com.example.deepseek.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object Conversations : Table("conversations") {
    val id = javaUUID("id")
    val title = varchar("title", 200).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Messages : Table("messages") {
    val id = long("id").autoIncrement()
    val conversationId = javaUUID("conversation_id")
        .references(Conversations.id, ReferenceOption.CASCADE)
    val role = varchar("role", 20)
    val content = text("content")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
