package io.github.mobdev.data.session

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "session")

class SessionStore(private val context: Context) {
    val sessionFlow: Flow<SessionData> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toSessionData() }

    suspend fun currentSession(): SessionData = sessionFlow.first()

    suspend fun saveCredentials(name: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NAME] = name
            prefs[Keys.PASSWORD] = password
        }
    }

    suspend fun saveToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(Keys.TOKEN)
            } else {
                prefs[Keys.TOKEN] = token
            }
        }
    }

    suspend fun clearToken() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.TOKEN)
        }
    }

    private fun Preferences.toSessionData(): SessionData = SessionData(
        name = this[Keys.NAME],
        password = this[Keys.PASSWORD],
        token = this[Keys.TOKEN]
    )

    private object Keys {
        val NAME = stringPreferencesKey("name")
        val PASSWORD = stringPreferencesKey("password")
        val TOKEN = stringPreferencesKey("token")
    }
}

data class SessionData(
    val name: String?,
    val password: String?,
    val token: String?
)