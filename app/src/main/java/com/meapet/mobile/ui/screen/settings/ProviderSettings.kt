package com.meapet.mobile.ui.screen.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meapet.mobile.R
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  提供商：API 接入、模型参数与 Telegram Bot
// ═══════════════════════════════════════════════════

@Composable
internal fun ApiConfigSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState
) {
    SectionTitle(stringResource(R.string.provider_api_config))

    Text(
        stringResource(R.string.provider_api_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    OutlinedTextField(
        value = local.apiKey,
        onValueChange = { local.apiKey = it },
        label = { Text(stringResource(R.string.provider_api_key)) },
        placeholder = { Text("sk-...") },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveApiKey(local.apiKey) },
        singleLine = true,
        visualTransformation = if (local.apiKeyVisible)
            VisualTransformation.None
        else
            PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = { local.apiKeyVisible = !local.apiKeyVisible },
                modifier = Modifier.width(64.dp)
            ) {
                Text(
                    text = if (local.apiKeyVisible) stringResource(R.string.action_hide)
                           else stringResource(R.string.action_show),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = local.apiUrl,
        onValueChange = { local.apiUrl = it },
        label = { Text(stringResource(R.string.provider_api_url)) },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveApiUrl(local.apiUrl) },
        singleLine = true,
        placeholder = { Text("https://api.deepseek.com/v1") }
    )
}

@Composable
internal fun ModelParamsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState,
    darkTheme: Boolean
) {
    SectionTitle(stringResource(R.string.provider_model_params))

    OutlinedTextField(
        value = local.model,
        onValueChange = { local.model = it },
        label = { Text(stringResource(R.string.provider_model)) },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveModel(local.model) },
        singleLine = true,
        placeholder = { Text("deepseek-v4-flash") }
    )

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            viewModel.saveApiKey(local.apiKey)
            viewModel.saveApiUrl(local.apiUrl)
            viewModel.fetchModels(local.apiKey, local.apiUrl)
        },
        enabled = !state.isLoadingModels,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isLoadingModels) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.provider_fetching_models))
        } else {
            Text(stringResource(R.string.provider_fetch_models))
        }
    }

    state.modelsError?.let { err ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.dismissModelsError() }
        )
    }

    if (state.availableModels.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.provider_models_count, state.availableModels.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_FAINT_TEXT)
        )
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG_MID)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.availableModels, key = { it }) { modelId ->
                    val selected = modelId == local.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                local.model = modelId
                                viewModel.selectModel(modelId)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelId,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.action_selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ALPHA_DIVIDER)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    ParamLabelWithHelp(
        label = stringResource(R.string.provider_temperature_label, "%.2f".format(local.temperature)),
        helpTitle = stringResource(R.string.provider_temperature_help_title),
        helpText = stringResource(R.string.provider_temperature_help_text)
    )
    Slider(
        value = local.temperature,
        onValueChange = { local.temperature = it },
        onValueChangeFinished = {
            viewModel.updateTemperature(local.temperature.toDouble())
        },
        valueRange = TEMPERATURE_RANGE,
        steps = TEMPERATURE_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.provider_max_tokens, local.maxTokens.toInt()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = local.maxTokens,
        onValueChange = { local.maxTokens = it },
        onValueChangeFinished = {
            viewModel.updateMaxTokens(local.maxTokens.toInt())
        },
        valueRange = MAX_TOKENS_RANGE,
        steps = MAX_TOKENS_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )
}

/** Telegram Bot configuration: enable switch, bot token, optional chat ID filter, test button, notification permission. */
@Composable
internal fun TelegramBotSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState,
    darkTheme: Boolean
) {
    val context = LocalContext.current

    SectionTitle(stringResource(R.string.telegram_section_title))

    SettingsSwitchRow(
        label = stringResource(R.string.telegram_enable_label),
        description = stringResource(R.string.telegram_enable_desc),
        checked = state.telegramEnabled,
        darkTheme = darkTheme,
        onCheckedChange = {
            viewModel.saveTelegramBotToken(local.telegramBotToken)
            viewModel.saveTelegramChatId(local.telegramChatId)
            viewModel.updateTelegramEnabled(it)
        }
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = local.telegramBotToken,
        onValueChange = { local.telegramBotToken = it },
        label = { Text(stringResource(R.string.telegram_bot_token)) },
        placeholder = { Text(stringResource(R.string.telegram_bot_token_placeholder)) },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveTelegramBotToken(local.telegramBotToken) },
        singleLine = true,
        visualTransformation = if (local.telegramTokenVisible)
            VisualTransformation.None
        else
            PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = { local.telegramTokenVisible = !local.telegramTokenVisible },
                modifier = Modifier.width(64.dp)
            ) {
                Text(
                    text = if (local.telegramTokenVisible) stringResource(R.string.action_hide)
                           else stringResource(R.string.action_show),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = local.telegramChatId,
        onValueChange = { local.telegramChatId = it },
        label = { Text(stringResource(R.string.telegram_chat_id)) },
        placeholder = { Text(stringResource(R.string.telegram_chat_id_placeholder)) },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveTelegramChatId(local.telegramChatId) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            viewModel.saveTelegramBotToken(local.telegramBotToken)
            viewModel.saveTelegramChatId(local.telegramChatId)
            viewModel.testTelegramConnection(local.telegramBotToken)
        },
        enabled = !state.telegramTesting,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.telegramTesting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.telegram_testing))
        } else {
            Text(stringResource(R.string.telegram_test_connection))
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        var notifGranted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
        val requestNotifPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            notifGranted = granted
        }
        if (!notifGranted) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.telegram_notif_permission_btn))
            }
        }
    }

    state.telegramTestResult?.let { msg ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.dismissTelegramFeedback() }
        )
    }

    state.telegramTestError?.let { err ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.dismissTelegramFeedback() }
        )
    }
}
