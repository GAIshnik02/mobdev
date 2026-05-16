package io.github.mobdev.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Part
import retrofit2.http.Query

interface ChatApi {
    @FormUrlEncoded
    @POST("addusr")
    suspend fun register(@Field("name") name: String): Response<ResponseBody>

    @POST("login")
    suspend fun login(@Body request: LoginRequest): Response<ResponseBody>

    @GET("channels")
    suspend fun channels(): Response<List<String>>

    @GET("channel/{channel}")
    suspend fun channelMessages(
        @Path("channel", encoded = true) channel: String,
        @Query("limit") limit: Int = 20,
        @Query("lastKnownId") lastKnownId: Long = 0L,
        @Query("reverse") reverse: Boolean = false
    ): Response<List<ChatMessage>>

    @POST("messages")
    suspend fun postMessage(@Body message: ChatMessage): Response<ResponseBody>

    @Multipart
    @POST("messages")
    suspend fun postMultipartMessage(
        @Part("msg") message: RequestBody,
        @Part picture: MultipartBody.Part
    ): Response<ResponseBody>

    @POST("logout")
    suspend fun logout(): Response<Unit>
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