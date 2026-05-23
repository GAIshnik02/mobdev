package io.github.mobdev.data.network

import com.google.gson.Gson
import io.github.mobdev.data.api.ChatApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class TokenHolder {
    @Volatile
    var token: String? = null
}

object NetworkModule {
    fun createApi(tokenHolder: TokenHolder): ChatApi {
        val authInterceptor = Interceptor { chain ->
            val token = tokenHolder.token
            val request = chain.request()
            val newRequest = if (!token.isNullOrBlank()) {
                request.newBuilder()
                    .addHeader("X-Auth-Token", token)
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()

        return ChatApi(client, Gson()) { tokenHolder.token }
    }
}