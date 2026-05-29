package io.github.mobdev.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobdev.data.ApiError
import io.github.mobdev.data.ApiException
import io.github.mobdev.data.ChatRepository
import io.github.mobdev.data.SessionState
import io.github.mobdev.data.api.ChatMessage
import io.github.mobdev.data.network.ChatWebSocketClient
import io.github.mobdev.data.network.NetworkModule
import io.github.mobdev.data.network.TokenHolder
import io.github.mobdev.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 20

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenHolder = TokenHolder()
    private val sessionStore = SessionStore(application.applicationContext)
    private val repository = ChatRepository(
        context = application.applicationContext,
        api = NetworkModule.createApi(tokenHolder),
        sessionStore = sessionStore,
        tokenHolder = tokenHolder
    )
    private val webSocketClient = ChatWebSocketClient()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        bootstrap()
        observeNetworkStatus()
    }

    private fun observeNetworkStatus() {
        viewModelScope.launch {
            repository.isNetworkAvailable.collect { isAvailable ->
                val previousOffline = _uiState.value.isOffline
                val isAuthorized = _uiState.value.isAuthorized
                val username = _uiState.value.username

                _uiState.update { it.copy(isOffline = !isAvailable) }

                // Сеть появилась
                if (isAvailable && isAuthorized) {
                    println("observeNetworkStatus: Network available, reconnecting WebSocket")
                    // Переподключаем WebSocket
                    username?.let {
                        connectWebSocket(it)
                    }
                    // Обновляем каналы и сообщения
                    refreshChannels()
                    _uiState.value.selectedChat?.let { chat ->
                        loadMessages(chat, reset = true)
                    }
                }

                // Сеть пропала
                if (!isAvailable && !previousOffline) {
                    println("observeNetworkStatus: Network lost, disconnecting WebSocket")
                    webSocketClient.disconnect()
                }
            }
        }
    }

    fun onLoginChanged(value: String) {
        _uiState.update { it.copy(loginInput = value) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(passwordInput = value) }
    }

    fun onRegistrationNameChanged(value: String) {
        _uiState.update { it.copy(registrationNameInput = value) }
    }

    fun showRegistration() {
        _uiState.update { it.copy(authMode = AuthMode.Registration, registrationError = null) }
    }

    fun showLogin() {
        _uiState.update { it.copy(authMode = AuthMode.Login, registrationError = null) }
    }

    fun login() {
        val name = uiState.value.loginInput.trim()
        val password = uiState.value.passwordInput
        if (name.isBlank() || password.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loginError = null) }
            runCatching { repository.login(name, password) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isAuthorized = true,
                            isLoading = false,
                            username = name
                        )
                    }
                    connectWebSocket(name)
                    refreshChannels()
                }
                .onFailure { handleLoginError(it) }
        }
    }

    fun register() {
        val name = uiState.value.registrationNameInput.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, registrationError = null) }
            runCatching { repository.register(name) }
                .onSuccess { password ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            authMode = AuthMode.Login,
                            loginInput = name,
                            passwordInput = password,
                            registeredPassword = password
                        )
                    }
                }
                .onFailure { handleRegistrationError(it) }
        }
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChannelsLoading = true, errorMessage = null) }
            runCatching { repository.loadChannels() }
                .onSuccess { channels ->
                    _uiState.update {
                        it.copy(
                            channels = channels,
                            isChannelsLoading = false
                        )
                    }
                }
                .onFailure { handleRepositoryError(it) { state -> state.copy(isChannelsLoading = false) } }
        }
    }

    fun openChat(chat: String) {
        _uiState.update {
            it.copy(
                selectedChat = chat,
                errorMessage = null
            )
        }
        loadMessages(chat, reset = true)
    }

    fun closeChat() {
        _uiState.update { it.copy(selectedChat = null) }
    }

    fun loadMore() {
        val state = uiState.value
        val chat = state.selectedChat ?: return
        if (!state.canLoadMoreByChat.getOrDefault(chat, true)) return
        val oldestId = state.messagesByChat[chat]
            .orEmpty()
            .firstOrNull()
            ?.id
            ?.toLongOrNull()
            ?: return
        loadMessages(chat, reset = false, lastKnownId = oldestId, reverse = true)
    }

    fun sendMessage() {
        println("sendMessage: START")

        val state = uiState.value
        val chat = state.selectedChat
        println("sendMessage: selectedChat=$chat")

        if (chat == null) {
            println("sendMessage: selectedChat is null, RETURN")
            return
        }

        val text = state.outgoingText.trim()
        val username = state.username
        println("sendMessage: text=$text, username=$username")

        if (text.isBlank()) {
            println("sendMessage: text is blank, RETURN")
            return
        }

        if (username.isNullOrBlank()) {
            println("sendMessage: username is null or blank, RETURN")
            return
        }

        if (state.isOffline) {
            println("sendMessage: offline mode, RETURN")
            _uiState.update { it.copy(errorMessage = UiError.Network) }
            return
        }

        println("sendMessage: ABOUT TO SEND to chat=$chat")

        val tempId = "temp-${System.currentTimeMillis()}"
        val tempMessage = ChatMessage(
            id = tempId,
            from = username,
            to = chat,
            data = io.github.mobdev.data.api.MessageData(
                text = io.github.mobdev.data.api.TextPayload(text)
            ),
            time = System.currentTimeMillis().toString()
        )

        // Добавляем временное сообщение в UI
        _uiState.update { currentState ->
            val currentMessages = currentState.messagesByChat[chat].orEmpty()
            println("sendMessage: adding temp message, currentSize=${currentMessages.size}")
            currentState.copy(
                messagesByChat = currentState.messagesByChat + (chat to (currentMessages + tempMessage)),
                outgoingText = "",
                isSending = true
            )
        }

        viewModelScope.launch {
            println("sendMessage: calling repository.sendTextMessage")
            runCatching { repository.sendTextMessage(from = username, to = chat, text = text) }
                .onSuccess {
                    println("sendMessage: SUCCESS")
                    _uiState.update { it.copy(isSending = false) }

                    // Вместо полной перезагрузки - удаляем только временное сообщение
                    // (серверные сообщения уже добавит WebSocket или следующий loadMessages)
                    val currentMessages = _uiState.value.messagesByChat[chat].orEmpty()
                    val filteredMessages = currentMessages.filter { it.id != tempId }
                    _uiState.update {
                        it.copy(
                            messagesByChat = it.messagesByChat + (chat to filteredMessages)
                        )
                    }
                    // Не делаем loadMessages, чтобы не создавать дубли
                    // loadMessages(chat = chat, reset = true)
                }
                .onFailure { error ->
                    println("sendMessage: FAILURE: ${error.message}")
                    _uiState.update { it.copy(isSending = false) }
                    // При ошибке - удаляем временное сообщение
                    val currentMessages = _uiState.value.messagesByChat[chat].orEmpty()
                    val filteredMessages = currentMessages.filter { it.id != tempId }
                    _uiState.update {
                        it.copy(
                            messagesByChat = it.messagesByChat + (chat to filteredMessages)
                        )
                    }
                    handleRepositoryError(error) { stateCopy -> stateCopy }
                }
        }
    }


    fun sendImage(uri: Uri) {
        val state = uiState.value
        val chat = state.selectedChat ?: return
        val username = state.username ?: return

        // Проверка офлайн режима
        if (state.isOffline) {
            _uiState.update { it.copy(errorMessage = UiError.Network) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            runCatching {
                val payload = readUriPayload(uri)
                repository.sendImageMessage(
                    from = username,
                    to = chat,
                    imageBytes = payload.bytes,
                    fileName = payload.fileName,
                    mimeType = payload.mimeType
                )
            }
                .onSuccess {
                    _uiState.update { it.copy(isSending = false) }
                    loadMessages(chat = chat, reset = true)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSending = false) }
                    handleImageSendError(error) { stateCopy -> stateCopy }
                }
        }
    }

    fun onOutgoingTextChanged(value: String) {
        _uiState.update { it.copy(outgoingText = value) }
    }

    fun openImage(path: String) {
        _uiState.update { it.copy(fullScreenImagePath = path) }
    }

    fun closeImage() {
        _uiState.update { it.copy(fullScreenImagePath = null) }
    }

    fun handleBack(isLandscape: Boolean) {
        val state = uiState.value
        when {
            state.fullScreenImagePath != null -> closeImage()
            isLandscape && state.selectedChat != null -> closeChat()
            !isLandscape && state.selectedChat != null -> closeChat()
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            webSocketClient.disconnect()
            _uiState.update {
                it.copy(
                    isAuthorized = false,
                    selectedChat = null,
                    messagesByChat = emptyMap(),
                    channels = emptyList(),
                    fullScreenImagePath = null
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearLoginError() {
        _uiState.update { it.copy(loginError = null) }
    }

    fun clearRegisteredPassword() {
        _uiState.update { it.copy(registeredPassword = null) }
    }

    fun showCreateChannelDialog() {
        _uiState.update { it.copy(isCreateChannelDialogVisible = true) }
    }

    fun hideCreateChannelDialog() {
        _uiState.update {
            it.copy(
                isCreateChannelDialogVisible = false,
                createChannelNameInput = "",
                createChannelFirstMessageInput = ""
            )
        }
    }

    fun onCreateChannelNameChanged(value: String) {
        _uiState.update { it.copy(createChannelNameInput = value) }
    }

    fun onCreateChannelFirstMessageChanged(value: String) {
        _uiState.update { it.copy(createChannelFirstMessageInput = value) }
    }

    fun createChannel() {
        val state = uiState.value
        val username = state.username ?: return
        val rawName = state.createChannelNameInput.trim()
        val message = state.createChannelFirstMessageInput.trim()
        if (rawName.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiError.ChannelNameRequired) }
            return
        }
        if (message.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiError.ChannelMessageRequired) }
            return
        }
        val channelName = if (rawName.endsWith("@channel")) rawName else "$rawName@channel"

        // Проверка офлайн режима
        if (state.isOffline) {
            _uiState.update { it.copy(errorMessage = UiError.Network) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            runCatching { repository.sendTextMessage(from = username, to = channelName, text = message) }
                .onSuccess {
                    _uiState.update {
                        val channels = if (channelName in it.channels) it.channels else (it.channels + channelName).sorted()
                        it.copy(
                            isSending = false,
                            channels = channels,
                            selectedChat = channelName,
                            createChannelNameInput = "",
                            createChannelFirstMessageInput = "",
                            isCreateChannelDialogVisible = false
                        )
                    }
                    loadMessages(channelName, reset = true)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSending = false) }
                    handleRepositoryError(error) { stateCopy -> stateCopy }
                }
        }
    }

    private suspend fun readUriPayload(uri: Uri): SelectedImage = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val mimeType = resolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            } ?: "upload.jpg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to read selected image")
        SelectedImage(fileName = fileName, mimeType = mimeType, bytes = bytes)
    }

    private fun bootstrap() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val session = repository.bootstrapSession()
            _uiState.update {
                it.copy(
                    loginInput = session.name.orEmpty(),
                    passwordInput = session.password.orEmpty()
                )
            }
            tryAutoLogin(session)
        }
    }

    private suspend fun tryAutoLogin(session: SessionState) {
        val name = session.name
        val password = session.password

        if (name.isNullOrBlank() || password.isNullOrBlank()) {
            _uiState.update { it.copy(isLoading = false, isAuthorized = false) }
            return
        }

        // Если нет сети - автоматически входим в офлайн-режим
        if (!repository.isNetworkAvailable.value) {
            // Входим без проверки пароля (только для просмотра кэша)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isAuthorized = true,
                    username = name,
                    isOffline = true  // ← помечаем как офлайн-режим
                )
            }
            // Загружаем кэшированные каналы
            refreshChannels()
            return
        }

        // Есть сеть - нормальная аутентификация
        runCatching { repository.login(name, password) }
            .onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAuthorized = true,
                        username = name,
                        isOffline = false
                    )
                }
                connectWebSocket(name)
                refreshChannels()
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAuthorized = false
                    )
                }
                if ((error as? ApiException)?.error != ApiError.Network) {
                    _uiState.update { it.copy(loginError = LoginError.Generic) }
                }
            }
    }


    private fun loadMessages(
        chat: String,
        reset: Boolean,
        lastKnownId: Long = 0L,
        reverse: Boolean = false
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMessagesLoading = true, errorMessage = null) }
            runCatching {
                repository.loadMessages(
                    chat = chat,
                    limit = PAGE_SIZE,
                    lastKnownId = lastKnownId,
                    reverse = reverse
                )
            }.onSuccess { incoming ->
                _uiState.update { state ->
                    val current = if (reset) emptyList() else state.messagesByChat[chat].orEmpty()
                    val merged = if (reverse) incoming + current else current + incoming
                    state.copy(
                        isMessagesLoading = false,
                        messagesByChat = state.messagesByChat + (chat to merged.distinctBy { it.id }),
                        canLoadMoreByChat = state.canLoadMoreByChat + (chat to (incoming.size >= PAGE_SIZE))
                    )
                }
            }.onFailure { handleRepositoryError(it) { state -> state.copy(isMessagesLoading = false) } }
        }
    }

    private fun handleLoginError(throwable: Throwable) {
        val loginError = when ((throwable as? ApiException)?.error) {
            ApiError.InvalidCredentials,
            ApiError.Unauthorized -> LoginError.InvalidCredentials
            else -> LoginError.Generic
        }
        _uiState.update { it.copy(isLoading = false, loginError = loginError) }
    }

    private fun handleRepositoryError(throwable: Throwable, fallback: (AppUiState) -> AppUiState) {
        val apiError = (throwable as? ApiException)?.error

        // Если нет сети - показываем ошибку
        if (apiError == ApiError.Network) {
            _uiState.update { state ->
                fallback(state).copy(errorMessage = UiError.Network)
            }
            return
        }

        if (apiError == ApiError.Unauthorized) {
            viewModelScope.launch {
                repository.clearSessionToken()
            }
            webSocketClient.disconnect()
            _uiState.update {
                fallback(
                    it.copy(
                        isAuthorized = false,
                        selectedChat = null,
                        fullScreenImagePath = null,
                        errorMessage = null
                    )
                )
            }
            return
        }

        _uiState.update { state ->
            fallback(state).copy(errorMessage = UiError.Generic)
        }
    }

    private fun connectWebSocket(username: String) {
        val token = repository.currentToken() ?: return
        webSocketClient.connect(
            username = username,
            token = token
        ) { message ->
            val chatName = message.to
            viewModelScope.launch {
                repository.addNewMessageFromWebSocket(message)
            }
            _uiState.update { state ->
                val existing = state.messagesByChat[chatName].orEmpty()
                val updatedMessages = (existing + message).distinctBy { it.id ?: "${it.from}-${it.time}" }
                val updatedChannels = if (chatName in state.channels) state.channels else (state.channels + chatName).sorted()
                state.copy(
                    channels = updatedChannels,
                    messagesByChat = state.messagesByChat + (chatName to updatedMessages)
                )
            }
        }
    }

    private fun handleRegistrationError(throwable: Throwable) {
        val registrationError = when (val apiError = (throwable as? ApiException)?.error) {
            is ApiError.Http -> if (apiError.code == 409) RegistrationError.NameTaken else RegistrationError.Generic
            ApiError.Network -> RegistrationError.Network
            else -> RegistrationError.Generic
        }
        _uiState.update { it.copy(isLoading = false, registrationError = registrationError) }
    }

    override fun onCleared() {
        webSocketClient.disconnect()
        super.onCleared()
    }

    private fun handleImageSendError(throwable: Throwable, fallback: (AppUiState) -> AppUiState) {
        val apiError = (throwable as? ApiException)?.error
        if (apiError == ApiError.Unauthorized) {
            handleRepositoryError(throwable, fallback)
            return
        }
        if (apiError == ApiError.Network) {
            _uiState.update { state ->
                fallback(state).copy(errorMessage = UiError.Network)
            }
            return
        }
        val message = when {
            throwable is IllegalStateException -> UiError.PhotoRead
            apiError is ApiError.Http -> UiError.PhotoHttp(apiError.code)
            else -> UiError.PhotoGeneric
        }
        _uiState.update { state ->
            fallback(state).copy(errorMessage = message)
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return AppViewModel(application) as T
        }
    }
}

data class AppUiState(
    val isLoading: Boolean = false,
    val isAuthorized: Boolean = false,
    val loginInput: String = "",
    val passwordInput: String = "",
    val registrationNameInput: String = "",
    val username: String? = null,
    val authMode: AuthMode = AuthMode.Login,
    val loginError: LoginError? = null,
    val registrationError: RegistrationError? = null,
    val registeredPassword: String? = null,
    val channels: List<String> = emptyList(),
    val selectedChat: String? = null,
    val messagesByChat: Map<String, List<ChatMessage>> = emptyMap(),
    val canLoadMoreByChat: Map<String, Boolean> = emptyMap(),
    val outgoingText: String = "",
    val isChannelsLoading: Boolean = false,
    val isMessagesLoading: Boolean = false,
    val isSending: Boolean = false,
    val isCreateChannelDialogVisible: Boolean = false,
    val createChannelNameInput: String = "",
    val createChannelFirstMessageInput: String = "",
    val fullScreenImagePath: String? = null,
    val errorMessage: UiError? = null,
    val isOffline: Boolean = false
)

enum class LoginError {
    InvalidCredentials,
    Generic
}

enum class RegistrationError {
    NameTaken,
    Network,
    Generic
}

enum class AuthMode {
    Login,
    Registration
}

sealed interface UiError {
    data object Network : UiError
    data object Generic : UiError
    data object PhotoNetwork : UiError
    data object PhotoRead : UiError
    data class PhotoHttp(val code: Int) : UiError
    data object PhotoGeneric : UiError
    data object ChannelNameRequired : UiError
    data object ChannelMessageRequired : UiError
}

private data class SelectedImage(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SelectedImage

        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}