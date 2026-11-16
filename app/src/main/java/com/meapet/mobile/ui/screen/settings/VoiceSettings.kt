package com.meapet.mobile.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meapet.mobile.R
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  语音：TTS 开关、语速与模型
// ═══════════════════════════════════════════════════

@Composable
internal fun TtsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    val modelReady = state.ttsModelState is com.meapet.mobile.tts.model.TtsModelState.Ready

    TtsModelCard(state, viewModel)

    Spacer(Modifier.height(8.dp))
    SectionTitle(stringResource(R.string.voice_output_section))

    val modelRequiredDesc = stringResource(R.string.voice_model_required_desc)
    SettingsSwitchRow(
        label = stringResource(R.string.voice_main_label),
        description = if (modelReady) stringResource(R.string.voice_main_desc_ready) else modelRequiredDesc,
        checked = state.ttsMainEnabled && modelReady,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateTtsMainEnabled(it) },
        enabled = modelReady
    )
    SettingsSwitchRow(
        label = stringResource(R.string.voice_overlay_label),
        description = if (modelReady) stringResource(R.string.voice_overlay_desc_ready) else modelRequiredDesc,
        checked = state.ttsOverlayEnabled && modelReady,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateTtsOverlayEnabled(it) },
        enabled = modelReady
    )

    val lengthScale = state.ttsLengthScale.toFloat().coerceIn(0.5f, 2.0f)
    var speed by remember {
        mutableStateOf((1f / lengthScale).coerceIn(0.5f, 2.0f))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.voice_speed_label, "%.2f".format(speed)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (modelReady) 1f else 0.5f)
    )
    Slider(
        value = speed,
        onValueChange = { speed = it },
        onValueChangeFinished = {
            val updatedLengthScale = kotlin.math.round((1f / speed) * 100) / 100.0
            viewModel.updateTtsLengthScale(updatedLengthScale)
        },
        valueRange = TTS_SPEED_RANGE,
        steps = TTS_SPEED_STEPS,
        enabled = modelReady,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    if (com.meapet.mobile.BuildConfig.DEBUG) {
        Spacer(Modifier.height(8.dp))
        val defaultPreviewText = stringResource(R.string.voice_debug_preview_default)
        var previewText by remember { mutableStateOf(defaultPreviewText) }
        var showDialog by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showDialog = true },
            enabled = modelReady,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.voice_debug_preview_btn)) }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.voice_debug_preview_title)) },
                text = {
                    OutlinedTextField(
                        value = previewText,
                        onValueChange = { previewText = it },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        viewModel.previewTts(previewText)
                    }) { Text(stringResource(R.string.voice_debug_speak)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun TtsModelCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importTtsModelZip(it) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (val st = state.ttsModelState) {
                is com.meapet.mobile.tts.model.TtsModelState.Ready -> {
                    Text(
                        stringResource(R.string.voice_model_ready),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.voice_model_ready_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.deleteTtsModel() }) {
                        Text(stringResource(R.string.voice_model_delete))
                    }
                }
                is com.meapet.mobile.tts.model.TtsModelState.Downloading -> {
                    Text(
                        stringResource(R.string.voice_model_downloading),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        st.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressLike(progress = st.progress)
                }
                is com.meapet.mobile.tts.model.TtsModelState.Importing -> {
                    Text(
                        stringResource(R.string.voice_model_importing),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.voice_model_importing_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is com.meapet.mobile.tts.model.TtsModelState.Error -> {
                    Text(
                        stringResource(R.string.voice_model_download_failed),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        st.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.downloadTtsModel() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                        OutlinedButton(
                            onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) {
                            Text(stringResource(R.string.voice_model_import_local))
                        }
                    }
                }
                is com.meapet.mobile.tts.model.TtsModelState.NotDownloaded -> {
                    Text(
                        stringResource(R.string.voice_model_not_downloaded),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        if (state.ttsModelUrlConfigured) {
                            stringResource(R.string.voice_model_desc_with_url)
                        } else {
                            stringResource(R.string.voice_model_desc_no_url)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.downloadTtsModel() },
                            enabled = state.ttsModelUrlConfigured
                        ) { Text(stringResource(R.string.voice_model_download)) }
                        OutlinedButton(
                            onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text(stringResource(R.string.voice_model_import_local)) }
                    }
                }
            }
        }
    }
}
