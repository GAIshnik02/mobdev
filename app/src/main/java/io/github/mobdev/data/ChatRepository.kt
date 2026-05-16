package io.github.mobdev.data

import com.google.gson.Gson
import io.github.mobdev.data.api.ChatApi
import io.github.mobdev.data.api.ChatMessage
import io.github.mobdev.data.api.ImagePayload
import io.github.mobdev.data.api.LoginRequest
import io.github.mobdev.data.api.MessageData
import io.github.mobdev.data.api.TextPayload
import io.github.mobdev.data.network.TokenHolder
import io.github.mobdev.data.session.SessionStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

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
        val response = api.login(LoginRequest(name = name, pwd = password))
        if (response.isSuccessful) {
            val token = response.body()?.string()?.trim().orEmpty()
            if (token.isBlank()) throw ApiException(ApiError.Unknown)
            sessionStore.saveCredentials(name, password)
            sessionStore.saveToken(token)
            tokenHolder.token = token
            return@withContext
        }
        if (response.code() == 401) {
            throw ApiException(ApiError.InvalidCredentials)
        }
        throw ApiException(ApiError.Http(response.code()))
    }

    suspend fun register(name: String): String = withContext(Dispatchers.IO) {
        val response = api.register(name)
        if (!response.isSuccessful) {
            if (response.code() == 401) throw ApiException(ApiError.Unauthorized)
            throw ApiException(ApiError.Http(response.code()))
        }
        val raw = response.body()?.string().orEmpty()
        val password = passwordRegex.find(raw)?.groupValues?.getOrNull(1)
        if (password.isNullOrBlank()) throw ApiException(ApiError.Unknown)
        password
    }

    suspend fun loadChannels(): List<String> = withContext(Dispatchers.IO) {
        runApi { api.channels() }.sorted()
    }

    suspend fun loadMessages(
        chat: String,
        limit: Int = 20,
        lastKnownId: Long = 0L,
        reverse: Boolean = false
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        runApi {
            api.channelMessages(
                channel = chat,
                limit = limit,
                lastKnownId = lastKnownId,
                reverse = reverse
            )
        }.sortedBy { it.id?.toLongOrNull() ?: Long.MAX_VALUE }
    }

    suspend fun sendTextMessage(from: String, to: String, text: String) = withContext(Dispatchers.IO) {
        val message = ChatMessage(
            from = from,
            to = to,
            data = MessageData(text = TextPayload(text = text))
        )
        runApi { api.postMessage(message) }
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
        runApi { api.postMultipartMessage(messageBody, imagePart) }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.logout() }
        sessionStore.clearToken()
        tokenHolder.token = null
    }

    suspend fun clearSessionToken() = withContext(Dispatchers.IO) {
        sessionStore.clearToken()
        tokenHolder.token = null
    }

    fun currentToken(): String? = tokenHolder.token

    private suspend fun <T> runApi(request: suspend () -> retrofit2.Response<T>): T {
        try {
            val response = request()
            if (response.isSuccessful) {
                return response.body() ?: throw ApiException(ApiError.Unknown)
            }
            if (response.code() == 401) throw ApiException(ApiError.Unauthorized)
            throw ApiException(ApiError.Http(response.code()))
        } catch (error: IOException) {
            throw ApiException(ApiError.Network, error)
        } catch (error: HttpException) {
            if (error.code() == 401) throw ApiException(ApiError.Unauthorized, error)
            throw ApiException(ApiError.Http(error.code()), error)
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