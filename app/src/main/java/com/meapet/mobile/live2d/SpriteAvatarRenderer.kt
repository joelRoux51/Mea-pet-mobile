package com.meapet.mobile.live2d

import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Renders a 2D transparent PNG sprite avatar in OpenGL ES 2.0 with breathing,
 * automatic blinking, touch/drag parallax, emotion bounce, and smooth cross-fade
 * transitions driven by `[EMOTION: ...]`.
 *
 * Used by both [Live2dManager] (main screen) and
 * [com.meapet.mobile.live2d.overlay.Live2dOverlayRenderer] (floating overlay).
 * All methods except [setEmotion] must be invoked on the owning GL thread.
 */
class SpriteAvatarRenderer {

    private val textures = mutableMapOf<String, Live2dTextureManager.TextureInfo>()
    private var sprite: Live2dSprite? = null
    private var spriteProgramId = 0
    private var loaded = false

    @Volatile
    private var currentEmotion = Live2dEmotion.EMOTION_NORMAL
    private var previousEmotion = Live2dEmotion.EMOTION_NORMAL
    private var transitionStartMs = 0L
    private var bounceStartMs = 0L

    fun isLoaded(): Boolean = loaded

    fun reset() {
        textures.clear()
        sprite = null
        spriteProgramId = 0
        loaded = false
        transitionStartMs = 0L
        bounceStartMs = 0L
    }

    fun load(dirPath: String, textureManager: Live2dTextureManager): Boolean {
        textures.clear()
        val baseDir = dirPath.trimEnd('/')
        for (emotionKey in SUPPORTED_EMOTIONS) {
            val filePath = "$baseDir/$emotionKey.png"
            val info = try {
                textureManager.createTextureFromPngFile(filePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load sprite texture $filePath: ${e.message}")
                null
            }
            if (info != null) {
                textures[emotionKey] = info
            }
        }
        loaded = textures.containsKey(Live2dEmotion.EMOTION_NORMAL)
        if (!loaded) {
            Log.e(TAG, "Missing required normal.png sprite in $baseDir")
        } else {
            Log.i(TAG, "Loaded ${textures.size} 2D sprite textures from $baseDir")
        }
        return loaded
    }

    fun setEmotion(rawEmotion: String) {
        val resolved = resolveSupportedEmotion(rawEmotion)
        val now = SystemClock.uptimeMillis()
        if (resolved != currentEmotion) {
            previousEmotion = currentEmotion
            currentEmotion = resolved
            transitionStartMs = now
        }
        bounceStartMs = now
    }

    fun draw(
        viewWidth: Int,
        viewHeight: Int,
        shaderProgramId: Int,
        dragX: Float = 0f,
        dragY: Float = 0f,
        opacity: Float = 1f,
        isOverlay: Boolean = false
    ) {
        if (!loaded || viewWidth <= 0 || viewHeight <= 0 || shaderProgramId == 0) return

        val baseTex = textures[currentEmotion] ?: textures[Live2dEmotion.EMOTION_NORMAL] ?: return
        val activeTex = applyAutoBlink(baseTex)
        val quad = ensureSprite(shaderProgramId)
        quad.setWindowSize(viewWidth, viewHeight)

        val now = SystemClock.uptimeMillis()
        val t = (now % 600_000L) / 1000f

        // Gentle idle breathing
        val breathSin = sin(t * 1.85f)
        val breathScaleY = 1.0f + 0.007f * breathSin
        val breathOffsetY = viewHeight * 0.006f * breathSin

        // Subtle celebratory bounce when a new emotion arrives
        val bounceOffsetY = if (bounceStartMs > 0L) {
            val elapsed = now - bounceStartMs
            if (elapsed in 0..EMOTION_BOUNCE_MS) {
                val p = elapsed.toFloat() / EMOTION_BOUNCE_MS.toFloat()
                sin(p * PI.toFloat()) * (1f - p) * viewHeight * 0.018f
            } else {
                0f
            }
        } else {
            0f
        }

        // Subtle drag/touch parallax
        val parallaxX = dragX.coerceIn(-1f, 1f) * viewWidth * 0.025f
        val parallaxY = dragY.coerceIn(-1f, 1f) * viewHeight * 0.025f

        val aspect = activeTex.width.toFloat() / activeTex.height.toFloat().coerceAtLeast(1f)
        val maxH = viewHeight * if (isOverlay) 0.96f else 0.78f
        val maxW = viewWidth * if (isOverlay) 0.96f else 0.94f
        var drawH = maxH
        var drawW = drawH * aspect
        if (drawW > maxW) {
            drawW = maxW
            drawH = drawW / aspect
        }
        drawH *= breathScaleY

        val centerX = viewWidth * 0.5f + parallaxX
        val centerY = viewHeight * (if (isOverlay) 0.50f else 0.52f) + breathOffsetY + bounceOffsetY + parallaxY
        quad.resize(centerX, centerY, drawW, drawH)

        GLES20.glUseProgram(shaderProgramId)
        GLES20.glEnable(GLES20.GL_BLEND)
        // PNG bitmaps are decoded with premultiplied alpha (Live2dDefine.PREMULTIPLIED_ALPHA_ENABLE = true)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val clampedAlpha = opacity.coerceIn(0f, 1f)
        val elapsedTrans = if (transitionStartMs > 0L) now - transitionStartMs else -1L
        val prevTex = textures[previousEmotion]

        if (prevTex != null &&
            prevTex.id != activeTex.id &&
            elapsedTrans in 0 until TRANSITION_MS
        ) {
            val progress = (elapsedTrans.toFloat() / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
            val prevAlpha = clampedAlpha * (1f - progress)
            val nextAlpha = clampedAlpha * progress
            if (prevAlpha > 0.01f) {
                quad.setColor(prevAlpha, prevAlpha, prevAlpha, prevAlpha)
                quad.renderImmediate(prevTex.id, UV_FULL)
            }
            if (nextAlpha > 0.01f) {
                quad.setColor(nextAlpha, nextAlpha, nextAlpha, nextAlpha)
                quad.renderImmediate(activeTex.id, UV_FULL)
            }
        } else {
            quad.setColor(clampedAlpha, clampedAlpha, clampedAlpha, clampedAlpha)
            quad.renderImmediate(activeTex.id, UV_FULL)
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun applyAutoBlink(baseTex: Live2dTextureManager.TextureInfo): Live2dTextureManager.TextureInfo {
        if (currentEmotion !in BLINK_ELIGIBLE_EMOTIONS) return baseTex
        val calmTex = textures[Live2dEmotion.EMOTION_CALME] ?: return baseTex
        val cyclePos = SystemClock.uptimeMillis() % BLINK_INTERVAL_MS
        return if (cyclePos < BLINK_DURATION_MS) calmTex else baseTex
    }

    private fun ensureSprite(programId: Int): Live2dSprite {
        val existing = sprite
        if (existing != null && spriteProgramId == programId) return existing
        val created = Live2dSprite(0f, 0f, 1f, 1f, 0, programId)
        sprite = created
        spriteProgramId = programId
        return created
    }

    companion object {
        private const val TAG = "SpriteAvatarRenderer"
        private const val TRANSITION_MS = 240L
        private const val EMOTION_BOUNCE_MS = 380L
        private const val BLINK_INTERVAL_MS = 3800L
        private const val BLINK_DURATION_MS = 135L

        private val UV_FULL = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f)

        val SUPPORTED_EMOTIONS = listOf(
            Live2dEmotion.EMOTION_NORMAL,
            Live2dEmotion.EMOTION_JOIE,
            Live2dEmotion.EMOTION_SERIEUX,
            Live2dEmotion.EMOTION_COLERE,
            Live2dEmotion.EMOTION_CALME,
            Live2dEmotion.EMOTION_FATIGUE
        )

        private val BLINK_ELIGIBLE_EMOTIONS = setOf(
            Live2dEmotion.EMOTION_NORMAL,
            Live2dEmotion.EMOTION_JOIE,
            Live2dEmotion.EMOTION_SERIEUX
        )

        fun resolveSupportedEmotion(rawEmotion: String): String {
            val normalized = Live2dEmotion.normalizeEmotionName(rawEmotion)
            return if (normalized in SUPPORTED_EMOTIONS) {
                normalized
            } else {
                Live2dEmotion.EMOTION_NORMAL
            }
        }
    }
}
