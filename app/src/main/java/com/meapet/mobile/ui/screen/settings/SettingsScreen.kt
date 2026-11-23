package com.meapet.mobile.ui.screen.settings

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.R
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.ui.component.AppSnackbarHost
import com.meapet.mobile.ui.component.UndoSnackbarEffect
import com.meapet.mobile.ui.component.PrivacyPolicyContent
import com.meapet.mobile.ui.theme.findPreset
import com.meapet.mobile.ui.theme.isDarkTheme
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsUndo
import com.meapet.mobile.viewmodel.SettingsViewModel

/**
 * 设置页的二级页面。
 *
 * [ROOT] 是入口列表，其余各项对应一个功能域子页。枚举顺序即列表顺序，也被
 * [AnimatedContent] 用来判断进入/返回方向，故 [POLICY] 须排在 [ABOUT] 之后。
 */
private enum class SettingsPage(@StringRes val titleRes: Int) {
    ROOT(R.string.settings_title),
    PROVIDER(R.string.settings_provider),
    CHAT(R.string.settings_chat),
    APPEARANCE(R.string.settings_appearance),
    VOICE(R.string.settings_voice),
    ABOUT(R.string.settings_about),
    POLICY(R.string.settings_privacy_policy),
}

/**
 * 设置页面：入口列表 + 若干二级子页。
 *
 * @param onBack 从根页返回（退出设置）
 * @param onExitApp 取消数据采集授权后终止进程
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onExitApp: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val state by settingsViewModel.state.collectAsState()

    val darkTheme = isDarkTheme(state.themeMode)

    var page by remember { mutableStateOf(SettingsPage.ROOT) }

    val snackbarHostState = remember { SnackbarHostState() }

    val local = rememberSettingsLocalState(state)

    UndoSnackbarEffect(
        pending = state.pendingUndo,
        hostState = snackbarHostState,
        onUndo = { undo ->
            settingsViewModel.undoPending()
            if (undo is SettingsUndo.ResetPrompt) local.systemPrompt = undo.previousPrompt
        }
    )

    DisposableEffect(Unit) {
        onDispose { local.persist(settingsViewModel) }
    }

    LaunchedEffect(state.model) {
        if (local.model != state.model) local.model = state.model
    }

    LaunchedEffect(page) {
        if (page != SettingsPage.ABOUT) settingsViewModel.dismissUpdateMessage()
    }

    BackHandler(enabled = page != SettingsPage.ROOT) {
        page = if (page == SettingsPage.POLICY) SettingsPage.ABOUT else SettingsPage.ROOT
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.titleRes)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            page = when (page) {
                                SettingsPage.ROOT -> {
                                    onBack()
                                    SettingsPage.ROOT
                                }
                                SettingsPage.POLICY -> SettingsPage.ABOUT
                                else -> SettingsPage.ROOT
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = ALPHA_TOP_BAR)
                )
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                if (forward) {
                    (slideInHorizontally { w -> w } + fadeIn())
                        .togetherWith(slideOutHorizontally { w -> -w / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { w -> -w } + fadeIn())
                        .togetherWith(slideOutHorizontally { w -> w / 3 } + fadeOut())
                }
            },
            label = "settingsPageTransition"
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                when (current) {
                    SettingsPage.ROOT -> RootPage(state = state, onNavigate = { page = it })

                    SettingsPage.PROVIDER -> {
                        SettingsCard { ApiConfigSection(state, settingsViewModel, local) }
                        SettingsCard {
                            ModelParamsSection(state, settingsViewModel, local, darkTheme)
                        }
                        SettingsCard {
                            TelegramBotSection(state, settingsViewModel, local, darkTheme)
                        }
                    }

                    SettingsPage.CHAT -> {
                        SettingsCard {
                            SystemPromptSection(
                                viewModel = settingsViewModel,
                                local = local
                            )
                        }
                        SettingsCard { MemorySection(state, settingsViewModel, local, darkTheme) }
                    }

                    SettingsPage.APPEARANCE -> {
                        SettingsCard {
                            DisplayModeSection(
                                state = state,
                                viewModel = settingsViewModel,
                                onLaunchFloatingWindow = onToggleOverlay
                            )
                        }
                        SettingsCard {
                            LanguageSection(state = state, viewModel = settingsViewModel)
                        }
                        SettingsCard {
                            Live2dModelSection(state = state, viewModel = settingsViewModel)
                        }
                        SettingsCard {
                            AppearanceSection(
                                state = state,
                                viewModel = settingsViewModel,
                                darkTheme = darkTheme
                            )
                        }
                    }

                    SettingsPage.VOICE -> {
                        SettingsCard { TtsSection(state, settingsViewModel, darkTheme) }
                    }

                    SettingsPage.ABOUT -> {
                        SettingsCard { AppInfoSection() }
                        SettingsCard { UpdateSection(state, settingsViewModel, darkTheme) }
                        SettingsCard {
                            PrivacySection(
                                state = state,
                                viewModel = settingsViewModel,
                                onOpenPrivacyPolicy = { page = SettingsPage.POLICY },
                                onExitApp = onExitApp
                            )
                        }
                        SettingsCard { AttributionSection() }
                    }

                    SettingsPage.POLICY -> PrivacyPolicyContent()
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 入口列表：五个功能域，每项显示当前状态摘要。 */
@Composable
private fun RootPage(
    state: SettingsUiState,
    onNavigate: (SettingsPage) -> Unit
) {
    val entries = listOf(
        SettingsEntry(
            page = SettingsPage.PROVIDER,
            iconRes = R.drawable.ic_settings_provider,
            summary = state.model.ifBlank { stringResource(R.string.settings_summary_no_model) }
        ),
        SettingsEntry(
            page = SettingsPage.CHAT,
            iconRes = R.drawable.ic_settings_chat,
            summary = if (state.enableMemory) stringResource(R.string.settings_summary_chat_on)
                      else stringResource(R.string.settings_summary_chat_off)
        ),
        SettingsEntry(
            page = SettingsPage.APPEARANCE,
            iconRes = R.drawable.ic_settings_appearance,
            summary = appearanceSummary(state)
        ),
        SettingsEntry(
            page = SettingsPage.VOICE,
            iconRes = R.drawable.ic_settings_voice,
            summary = if (state.ttsMainEnabled || state.ttsOverlayEnabled) {
                stringResource(R.string.settings_summary_voice_on)
            } else {
                stringResource(R.string.settings_summary_voice_off)
            }
        ),
        SettingsEntry(
            page = SettingsPage.ABOUT,
            iconRes = R.drawable.ic_settings_about,
            summary = if (state.appVersion.isBlank()) {
                stringResource(R.string.settings_summary_about_default)
            } else {
                stringResource(R.string.settings_summary_about_version, state.appVersion)
            }
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                SettingsEntryRow(entry = entry, onClick = { onNavigate(entry.page) })
                if (index != entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                            .copy(alpha = ALPHA_DIVIDER)
                    )
                }
            }
        }
    }
}

/** 入口列表的一行数据。 */
private data class SettingsEntry(
    val page: SettingsPage,
    val iconRes: Int,
    val summary: String,
)

/** 入口行：MD3 ListItem（前置图标 + 标题 + 摘要 + 右侧箭头）。 */
@Composable
private fun SettingsEntryRow(
    entry: SettingsEntry,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                painter = painterResource(entry.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        },
        headlineContent = { Text(stringResource(entry.page.titleRes)) },
        supportingContent = {
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_FAINT_TEXT)
            )
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.size(20.dp)
            )
        }
    )
}

/** 外观入口摘要：显示模式 + 主题模式 + 配色来源。 */
@SuppressLint("ComposableNaming")
@Composable
private fun appearanceSummary(state: SettingsUiState): String {
    val display = if (state.displayMode == SettingsKeys.DISPLAY_MODE_IMMERSIVE) {
        stringResource(R.string.display_mode_immersive_title)
    } else {
        stringResource(R.string.display_mode_normal_title)
    }
    val mode = when (state.themeMode) {
        "light" -> stringResource(R.string.theme_mode_light)
        "dark" -> stringResource(R.string.theme_mode_dark)
        else -> stringResource(R.string.theme_mode_system)
    }
    val color = if (state.enableDynamicColor) {
        stringResource(R.string.settings_summary_dynamic_color)
    } else {
        stringResource(findPreset(state.colorPreset).nameRes)
    }
    return "$display · $mode · $color"
}
