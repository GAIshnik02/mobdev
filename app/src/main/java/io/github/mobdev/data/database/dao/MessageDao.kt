package io.github.mobdev.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.mobdev.data.database.entities.MessageEntity

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE `to` = :channel ORDER BY timestamp DESC")
    suspend fun getMessagesForChannel(channel: String): List<MessageEntity>
}