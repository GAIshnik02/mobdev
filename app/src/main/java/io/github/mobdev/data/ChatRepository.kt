package io.github.mobdev.data

import android.util.Log
import com.google.gson.Gson
import io.github.mobdev.data.api.ChatApi
import io.github.mobdev.data.api.ChatMessage
import io.github.mobdev.data.api.ImagePayload
import io.github.mobdev.data.api.MessageData
import io.github.mobdev.data.api.TextPayload
import io.github.mobdev.data.network.TokenHolder
import io.github.mobdev.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ChatRepository(
    private val api: ChatApi,
    private val sessionStore: SessionStore,
    private val tokenHolder: TokenHolder
) {
    private val gson = Gson()
    private val passwordRegex = Regex("password:\\s*'([^']+)'")

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
            val rawResponse = api.register(name)
            val password = passwordRegex.find(rawResponse)?.groupValues?.getOrNull(1)
            if (password.isNullOrBlank()) throw ApiException(ApiError.Unknown)
            password
        } catch (e: Exception) {
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
        try {
            api.channels().sorted()
        } catch (e: Exception) {
            throw handleException(e)
        }
    }

    suspend fun loadMessages(
        chat: String,
        limit: Int = 20,
        lastKnownId: Long = 0L,
        reverse: Boolean = false
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            api.channelMessages(
                channel = chat,
                limit = limit,
                lastKnownId = lastKnownId,
                reverse = reverse
            ).sortedBy { it.id?.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: Exception) {
            throw handleException(e)
        }
    }

    suspend fun sendTextMessage(from: String, to: String, text: String) = withContext(Dispatchers.IO) {
        val message = ChatMessage(
            from = from,
            to = to,
            data = MessageData(text = TextPayload(text = text))
        )
        try {
            api.postMessage(message)
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
        } catch (e: Exception) {
            throw handleException(e)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            api.logout()
        } catch (e: Exception) {
            // ignore error on logout
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