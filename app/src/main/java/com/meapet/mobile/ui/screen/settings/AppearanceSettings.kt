package com.meapet.mobile.ui.screen.settings

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.meapet.mobile.R
import com.meapet.mobile.core.LocaleHelper
import com.meapet.mobile.live2d.AvatarRenderType
import com.meapet.mobile.live2d.Live2dModelStore
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.ui.theme.THEME_PRESETS
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════
//  外观：显示模式、语言、Live2D 模型、背景壁纸、气泡透明度与主题
// ═══════════════════════════════════════════════════

@Composable
internal fun DisplayModeSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onLaunchFloatingWindow: () -> Unit
) {
    SectionTitle(stringResource(R.string.display_mode_section_title))
    Text(
        text = stringResource(R.string.display_mode_section_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 10.dp)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayModeOptionCard(
            title = stringResource(R.string.display_mode_normal_title),
            description = stringResource(R.string.display_mode_normal_desc),
            selected = state.displayMode != SettingsKeys.DISPLAY_MODE_IMMERSIVE,
            onClick = { viewModel.updateDisplayMode(SettingsKeys.DISPLAY_MODE_NORMAL) }
        )
        DisplayModeOptionCard(
            title = stringResource(R.string.display_mode_immersive_title),
            description = stringResource(R.string.display_mode_immersive_desc),
            selected = state.displayMode == SettingsKeys.DISPLAY_MODE_IMMERSIVE,
            onClick = { viewModel.updateDisplayMode(SettingsKeys.DISPLAY_MODE_IMMERSIVE) }
        )
        DisplayModeOptionCard(
            title = stringResource(R.string.display_mode_floating_title),
            description = stringResource(R.string.display_mode_floating_desc),
            selected = false,
            onClick = onLaunchFloatingWindow
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DisplayModeOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG_MID)
        },
        shape = RoundedCornerShape(12.dp),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
                    }
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.action_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val activity = context as? Activity

    SectionTitle(stringResource(R.string.language_section_title))
    Text(
        text = stringResource(R.string.language_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    val options = listOf(
        LocaleHelper.LANG_SYSTEM to R.string.language_system,
        LocaleHelper.LANG_FR to R.string.language_fr,
        LocaleHelper.LANG_EN to R.string.language_en,
        LocaleHelper.LANG_ES to R.string.language_es,
        LocaleHelper.LANG_ZH to R.string.language_zh
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        options.forEach { (code, labelRes) ->
            FilterChipLike(
                label = stringResource(labelRes),
                selected = state.appLanguage == code,
                enabled = true,
                onClick = {
                    if (state.appLanguage != code) {
                        LocaleHelper.setLanguage(context, code)
                        viewModel.updateAppLanguage(code)
                        activity?.recreate()
                    }
                }
            )
        }
    }
}

@Composable
internal fun Live2dModelSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val pickModelZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importLive2dModelZip(it) }
    }

    SectionTitle(stringResource(R.string.live2d_section_title))
    Text(
        text = stringResource(R.string.live2d_section_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.live2dModels.forEach { model ->
            val isActive = model.id == state.activeLive2dModelId
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectLive2dModel(model.id) },
                color = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG_MID)
                },
                shape = RoundedCornerShape(12.dp),
                border = if (isActive) {
                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val label = when {
                            model.id == Live2dModelStore.ALITA_SPRITE_MODEL_ID ->
                                stringResource(R.string.live2d_alita_builtin_name)
                            model.id == Live2dModelStore.ALITA_FACE_MODEL_ID ->
                                stringResource(R.string.live2d_alita_face_builtin_name)
                            model.id == Live2dModelStore.DESK_BUDDY_PIXEL_MODEL_ID ->
                                stringResource(R.string.live2d_desk_buddy_pixel_name)
                            model.id == Live2dModelStore.DESK_BUDDY_VECTOR_MODEL_ID ->
                                stringResource(R.string.live2d_desk_buddy_vector_name)
                            model.id == Live2dModelStore.DESK_BUDDY_ALITA_EYES_MODEL_ID ->
                                stringResource(R.string.live2d_desk_buddy_alita_name)
                            model.isBuiltIn ->
                                stringResource(R.string.live2d_builtin_name)
                            else ->
                                model.displayName
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val modeBadgeRes = when (model.renderType) {
                            AvatarRenderType.SPRITE_2D -> R.string.live2d_sprite_2d_badge
                            AvatarRenderType.ALITA_FACE_LIVE2D -> R.string.live2d_face_live2d_badge
                            AvatarRenderType.DESK_BUDDY_PIXEL,
                            AvatarRenderType.DESK_BUDDY_VECTOR,
                            AvatarRenderType.DESK_BUDDY_ALITA_EYES -> R.string.live2d_desk_buddy_oled_badge
                            AvatarRenderType.LIVE2D -> null
                        }
                        if (isActive || modeBadgeRes != null) {
                            val subText = buildString {
                                if (isActive) append(stringResource(R.string.live2d_active_badge))
                                if (modeBadgeRes != null) {
                                    if (isNotEmpty()) append(" · ")
                                    append(stringResource(modeBadgeRes))
                                }
                            }
                            Text(
                                text = subText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
                                }
                            )
                        }
                    }
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.live2d_active_badge),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (!model.isBuiltIn) {
                        IconButton(
                            onClick = { viewModel.deleteLive2dModel(model.id) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    OutlinedButton(
        onClick = {
            pickModelZip.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        },
        enabled = !state.live2dImporting,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.live2dImporting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.live2d_importing))
        } else {
            Text(stringResource(R.string.live2d_import_zip))
        }
    }

    state.live2dStatusMessage?.let { msg ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.dismissLive2dFeedback() }
        )
    }

    state.live2dErrorMessage?.let { err ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.dismissLive2dFeedback() }
        )
    }

    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun BackgroundWallpaperSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    SectionTitle(stringResource(R.string.wallpaper_section_title))
    Text(
        text = stringResource(R.string.wallpaper_section_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.importWallpaper(it) }
    }

    // ── 模糊强度（本地拖动状态）──
    // 仅在有壁纸时启用。用本地 remember 拖动顺滑，松手才落盘（与语速/透明度一致）。
    // 先于预览声明，让预览在拖动时实时跟随。
    val blur = state.wallpaperBlur.toFloat().coerceIn(0f, 1f)
    var localBlur by remember { mutableStateOf(blur) }
    LaunchedEffect(state.wallpaperBlur) {
        if (localBlur != state.wallpaperBlur.toFloat()) {
            localBlur = state.wallpaperBlur.toFloat()
        }
    }

    // 源缩略图（未模糊）：路径不变就复用，避免随滑杆拖动反复解码原图
    var sourceThumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state.wallpaperPath) {
        sourceThumb = if (state.wallpaperPath.isBlank()) null
        else withContext(Dispatchers.IO) { decodeThumbnail(state.wallpaperPath, 480) }
    }

    // 实时预览：模糊强度（localBlur）变化即在 IO 线程重算；0 = 直接显示源图。
    // σ 与 GL 渲染同源：blur→σ = 12·√blur（见 Live2dDefine.blurToSigma），再按
    // 「缩略图宽 / 屏幕宽」等比换算，保证预览模糊观感与主界面一致。
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val preview by produceState<Bitmap?>(initialValue = sourceThumb, sourceThumb, localBlur) {
        val src = sourceThumb
        value = if (src == null || localBlur <= 0.001f) src
        else withContext(Dispatchers.IO) {
            val sigma = com.meapet.mobile.live2d.Live2dDefine.blurToSigma(localBlur) *
                src.width / screenWidthPx
            gaussianBlurBitmap(src, sigma)
        }
    }

    // 预览盒纵横比（宽 / 高）：竖图源也按盒子比例做 cover 裁剪，不溢出盒高、不叠到下方按钮。
    // 竖图放大到宽度填满盒宽后高度远超盒高，需按比例约束（aspectRatio 锁形状），
    // 再让 Image 在盒内居中铺满并裁掉超出的上/下部分。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.wallpaper_preview_cd),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT_RATIO)
                    .align(Alignment.Center),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = stringResource(R.string.wallpaper_default_solid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.wallpaper_pick_gallery))
        }
        OutlinedButton(
            onClick = { viewModel.clearWallpaper() },
            enabled = state.wallpaperPath.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_restore_default))
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wallpaper_blur_label, kotlin.math.round(localBlur * 100).toInt()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (state.wallpaperPath.isNotBlank()) 1f else 0.5f
        )
    )
    Slider(
        value = localBlur,
        onValueChange = { localBlur = it },
        onValueChangeFinished = {
            viewModel.updateWallpaperBlur(localBlur.toDouble())
        },
        valueRange = WALLPAPER_BLUR_RANGE,
        steps = WALLPAPER_BLUR_STEPS,
        enabled = state.wallpaperPath.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )
}

/** 按 maxDim 限制采样解码缩略图。 */
private fun decodeThumbnail(path: String, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}

/**
 * 可分离高斯模糊（CPU，两遍同轴，约 3σ 截断）。
 *
 * 仅在 IO 线程调用（随滑杆拖动时也会触发，保持 480px 缩略图尺度、单遍 O(n) 成本）。
 * 内核 tap 数随 σ 自适应（与 GL 33-tap / σ≤16px 的口径一致）；σ≤0.6 时截断半径不足
 * 1 像素，直接返回原图避免画质损失与多余开销。
 */
private fun gaussianBlurBitmap(src: Bitmap, sigma: Float): Bitmap {
    val w = src.width
    val h = src.height
    if (sigma <= 0.6f) return src

    val sigmaC = sigma.coerceIn(0.6f, 16f)
    val radius = (sigmaC * 3f).toInt().coerceIn(1, 33)
    val half = radius.coerceAtMost(33)

    // 预计算 (2*half+1)-tap 高斯权重（对称），并归一化
    val weights = FloatArray(2 * half + 1)
    var sum = 0f
    val twoSigma2 = 2f * sigmaC * sigmaC
    for (i in -half..half) {
        val wgt = kotlin.math.exp(-(i * i) / twoSigma2)
        weights[i + half] = wgt
        sum += wgt
    }
    for (i in weights.indices) weights[i] /= sum

    val srcPx = IntArray(w * h)
    src.getPixels(srcPx, 0, w, 0, 0, w, h)

    // 第 1 遍：水平模糊
    val tmp = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (i in -half..half) {
                val px = srcPx[row + (x + i).coerceIn(0, w - 1)]
                val wt = weights[i + half]
                a += ((px ushr 24) and 0xFF) * wt
                r += ((px ushr 16) and 0xFF) * wt
                g += ((px ushr 8) and 0xFF) * wt
                b += (px and 0xFF) * wt
            }
            tmp[row + x] = ((a.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    // 第 2 遍：垂直模糊，直接写入结果
    val outPx = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (i in -half..half) {
                val px = tmp[(y + i).coerceIn(0, h - 1) * w + x]
                val wt = weights[i + half]
                a += ((px ushr 24) and 0xFF) * wt
                r += ((px ushr 16) and 0xFF) * wt
                g += ((px ushr 8) and 0xFF) * wt
                b += (px and 0xFF) * wt
            }
            outPx[y * w + x] = ((a.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
}

/** 外观：背景壁纸 + 聊天气泡透明度 + 主题（模式 / 动态颜色 / 颜色预设）。 */
@Composable
internal fun AppearanceSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    // ── 背景壁纸 ──
    BackgroundWallpaperSection(state, viewModel, darkTheme)

    Spacer(Modifier.height(12.dp))

    // ── 聊天气泡 ──
    // 透明度滑杆（0.2~1.0）。用本地 remember 拖动顺滑，松手才落盘（与语速一致）。
    val alpha = state.chatBubbleAlpha.toFloat().coerceIn(0.2f, 1.0f)
    var localAlpha by remember { mutableStateOf(alpha) }
    LaunchedEffect(state.chatBubbleAlpha) {
        if (localAlpha != state.chatBubbleAlpha.toFloat()) {
            localAlpha = state.chatBubbleAlpha.toFloat()
        }
    }

    Text(
        text = stringResource(R.string.chat_bubble_alpha_label, (localAlpha * 100).toInt()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = localAlpha,
        onValueChange = { localAlpha = it },
        onValueChangeFinished = {
            viewModel.updateChatBubbleAlpha(localAlpha.toDouble())
        },
        valueRange = CHAT_BUBBLE_ALPHA_RANGE,
        steps = CHAT_BUBBLE_ALPHA_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.theme_section_title))

    ThemeModeSelector(
        current = state.themeMode,
        onSelect = { viewModel.updateThemeMode(it) }
    )

    Spacer(Modifier.height(12.dp))

    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsSwitchRow(
        label = stringResource(R.string.theme_dynamic_color),
        description = if (dynamicColorSupported) {
            stringResource(R.string.theme_dynamic_color_on_desc)
        } else {
            stringResource(R.string.theme_dynamic_color_off_desc)
        },
        checked = state.enableDynamicColor && dynamicColorSupported,
        darkTheme = darkTheme,
        onCheckedChange = { if (dynamicColorSupported) viewModel.updateEnableDynamicColor(it) },
        enabled = dynamicColorSupported
    )

    AnimatedVisibility(
        visible = !(state.enableDynamicColor && dynamicColorSupported),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        ColorPresetSelector(
            currentPreset = state.colorPreset,
            onSelect = { viewModel.updateColorPreset(it) }
        )
    }
}

@Composable
private fun ThemeModeSelector(
    current: String,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        "system" to stringResource(R.string.theme_mode_system),
        "light" to stringResource(R.string.theme_mode_light),
        "dark" to stringResource(R.string.theme_mode_dark)
    )
    var expanded by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableStateOf(0) }
    var boxHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            animProgress.animateTo(1f, animationSpec = tween(200))
        } else if (showPopup) {
            animProgress.animateTo(0f, animationSpec = tween(200))
            showPopup = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                boxWidthPx = it.size.width
                boxHeightPx = it.size.height
            }
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == current }?.second ?: options.first().second,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.theme_mode_label)) },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                                  else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = !expanded }
        )

        if (showPopup) {
            val popupWidth = with(density) { boxWidthPx.toDp().coerceAtLeast(160.dp) }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x = 0, y = boxHeightPx + 4),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    modifier = Modifier
                        .width(popupWidth)
                        .graphicsLayer {
                            alpha = animProgress.value
                            scaleX = 0.95f + 0.05f * animProgress.value
                            scaleY = 0.95f + 0.05f * animProgress.value
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Column {
                        options.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onSelect(value); expanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresetSelector(
    currentPreset: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.theme_presets_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            THEME_PRESETS.forEach { preset ->
                val isSelected = preset.id == currentPreset

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(preset.id) }
                        .width(56.dp)
                ) {
                    val borderMod = if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(preset.seed)
                            .then(borderMod)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(preset.nameRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
