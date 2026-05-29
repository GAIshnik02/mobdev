package io.github.mobdev.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.github.mobdev.data.api.ChatApi
import io.github.mobdev.data.api.ChatMessage
import io.github.mobdev.data.api.ImagePayload
import io.github.mobdev.data.api.MessageData
import io.github.mobdev.data.api.TextPayload
import io.github.mobdev.data.database.AppDatabase
import io.github.mobdev.data.database.entities.ChannelEntity
import io.github.mobdev.data.database.entities.MessageEntity
import io.github.mobdev.data.network.NetworkMonitor
import io.github.mobdev.data.network.TokenHolder
import io.github.mobdev.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ChatRepository(
    private val context: Context,
    private val api: ChatApi,
    private val sessionStore: SessionStore,
    private val tokenHolder: TokenHolder
) {
    private val gson = Gson()


    private val database = AppDatabase.getInstance(context)

    private val networkMonitor = NetworkMonitor(context)

    private val _isNetworkAvailable = MutableStateFlow(networkMonitor.isNetworkAvailable())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    init {
        // Следим за сетью
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            networkMonitor.observeNetworkStatus().collect { available ->
                _isNetworkAvailable.value = available
            }
        }
    }


    suspend fun bootstrapSession(): SessionState {
        val session = sessionStore.currentSession()
        tokenHolder.token = session.token
        return SessionState(
            name = session.name,
            password = session.password,
            hasToken = !session.token.isNullOrBlank()
        )
    }

    suspend fun login(name: String, password: String) = withContext(Dispatchers.IO) {
        try {
            val token = api.login(name, password)
            if (token.isBlank()) throw ApiException(ApiError.Unknown)
            sessionStore.saveCredentials(name, password)
            sessionStore.saveToken(token)
            tokenHolder.token = token
        } catch (e: Exception) {
            val message = e.message ?: ""
            when {
                message.contains("401") || message.contains("Invalid credentials") ->
                    throw ApiException(ApiError.InvalidCredentials, e)
                message.contains("Unauthorized") ->
                    throw ApiException(ApiError.Unauthorized, e)
                message.startsWith("HTTP") -> {
                    val code = message.substringAfter("HTTP ").toIntOrNull() ?: 0
                    throw ApiException(ApiError.Http(code), e)
                }
                e is IOException -> throw ApiException(ApiError.Network, e)
                else -> throw ApiException(ApiError.Unknown, e)
            }
        }
    }

    suspend fun register(name: String): String = withContext(Dispatchers.IO) {
        try {
            println("register: attempting to register name=$name")
            // api.register уже возвращает пароль (ты в ChatApi вытащил его из regex)
            val password = api.register(name)
            println("register: password=$password")
            if (password.isNullOrBlank()) {
                throw ApiException(ApiError.Unknown)
            }
            password
        } catch (e: Exception) {
            println("register: exception=${e.message}")
            val message = e.message ?: ""
            when {
                message.contains("409") -> throw ApiException(ApiError.Http(409), e)
                message.contains("401") -> throw ApiException(ApiError.Unauthorized, e)
                message.startsWith("HTTP") -> {
                    val code = message.substringAfter("HTTP ").toIntOrNull() ?: 0
                    throw ApiException(ApiError.Http(code), e)
                }
                e is IOException -> throw ApiException(ApiError.Network, e)
                else -> throw ApiException(ApiError.Unknown, e)
            }
        }
    }

    suspend fun loadChannels(): List<String> = withContext(Dispatchers.IO) {
        if (_isNetworkAvailable.value) {
            try {
                val channels = api.channels().sorted()
                database.channelDao().insertAll(channels.map { ChannelEntity(it) })
                return@withContext channels
            } catch (e: Exception) {
                return@withContext getCachedChannels()
            }
        } else {
            return@withContext getCachedChannels()
        }
    }

    private suspend fun getCachedChannels(): List<String> {
        return database.channelDao().getAll().map { it.name }
    }

    suspend fun loadMessages(
        chat: String,
        limit: Int = 20,
        lastKnownId: Long = 0L,
        reverse: Boolean = false
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (_isNetworkAvailable.value) {
            try {
                val messages = api.channelMessages(
                    channel = chat,
                    limit = limit,
                    lastKnownId = lastKnownId,
                    reverse = reverse
                ).sortedBy { it.id?.toLongOrNull() ?: Long.MAX_VALUE }

                // Сохраняем в БД (без дубликатов)
                messages.forEach { message ->
                    val existing = database.messageDao().getMessagesForChannel(chat)
                    if (existing.none { it.id == message.id }) {
                        database.messageDao().insert(MessageEntity.fromChatMessage(message))
                    }
                }
                return@withContext messages
            } catch (e: Exception) {
                return@withContext getCachedMessages(chat)
            }
        } else {
            return@withContext getCachedMessages(chat)
        }
    }

    private suspend fun getCachedMessages(chat: String): List<ChatMessage> {
        return database.messageDao()
            .getMessagesForChannel(chat)
            .map { it.toChatMessage() }
            .distinctBy { it.id }
    }

    suspend fun sendTextMessage(from: String, to: String, text: String) = withContext(Dispatchers.IO) {
        // Проверка сети
        if (!_isNetworkAvailable.value) {
            throw ApiException(ApiError.Network, IOException("No network connection"))
        }

        val message = ChatMessage(
            from = from,
            to = to,
            data = MessageData(text = TextPayload(text = text))
        )

        try {
            api.postMessage(message)
            // Сохраняем отправленное сообщение локально
            database.messageDao().insert(MessageEntity.fromChatMessage(message))
        } catch (e: Exception) {
            throw handleException(e)
        }
    }

    suspend fun sendImageMessage(
        from: String,
        to: String,
        imageBytes: ByteArray,
        fileName: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        // Проверка сети
        if (!_isNetworkAvailable.value) {
            throw ApiException(ApiError.Network, IOException("No network connection"))
        }

        val normalizedMimeType = mimeType.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val message = ChatMessage(
            from = from,
            to = to,
            data = MessageData(image = ImagePayload())
        )
        val messageBody = gson.toJson(message).toRequestBody("application/json".toMediaTypeOrNull())
        val imageBody = imageBytes.toRequestBody(normalizedMimeType.toMediaTypeOrNull())
        val imagePart = MultipartBody.Part.createFormData("picture", fileName, imageBody)

        try {
            api.postMultipartMessage(messageBody, imagePart)
            // Сохраняем отправленное сообщение локально
            database.messageDao().insert(MessageEntity.fromChatMessage(message))
        } catch (e: Exception) {
            throw handleException(e)
        }
    }

    suspend fun addNewMessageFromWebSocket(message: ChatMessage) {
        val existing = database.messageDao().getMessagesForChannel(message.to)
        if (existing.none { it.id == message.id }) {
            database.messageDao().insert(MessageEntity.fromChatMessage(message))
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            api.logout()
        } catch (e: Exception) {
            Log.d("ChatRepository", "Logout error ignored: ${e.message}")
        } finally {
            sessionStore.clearToken()
            tokenHolder.token = null
        }
    }

    suspend fun clearSessionToken() = withContext(Dispatchers.IO) {
        sessionStore.clearToken()
        tokenHolder.token = null
    }

    fun currentToken(): String? = tokenHolder.token

    private fun handleException(e: Exception): ApiException {
        val message = e.message ?: ""
        return when {
            message.contains("401") || message.contains("Unauthorized") ->
                ApiException(ApiError.Unauthorized, e)
            message.startsWith("HTTP") -> {
                val code = message.substringAfter("HTTP ").toIntOrNull() ?: 0
                ApiException(ApiError.Http(code), e)
            }
            e is IOException -> ApiException(ApiError.Network, e)
            else -> ApiException(ApiError.Unknown, e)
        }
    }
}

data class SessionState(
    val name: String?,
    val password: String?,
    val hasToken: Boolean
)

class ApiException(val error: ApiError, cause: Throwable? = null) : Exception(cause)

sealed interface ApiError {
    data object InvalidCredentials : ApiError
    data object Unauthorized : ApiError
    data object Network : ApiError
    data class Http(val code: Int) : ApiError
    data object Unknown : ApiError
}