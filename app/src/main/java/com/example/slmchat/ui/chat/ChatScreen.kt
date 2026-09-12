package com.example.slmchat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.slmchat.llm.EngineState
import com.example.slmchat.llm.ModelCatalog
import com.example.slmchat.llm.ModelDownloadState
import com.example.slmchat.ui.chat.components.ChatInputBar
import com.example.slmchat.ui.chat.components.MessageBubble
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val input by viewModel.input.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val activeId by viewModel.activeConversationId.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val error by viewModel.error.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val activeModel = ModelCatalog.requireById(settings.modelId)
    val downloadState = downloadStates[settings.modelId] ?: ModelDownloadState.NotDownloaded
    val modelReady = downloadState is ModelDownloadState.Downloaded

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Conversations", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                conversations.forEach { convo ->
                    ListItem(
                        headlineContent = {
                            Text(convo.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(convo.updatedAt))
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteConversation(convo.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.selectConversation(convo.id)
                            scope.launch { drawerState.close() }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.newConversation()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("New chat") }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                activeModel.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                engineStatusText(engineState, isGenerating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.newConversation() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                ChatInputBar(
                    value = input,
                    onValueChange = viewModel::onInputChange,
                    onSend = viewModel::send,
                    onStop = viewModel::stopGenerating,
                    isGenerating = isGenerating,
                    enabled = modelReady
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (!modelReady) {
                    ModelDownloadBanner(
                        downloadState = downloadState,
                        modelName = activeModel.name,
                        modelSizeMb = activeModel.sizeMb,
                        onDownload = viewModel::downloadActiveModel,
                        onCancel = viewModel::cancelActiveDownload,
                        onOpenSettings = onOpenSettings
                    )
                }
                if (messages.isEmpty() && streamingText.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (modelReady) "Say hello to your on-device model.\nEverything stays on this phone."
                            else "Download the model to start chatting.\nInference runs fully offline afterwards.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        items(messages, key = { it.id }) { message ->
                            // Overlay live tokens onto the trailing assistant placeholder.
                            val isLastAssistant =
                                message.id == messages.lastOrNull()?.id && streamingText.isNotBlank()
                            MessageBubble(
                                message = if (isLastAssistant) message.copy(content = streamingText)
                                else message,
                                isStreaming = isLastAssistant
                            )
                        }
                        if (isGenerating && streamingText.isBlank()) {
                            item {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .width(20.dp)
                                            .height(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Thinking on-device…",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadBanner(
    downloadState: ModelDownloadState,
    modelName: String,
    modelSizeMb: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Model: $modelName (~${modelSizeMb} MB)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            when (downloadState) {
                is ModelDownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { downloadState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Downloading… ${downloadState.progress}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is ModelDownloadState.Failed -> {
                    Text(
                        "Download failed: ${downloadState.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = onDownload) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Retry")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onOpenSettings) { Text("Change model") }
                    }
                }
                else -> {
                    Text(
                        "Needed once. After that, chat works fully offline.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = onDownload) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onOpenSettings) { Text("Change model") }
                    }
                }
            }
        }
    }
}

private fun engineStatusText(state: EngineState, generating: Boolean): String = when {
    generating -> "Generating…"
    state is EngineState.Ready -> "Ready • on-device"
    state is EngineState.Loading -> "Loading model…"
    state is EngineState.Error -> "Error: ${state.message}"
    else -> "Unloaded"
}
