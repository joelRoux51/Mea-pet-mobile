package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meapet.mobile.R
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  对话：System Prompt 与记忆系统
// ═══════════════════════════════════════════════════

@Composable
internal fun SystemPromptSection(
    viewModel: SettingsViewModel,
    local: SettingsLocalState
) {
    SectionTitle(stringResource(R.string.chat_settings_system_prompt))

    Column {
        OutlinedTextField(
            value = local.systemPrompt,
            onValueChange = { local.systemPrompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .saveOnFocusChange { viewModel.saveSystemPrompt(local.systemPrompt) },
            maxLines = 6
        )

        Spacer(Modifier.height(8.dp))

        val isDefault = local.systemPrompt == SettingsKeys.Defaults.SYSTEM_PROMPT
        OutlinedButton(
            onClick = {
                val previous = local.systemPrompt
                local.systemPrompt = SettingsKeys.Defaults.SYSTEM_PROMPT
                viewModel.resetSystemPrompt(previous)
            },
            enabled = !isDefault,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.action_restore_default),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun MemorySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState,
    darkTheme: Boolean
) {
    SectionTitle(stringResource(R.string.chat_settings_memory_system))

    SettingsSwitchRow(
        label = stringResource(R.string.chat_settings_enable_memory),
        description = stringResource(R.string.chat_settings_enable_memory_desc),
        checked = state.enableMemory,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableMemory(it) }
    )
    SettingsSwitchRow(
        label = stringResource(R.string.chat_settings_auto_summary),
        description = stringResource(R.string.chat_settings_auto_summary_desc),
        checked = state.enableAutoSummary,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableAutoSummary(it) }
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.chat_settings_summary_interval, local.summaryInterval.toInt()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (state.enableAutoSummary) 1f else 0.5f
        )
    )
    Slider(
        value = local.summaryInterval,
        onValueChange = { local.summaryInterval = it },
        onValueChangeFinished = {
            viewModel.updateSummaryInterval(local.summaryInterval.toInt())
        },
        valueRange = SUMMARY_INTERVAL_RANGE,
        steps = SUMMARY_INTERVAL_STEPS,
        enabled = state.enableAutoSummary,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            inactiveTrackColor = sliderTrackColor(darkTheme)
        )
    )
}
