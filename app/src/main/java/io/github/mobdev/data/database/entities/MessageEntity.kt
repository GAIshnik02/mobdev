package io.github.mobdev.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mobdev.data.api.ChatMessage
import io.github.mobdev.data.api.MessageData
import io.github.mobdev.data.api.TextPayload
import io.github.mobdev.data.api.ImagePayload

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val from: String,
    val to: String,
    val text: String?,
    val imageLink: String?,
    val time: String?,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        from = from,
        to = to,
        data = MessageData(
            text = text?.let { TextPayload(it) },
            image = imageLink?.let { ImagePayload(it) }
        ),
        time = time
    )

    companion object {
        fun fromChatMessage(message: ChatMessage): MessageEntity = MessageEntity(
            id = message.id ?: "${message.from}-${System.currentTimeMillis()}",
            from = message.from,
            to = message.to,
            text = message.data.text?.text,
            imageLink = message.data.image?.link,
            time = message.time
        )
    }
}