package com.meapet.mobile.live2d

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class DeskBuddyStyle {
    PIXEL_OLED,
    VECTOR_SMOOTH,
    ALITA_CYBER_EYES;

    companion object {
        fun fromRenderType(renderType: AvatarRenderType): DeskBuddyStyle = when (renderType) {
            AvatarRenderType.DESK_BUDDY_PIXEL -> PIXEL_OLED
            AvatarRenderType.DESK_BUDDY_VECTOR -> VECTOR_SMOOTH
            AvatarRenderType.DESK_BUDDY_ALITA_EYES -> ALITA_CYBER_EYES
            else -> VECTOR_SMOOTH
        }
    }
}

enum class DeskBuddyBadge {
    NONE,
    QUESTION,
    ALERT,
    SLEEP_ZZ,
    SPARKLE,
    MESSAGE_BUBBLE,
    HEART
}

enum class DeskBuddyEyeShape {
    NORMAL,
    CURIOUS,
    ALERT,
    WIDE_EYES,
    BLUSH,
    HAPPY_ARC,
    CALM_CLOSED,
    SLEEPY,
    ANGRY,
    SLY_MALICE,
    WINK_LEFT,
    FOCUSED
}

data class DeskBuddyEyePreset(
    val canonicalEmotion: String,
    val shape: DeskBuddyEyeShape,
    val badge: DeskBuddyBadge,
    val isAlertColor: Boolean = false,
    val isDimmed: Boolean = false,
    val hasBlush: Boolean = false,
    val brightness: Float = 1.0f,
    val openLeft: Float = 1.0f,
    val openRight: Float = 1.0f
)

data class DeskBuddyGazeState(
    val x: Float,
    val y: Float
)

/**
 * Renders the 3 Desk Buddy OLED eyes-only models (`PIXEL_OLED`, `VECTOR_SMOOTH`, `ALITA_CYBER_EYES`)
 * on a pure `#000000` OLED battery-saving background with:
 * - Periodic left-right eye gaze movement + touch drag tracking
 * - Natural eyelid blinking (with occasional double-blinks)
 * - Notification-reactive expressions & subtle glyphs (`?`, `!`, message bubble, `Zz`, sparkle)
 */
class DeskBuddyEyesRenderer {

    private var style: DeskBuddyStyle = DeskBuddyStyle.VECTOR_SMOOTH
    private var loaded = false

    @Volatile
    private var currentEmotion = Live2dEmotion.EMOTION_NORMAL
    private var emotionChangedAtMs = 0L

    private var textureId = 0
    private var textureInitialized = false
    private var sprite: Live2dSprite? = null
    private var spriteProgramId = 0

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private var smoothGazeX = 0f
    private var smoothGazeY = 0f
    private var lastFrameMs = 0L

    // Reusable Paints (GL thread only)
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }
    private val glowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rectTmp = RectF()
    private val pathTmp = Path()

    fun isLoaded(): Boolean = loaded

    fun load(renderType: AvatarRenderType): Boolean {
        style = DeskBuddyStyle.fromRenderType(renderType)
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap!!)
        }
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            textureInitialized = false
        }
        lastFrameMs = SystemClock.uptimeMillis()
        loaded = textureId != 0 && bitmap != null
        return loaded
    }

    fun reset() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        textureInitialized = false
        sprite = null
        spriteProgramId = 0
        bitmap?.recycle()
        bitmap = null
        canvas = null
        loaded = false
        smoothGazeX = 0f
        smoothGazeY = 0f
        lastFrameMs = 0L
    }

    fun setEmotion(emotion: String) {
        val canonical = Live2dEmotion.normalizeEmotionName(emotion)
        if (canonical != currentEmotion) {
            currentEmotion = canonical
            emotionChangedAtMs = SystemClock.uptimeMillis()
        }
    }

    fun draw(
        viewWidth: Int,
        viewHeight: Int,
        shaderProgramId: Int,
        dragX: Float,
        dragY: Float,
        opacity: Float = 1f,
        isOverlay: Boolean = false,
        isImmersive: Boolean = false
    ) {
        if (!loaded || viewWidth <= 0 || viewHeight <= 0 || shaderProgramId == 0 || textureId == 0) return
        val bmp = bitmap ?: return
        val cvs = canvas ?: return

        val nowMs = SystemClock.uptimeMillis()
        val dtSec = if (lastFrameMs > 0L) ((nowMs - lastFrameMs).coerceIn(1L, 80L) / 1000f) else 0.016f
        lastFrameMs = nowMs

        val effectiveEmotion = computeIdleExpression(nowMs, currentEmotion)
        val preset = resolveEyePreset(effectiveEmotion)
        val idleGaze = computeIdleGaze(nowMs, preset.canonicalEmotion)
        val hasDrag = abs(dragX) > 0.02f || abs(dragY) > 0.02f
        val targetX = if (hasDrag) dragX.coerceIn(-1f, 1f) else idleGaze.x
        val targetY = if (hasDrag) dragY.coerceIn(-0.6f, 0.6f) else idleGaze.y
        val lerpAlpha = (dtSec * if (hasDrag) 14f else 6.5f).coerceIn(0.05f, 0.55f)
        smoothGazeX += (targetX - smoothGazeX) * lerpAlpha
        smoothGazeY += (targetY - smoothGazeY) * lerpAlpha

        val blinkWeight = computeBlinkWeight(nowMs, preset.canonicalEmotion)
        val pulse = 0.5f + 0.5f * sin(nowMs * 0.0045).toFloat()

        // Render procedurally onto 640x360 canvas
        renderCanvas(
            cvs = cvs,
            preset = preset,
            gazeX = smoothGazeX,
            gazeY = smoothGazeY,
            blinkWeight = blinkWeight,
            pulse = pulse,
            isOverlay = isOverlay
        )

        // Upload frame to GL texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (!textureInitialized) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            textureInitialized = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }

        // Compute responsive viewport rect
        val texAspect = TEX_WIDTH.toFloat() / TEX_HEIGHT.toFloat()
        val quadW: Float
        val quadH: Float
        val centerY: Float
        when {
            isOverlay -> {
                val maxW = viewWidth * 0.96f
                val maxH = viewHeight * 0.94f
                if (maxW / maxH > texAspect) {
                    quadH = maxH
                    quadW = quadH * texAspect
                } else {
                    quadW = maxW
                    quadH = quadW / texAspect
                }
                centerY = viewHeight * 0.50f
            }
            isImmersive -> {
                quadW = min(viewWidth * 0.94f, viewHeight * 0.42f * texAspect)
                quadH = quadW / texAspect
                centerY = viewHeight * 0.56f
            }
            else -> {
                // Normal mode: place cleanly in top pure-black OLED zone above notification bubble & chat
                quadW = min(viewWidth * 0.86f, viewHeight * 0.22f * texAspect)
                quadH = quadW / texAspect
                centerY = viewHeight * 0.79f
            }
        }
        val centerX = viewWidth * 0.50f

        if (sprite == null || spriteProgramId != shaderProgramId) {
            sprite = Live2dSprite(centerX, centerY, quadW, quadH, textureId, shaderProgramId)
            spriteProgramId = shaderProgramId
        } else {
            sprite?.resize(centerX, centerY, quadW, quadH)
        }

        GLES20.glUseProgram(shaderProgramId)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val effectiveOpacity = opacity.coerceIn(0f, 1f)
        sprite?.apply {
            setWindowSize(viewWidth, viewHeight)
            setColor(effectiveOpacity, effectiveOpacity, effectiveOpacity, effectiveOpacity)
            renderImmediate(textureId, UV_FULL)
        }
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun renderCanvas(
        cvs: Canvas,
        preset: DeskBuddyEyePreset,
        gazeX: Float,
        gazeY: Float,
        blinkWeight: Float,
        pulse: Float,
        isOverlay: Boolean
    ) {
        if (isOverlay) {
            cvs.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            rectTmp.set(6f, 6f, TEX_WIDTH - 6f, TEX_HEIGHT - 6f)
            solidPaint.color = Color.BLACK
            cvs.drawRoundRect(rectTmp, 38f, 38f, solidPaint)
            strokePaint.color = if (preset.isAlertColor) {
                Color.argb(145, 255, 112, 67)
            } else {
                Color.argb(110, 56, 189, 248)
            }
            strokePaint.strokeWidth = 3f
            cvs.drawRoundRect(rectTmp, 38f, 38f, strokePaint)
        } else {
            cvs.drawColor(Color.BLACK)
        }

        val leftOpen = (preset.openLeft * (1f - blinkWeight)).coerceIn(0f, 1f)
        val rightOpen = (preset.openRight * (1f - blinkWeight)).coerceIn(0f, 1f)

        val primaryColor = when {
            preset.isAlertColor -> Color.rgb(255, 118, 56)
            preset.shape == DeskBuddyEyeShape.ANGRY -> Color.rgb(255, 86, 72)
            preset.isDimmed -> Color.rgb(92, 198, 224)
            else -> Color.rgb(42, 232, 255)
        }
        val coreColor = when {
            preset.isAlertColor -> Color.rgb(255, 212, 168)
            preset.shape == DeskBuddyEyeShape.ANGRY -> Color.rgb(255, 186, 174)
            preset.isDimmed -> Color.rgb(154, 224, 240)
            else -> Color.rgb(214, 252, 255)
        }

        when (style) {
            DeskBuddyStyle.PIXEL_OLED -> drawPixelOledEyes(
                cvs, preset, gazeX, gazeY, leftOpen, rightOpen, primaryColor, coreColor, pulse
            )
            DeskBuddyStyle.VECTOR_SMOOTH -> drawVectorSmoothEyes(
                cvs, preset, gazeX, gazeY, leftOpen, rightOpen, primaryColor, coreColor, pulse
            )
            DeskBuddyStyle.ALITA_CYBER_EYES -> drawMangaRobotEyes(
                cvs, preset, gazeX, gazeY, leftOpen, rightOpen, primaryColor, coreColor, pulse
            )
        }

        if (preset.hasBlush) {
            drawBlushCheeks(cvs, style, pulse)
        }

        drawNotificationBadge(cvs, preset.badge, primaryColor, coreColor, pulse)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Option 1: Desk Buddy Pixel-LED (Dasai Mochi / Retro OLED Matrix)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun drawPixelOledEyes(
        cvs: Canvas,
        preset: DeskBuddyEyePreset,
        gazeX: Float,
        gazeY: Float,
        leftOpen: Float,
        rightOpen: Float,
        primaryColor: Int,
        coreColor: Int,
        pulse: Float
    ) {
        val cols = 13
        val rows = 11
        val cellStep = 14.5f
        val cellSize = 11.4f
        val eyeW = cols * cellStep
        val eyeH = rows * cellStep
        val leftCenterX = TEX_WIDTH * 0.34f
        val rightCenterX = TEX_WIDTH * 0.66f
        val centerY = TEX_HEIGHT * 0.50f

        val dimLedColor = Color.argb(
            if (preset.isDimmed) 85 else 135,
            Color.red(primaryColor),
            Color.green(primaryColor),
            Color.blue(primaryColor)
        )

        drawSinglePixelEye(
            cvs = cvs,
            cx = leftCenterX,
            cy = centerY,
            eyeW = eyeW,
            eyeH = eyeH,
            cols = cols,
            rows = rows,
            cellStep = cellStep,
            cellSize = cellSize,
            isLeft = true,
            shape = preset.shape,
            openFactor = leftOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            litColor = primaryColor,
            brightColor = coreColor,
            dimColor = dimLedColor,
            pulse = pulse
        )
        drawSinglePixelEye(
            cvs = cvs,
            cx = rightCenterX,
            cy = centerY,
            eyeW = eyeW,
            eyeH = eyeH,
            cols = cols,
            rows = rows,
            cellStep = cellStep,
            cellSize = cellSize,
            isLeft = false,
            shape = preset.shape,
            openFactor = rightOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            litColor = primaryColor,
            brightColor = coreColor,
            dimColor = dimLedColor,
            pulse = pulse
        )
    }

    private fun drawSinglePixelEye(
        cvs: Canvas,
        cx: Float,
        cy: Float,
        eyeW: Float,
        eyeH: Float,
        cols: Int,
        rows: Int,
        cellStep: Float,
        cellSize: Float,
        isLeft: Boolean,
        shape: DeskBuddyEyeShape,
        openFactor: Float,
        gazeX: Float,
        gazeY: Float,
        litColor: Int,
        brightColor: Int,
        dimColor: Int,
        pulse: Float
    ) {
        val originX = cx - eyeW * 0.5f + gazeX * 16f
        val originY = cy - eyeH * 0.5f - gazeY * 10f
        val midC = (cols - 1) * 0.5f
        val midR = (rows - 1) * 0.5f
        val pupilCx = midC + gazeX * 2.4f
        val pupilCy = midR - gazeY * 1.6f

        glowPaint.color = Color.argb(65, Color.red(litColor), Color.green(litColor), Color.blue(litColor))

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val nx = (c - midC) / midC
                val ny = (r - midR) / midR
                var state = 0 // 0=off, 1=dim, 2=lit, 3=bright

                when (shape) {
                    DeskBuddyEyeShape.HAPPY_ARC -> {
                        val arcY = -0.35f + 0.95f * nx * nx
                        if (abs(ny - arcY) <= 0.28f && abs(nx) <= 0.92f) state = 2
                    }
                    DeskBuddyEyeShape.BLUSH -> {
                        val arcY = -0.25f + 0.85f * nx * nx
                        if (abs(ny - arcY) <= 0.30f && abs(nx) <= 0.94f) {
                            state = if (abs(ny - arcY) <= 0.14f) 3 else 2
                        }
                    }
                    DeskBuddyEyeShape.WIDE_EYES -> {
                        val ellipse = (nx * nx) / 1.15f + (ny * ny) / 1.15f
                        if (ellipse <= 1.05f) {
                            val pDist = ((c - pupilCx) * (c - pupilCx) + (r - pupilCy) * (r - pupilCy))
                            state = if (pDist <= 1.25f) 0 else if (pDist <= 3.6f && r < pupilCy) 3 else 2
                        }
                    }
                    DeskBuddyEyeShape.CALM_CLOSED -> {
                        val arcY = 0.28f - 0.58f * nx * nx
                        if (abs(ny - arcY) <= 0.24f && abs(nx) <= 0.88f) state = 1
                    }
                    DeskBuddyEyeShape.WINK_LEFT -> {
                        if (isLeft || openFactor < 0.25f) {
                            val arcY = -0.32f + 0.90f * nx * nx
                            if (abs(ny - arcY) <= 0.28f && abs(nx) <= 0.92f) state = 2
                        } else {
                            state = evalOpenPixelCell(nx, ny, c, r, pupilCx, pupilCy, 1.0f, openFactor)
                        }
                    }
                    DeskBuddyEyeShape.ALERT -> {
                        val dist = abs(nx) * 0.92f + abs(ny) * 0.92f
                        if (dist <= 1.15f) {
                            val pDist = ((c - pupilCx) * (c - pupilCx) + (r - pupilCy) * (r - pupilCy))
                            state = if (pDist <= 2.6f) 0 else if (pulse > 0.55f) 3 else 2
                        }
                    }
                    DeskBuddyEyeShape.ANGRY -> {
                        val slope = if (isLeft) (nx * 0.45f) else (-nx * 0.45f)
                        val insideTop = ny >= -0.55f + slope
                        val insideEllipse = (nx * nx) / 1.0f + (ny * ny) / 0.85f <= 1.05f
                        if (insideEllipse && insideTop) {
                            val pDist = ((c - pupilCx) * (c - pupilCx) + (r - pupilCy) * (r - pupilCy))
                            state = if (pDist <= 2.2f) 0 else 2
                        }
                    }
                    DeskBuddyEyeShape.SLEEPY -> {
                        val insideEllipse = (nx * nx) / 1.05f + (ny * ny) / 0.95f <= 1.05f
                        if (insideEllipse) {
                            if (ny < 0.05f) state = 1
                            else state = 2
                        }
                    }
                    DeskBuddyEyeShape.CURIOUS -> {
                        val radiusMod = if (isLeft) 1.08f else 0.86f
                        state = evalOpenPixelCell(nx, ny, c, r, pupilCx, pupilCy, radiusMod, openFactor)
                    }
                    else -> {
                        val eyeRadius = if (shape == DeskBuddyEyeShape.FOCUSED) 0.92f else 1.0f
                        val verticalScale = when (shape) {
                            DeskBuddyEyeShape.FOCUSED -> 0.72f
                            DeskBuddyEyeShape.SLY_MALICE -> if (isLeft) 0.58f else 0.95f
                            else -> 1.0f
                        }
                        state = evalOpenPixelCell(
                            nx, ny, c, r, pupilCx, pupilCy, eyeRadius, openFactor * verticalScale
                        )
                    }
                }

                if (state == 0) continue

                val px = originX + c * cellStep
                val py = originY + r * cellStep
                rectTmp.set(px, py, px + cellSize, py + cellSize)

                if (state >= 2) {
                    cvs.drawRoundRect(rectTmp, 3.5f, 3.5f, glowPaint)
                }
                solidPaint.color = when (state) {
                    1 -> dimColor
                    3 -> brightColor
                    else -> litColor
                }
                cvs.drawRoundRect(rectTmp, 3.2f, 3.2f, solidPaint)
            }
        }
    }

    private fun evalOpenPixelCell(
        nx: Float,
        ny: Float,
        c: Int,
        r: Int,
        pupilCx: Float,
        pupilCy: Float,
        radiusScale: Float,
        openFactor: Float
    ): Int {
        if (openFactor <= 0.14f) {
            return if (abs(ny - 0.12f) <= 0.20f && abs(nx) <= 0.86f * radiusScale) 2 else 0
        }
        val maxY = -0.95f + (1f - openFactor) * 1.45f
        if (ny < maxY) return 0
        val ellipse = (nx * nx) / (1.02f * radiusScale) + (ny * ny) / (1.04f * radiusScale)
        if (ellipse > 1.02f) return 0
        val pDist = (c - pupilCx) * (c - pupilCx) + (r - pupilCy) * (r - pupilCy)
        return when {
            pDist <= 2.4f -> 0 // dark pupil cutout inside LED ring
            pDist <= 5.0f && r < pupilCy && c < pupilCx -> 3 // subtle highlight LED
            else -> 2
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Option 2: Desk Buddy Vector / EMO Smooth OLED Eyes
    // ─────────────────────────────────────────────────────────────────────────────

    private fun drawVectorSmoothEyes(
        cvs: Canvas,
        preset: DeskBuddyEyePreset,
        gazeX: Float,
        gazeY: Float,
        leftOpen: Float,
        rightOpen: Float,
        primaryColor: Int,
        coreColor: Int,
        pulse: Float
    ) {
        val leftX = TEX_WIDTH * 0.345f + gazeX * 18f
        val rightX = TEX_WIDTH * 0.655f + gazeX * 18f
        val baseY = TEX_HEIGHT * 0.50f - gazeY * 11f

        val leftScale = if (preset.shape == DeskBuddyEyeShape.CURIOUS) 1.12f else 1.0f
        val rightScale = if (preset.shape == DeskBuddyEyeShape.CURIOUS) 0.86f else 1.0f

        drawSingleVectorEye(
            cvs = cvs,
            cx = leftX,
            cy = baseY,
            scale = leftScale,
            isLeft = true,
            shape = preset.shape,
            openFactor = leftOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            primaryColor = primaryColor,
            coreColor = coreColor,
            pulse = pulse
        )
        drawSingleVectorEye(
            cvs = cvs,
            cx = rightX,
            cy = baseY,
            scale = rightScale,
            isLeft = false,
            shape = preset.shape,
            openFactor = rightOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            primaryColor = primaryColor,
            coreColor = coreColor,
            pulse = pulse
        )
    }

    private fun drawSingleVectorEye(
        cvs: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        isLeft: Boolean,
        shape: DeskBuddyEyeShape,
        openFactor: Float,
        gazeX: Float,
        gazeY: Float,
        primaryColor: Int,
        coreColor: Int,
        pulse: Float
    ) {
        val halfW = (if (shape == DeskBuddyEyeShape.WIDE_EYES) 74f else 66f) * scale
        val halfH = (if (shape == DeskBuddyEyeShape.WIDE_EYES) 76f else 66f) * scale

        glowPaint.color = Color.argb(90, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))

        when (shape) {
            DeskBuddyEyeShape.HAPPY_ARC, DeskBuddyEyeShape.BLUSH -> {
                drawSmoothArcEye(cvs, cx, cy + 8f, halfW * 1.02f, 34f, upward = true, primaryColor, coreColor, thickness = 20f)
                return
            }
            DeskBuddyEyeShape.CALM_CLOSED -> {
                val dim = Color.argb(175, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
                drawSmoothArcEye(cvs, cx, cy - 6f, halfW * 0.94f, 22f, upward = false, dim, dim, thickness = 14f)
                return
            }
            DeskBuddyEyeShape.WINK_LEFT -> {
                if (isLeft || openFactor < 0.22f) {
                    drawSmoothArcEye(cvs, cx, cy + 8f, halfW, 32f, upward = true, primaryColor, coreColor, thickness = 18f)
                    return
                }
            }
            DeskBuddyEyeShape.ALERT -> {
                val r = 56f + pulse * 4f
                pathTmp.reset()
                pathTmp.moveTo(cx, cy - r)
                pathTmp.lineTo(cx + r * 0.96f, cy)
                pathTmp.lineTo(cx, cy + r)
                pathTmp.lineTo(cx - r * 0.96f, cy)
                pathTmp.close()
                cvs.drawPath(pathTmp, glowPaint)
                solidPaint.color = primaryColor
                cvs.drawPath(pathTmp, solidPaint)

                solidPaint.color = Color.BLACK
                cvs.drawCircle(cx + gazeX * 14f, cy - gazeY * 9f, 20f, solidPaint)
                solidPaint.color = coreColor
                cvs.drawCircle(cx + gazeX * 14f - 8f, cy - gazeY * 9f - 9f, 7f, solidPaint)
                return
            }
            else -> Unit
        }

        if (openFactor <= 0.14f) {
            drawSmoothArcEye(cvs, cx, cy + 6f, halfW * 0.95f, 10f, upward = false, primaryColor, coreColor, thickness = 12f)
            return
        }

        val effectiveHalfH = max(14f, halfH * openFactor)
        val topY = cy - effectiveHalfH
        val botY = cy + effectiveHalfH
        rectTmp.set(cx - halfW, topY, cx + halfW, botY)

        cvs.save()
        if (shape == DeskBuddyEyeShape.ANGRY) {
            cvs.rotate(if (isLeft) 16f else -16f, cx, cy)
        } else if (shape == DeskBuddyEyeShape.SLY_MALICE && isLeft) {
            rectTmp.top = cy - effectiveHalfH * 0.55f
        }

        // Neon glow + rounded OLED eye shell
        cvs.drawRoundRect(rectTmp, 38f * scale, 38f * scale, glowPaint)
        solidPaint.shader = RadialGradient(
            cx + gazeX * 10f,
            cy - gazeY * 10f - 10f,
            halfW * 1.35f,
            coreColor,
            primaryColor,
            Shader.TileMode.CLAMP
        )
        cvs.drawRoundRect(rectTmp, 38f * scale, 38f * scale, solidPaint)
        solidPaint.shader = null

        // Pupil cutout moving with gaze (smaller pinpoint pupil when making big wide eyes)
        val pupilX = cx + gazeX * 22f * scale
        val pupilY = cy - gazeY * 14f * scale
        val pupilHalfW = if (shape == DeskBuddyEyeShape.WIDE_EYES) 12f else 18f
        val pupilHalfH = if (shape == DeskBuddyEyeShape.WIDE_EYES) 14f else 24f
        solidPaint.color = Color.rgb(6, 16, 28)
        rectTmp.set(pupilX - pupilHalfW * scale, pupilY - pupilHalfH * scale * openFactor, pupilX + pupilHalfW * scale, pupilY + pupilHalfH * scale * openFactor)
        cvs.drawRoundRect(rectTmp, 16f, 16f, solidPaint)

        // Crisp specular highlight dot
        solidPaint.color = Color.argb(235, 255, 255, 255)
        cvs.drawCircle(pupilX - 9f * scale, pupilY - 12f * scale * openFactor, if (shape == DeskBuddyEyeShape.WIDE_EYES) 8.5f * scale else 6.5f * scale, solidPaint)
        if (shape == DeskBuddyEyeShape.WIDE_EYES) {
            cvs.drawCircle(pupilX + 10f * scale, pupilY + 9f * scale * openFactor, 4.2f * scale, solidPaint)
        }

        // Heavy sleepy / focused top eyelid cover
        if (shape == DeskBuddyEyeShape.SLEEPY || shape == DeskBuddyEyeShape.FOCUSED || shape == DeskBuddyEyeShape.ANGRY) {
            val lidDrop = when (shape) {
                DeskBuddyEyeShape.SLEEPY -> effectiveHalfH * 0.92f
                DeskBuddyEyeShape.FOCUSED -> effectiveHalfH * 0.62f
                else -> effectiveHalfH * 0.52f
            }
            solidPaint.color = Color.argb(185, 8, 16, 28)
            rectTmp.set(cx - halfW - 6f, topY - 8f, cx + halfW + 6f, topY + lidDrop)
            cvs.drawRect(rectTmp, solidPaint)
            strokePaint.color = primaryColor
            strokePaint.strokeWidth = 5f
            cvs.drawLine(cx - halfW + 4f, topY + lidDrop, cx + halfW - 4f, topY + lidDrop, strokePaint)
        }
        cvs.restore()
    }

    private fun drawSmoothArcEye(
        cvs: Canvas,
        cx: Float,
        cy: Float,
        halfW: Float,
        arcLift: Float,
        upward: Boolean,
        primaryColor: Int,
        coreColor: Int,
        thickness: Float
    ) {
        pathTmp.reset()
        val endY = if (upward) cy + arcLift * 0.35f else cy - arcLift * 0.35f
        val ctrlY = if (upward) cy - arcLift * 1.25f else cy + arcLift * 1.25f
        pathTmp.moveTo(cx - halfW, endY)
        pathTmp.quadTo(cx, ctrlY, cx + halfW, endY)

        glowStrokePaint.color = Color.argb(120, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        glowStrokePaint.strokeWidth = thickness + 10f
        cvs.drawPath(pathTmp, glowStrokePaint)

        strokePaint.color = primaryColor
        strokePaint.strokeWidth = thickness
        cvs.drawPath(pathTmp, strokePaint)

        strokePaint.color = coreColor
        strokePaint.strokeWidth = thickness * 0.45f
        cvs.drawPath(pathTmp, strokePaint)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Option 3: Yeux Manga-Robot OLED (cadres mecha géométriques & iris HUD sur noir)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun drawMangaRobotEyes(
        cvs: Canvas,
        preset: DeskBuddyEyePreset,
        gazeX: Float,
        gazeY: Float,
        leftOpen: Float,
        rightOpen: Float,
        primaryColor: Int,
        coreColor: Int,
        pulse: Float
    ) {
        val leftCx = TEX_WIDTH * 0.32f
        val rightCx = TEX_WIDTH * 0.68f
        val eyeCy = TEX_HEIGHT * 0.52f

        drawSingleMangaRobotEye(
            cvs = cvs,
            cx = leftCx,
            cy = eyeCy,
            isLeft = true,
            shape = preset.shape,
            openFactor = leftOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            primaryColor = primaryColor,
            coreColor = coreColor,
            pulse = pulse
        )
        drawSingleMangaRobotEye(
            cvs = cvs,
            cx = rightCx,
            cy = eyeCy,
            isLeft = false,
            shape = preset.shape,
            openFactor = rightOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            primaryColor = primaryColor,
            coreColor = coreColor,
            pulse = pulse
        )
    }

    private fun drawSingleMangaRobotEye(
        cvs: Canvas,
        cx: Float,
        cy: Float,
        isLeft: Boolean,
        shape: DeskBuddyEyeShape,
        openFactor: Float,
        gazeX: Float,
        gazeY: Float,
        primaryColor: Int,
        coreColor: Int,
        pulse: Float
    ) {
        val dir = if (isLeft) -1f else 1f
        val eyeHalfW = if (shape == DeskBuddyEyeShape.WIDE_EYES || shape == DeskBuddyEyeShape.ALERT) 88f else 84f

        // Bold angular manga mecha brow armor above each eye (no feminine lashes)
        drawMechaBrow(cvs, cx, cy, eyeHalfW, isLeft, shape, openFactor, primaryColor, coreColor)

        val closedOnly = shape == DeskBuddyEyeShape.CALM_CLOSED ||
            (shape == DeskBuddyEyeShape.WINK_LEFT && isLeft) ||
            openFactor <= 0.14f

        if (closedOnly) {
            val dimAlpha = if (shape == DeskBuddyEyeShape.CALM_CLOSED) 185 else 255
            val arcColor = Color.argb(dimAlpha, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
            pathTmp.reset()
            when {
                shape == DeskBuddyEyeShape.WINK_LEFT && isLeft -> {
                    // Crisp dynamic manga mecha '>' wink
                    pathTmp.moveTo(cx - eyeHalfW * 0.68f, cy - 22f)
                    pathTmp.lineTo(cx + eyeHalfW * 0.52f, cy + 2f)
                    pathTmp.lineTo(cx - eyeHalfW * 0.68f, cy + 24f)
                }
                shape == DeskBuddyEyeShape.HAPPY_ARC || shape == DeskBuddyEyeShape.BLUSH -> {
                    pathTmp.moveTo(cx - eyeHalfW * 0.72f, cy + 12f)
                    pathTmp.lineTo(cx, cy - 24f)
                    pathTmp.lineTo(cx + eyeHalfW * 0.72f, cy + 12f)
                }
                else -> {
                    pathTmp.moveTo(cx - eyeHalfW * 0.76f, cy + 4f)
                    pathTmp.lineTo(cx + eyeHalfW * 0.76f, cy + 4f)
                }
            }
            glowStrokePaint.color = Color.argb(120, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
            glowStrokePaint.strokeWidth = 14f
            cvs.drawPath(pathTmp, glowStrokePaint)
            strokePaint.color = arcColor
            strokePaint.strokeWidth = 7.5f
            cvs.drawPath(pathTmp, strokePaint)
            strokePaint.color = coreColor
            strokePaint.strokeWidth = 2.8f
            cvs.drawPath(pathTmp, strokePaint)
            return
        }

        val heightFactor = when (shape) {
            DeskBuddyEyeShape.SLEEPY -> 0.52f
            DeskBuddyEyeShape.FOCUSED, DeskBuddyEyeShape.ANGRY -> 0.68f
            DeskBuddyEyeShape.WIDE_EYES, DeskBuddyEyeShape.ALERT -> 1.18f
            DeskBuddyEyeShape.SLY_MALICE -> if (isLeft) 0.60f else 0.96f
            DeskBuddyEyeShape.CURIOUS -> if (isLeft) 1.06f else 0.88f
            else -> 1.0f
        }
        val halfH = max(14f, 52f * openFactor * heightFactor)

        // Sharp angular hexagonal/octagonal anime mecha eye socket
        val innerEdge = -dir * eyeHalfW * 0.84f
        val outerEdge = dir * eyeHalfW * 0.92f
        val topFlatInner = -dir * eyeHalfW * 0.42f
        val topFlatOuter = dir * eyeHalfW * 0.56f
        val botFlatInner = -dir * eyeHalfW * 0.46f
        val botFlatOuter = dir * eyeHalfW * 0.48f

        val eyePath = Path().apply {
            moveTo(innerEdge, cy + 6f)
            lineTo(topFlatInner, cy - halfH)
            lineTo(topFlatOuter, cy - halfH * 0.84f)
            lineTo(outerEdge, cy - 2f)
            lineTo(botFlatOuter, cy + halfH * 0.88f)
            lineTo(botFlatInner, cy + halfH * 0.96f)
            close()
        }

        cvs.save()
        if (shape == DeskBuddyEyeShape.ANGRY) {
            cvs.rotate(if (isLeft) 11f else -11f, cx, cy)
        }

        // Deep dark cyber-lens interior + luminous HUD iris clipped inside mecha frame
        cvs.save()
        cvs.clipPath(eyePath)
        solidPaint.color = if (shape == DeskBuddyEyeShape.ALERT || shape == DeskBuddyEyeShape.WIDE_EYES) {
            Color.rgb(16, 36, 56)
        } else {
            Color.rgb(8, 20, 34)
        }
        cvs.drawPath(eyePath, solidPaint)

        // Glowing HUD iris & targeting reticle tracking left-to-right gaze
        val irisX = cx + gazeX * 26f
        val irisY = cy - gazeY * 14f
        val irisR = when (shape) {
            DeskBuddyEyeShape.WIDE_EYES -> 44f
            DeskBuddyEyeShape.ALERT -> 36f
            else -> 39f
        }

        glowPaint.color = Color.argb(130, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        cvs.drawCircle(irisX, irisY, irisR + 8f, glowPaint)

        solidPaint.shader = RadialGradient(
            irisX,
            irisY,
            irisR,
            intArrayOf(coreColor, primaryColor, Color.rgb(10, 56, 86)),
            floatArrayOf(0.20f, 0.68f, 1.0f),
            Shader.TileMode.CLAMP
        )
        cvs.drawCircle(irisX, irisY, irisR, solidPaint)
        solidPaint.shader = null

        // Segmented HUD scanner ring + 4 orthogonal mecha reticle ticks
        strokePaint.color = Color.argb(205, 255, 255, 255)
        strokePaint.strokeWidth = 2.6f
        rectTmp.set(irisX - irisR * 0.72f, irisY - irisR * 0.72f, irisX + irisR * 0.72f, irisY + irisR * 0.72f)
        cvs.drawArc(rectTmp, -35f + pulse * 24f, 115f, false, strokePaint)
        cvs.drawArc(rectTmp, 145f + pulse * 24f, 115f, false, strokePaint)

        strokePaint.color = coreColor
        strokePaint.strokeWidth = 2.0f
        cvs.drawLine(irisX - irisR * 0.92f, irisY, irisX - irisR * 0.56f, irisY, strokePaint)
        cvs.drawLine(irisX + irisR * 0.56f, irisY, irisX + irisR * 0.92f, irisY, strokePaint)
        cvs.drawLine(irisX, irisY - irisR * 0.92f, irisX, irisY - irisR * 0.56f, strokePaint)
        cvs.drawLine(irisX, irisY + irisR * 0.56f, irisX, irisY + irisR * 0.92f, strokePaint)

        // Pinpoint mechanical aperture pupil (extra small for wide eyes / shock!)
        solidPaint.color = Color.rgb(3, 8, 16)
        val pupilR = when (shape) {
            DeskBuddyEyeShape.ALERT -> 10f
            DeskBuddyEyeShape.WIDE_EYES -> 12f
            else -> 16f
        }
        cvs.drawCircle(irisX, irisY, pupilR, solidPaint)

        // Crisp manga specular highlights (bold square + dot)
        solidPaint.color = Color.WHITE
        rectTmp.set(irisX - 18f, irisY - 19f, irisX - 6f, irisY - 7f)
        cvs.drawRoundRect(rectTmp, 3f, 3f, solidPaint)
        cvs.drawCircle(irisX + 12f, irisY + 11f, if (shape == DeskBuddyEyeShape.WIDE_EYES) 5.5f else 3.8f, solidPaint)

        // Mechanical upper shutter plate for SLEEPY / BLUSH warmth
        if (shape == DeskBuddyEyeShape.SLEEPY) {
            solidPaint.color = Color.argb(235, 0, 0, 0)
            rectTmp.set(cx - eyeHalfW - 8f, cy - 80f, cx + eyeHalfW + 8f, cy - 2f)
            cvs.drawRect(rectTmp, solidPaint)
            strokePaint.color = primaryColor
            strokePaint.strokeWidth = 4f
            cvs.drawLine(cx - eyeHalfW, cy - 2f, cx + eyeHalfW, cy - 2f, strokePaint)
        }
        cvs.restore()

        // Glowing crisp mecha frame outline (angular cyber armor bevel)
        glowStrokePaint.color = Color.argb(135, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        glowStrokePaint.strokeWidth = 9f
        cvs.drawPath(eyePath, glowStrokePaint)

        strokePaint.color = primaryColor
        strokePaint.strokeWidth = 4.2f
        cvs.drawPath(eyePath, strokePaint)

        // Thick bright top visor rim + small side sensor notches
        pathTmp.reset()
        pathTmp.moveTo(innerEdge, cy + 6f)
        pathTmp.lineTo(topFlatInner, cy - halfH)
        pathTmp.lineTo(topFlatOuter, cy - halfH * 0.84f)
        pathTmp.lineTo(outerEdge, cy - 2f)
        strokePaint.color = coreColor
        strokePaint.strokeWidth = 5.4f
        cvs.drawPath(pathTmp, strokePaint)

        // Tiny futuristic HUD cheek bracket ticks at outer bottom corner
        strokePaint.color = Color.argb(170, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        strokePaint.strokeWidth = 2.6f
        val tickX = outerEdge + dir * 10f
        cvs.drawLine(tickX, cy + halfH * 0.42f, tickX - dir * 14f, cy + halfH * 0.72f, strokePaint)
        cvs.drawLine(tickX + dir * 8f, cy + halfH * 0.48f, tickX - dir * 6f, cy + halfH * 0.78f, strokePaint)

        cvs.restore()
    }

    private fun drawMechaBrow(
        cvs: Canvas,
        cx: Float,
        cy: Float,
        eyeHalfW: Float,
        isLeft: Boolean,
        shape: DeskBuddyEyeShape,
        openFactor: Float,
        primaryColor: Int,
        coreColor: Int
    ) {
        val dir = if (isLeft) -1f else 1f
        val baseLift = when (shape) {
            DeskBuddyEyeShape.ALERT, DeskBuddyEyeShape.WIDE_EYES -> 78f
            DeskBuddyEyeShape.ANGRY -> 54f
            DeskBuddyEyeShape.SLEEPY -> 44f
            else -> 64f
        } * openFactor.coerceIn(0.55f, 1.15f)
        val innerTilt = when (shape) {
            DeskBuddyEyeShape.ANGRY -> 18f
            DeskBuddyEyeShape.WIDE_EYES, DeskBuddyEyeShape.ALERT -> -12f
            DeskBuddyEyeShape.BLUSH -> -8f
            DeskBuddyEyeShape.CURIOUS -> if (isLeft) -10f else 4f
            else -> 2f
        }

        val innerX = cx - dir * eyeHalfW * 0.86f
        val midX = cx - dir * eyeHalfW * 0.12f
        val outerX = cx + dir * eyeHalfW * 0.92f
        val browY = cy - baseLift

        pathTmp.reset()
        pathTmp.moveTo(innerX, browY + innerTilt)
        pathTmp.lineTo(midX, browY - 10f)
        pathTmp.lineTo(outerX, browY + 4f)

        glowStrokePaint.color = Color.argb(120, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        glowStrokePaint.strokeWidth = 11f
        cvs.drawPath(pathTmp, glowStrokePaint)

        strokePaint.color = primaryColor
        strokePaint.strokeWidth = 6.2f
        cvs.drawPath(pathTmp, strokePaint)

        strokePaint.color = coreColor
        strokePaint.strokeWidth = 2.6f
        cvs.drawPath(pathTmp, strokePaint)
    }

    private fun drawBlushCheeks(
        cvs: Canvas,
        style: DeskBuddyStyle,
        pulse: Float
    ) {
        val blushAlpha = (180 + (pulse * 55f).toInt()).coerceIn(150, 235)
        val pinkGlow = Color.argb(95, 255, 82, 146)
        val pinkBright = Color.argb(blushAlpha, 255, 108, 168)

        val leftCx = TEX_WIDTH * 0.27f
        val rightCx = TEX_WIDTH * 0.73f
        val blushY = TEX_HEIGHT * 0.72f

        for (cx in floatArrayOf(leftCx, rightCx)) {
            when (style) {
                DeskBuddyStyle.PIXEL_OLED -> {
                    glowPaint.color = pinkGlow
                    rectTmp.set(cx - 34f, blushY - 12f, cx + 34f, blushY + 12f)
                    cvs.drawRoundRect(rectTmp, 6f, 6f, glowPaint)
                    solidPaint.color = pinkBright
                    for (i in -2..1) {
                        val px = cx + i * 15f
                        val py = blushY - 6f + (if ((i and 1) == 0) 0f else 8f)
                        rectTmp.set(px, py, px + 11f, py + 11f)
                        cvs.drawRoundRect(rectTmp, 3f, 3f, solidPaint)
                    }
                }
                else -> {
                    // Glowing soft pink OLED cheek halo + crisp diagonal anime blush strokes ///
                    glowPaint.color = pinkGlow
                    rectTmp.set(cx - 42f, blushY - 16f, cx + 42f, blushY + 16f)
                    cvs.drawOval(rectTmp, glowPaint)
                    strokePaint.color = pinkBright
                    strokePaint.strokeWidth = 5.2f
                    for (offset in floatArrayOf(-20f, 0f, 20f)) {
                        cvs.drawLine(cx + offset - 8f, blushY + 12f, cx + offset + 9f, blushY - 12f, strokePaint)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Notification Glyphs (?, !, Message Bubble, Zz, Sparkle, Heart)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun drawNotificationBadge(
        cvs: Canvas,
        badge: DeskBuddyBadge,
        primaryColor: Int,
        coreColor: Int,
        pulse: Float
    ) {
        if (badge == DeskBuddyBadge.NONE) return
        val bx = TEX_WIDTH * 0.875f
        val by = TEX_HEIGHT * 0.22f
        val alpha = (185 + (pulse * 70f).toInt()).coerceIn(140, 255)
        val badgeColor = Color.argb(alpha, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        glowStrokePaint.color = Color.argb(110, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))

        when (badge) {
            DeskBuddyBadge.ALERT -> {
                strokePaint.color = badgeColor
                strokePaint.strokeWidth = 8.5f
                glowStrokePaint.strokeWidth = 14f
                cvs.drawLine(bx, by - 22f, bx, by + 6f, glowStrokePaint)
                cvs.drawLine(bx, by - 22f, bx, by + 6f, strokePaint)
                solidPaint.color = coreColor
                cvs.drawCircle(bx, by + 22f, 5.2f, solidPaint)
            }
            DeskBuddyBadge.QUESTION -> {
                pathTmp.reset()
                rectTmp.set(bx - 13f, by - 22f, bx + 13f, by + 2f)
                pathTmp.arcTo(rectTmp, 195f, 225f)
                pathTmp.lineTo(bx, by + 10f)
                strokePaint.color = badgeColor
                strokePaint.strokeWidth = 6.5f
                glowStrokePaint.strokeWidth = 12f
                cvs.drawPath(pathTmp, glowStrokePaint)
                cvs.drawPath(pathTmp, strokePaint)
                solidPaint.color = coreColor
                cvs.drawCircle(bx, by + 22f, 4.5f, solidPaint)
            }
            DeskBuddyBadge.MESSAGE_BUBBLE -> {
                rectTmp.set(bx - 24f, by - 16f, bx + 24f, by + 14f)
                glowPaint.color = Color.argb(85, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
                cvs.drawRoundRect(rectTmp, 14f, 14f, glowPaint)
                solidPaint.color = badgeColor
                cvs.drawRoundRect(rectTmp, 14f, 14f, solidPaint)
                solidPaint.color = Color.BLACK
                cvs.drawCircle(bx - 10f, by - 1f, 3.3f, solidPaint)
                cvs.drawCircle(bx, by - 1f, 3.3f, solidPaint)
                cvs.drawCircle(bx + 10f, by - 1f, 3.3f, solidPaint)
            }
            DeskBuddyBadge.SPARKLE -> {
                pathTmp.reset()
                pathTmp.moveTo(bx, by - 20f)
                pathTmp.lineTo(bx + 5f, by - 5f)
                pathTmp.lineTo(bx + 20f, by)
                pathTmp.lineTo(bx + 5f, by + 5f)
                pathTmp.lineTo(bx, by + 20f)
                pathTmp.lineTo(bx - 5f, by + 5f)
                pathTmp.lineTo(bx - 20f, by)
                pathTmp.lineTo(bx - 5f, by - 5f)
                pathTmp.close()
                solidPaint.color = coreColor
                cvs.drawPath(pathTmp, solidPaint)
            }
            DeskBuddyBadge.HEART -> {
                pathTmp.reset()
                pathTmp.moveTo(bx, by + 16f)
                pathTmp.cubicTo(bx - 24f, by + 2f, bx - 22f, by - 18f, bx, by - 6f)
                pathTmp.cubicTo(bx + 22f, by - 18f, bx + 24f, by + 2f, bx, by + 16f)
                pathTmp.close()
                glowPaint.color = Color.argb(110, 255, 92, 152)
                cvs.drawPath(pathTmp, glowPaint)
                solidPaint.color = Color.argb(alpha, 255, 110, 170)
                cvs.drawPath(pathTmp, solidPaint)
            }
            DeskBuddyBadge.SLEEP_ZZ -> {
                strokePaint.color = badgeColor
                strokePaint.strokeWidth = 4.5f
                drawZGlyph(cvs, bx - 14f, by + 14f, 14f, strokePaint)
                drawZGlyph(cvs, bx + 6f, by - 10f, 20f, strokePaint)
            }
            DeskBuddyBadge.NONE -> Unit
        }
    }

    private fun drawZGlyph(cvs: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        pathTmp.reset()
        pathTmp.moveTo(x - size * 0.5f, y - size * 0.5f)
        pathTmp.lineTo(x + size * 0.5f, y - size * 0.5f)
        pathTmp.lineTo(x - size * 0.5f, y + size * 0.5f)
        pathTmp.lineTo(x + size * 0.5f, y + size * 0.5f)
        cvs.drawPath(pathTmp, paint)
    }

    companion object {
        private const val TEX_WIDTH = 640
        private const val TEX_HEIGHT = 360
        private val UV_FULL = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f)

        private const val BLINK_INTERVAL_MS = 3800L
        private const val BLINK_DURATION_MS = 190L
        private const val IDLE_EXPRESSION_CYCLE_MS = 28_000L

        /**
         * Maps any canonical/raw emotion (from chat, touch, caress, or webhook notification)
         * to its Desk Buddy eyes-only preset.
         */
        fun resolveEyePreset(rawEmotion: String): DeskBuddyEyePreset {
            val canonical = Live2dEmotion.normalizeEmotionName(rawEmotion)
            return when (canonical) {
                Live2dEmotion.EMOTION_CURIOSITE -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.CURIOUS,
                    badge = DeskBuddyBadge.MESSAGE_BUBBLE
                )
                Live2dEmotion.EMOTION_SURPRISE -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.ALERT,
                    badge = DeskBuddyBadge.ALERT,
                    isAlertColor = true
                )
                Live2dEmotion.EMOTION_GROS_YEUX -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.WIDE_EYES,
                    badge = DeskBuddyBadge.SPARKLE
                )
                Live2dEmotion.EMOTION_ROUGIR -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.BLUSH,
                    badge = DeskBuddyBadge.HEART,
                    hasBlush = true
                )
                Live2dEmotion.EMOTION_JOIE -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.HAPPY_ARC,
                    badge = DeskBuddyBadge.SPARKLE
                )
                Live2dEmotion.EMOTION_CALME -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.CALM_CLOSED,
                    badge = DeskBuddyBadge.NONE,
                    isDimmed = true,
                    brightness = 0.62f,
                    openLeft = 0f,
                    openRight = 0f
                )
                Live2dEmotion.EMOTION_FATIGUE -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.SLEEPY,
                    badge = DeskBuddyBadge.SLEEP_ZZ,
                    isDimmed = true,
                    brightness = 0.72f,
                    openLeft = 0.52f,
                    openRight = 0.52f
                )
                Live2dEmotion.EMOTION_COLERE -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.ANGRY,
                    badge = DeskBuddyBadge.ALERT,
                    isAlertColor = true
                )
                Live2dEmotion.EMOTION_MALICE -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.SLY_MALICE,
                    badge = DeskBuddyBadge.SPARKLE,
                    openLeft = 0.65f,
                    openRight = 1.0f
                )
                Live2dEmotion.EMOTION_CLIN_DOEIL -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.WINK_LEFT,
                    badge = DeskBuddyBadge.SPARKLE,
                    openLeft = 0f,
                    openRight = 1.0f
                )
                Live2dEmotion.EMOTION_ETIREMENT -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.HAPPY_ARC,
                    badge = DeskBuddyBadge.NONE
                )
                Live2dEmotion.EMOTION_SERIEUX -> DeskBuddyEyePreset(
                    canonicalEmotion = canonical,
                    shape = DeskBuddyEyeShape.FOCUSED,
                    badge = DeskBuddyBadge.QUESTION,
                    openLeft = 0.78f,
                    openRight = 0.78f
                )
                else -> DeskBuddyEyePreset(
                    canonicalEmotion = Live2dEmotion.EMOTION_NORMAL,
                    shape = DeskBuddyEyeShape.NORMAL,
                    badge = DeskBuddyBadge.NONE
                )
            }
        }

        /**
         * Spontaneous idle lifecycle when Desk Buddy is in `normal` mode:
         * periodically winks, opens wide eyes ("gros yeux"), or briefly dozes off ("s'endort"),
         * while always yielding immediately when an active notification, touch, or chat emotion is set.
         */
        fun computeIdleExpression(nowMs: Long, baseCanonicalEmotion: String): String {
            if (baseCanonicalEmotion != Live2dEmotion.EMOTION_NORMAL) {
                return baseCanonicalEmotion
            }
            val phaseMs = Math.floorMod(nowMs, IDLE_EXPRESSION_CYCLE_MS)
            return when {
                phaseMs in 6_000L until 7_900L -> Live2dEmotion.EMOTION_CLIN_DOEIL
                phaseMs in 13_500L until 16_000L -> Live2dEmotion.EMOTION_GROS_YEUX
                phaseMs in 20_500L until 26_000L -> Live2dEmotion.EMOTION_FATIGUE
                else -> Live2dEmotion.EMOTION_NORMAL
            }
        }

        /**
         * Periodic organic left-to-right eye saccade pattern so Desk Buddy glances around naturally.
         */
        fun computeIdleGaze(nowMs: Long, canonicalEmotion: String): DeskBuddyGazeState {
            if (canonicalEmotion == Live2dEmotion.EMOTION_CALME) {
                return DeskBuddyGazeState(0f, -0.15f)
            }
            val periodMs = if (canonicalEmotion == Live2dEmotion.EMOTION_CURIOSITE) 3600L else 6400L
            val phase = ((nowMs % periodMs).toFloat() / periodMs.toFloat())
            val amplitudeX = when (canonicalEmotion) {
                Live2dEmotion.EMOTION_CURIOSITE -> 0.85f
                Live2dEmotion.EMOTION_MALICE -> 0.72f
                Live2dEmotion.EMOTION_FATIGUE -> 0.35f
                else -> 0.75f
            }
            // Stepped smooth hold: center -> left -> center -> right -> center
            val x = when {
                phase < 0.18f -> 0f
                phase < 0.38f -> -amplitudeX
                phase < 0.54f -> 0f
                phase < 0.76f -> amplitudeX
                else -> 0f
            }
            val y = when {
                canonicalEmotion == Live2dEmotion.EMOTION_FATIGUE -> -0.22f
                phase in 0.18f..0.38f -> 0.14f
                phase in 0.54f..0.76f -> -0.10f
                else -> 0.0f
            }
            return DeskBuddyGazeState(x = x, y = y)
        }

        /**
         * Natural periodic eyelid blink weight (`0f` = open, `1f` = closed), with an occasional quick double-blink.
         */
        fun computeBlinkWeight(nowMs: Long, canonicalEmotion: String): Float {
            if (canonicalEmotion == Live2dEmotion.EMOTION_CALME ||
                canonicalEmotion == Live2dEmotion.EMOTION_JOIE ||
                canonicalEmotion == Live2dEmotion.EMOTION_ETIREMENT ||
                canonicalEmotion == Live2dEmotion.EMOTION_ROUGIR
            ) {
                return 0f
            }
            val cycleIdx = nowMs / BLINK_INTERVAL_MS
            val phaseMs = nowMs % BLINK_INTERVAL_MS
            val primary = singleBlinkCurve(phaseMs)
            // Every 4th cycle performs a cute natural double-blink
            val doubleBlink = if (cycleIdx % 4L == 2L) {
                singleBlinkCurve(phaseMs - 340L)
            } else {
                0f
            }
            return max(primary, doubleBlink).coerceIn(0f, 1f)
        }

        private fun singleBlinkCurve(elapsedMs: Long): Float {
            if (elapsedMs < 0L || elapsedMs >= BLINK_DURATION_MS) return 0f
            val t = elapsedMs.toFloat() / BLINK_DURATION_MS.toFloat()
            return 0.5f * (1f - cos(2f * PI.toFloat() * t))
        }
    }
}
