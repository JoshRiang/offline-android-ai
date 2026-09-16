package com.example.slmchat.ui.chat

import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val haptics = LocalHapticFeedback.current

    val activeModel = ModelCatalog.requireById(settings.modelId)
    val downloadState = downloadStates[settings.modelId] ?: ModelDownloadState.NotDownloaded
    val modelReady = downloadState is ModelDownloadState.Downloaded

    // Smooth scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Show error snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Animate the "Thinking" indicator
    val thinkingAlpha by animateFloatAsState(
        targetValue = if (isGenerating && streamingText.isBlank()) 1f else 0f,
        animationSpec = tween(300)
    )
    
    // Animate model loading state
    val loadingAlpha by animateFloatAsState(
        targetValue = if (engineState is EngineState.Loading) 1f else 0f,
        animationSpec = tween(200)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Text("Conversations", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                conversations.forEach { convo ->
                    ListItem(
                        headlineContent = {
                            Text(
                                convo.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        supportingContent = {
                            Text(
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(convo.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { viewModel.deleteConversation(convo.id) },
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectConversation(convo.id)
                                scope.launch { drawerState.close() }
                            }
                            .padding(vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.newConversation()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New chat")
                }
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
                            AnimatedEngineStatus(
                                engineState = engineState,
                                isGenerating = isGenerating,
                                loadingAlpha = loadingAlpha
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.newConversation()
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenSettings()
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                ChatInputBar(
                    value = input,
                    onValueChange = viewModel::onInputChange,
                    onSend = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.send()
                    },
                    onStop = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.stopGenerating()
                    },
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
                // Model download banner
                if (!modelReady) {
                    ModelDownloadBanner(
                        downloadState = downloadState,
                        modelName = activeModel.name,
                        modelSizeMb = activeModel.sizeMb,
                        onDownload = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.downloadActiveModel()
                        },
                        onCancel = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelActiveDownload()
                        },
                        onOpenSettings = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenSettings()
                        }
                    )
                }
                
                // Empty state or messages
                if (messages.isEmpty() && streamingText.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Animated icon
                            Text(
                                "💬",
                                fontSize = 64.sp
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (modelReady) 
                                    "Say hello to your on-device model.\nEverything stays on this phone."
                                else
                                    "Download the model to start chatting.\nInference runs fully offline afterwards.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 24.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp).Bottom,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
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
                        // Thinking indicator
                        if (isGenerating && streamingText.isBlank()) {
                            item {
                                AnimatedThinkingIndicator(alpha = thinkingAlpha)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedEngineStatus(
    engineState: EngineState,
    isGenerating: Boolean,
    loadingAlpha: Float
) {
    val (text, color) = when {
        isGenerating -> "Generating…" to MaterialTheme.colorScheme.primary
        engineState is EngineState.Ready -> "Ready • on-device" to MaterialTheme.colorScheme.primary
        engineState is EngineState.Loading -> "Loading model…" to MaterialTheme.colorScheme.onSurfaceVariant
        engineState is EngineState.Error -> "Error: ${engineState.message}" to MaterialTheme.colorScheme.error
        else -> "Unloaded" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = if (engineState is EngineState.Loading) loadingAlpha else 1f),
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun AnimatedThinkingIndicator(alpha: Float) {
    if (alpha > 0.01f) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Thinking on-device…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        modelName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        "~${modelSizeMb} MB • Downloads once, runs offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when (downloadState) {
                is ModelDownloadState.Downloading -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { downloadState.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Downloading… ${downloadState.progress}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onCancel) { 
                                Text("Cancel", style = MaterialTheme.typography.labelLarge) 
                            }
                        }
                    }
                }
                is ModelDownloadState.Failed -> {
                    Text(
                        "Download failed: ${downloadState.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(12.dp))
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
