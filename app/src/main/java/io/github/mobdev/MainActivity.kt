package io.github.mobdev

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ContactApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ContactApp() {
    val permissionState = rememberPermissionState(Manifest.permission.READ_CONTACTS)

    when {
        permissionState.status.isGranted -> {
            ContactListScreen()
        }
        permissionState.status.shouldShowRationale -> {
            RationaleScreen(
                onRequestPermission = { permissionState.launchPermissionRequest() }
            )
        }
        else -> {
            NoPermissionScreen(
                onRequestPermission = { permissionState.launchPermissionRequest() }
            )
        }
    }
}

@Composable
fun ContactListScreen() {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Загрузка при первом входе
    LaunchedEffect(Unit) {
        contacts = context.fetchAllContacts()
        isLoading = false
    }

    // Функция обновления
    val refreshContacts = {
        isRefreshing = true
        contacts = context.fetchAllContacts()
        isRefreshing = false
    }

    if (selectedContact != null) {
        ContactDetailScreen(
            contact = selectedContact!!,
            onBack = { selectedContact = null }
        )
    } else {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                Column {
                    // Кнопка обновления
                    Button(
                        onClick = { refreshContacts() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.refreshing))
                        } else {
                            Text(stringResource(R.string.refresh))
                        }
                    }

                    if (contacts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.no_contacts))
                        }
                    } else {
                        LazyColumn {
                            items(contacts) { contact ->
                                ContactListItem(
                                    contact = contact,
                                    onClick = { selectedContact = contact }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactListItem(contact: Contact, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Text(
            text = contact.name ?: stringResource(R.string.no_name),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun ContactDetailScreen(contact: Contact, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                R.string.back
            ))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${stringResource(R.string.name_label)}${contact.name ?:
                    stringResource(R.string.not_specified)}",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "${stringResource(R.string.phone_label)}${contact.phoneNumber ?:
                    stringResource(R.string.not_specified)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${stringResource(R.string.email_label)}${contact.email ?:
                            stringResource(R.string.not_specified)}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
fun NoPermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.request_permission))
        }
    }
}

@Composable
fun RationaleScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.rationale_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.rationale_text),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.grant_access))
        }
    }
}