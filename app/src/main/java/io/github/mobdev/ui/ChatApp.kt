package io.github.mobdev.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.mobdev.R
import io.github.mobdev.data.api.ChatMessage

private const val BASE_URL = "https://faerytea.name/"

@Composable
fun ChatApp(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val backEnabled = state.fullScreenImagePath != null || state.selectedChat != null
    if (backEnabled) {
        BackHandler {
            viewModel.handleBack(isLandscape)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Основной контент
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> LoadingScreen()
                !state.isAuthorized -> LoginScreen(
                    state = state,
                    onLoginChanged = viewModel::onLoginChanged,
                    onPasswordChanged = viewModel::onPasswordChanged,
                    onLoginClick = viewModel::login,
                    onDismissLoginError = viewModel::clearLoginError,
                    onNavigateToRegistration = viewModel::showRegistration,
                    onRegistrationNameChanged = viewModel::onRegistrationNameChanged,
                    onRegisterClick = viewModel::register,
                    onNavigateToLogin = viewModel::showLogin
                )
                isLandscape -> LandscapeContent(
                    state = state,
                    onOpenChat = viewModel::openChat,
                    onLogout = viewModel::logout,
                    onOutgoingTextChanged = viewModel::onOutgoingTextChanged,
                    onSendMessage = viewModel::sendMessage,
                    onLoadMore = viewModel::loadMore,
                    onImageClick = viewModel::openImage,
                    onRetry = viewModel::refreshChannels,
                    onPickImage = viewModel::sendImage,
                    onShowCreateChannel = viewModel::showCreateChannelDialog
                )
                state.selectedChat == null -> ChatsScreen(
                    state = state,
                    onOpenChat = viewModel::openChat,
                    onLogout = viewModel::logout,
                    onRetry = viewModel::refreshChannels,
                    onShowCreateChannel = viewModel::showCreateChannelDialog
                )
                else -> MessagesScreen(
                    state = state,
                    onBackToChats = viewModel::closeChat,
                    onOutgoingTextChanged = viewModel::onOutgoingTextChanged,
                    onSendMessage = viewModel::sendMessage,
                    onLoadMore = viewModel::loadMore,
                    onImageClick = viewModel::openImage,
                    onPickImage = viewModel::sendImage
                )
            }
        }

        // Офлайн-индикатор (поверх всего)
        if (state.isOffline) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .align(Alignment.TopCenter)
            ) {
                Text(
                    text = "⚠️ Нет подключения к интернету",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // FullScreen image поверх всего
        state.fullScreenImagePath?.let { path ->
            ImageScreen(path = path, onClose = viewModel::closeImage)
        }
    }

    // Диалоги
    val registeredPassword = state.registeredPassword
    if (registeredPassword != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearRegisteredPassword,
            title = { Text(text = stringResource(id = R.string.registration_success_title)) },
            text = {
                Text(text = stringResource(id = R.string.registration_success_message, registeredPassword))
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearRegisteredPassword) {
                    Text(text = stringResource(id = R.string.ok_button))
                }
            }
        )
    }

    if (state.isCreateChannelDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::hideCreateChannelDialog,
            title = { Text(text = stringResource(id = R.string.create_channel_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.createChannelNameInput,
                        onValueChange = viewModel::onCreateChannelNameChanged,
                        label = { Text(text = stringResource(id = R.string.create_channel_name_hint)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.createChannelFirstMessageInput,
                        onValueChange = viewModel::onCreateChannelFirstMessageChanged,
                        label = { Text(text = stringResource(id = R.string.create_channel_message_hint)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::createChannel) {
                    Text(text = stringResource(id = R.string.create_channel_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideCreateChannelDialog) {
                    Text(text = stringResource(id = R.string.cancel_button))
                }
            }
        )
    }

    if (state.errorMessage != null) {
        val errorTitle = when (val error = state.errorMessage) {
            UiError.Network,
            UiError.Generic -> stringResource(id = R.string.network_error)
            UiError.PhotoNetwork,
            UiError.PhotoRead,
            UiError.PhotoGeneric,
            is UiError.PhotoHttp -> stringResource(id = R.string.photo_send_error_title)
            UiError.ChannelNameRequired,
            UiError.ChannelMessageRequired -> stringResource(id = R.string.create_channel_title)
            null -> ""
        }
        val errorText = when (val error = state.errorMessage) {
            UiError.Network -> stringResource(R.string.network_error)
            UiError.Generic -> stringResource(R.string.login_error_generic)
            UiError.PhotoNetwork -> stringResource(R.string.photo_send_error_network)
            UiError.PhotoRead -> stringResource(R.string.photo_send_error_read)
            is UiError.PhotoHttp -> stringResource(R.string.photo_send_error_http, error.code)
            UiError.PhotoGeneric -> stringResource(R.string.photo_send_error_generic)
            UiError.ChannelNameRequired -> stringResource(R.string.create_channel_name_required)
            UiError.ChannelMessageRequired -> stringResource(R.string.create_channel_message_required)
            null -> ""
        }
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(text = errorTitle) },
            text = {
                Text(text = errorText)
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text(text = stringResource(id = R.string.ok_button))
                }
            }
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoginScreen(
    state: AppUiState,
    onLoginChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
    onDismissLoginError: () -> Unit,
    onNavigateToRegistration: () -> Unit,
    onRegistrationNameChanged: (String) -> Unit,
    onRegisterClick: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
        ) {
            if (state.authMode == AuthMode.Login) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = stringResource(id = R.string.login_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(id = R.string.login_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.loginInput,
                        onValueChange = onLoginChanged,
                        label = { Text(text = stringResource(id = R.string.login_label)) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.passwordInput,
                        onValueChange = onPasswordChanged,
                        label = { Text(text = stringResource(id = R.string.password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onLoginClick,
                        enabled = !state.isLoading
                    ) {
                        Text(text = stringResource(id = R.string.login_button))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onNavigateToRegistration) {
                        Text(text = stringResource(id = R.string.go_to_registration))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = stringResource(id = R.string.registration_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(id = R.string.registration_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.registrationNameInput,
                        onValueChange = onRegistrationNameChanged,
                        label = { Text(text = stringResource(id = R.string.registration_name_label)) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRegisterClick,
                        enabled = !state.isLoading
                    ) {
                        Text(text = stringResource(id = R.string.register_button))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onNavigateToLogin) {
                        Text(text = stringResource(id = R.string.go_to_login))
                    }
                }
            }
        }
    }

    if (state.loginError != null) {
        val errorText = when (state.loginError) {
            LoginError.InvalidCredentials -> stringResource(id = R.string.login_error_invalid_credentials)
            LoginError.Generic -> stringResource(id = R.string.login_error_generic)
        }
        AlertDialog(
            onDismissRequest = onDismissLoginError,
            title = { Text(text = stringResource(id = R.string.login_error_title)) },
            text = { Text(text = errorText) },
            confirmButton = {
                TextButton(onClick = onDismissLoginError) {
                    Text(text = stringResource(id = R.string.ok_button))
                }
            }
        )
    }

    if (state.registrationError != null) {
        val errorText = when (state.registrationError) {
            RegistrationError.NameTaken -> stringResource(id = R.string.registration_error_name_taken)
            RegistrationError.Network -> stringResource(id = R.string.registration_error_network)
            RegistrationError.Generic -> stringResource(id = R.string.registration_error_generic)
        }
        AlertDialog(
            onDismissRequest = onNavigateToLogin,
            title = { Text(text = stringResource(id = R.string.registration_error_title)) },
            text = { Text(text = errorText) },
            confirmButton = {
                TextButton(onClick = onNavigateToLogin) {
                    Text(text = stringResource(id = R.string.ok_button))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsScreen(
    state: AppUiState,
    onOpenChat: (String) -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    onShowCreateChannel: () -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = stringResource(id = R.string.channels_title)) },
            actions = {
                TextButton(onClick = onShowCreateChannel) {
                    Text(text = stringResource(id = R.string.create_channel_short))
                }
                Text(
                    text = stringResource(id = R.string.channels_count, state.channels.size),
                    modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onLogout) {
                    Text(text = stringResource(id = R.string.logout_button))
                }
            }
        )
    }) { padding ->
        ChannelsList(
            modifier = Modifier.padding(padding),
            state = state,
            onOpenChat = onOpenChat,
            onRetry = onRetry
        )
    }
}

@Composable
private fun ChannelsList(
    modifier: Modifier = Modifier,
    state: AppUiState,
    onOpenChat: (String) -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.isChannelsLoading -> LoadingScreen()
        state.channels.isEmpty() -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = stringResource(id = R.string.empty_channels))
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(id = R.string.retry_button))
            }
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.channels) { chat ->
                val selected = chat == state.selectedChat
                val background = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(background)
                        .border(
                            width = if (selected) 1.5.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onOpenChat(chat) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(text = chat, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesScreen(
    state: AppUiState,
    onBackToChats: () -> Unit,
    onOutgoingTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onLoadMore: () -> Unit,
    onImageClick: (String) -> Unit,
    onPickImage: (android.net.Uri) -> Unit
) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPickImage)
    }
    val selectedChat = state.selectedChat ?: return
    val messages = state.messagesByChat[selectedChat].orEmpty()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = stringResource(id = R.string.messages_for_chat, selectedChat)) },
            navigationIcon = {
                TextButton(onClick = onBackToChats) {
                    Text(text = stringResource(id = R.string.back_to_chats))
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Button(
                onClick = onLoadMore,
                enabled = state.canLoadMoreByChat.getOrDefault(selectedChat, true)
            ) {
                Text(text = stringResource(id = R.string.load_more))
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.empty_messages),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id ?: it.time ?: it.hashCode() }) { message ->
                        MessageItem(
                            message = message,
                            currentUser = state.username,
                            onImageClick = onImageClick
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !state.isSending
                ) {
                    Text(text = stringResource(id = R.string.attach_photo_button))
                }
                Spacer(modifier = Modifier.size(4.dp))
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = state.outgoingText,
                    onValueChange = onOutgoingTextChanged,
                    label = { Text(text = stringResource(id = R.string.message_hint)) }
                )
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onSendMessage, enabled = !state.isSending) {
                    Text(text = stringResource(id = R.string.send_button))
                }
            }
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage, currentUser: String?, onImageClick: (String) -> Unit) {
    val isMine = currentUser == message.from
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    if (isMine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape
                )
                .padding(12.dp)
        ) {
            Text(
                text = if (isMine) stringResource(id = R.string.message_author_me) else message.from,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            message.data.text?.let { payload ->
                Text(text = payload.text, style = MaterialTheme.typography.bodyLarge)
            }
            message.data.image?.link?.let { imagePath ->
                Spacer(modifier = Modifier.height(8.dp))
                AsyncImage(
                    model = "${BASE_URL}thumb/$imagePath",
                    contentDescription = stringResource(id = R.string.thumbnail_content_description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onImageClick(imagePath) },
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun ImageScreen(path: String, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = "${BASE_URL}img/$path",
            contentDescription = stringResource(id = R.string.image_content_description),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Text(text = stringResource(id = R.string.close_image), color = Color.White)
        }
    }
}

@Composable
private fun LandscapeContent(
    state: AppUiState,
    onOpenChat: (String) -> Unit,
    onLogout: () -> Unit,
    onOutgoingTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onLoadMore: () -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: () -> Unit,
    onPickImage: (android.net.Uri) -> Unit,
    onShowCreateChannel: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.38f)
        ) {
            ChatsScreen(
                state = state,
                onOpenChat = onOpenChat,
                onLogout = onLogout,
                onRetry = onRetry,
                onShowCreateChannel = onShowCreateChannel
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.62f)
        ) {
            if (state.selectedChat == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(id = R.string.select_chat_placeholder))
                }
            } else {
                MessagesScreen(
                    state = state,
                    onBackToChats = {},
                    onOutgoingTextChanged = onOutgoingTextChanged,
                    onSendMessage = onSendMessage,
                    onLoadMore = onLoadMore,
                    onImageClick = onImageClick,
                    onPickImage = onPickImage
                )
            }
        }
    }
}