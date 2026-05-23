package io.github.mobdev.data.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.coroutines.resume

private const val BASE_URL = "https://faerytea.name/"

class ChatApi(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val tokenProvider: () -> String?
) {

    suspend fun register(name: String): String = suspendCancellableCoroutine { continuation ->
        val body = "name=$name".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("${BASE_URL}addusr")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val passwordRegex = Regex("password:\\s*'([^']+)'")
                val password = passwordRegex.find(responseBody)?.groupValues?.getOrNull(1)
                if (!password.isNullOrBlank()) {
                    continuation.resume(password)
                } else {
                    continuation.resumeWith(Result.failure(Exception("Failed to extract password")))
                }
            } else {
                continuation.resumeWith(Result.failure(Exception("HTTP ${response.code}")))
            }
        }
    }

    suspend fun login(name: String, password: String): String =
        suspendCancellableCoroutine { continuation ->
            val json = gson.toJson(LoginRequest(name, password))
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BASE_URL}login")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val token = response.body?.string()?.trim() ?: ""
                    continuation.resume(token)
                } else {
                    continuation.resumeWith(Result.failure(Exception("HTTP ${response.code}")))
                }
            }
        }

    suspend fun channels(): List<String> = suspendCancellableCoroutine { continuation ->
        val token = tokenProvider()
        val requestBuilder = Request.Builder()
            .url("${BASE_URL}channels")
            .get()

        token?.let { requestBuilder.addHeader("X-Auth-Token", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                val type = object : TypeToken<List<String>>() {}.type
                continuation.resume(gson.fromJson(body, type))
            } else {
                continuation.resumeWith(Result.failure(Exception("HTTP ${response.code}")))
            }
        }
    }

    suspend fun channelMessages(
        channel: String,
        limit: Int = 20,
        lastKnownId: Long = 0L,
        reverse: Boolean = false
    ): List<ChatMessage> = suspendCancellableCoroutine { continuation ->
        val encodedChannel = URLEncoder.encode(channel, "UTF-8")
        val url =
            "${BASE_URL}channel/$encodedChannel?limit=$limit&lastKnownId=$lastKnownId&reverse=$reverse"

        val token = tokenProvider()
        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        token?.let { requestBuilder.addHeader("X-Auth-Token", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                continuation.resume(gson.fromJson(body, type))
            } else {
                continuation.resumeWith(Result.failure(Exception("HTTP ${response.code}")))
            }
        }
    }

    suspend fun postMessage(message: ChatMessage) = suspendCancellableCoroutine { continuation ->
        val json = gson.toJson(message)
        val body = json.toRequestBody("application/json".toMediaType())

        val token = tokenProvider()
        val requestBuilder = Request.Builder()
            .url("${BASE_URL}messages")
            .post(body)

        token?.let { requestBuilder.addHeader("X-Auth-Token", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWith(Result.failure(Exception("HTTP ${response.code}")))
            }
        }
    }

    suspend fun postMultipartMessage(
        messageBody: okhttp3.RequestBody,
        picture: MultipartBody.Part
    ) = suspendCancellableCoroutine { continuation ->
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("msg", null, messageBody)
            .addPart(picture)
            .build()

        val token = tokenProvider()
        val requestBuilder = Request.Builder()
            .url("${BASE_URL}messages")
            .post(multipartBody)

        token?.let { requestBuilder.addHeader("X-Auth-Token", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWith(Result.failure(Exception("HTTP ${response.code}")))
            }
        }
    }

    suspend fun logout() = suspendCancellableCoroutine { continuation ->
        val token = tokenProvider()
        val requestBuilder = Request.Builder()
            .url("${BASE_URL}logout")
            .post("".toRequestBody())

        token?.let { requestBuilder.addHeader("X-Auth-Token", it) }

        client.newCall(requestBuilder.build()).execute().use {
            continuation.resume(Unit)
        }
    }
}
data class LoginRequest(
    val name: String,
    val pwd: String
)

data class ChatMessage(
    val id: String? = null,
    val from: String,
    val to: String = "1@channel",
    val data: MessageData,
    val time: String? = null
)

data class MessageData(
    @SerializedName("Text") val text: TextPayload? = null,
    @SerializedName("Image") val image: ImagePayload? = null
)

data class TextPayload(
    val text: String
)

data class ImagePayload(
    val link: String? = null
)