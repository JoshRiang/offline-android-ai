package com.example.slmchat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.slmchat.llm.ModelCatalog
import com.example.slmchat.llm.ModelDownloadState
import com.example.slmchat.llm.ModelInfo
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearedMessage by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Model picker ----
            Text("On-device model", style = MaterialTheme.typography.titleMedium)
            Text(
                "Models download once, then run fully offline via MediaPipe LLM Inference.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ModelCatalog.models.forEach { model ->
                val state = downloadStates[model.id] ?: ModelDownloadState.NotDownloaded
                ModelCard(
                    model = model,
                    selected = settings.modelId == model.id,
                    downloadState = state,
                    onSelect = { viewModel.selectModel(model.id) },
                    onDownload = { viewModel.downloadModel(model.id) },
                    onCancel = { viewModel.cancelDownload(model.id) },
                    onDelete = { viewModel.deleteModel(model.id) }
                )
            }

            // ---- Custom URL override ----
            Text("Custom model URL", style = MaterialTheme.typography.titleMedium)
            var urlDraft by remember(settings.customModelUrl) {
                mutableStateOf(settings.customModelUrl)
            }
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Direct .task download URL (optional)") },
                placeholder = { Text("https://…/model.task") },
                singleLine = true
            )
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = { viewModel.setCustomModelUrl(urlDraft) }) {
                    Text("Save URL")
                }
            }
            if (settings.customModelUrl.isNotBlank()) {
                Text(
                    "Active override: ${settings.customModelUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- Sampling params ----
            Text("Generation", style = MaterialTheme.typography.titleMedium)

            SliderSetting(
                label = "Temperature",
                valueText = "%.2f".format(settings.temperature),
                value = settings.temperature,
                range = 0f..2f,
                onChange = viewModel::setTemperature
            )
            SliderSetting(
                label = "Top-P",
                valueText = "%.2f".format(settings.topP),
                value = settings.topP,
                range = 0.01f..1f,
                onChange = viewModel::setTopP
            )
            SliderSetting(
                label = "Top-K",
                valueText = settings.topK.toString(),
                value = settings.topK.toFloat(),
                range = 1f..100f,
                onChange = { viewModel.setTopK(it.roundToInt()) }
            )
            SliderSetting(
                label = "Max tokens",
                valueText = settings.maxTokens.toString(),
                value = settings.maxTokens.toFloat(),
                range = 128f..4096f,
                onChange = { viewModel.setMaxTokens(it.roundToInt()) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use GPU", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Falls back to CPU where unsupported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.useGpu, onCheckedChange = viewModel::setUseGpu)
            }

            Text("System prompt", style = MaterialTheme.typography.titleMedium)
            var promptDraft by remember(settings.systemPrompt) {
                mutableStateOf(settings.systemPrompt)
            }
            OutlinedTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8
            )
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = { viewModel.setSystemPrompt(promptDraft) }) {
                    Text("Save prompt")
                }
            }

            // ---- Danger zone ----
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear all chats")
            }
            if (clearedMessage) {
                Text(
                    "All conversations deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all chats?") },
            text = { Text("This permanently deletes every conversation and message on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAllChats { clearedMessage = true }
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    selected: Boolean,
    downloadState: ModelDownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onSelect
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${model.sizeMb} MB • ${model.license} • ${model.contextLength} ctx",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected model")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (model.gated) {
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("Gated license — may need manual download") }
                )
            }
            Spacer(Modifier.height(8.dp))
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
                is ModelDownloadState.Downloaded -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Downloaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Delete file") }
                    }
                }
                is ModelDownloadState.Failed -> {
                    Text(
                        "Download failed: ${downloadState.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Retry download")
                    }
                }
                is ModelDownloadState.NotDownloaded -> {
                    Button(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Download (~${model.sizeMb} MB)")
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft) },
            valueRange = range
        )
    }
}
