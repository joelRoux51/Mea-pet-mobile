package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.R
import com.meapet.mobile.core.AppInfo
import com.meapet.mobile.ui.component.LinkItem
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  关于：应用信息、版本更新、隐私政策与数据授权
// ═══════════════════════════════════════════════════

/** 关于卡片中的 Live2D 模型来源链接。 */
private const val LIVE2D_MODEL_SOURCE_URL = "https://www.bilibili.com/video/BV1AoX7BXEaN"

@Composable
internal fun AppInfoSection() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val appVersion = remember(context) { AppInfo.readVersion(context) }
    val linkStyle = MaterialTheme.typography.bodySmall

    SectionTitle(stringResource(R.string.about_app_info))

    Text(stringResource(R.string.about_app_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    Text(
        stringResource(R.string.about_app_tagline),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.about_app_version, appVersion),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(Modifier.height(10.dp))

    Text(
        stringResource(R.string.about_poem),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(3.dp))
    LinkItem(stringResource(R.string.about_link_model_source), LIVE2D_MODEL_SOURCE_URL, uriHandler, linkStyle)
    Spacer(Modifier.height(3.dp))
    LinkItem(stringResource(R.string.about_link_github), AppInfo.gitRepoUrl, uriHandler, linkStyle)
    Spacer(Modifier.height(3.dp))
    LinkItem(stringResource(R.string.about_link_group), AppInfo.qqGroupUrl, uriHandler, linkStyle)

    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.about_tech_stack),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
internal fun UpdateSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    val uriHandler = LocalUriHandler.current

    SectionTitle(stringResource(R.string.about_update_section))

    SettingsSwitchRow(
        label = stringResource(R.string.about_auto_update_label),
        description = stringResource(R.string.about_auto_update_desc),
        checked = state.enableAutoUpdateCheck,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableAutoUpdateCheck(it) }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { viewModel.checkForUpdate() },
            enabled = !state.isCheckingUpdate
        ) {
            if (state.isCheckingUpdate) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (state.isCheckingUpdate) stringResource(R.string.about_checking)
                else stringResource(R.string.about_check_now)
            )
        }
    }

    state.updateMessage?.let { message ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        state.updateReleaseUrl?.let { url ->
            Spacer(Modifier.height(2.dp))
            LinkItem(
                stringResource(R.string.about_open_release),
                url,
                uriHandler,
                MaterialTheme.typography.bodySmall
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun PrivacySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenPrivacyPolicy: () -> Unit,
    onExitApp: () -> Unit
) {
    val umengAgreed = state.privacyAgreed
    var showRevokeDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPrivacyPolicy() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.about_view_privacy),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 180f }
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    val exportingLog by viewModel.exportingLog.collectAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !exportingLog) { viewModel.exportLog() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (exportingLog) stringResource(R.string.about_exporting_logs)
                       else stringResource(R.string.about_export_logs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (exportingLog) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = 180f }
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.about_analytics_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!BuildConfig.UMENG_ENABLED) {
                Text(
                    text = stringResource(R.string.about_analytics_no_sdk),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    text = if (umengAgreed) stringResource(R.string.about_analytics_agreed)
                           else stringResource(R.string.about_analytics_revoked),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (umengAgreed)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (umengAgreed) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showRevokeDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.about_revoke_consent))
                    }
                }
            }
        }
    }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text(stringResource(R.string.about_revoke_consent)) },
            text = { Text(stringResource(R.string.about_revoke_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revokePrivacyConsent()
                    showRevokeDialog = false
                    onExitApp()
                }) {
                    Text(stringResource(R.string.about_revoke_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) {
                    Text(stringResource(R.string.about_revoke_keep))
                }
            }
        )
    }
}

@Composable
internal fun AttributionSection() {
    SectionTitle(stringResource(R.string.about_icons_attribution_title))
    Text(
        text = stringResource(R.string.about_icons_attribution_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_FAINT_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
