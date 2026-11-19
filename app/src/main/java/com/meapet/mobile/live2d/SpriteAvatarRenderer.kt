package com.meapet.mobile.live2d

import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders Alita in layered 2.5D / 3D volumetric perspective in OpenGL ES 2.0:
 * - Curved 16x20 subdivided 3D mesh with analytical surface normals & Fresnel rim lighting
 * - Perspective camera (`perspectiveM` + `setLookAtM`) with idle 3D sway & spring-damped touch/drag tilt
 * - Independent Z-depth layers (shadow, torso, arms, head, eyes, pupils, eyelids, brows, mouth, hair)
 * - Progressive vertex-weighted hair sway (`meches_cheveux`) active across all 6 emotions
 * - Real eyelid blinking (`paupiere_gauche` / `paupiere_droite`) without flashing `calme.png`
 *
 * Used by both [Live2dManager] (main screen) and
 * [com.meapet.mobile.live2d.overlay.Live2dOverlayRenderer] (floating overlay).
 * All methods except [setEmotion] must be invoked on the owning GL thread.
 */
class SpriteAvatarRenderer {

    private val textures = mutableMapOf<String, Live2dTextureManager.TextureInfo>()
    private val layerTextures = mutableMapOf<String, Live2dTextureManager.TextureInfo>()

    private var fallbackSprite: Live2dSprite? = null
    private var fallbackProgramId = 0
    private var loaded = false

    @Volatile
    private var currentEmotion = Live2dEmotion.EMOTION_NORMAL
    private var previousEmotion = Live2dEmotion.EMOTION_NORMAL
    private var transitionStartMs = 0L
    private var bounceStartMs = 0L
    private var lastFrameMs = 0L

    // Spring-smoothed drag / hair secondary motion state (GL thread only)
    private var smoothDragX = 0f
    private var smoothDragY = 0f
    private var hairLagX = 0f
    private var hairVelX = 0f

    // Reusable 3D matrices (GL thread only)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val partMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Reusable 3D subdivided mesh buffers (GL thread only)
    private val vertexPositions = FloatArray(VERTEX_COUNT * 3)
    private val vertexUvs = FloatArray(VERTEX_COUNT * 2)
    private val vertexNormals = FloatArray(VERTEX_COUNT * 3)
    private val posBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COUNT * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val uvBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COUNT * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val normalBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COUNT * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val indexBuffer: ShortBuffer = buildIndexBuffer()

    fun isLoaded(): Boolean = loaded

    fun reset() {
        textures.clear()
        layerTextures.clear()
        fallbackSprite = null
        fallbackProgramId = 0
        loaded = false
        transitionStartMs = 0L
        bounceStartMs = 0L
        lastFrameMs = 0L
        smoothDragX = 0f
        smoothDragY = 0f
        hairLagX = 0f
        hairVelX = 0f
    }

    fun load(dirPath: String, textureManager: Live2dTextureManager): Boolean {
        textures.clear()
        layerTextures.clear()
        val baseDir = dirPath.trimEnd('/')

        for (emotionKey in SUPPORTED_EMOTIONS) {
            val filePath = "$baseDir/$emotionKey.png"
            loadOptionalTexture(textureManager, filePath)?.let { textures[emotionKey] = it }
        }
        for (layerKey in LAYER_NAMES) {
            val filePath = "$baseDir/layers/$layerKey.png"
            loadOptionalTexture(textureManager, filePath)?.let { layerTextures[layerKey] = it }
        }

        loaded = textures.containsKey(Live2dEmotion.EMOTION_NORMAL)
        if (!loaded) {
            Log.e(TAG, "Missing required normal.png sprite in $baseDir")
        } else {
            Log.i(
                TAG,
                "Loaded ${textures.size} poses & ${layerTextures.size} 3D layers from $baseDir"
            )
        }
        return loaded
    }

    private fun loadOptionalTexture(
        tm: Live2dTextureManager,
        filePath: String
    ): Live2dTextureManager.TextureInfo? {
        return try {
            tm.createTextureFromPngFile(filePath)
        } catch (e: Exception) {
            Log.w(TAG, "Optional texture skipped ($filePath): ${e.message}")
            null
        }
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
        shader3d: Sprite3dShader? = null,
        dragX: Float = 0f,
        dragY: Float = 0f,
        opacity: Float = 1f,
        isOverlay: Boolean = false
    ) {
        if (!loaded || viewWidth <= 0 || viewHeight <= 0) return
        val baseTex = textures[currentEmotion] ?: textures[Live2dEmotion.EMOTION_NORMAL] ?: return

        if (shader3d == null || !shader3d.isValid) {
            drawFlatFallback(viewWidth, viewHeight, shaderProgramId, baseTex, dragX, dragY, opacity, isOverlay)
            return
        }

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs > 0L) ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.05f) else 0.016f
        lastFrameMs = now
        val t = (now % 600_000L) / 1000f

        // Spring-smooth touch drag & secondary hair sway
        val targetDragX = dragX.coerceIn(-1f, 1f)
        val targetDragY = dragY.coerceIn(-1f, 1f)
        val dragLerp = 1f - kotlin.math.exp((-dt * 11f).toDouble()).toFloat()
        val prevSmoothX = smoothDragX
        smoothDragX += (targetDragX - smoothDragX) * dragLerp
        smoothDragY += (targetDragY - smoothDragY) * dragLerp

        val headImpulse = (smoothDragX - prevSmoothX) / dt
        val windX = sin(t * 2.15f) * 0.014f + sin(t * 3.45f + 1.1f) * 0.006f
        val springForce = (windX - hairLagX) * 28f - headImpulse * 0.020f
        hairVelX = (hairVelX + springForce * dt) * kotlin.math.exp((-dt * 5.5f).toDouble()).toFloat()
        hairLagX = (hairLagX + hairVelX * dt).coerceIn(-0.045f, 0.045f)

        // Emotion-dependent breathing, bounce & anger shake
        val isAngry = currentEmotion == Live2dEmotion.EMOTION_COLERE
        val isHappy = currentEmotion == Live2dEmotion.EMOTION_JOIE
        val isCalmOrTired = currentEmotion == Live2dEmotion.EMOTION_CALME ||
            currentEmotion == Live2dEmotion.EMOTION_FATIGUE

        val breathSpeed = when {
            isAngry -> 2.65f
            isHappy -> 2.20f
            isCalmOrTired -> 1.35f
            else -> 1.85f
        }
        val breathSin = sin(t * breathSpeed)
        val breathScaleY = 1.0f + 0.007f * breathSin
        val breathOffsetY = 0.007f * breathSin

        val bounceElapsed = if (bounceStartMs > 0L) now - bounceStartMs else -1L
        val bounceOffsetY = if (bounceElapsed in 0..EMOTION_BOUNCE_MS) {
            val p = bounceElapsed.toFloat() / EMOTION_BOUNCE_MS.toFloat()
            sin(p * PI.toFloat()) * (1f - p) * 0.032f
        } else {
            0f
        }
        val angerShakeX = if (isAngry && bounceElapsed in 0..420L) {
            val p = bounceElapsed.toFloat() / 420f
            sin(p * PI.toFloat() * 8f) * (1f - p) * 0.014f
        } else {
            0f
        }

        // Camera & perspective fitting so character matches original framing cleanly
        val texAspect = baseTex.width.toFloat() / baseTex.height.toFloat().coerceAtLeast(1f)
        val screenAspect = viewWidth.toFloat() / viewHeight.toFloat().coerceAtLeast(1f)
        val fillH = if (isOverlay) 0.94f else 0.78f
        val fillW = if (isOverlay) 0.94f else 0.92f
        val tanHalfFov = tan(Math.toRadians(FOV_DEG * 0.5)).toFloat()
        val visHalfH = max(1.0f / fillH, texAspect / (fillW * screenAspect))
        val camDist = visHalfH / tanHalfFov

        Matrix.perspectiveM(projMatrix, 0, FOV_DEG, screenAspect, 0.4f, 24f)
        Matrix.setLookAtM(
            viewMatrix, 0,
            0f, 0f, camDist,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // Base 3D pose orientation: gentle figure-8 idle + touch/drag 3D look
        val bodyYaw = sin(t * 0.88f) * 4.8f + smoothDragX * 14.5f
        val bodyPitch = sin(t * 0.58f + 0.7f) * 2.2f - smoothDragY * 8.5f
        val bodyRoll = sin(t * 0.46f) * 1.1f + smoothDragX * 2.0f
        val screenShiftY = (if (isOverlay) 0.0f else -0.04f) * visHalfH
        val baseShiftX = smoothDragX * 0.045f + angerShakeX
        val baseShiftY = screenShiftY + breathOffsetY + bounceOffsetY - smoothDragY * 0.035f

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, baseShiftX, baseShiftY, 0f)
        Matrix.rotateM(modelMatrix, 0, bodyYaw, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, bodyPitch, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, bodyRoll, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, 1f, breathScaleY, 1f)

        GLES20.glUseProgram(shader3d.programId)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(shader3d.textureLoc, 0)
        GLES20.glUniform3f(shader3d.lightDirLoc, 0.36f, 0.52f, 0.78f)

        val clampedAlpha = opacity.coerceIn(0f, 1f)
        val blinkWeight = computeBlinkWeight(now)
        val hasFullRig = hasFullNormalRig()
        val hairTex = layerTextures[LAYER_HAIR]

        // Cross-fade smoothly if switching emotion
        val elapsedTrans = if (transitionStartMs > 0L) now - transitionStartMs else -1L
        val prevTex = textures[previousEmotion]
        if (prevTex != null && previousEmotion != currentEmotion && elapsedTrans in 0 until TRANSITION_MS) {
            val progress = (elapsedTrans.toFloat() / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
            val prevBody = resolveBodyTexture(previousEmotion) ?: prevTex
            val nextBody = resolveBodyTexture(currentEmotion) ?: baseTex

            // Soft drop shadow behind cross-fade
            drawDropShadow3d(shader3d, nextBody, texAspect, clampedAlpha * (if (isOverlay) 0.16f else 0.28f))

            if (prevBody.id == nextBody.id) {
                drawMeshPart(shader3d, nextBody, modelMatrix, texAspect, 0f, 0.075f, clampedAlpha, 0.26f)
            } else {
                val pA = clampedAlpha * (1f - progress)
                val nA = clampedAlpha * progress
                if (pA > 0.01f) {
                    drawMeshPart(shader3d, prevBody, modelMatrix, texAspect, 0f, 0.075f, pA, 0.24f)
                }
                if (nA > 0.01f) {
                    drawMeshPart(shader3d, nextBody, modelMatrix, texAspect, 0f, 0.075f, nA, 0.26f)
                }
            }
            if (hairTex != null) {
                drawAnimatedHair3d(shader3d, hairTex, modelMatrix, texAspect, t, clampedAlpha)
            }
            GLES20.glDisableVertexAttribArray(shader3d.positionLoc)
            GLES20.glDisableVertexAttribArray(shader3d.texCoordLoc)
            GLES20.glDisableVertexAttribArray(shader3d.normalLoc)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            return
        }

        if (currentEmotion == Live2dEmotion.EMOTION_NORMAL && hasFullRig) {
            drawLayeredNormalRig(
                shader = shader3d,
                texAspect = texAspect,
                t = t,
                breathSin = breathSin,
                blinkWeight = blinkWeight,
                alpha = clampedAlpha,
                isOverlay = isOverlay
            )
        } else {
            drawLayeredEmotionPose(
                shader = shader3d,
                emotion = currentEmotion,
                fallbackTex = baseTex,
                texAspect = texAspect,
                t = t,
                blinkWeight = blinkWeight,
                alpha = clampedAlpha,
                isOverlay = isOverlay
            )
        }

        GLES20.glDisableVertexAttribArray(shader3d.positionLoc)
        GLES20.glDisableVertexAttribArray(shader3d.texCoordLoc)
        GLES20.glDisableVertexAttribArray(shader3d.normalLoc)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawLayeredNormalRig(
        shader: Sprite3dShader,
        texAspect: Float,
        t: Float,
        breathSin: Float,
        blinkWeight: Float,
        alpha: Float,
        isOverlay: Boolean
    ) {
        val torso = layerTextures[LAYER_TORSO] ?: textures[Live2dEmotion.EMOTION_NORMAL] ?: return
        val leftArm = layerTextures[LAYER_LEFT_ARM]
        val rightArm = layerTextures[LAYER_RIGHT_ARM]
        val head = layerTextures[LAYER_HEAD]
        val leftEye = layerTextures[LAYER_LEFT_EYE]
        val rightEye = layerTextures[LAYER_RIGHT_EYE]
        val leftPupil = layerTextures[LAYER_LEFT_PUPIL]
        val rightPupil = layerTextures[LAYER_RIGHT_PUPIL]
        val leftLid = layerTextures[LAYER_LEFT_EYELID]
        val rightLid = layerTextures[LAYER_RIGHT_EYELID]
        val leftBrow = layerTextures[LAYER_LEFT_BROW]
        val rightBrow = layerTextures[LAYER_RIGHT_BROW]
        val mouth = layerTextures[LAYER_MOUTH]
        val hair = layerTextures[LAYER_HAIR]

        // 1. Soft 3D depth drop shadow
        drawDropShadow3d(shader, torso, texAspect, alpha * (if (isOverlay) 0.16f else 0.30f))

        // 2. Torso base (curved chest/waist armor)
        drawMeshPart(shader, torso, modelMatrix, texAspect, 0f, 0.078f, alpha, 0.25f)

        // 3. Independent articulated arms around shoulder pivots
        val armSwingDeg = breathSin * 1.15f + smoothDragX * 1.4f
        if (leftArm != null) {
            buildPivotedMatrix(
                base = modelMatrix,
                out = partMatrix,
                pivotX = -0.22f,
                pivotY = 0.36f,
                zOffset = 0.024f,
                rollDeg = armSwingDeg
            )
            drawMeshPart(shader, leftArm, partMatrix, texAspect, 0f, 0.068f, alpha, 0.28f)
        }
        if (rightArm != null) {
            buildPivotedMatrix(
                base = modelMatrix,
                out = partMatrix,
                pivotX = 0.22f,
                pivotY = 0.36f,
                zOffset = 0.024f,
                rollDeg = -armSwingDeg
            )
            drawMeshPart(shader, rightArm, partMatrix, texAspect, 0f, 0.068f, alpha, 0.28f)
        }

        // 4. Independent head tilt around neck pivot (extra 3D depth vs shoulders!)
        val headMatrix = FloatArray(16)
        val headYaw = smoothDragX * 6.5f + sin(t * 1.12f + 0.4f) * 2.2f
        val headPitch = -smoothDragY * 4.5f + sin(t * 0.84f) * 1.5f
        val headRoll = smoothDragX * 2.1f + sin(t * 0.72f) * 1.7f
        buildHeadMatrix(modelMatrix, headMatrix, zOffset = 0.056f, yawDeg = headYaw, pitchDeg = headPitch, rollDeg = headRoll)

        if (head != null) {
            drawMeshPart(shader, head, headMatrix, texAspect, 0f, 0.082f, alpha, 0.28f)
        }

        // 5. Eyes & sclera
        leftEye?.let { drawMeshPart(shader, it, headMatrix, texAspect, 0.016f, 0.082f, alpha, 0.12f) }
        rightEye?.let { drawMeshPart(shader, it, headMatrix, texAspect, 0.016f, 0.082f, alpha, 0.12f) }

        // 6. Tracked cyan pupils inside eye sockets
        val eyeLookX = (smoothDragX * 0.0105f + sin(t * 0.45f) * 0.0018f).coerceIn(-0.011f, 0.011f)
        val eyeLookY = (smoothDragY * 0.0065f + cos(t * 0.38f) * 0.0012f).coerceIn(-0.007f, 0.007f)
        if (blinkWeight < 0.85f) {
            leftPupil?.let {
                Matrix.translateM(partMatrix, 0, headMatrix, 0, eyeLookX, eyeLookY, 0.022f)
                drawMeshPart(shader, it, partMatrix, texAspect, 0f, 0.082f, alpha, 0.16f)
            }
            rightPupil?.let {
                Matrix.translateM(partMatrix, 0, headMatrix, 0, eyeLookX, eyeLookY, 0.022f)
                drawMeshPart(shader, it, partMatrix, texAspect, 0f, 0.082f, alpha, 0.16f)
            }
        }

        // 7. True blinking eyelids (paupiere_gauche / paupiere_droite)
        if (blinkWeight > 0.05f) {
            val lidAlpha = alpha * blinkWeight
            leftLid?.let { drawMeshPart(shader, it, headMatrix, texAspect, 0.028f, 0.082f, lidAlpha, 0.14f) }
            rightLid?.let { drawMeshPart(shader, it, headMatrix, texAspect, 0.028f, 0.082f, lidAlpha, 0.14f) }
        }

        // 8. Eyebrows & mouth
        val browLift = breathSin * 0.0018f
        if (leftBrow != null || rightBrow != null) {
            Matrix.translateM(partMatrix, 0, headMatrix, 0, 0f, browLift, 0.032f)
            leftBrow?.let { drawMeshPart(shader, it, partMatrix, texAspect, 0f, 0.082f, alpha, 0.14f) }
            rightBrow?.let { drawMeshPart(shader, it, partMatrix, texAspect, 0f, 0.082f, alpha, 0.14f) }
        }
        mouth?.let { drawMeshPart(shader, it, headMatrix, texAspect, 0.026f, 0.082f, alpha, 0.14f) }

        // 9. Animated 3D floating hair strands (meches_cheveux)
        if (hair != null) {
            drawAnimatedHair3d(shader, hair, headMatrix, texAspect, t, alpha)
        }
    }

    private fun drawLayeredEmotionPose(
        shader: Sprite3dShader,
        emotion: String,
        fallbackTex: Live2dTextureManager.TextureInfo,
        texAspect: Float,
        t: Float,
        blinkWeight: Float,
        alpha: Float,
        isOverlay: Boolean
    ) {
        val bodyKey = "pose_${emotion}_body"
        val bodyTex = layerTextures[bodyKey]
        val hair = layerTextures[LAYER_HAIR]

        drawDropShadow3d(shader, bodyTex ?: fallbackTex, texAspect, alpha * (if (isOverlay) 0.16f else 0.30f))

        if (bodyTex != null && hair != null) {
            // Draw hairless body in 3D curved relief
            drawMeshPart(shader, bodyTex, modelMatrix, texAspect, 0f, 0.082f, alpha, 0.26f)

            // Blink cleanly with real eyelids on open-eye emotions (never swap calme praying pose!)
            if (emotion in BLINK_ELIGIBLE_EMOTIONS && blinkWeight > 0.05f) {
                val lidAlpha = alpha * blinkWeight
                layerTextures[LAYER_LEFT_EYELID]?.let {
                    drawMeshPart(shader, it, modelMatrix, texAspect, 0.072f, 0.082f, lidAlpha, 0.14f)
                }
                layerTextures[LAYER_RIGHT_EYELID]?.let {
                    drawMeshPart(shader, it, modelMatrix, texAspect, 0.072f, 0.082f, lidAlpha, 0.14f)
                }
            }

            // Draw swaying hair in 3D foreground layer over the body
            drawAnimatedHair3d(shader, hair, modelMatrix, texAspect, t, alpha)
        } else {
            drawMeshPart(shader, fallbackTex, modelMatrix, texAspect, 0f, 0.082f, alpha, 0.26f)
        }
    }

    private fun drawDropShadow3d(
        shader: Sprite3dShader,
        tex: Live2dTextureManager.TextureInfo,
        texAspect: Float,
        shadowAlpha: Float
    ) {
        if (shadowAlpha <= 0.01f) return
        Matrix.translateM(partMatrix, 0, modelMatrix, 0, 0.042f, -0.025f, -0.095f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, partMatrix, 0)
        buildCurvedMesh(texAspect = texAspect, zOffset = 0f, reliefDepth = 0.04f, isHair = false, timeSec = 0f)
        submitDraw(
            shader = shader,
            texId = tex.id,
            modelMat = partMatrix,
            mvpMat = mvpMatrix,
            alpha = shadowAlpha,
            rimStrength = 0f,
            shadowMode = 1f
        )
    }

    private fun drawAnimatedHair3d(
        shader: Sprite3dShader,
        hairTex: Live2dTextureManager.TextureInfo,
        parentMatrix: FloatArray,
        texAspect: Float,
        t: Float,
        alpha: Float
    ) {
        // Hair layer sits slightly forward (+Z) and sways progressively from crown down to tips
        Matrix.translateM(partMatrix, 0, parentMatrix, 0, 0f, 0f, 0.048f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, partMatrix, 0)
        buildCurvedMesh(
            texAspect = texAspect,
            zOffset = 0f,
            reliefDepth = 0.105f,
            isHair = true,
            timeSec = t
        )
        submitDraw(
            shader = shader,
            texId = hairTex.id,
            modelMat = partMatrix,
            mvpMat = mvpMatrix,
            alpha = alpha,
            rimStrength = 0.34f,
            shadowMode = 0f
        )
    }

    private fun drawMeshPart(
        shader: Sprite3dShader,
        tex: Live2dTextureManager.TextureInfo,
        mat: FloatArray,
        texAspect: Float,
        zOffset: Float,
        reliefDepth: Float,
        alpha: Float,
        rimStrength: Float
    ) {
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, mat, 0)
        buildCurvedMesh(
            texAspect = texAspect,
            zOffset = zOffset,
            reliefDepth = reliefDepth,
            isHair = false,
            timeSec = 0f
        )
        submitDraw(
            shader = shader,
            texId = tex.id,
            modelMat = mat,
            mvpMat = mvpMatrix,
            alpha = alpha,
            rimStrength = rimStrength,
            shadowMode = 0f
        )
    }

    /**
     * Populates a 16x20 subdivided curved 3D mesh with analytical normals.
     * For [isHair], vertices near the crown (`v <= 0.11`) stay anchored to the skull
     * while bangs & side locks sway and flutter organically in X and Z.
     */
    private fun buildCurvedMesh(
        texAspect: Float,
        zOffset: Float,
        reliefDepth: Float,
        isHair: Boolean,
        timeSec: Float
    ) {
        val halfH = 1.0f
        val halfW = texAspect
        val colsPlus1 = MESH_COLS + 1
        val rowsPlus1 = MESH_ROWS + 1

        var pIdx = 0
        var uvIdx = 0
        for (r in 0..MESH_ROWS) {
            val v = r.toFloat() / MESH_ROWS.toFloat()
            val y = halfH * (1f - 2f * v)

            // Hair sway envelope: anchored at crown (v <= 0.11), growing along bangs & side locks
            val hairT = ((v - 0.10f) / 0.18f).coerceIn(0f, 1f)
            val hairWeight = hairT.pow(1.45f)

            for (c in 0..MESH_COLS) {
                val u = c.toFloat() / MESH_COLS.toFloat()
                var x = halfW * (2f * u - 1f)

                val dxNorm = (u - 0.5f) * 2f
                val dyNorm = (v - 0.45f) * 2f
                val horizCurve = cos((dxNorm * PI * 0.43).coerceIn(-PI * 0.48, PI * 0.48)).toFloat().coerceAtLeast(0f)
                val vertCurve = cos((dyNorm * PI * 0.25).coerceIn(-PI * 0.45, PI * 0.45)).toFloat().coerceAtLeast(0.35f)
                var z = zOffset + reliefDepth * horizCurve * vertCurve

                if (isHair && hairWeight > 0f) {
                    val sideFlare = 1.0f + 0.55f * (kotlin.math.abs(dxNorm) * 1.4f).coerceIn(0f, 1f)
                    val swayX = (sin(timeSec * 2.25f + u * 4.5f) * 0.019f +
                        sin(timeSec * 3.8f + v * 7.0f) * 0.006f +
                        hairLagX * 0.85f) * hairWeight * sideFlare
                    val flutterZ = (cos(timeSec * 2.6f + dxNorm * 3.0f) * 0.015f +
                        kotlin.math.abs(hairLagX) * 0.35f) * hairWeight * sideFlare
                    x += swayX
                    z += flutterZ
                }

                vertexPositions[pIdx] = x
                vertexPositions[pIdx + 1] = y
                vertexPositions[pIdx + 2] = z
                pIdx += 3

                vertexUvs[uvIdx] = u
                vertexUvs[uvIdx + 1] = v
                uvIdx += 2
            }
        }

        // Compute smooth surface normals from finite differences on the curved grid
        for (r in 0..MESH_ROWS) {
            val rPrev = max(0, r - 1)
            val rNext = kotlin.math.min(MESH_ROWS, r + 1)
            for (c in 0..MESH_COLS) {
                val cPrev = max(0, c - 1)
                val cNext = kotlin.math.min(MESH_COLS, c + 1)

                val idxL = (r * colsPlus1 + cPrev) * 3
                val idxR = (r * colsPlus1 + cNext) * 3
                val idxU = (rPrev * colsPlus1 + c) * 3
                val idxD = (rNext * colsPlus1 + c) * 3

                // Tangent along +X (cPrev -> cNext)
                val tx = vertexPositions[idxR] - vertexPositions[idxL]
                val ty = vertexPositions[idxR + 1] - vertexPositions[idxL + 1]
                val tz = vertexPositions[idxR + 2] - vertexPositions[idxL + 2]

                // Tangent along +Y (rNext -> rPrev, since r=0 is top +Y)
                val bx = vertexPositions[idxU] - vertexPositions[idxD]
                val by = vertexPositions[idxU + 1] - vertexPositions[idxD + 1]
                val bz = vertexPositions[idxU + 2] - vertexPositions[idxD + 2]

                // Normal = T x B (points toward +Z camera)
                var nx = ty * bz - tz * by
                var ny = tz * bx - tx * bz
                var nz = tx * by - ty * bx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-6f) {
                    nx /= len
                    ny /= len
                    nz /= len
                } else {
                    nx = 0f; ny = 0f; nz = 1f
                }

                val idx = (r * colsPlus1 + c) * 3
                vertexNormals[idx] = nx
                vertexNormals[idx + 1] = ny
                vertexNormals[idx + 2] = nz
            }
        }

        posBuffer.clear(); posBuffer.put(vertexPositions); posBuffer.position(0)
        uvBuffer.clear(); uvBuffer.put(vertexUvs); uvBuffer.position(0)
        normalBuffer.clear(); normalBuffer.put(vertexNormals); normalBuffer.position(0)
    }

    private fun submitDraw(
        shader: Sprite3dShader,
        texId: Int,
        modelMat: FloatArray,
        mvpMat: FloatArray,
        alpha: Float,
        rimStrength: Float,
        shadowMode: Float
    ) {
        GLES20.glEnableVertexAttribArray(shader.positionLoc)
        GLES20.glEnableVertexAttribArray(shader.texCoordLoc)
        GLES20.glEnableVertexAttribArray(shader.normalLoc)

        GLES20.glVertexAttribPointer(shader.positionLoc, 3, GLES20.GL_FLOAT, false, 0, posBuffer)
        GLES20.glVertexAttribPointer(shader.texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glVertexAttribPointer(shader.normalLoc, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)

        GLES20.glUniformMatrix4fv(shader.mvpMatrixLoc, 1, false, mvpMat, 0)
        GLES20.glUniformMatrix4fv(shader.modelMatrixLoc, 1, false, modelMat, 0)
        GLES20.glUniform4f(shader.baseColorLoc, alpha, alpha, alpha, alpha)
        GLES20.glUniform1f(shader.rimStrengthLoc, rimStrength)
        GLES20.glUniform1f(shader.shadowModeLoc, shadowMode)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDEX_COUNT, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
    }

    private fun buildHeadMatrix(
        base: FloatArray,
        out: FloatArray,
        zOffset: Float,
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float
    ) {
        Matrix.translateM(out, 0, base, 0, 0f, HEAD_PIVOT_Y, zOffset)
        Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        Matrix.rotateM(out, 0, pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(out, 0, rollDeg, 0f, 0f, 1f)
        Matrix.translateM(out, 0, 0f, -HEAD_PIVOT_Y, 0f)
    }

    private fun buildPivotedMatrix(
        base: FloatArray,
        out: FloatArray,
        pivotX: Float,
        pivotY: Float,
        zOffset: Float,
        rollDeg: Float
    ) {
        Matrix.translateM(out, 0, base, 0, pivotX, pivotY, zOffset)
        Matrix.rotateM(out, 0, rollDeg, 0f, 0f, 1f)
        Matrix.translateM(out, 0, -pivotX, -pivotY, 0f)
    }

    /**
     * Smooth 165 ms eyelid blink envelope every 3.8 s (returns 0f = open, 1f = closed).
     * Never swaps the full `calme.png` praying pose!
     */
    private fun computeBlinkWeight(nowMs: Long): Float {
        val cyclePos = nowMs % BLINK_INTERVAL_MS
        if (cyclePos >= BLINK_DURATION_MS) return 0f
        val p = cyclePos.toFloat() / BLINK_DURATION_MS.toFloat()
        return sin(p * PI.toFloat()).coerceIn(0f, 1f)
    }

    private fun hasFullNormalRig(): Boolean {
        return layerTextures.containsKey(LAYER_TORSO) &&
            layerTextures.containsKey(LAYER_HEAD) &&
            layerTextures.containsKey(LAYER_HAIR)
    }

    private fun resolveBodyTexture(emotion: String): Live2dTextureManager.TextureInfo? {
        if (emotion == Live2dEmotion.EMOTION_NORMAL) {
            return textures[Live2dEmotion.EMOTION_NORMAL]
        }
        return layerTextures["pose_${emotion}_body"] ?: textures[emotion]
    }

    private fun drawFlatFallback(
        viewWidth: Int,
        viewHeight: Int,
        shaderProgramId: Int,
        activeTex: Live2dTextureManager.TextureInfo,
        dragX: Float,
        dragY: Float,
        opacity: Float,
        isOverlay: Boolean
    ) {
        if (shaderProgramId == 0) return
        val quad = ensureFallbackSprite(shaderProgramId)
        quad.setWindowSize(viewWidth, viewHeight)
        val aspect = activeTex.width.toFloat() / activeTex.height.toFloat().coerceAtLeast(1f)
        val maxH = viewHeight * if (isOverlay) 0.94f else 0.78f
        val maxW = viewWidth * if (isOverlay) 0.94f else 0.92f
        var drawH = maxH
        var drawW = drawH * aspect
        if (drawW > maxW) {
            drawW = maxW
            drawH = drawW / aspect
        }
        val centerX = viewWidth * 0.5f + dragX.coerceIn(-1f, 1f) * viewWidth * 0.025f
        val centerY = viewHeight * (if (isOverlay) 0.50f else 0.52f) + dragY.coerceIn(-1f, 1f) * viewHeight * 0.025f
        quad.resize(centerX, centerY, drawW, drawH)

        GLES20.glUseProgram(shaderProgramId)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val a = opacity.coerceIn(0f, 1f)
        quad.setColor(a, a, a, a)
        quad.renderImmediate(activeTex.id, UV_FULL)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun ensureFallbackSprite(programId: Int): Live2dSprite {
        val existing = fallbackSprite
        if (existing != null && fallbackProgramId == programId) return existing
        val created = Live2dSprite(0f, 0f, 1f, 1f, 0, programId)
        fallbackSprite = created
        fallbackProgramId = programId
        return created
    }

    companion object {
        private const val TAG = "SpriteAvatarRenderer"
        private const val TRANSITION_MS = 180L
        private const val EMOTION_BOUNCE_MS = 380L
        private const val BLINK_INTERVAL_MS = 3800L
        private const val BLINK_DURATION_MS = 165L

        private const val FOV_DEG = 34f
        private const val HEAD_PIVOT_Y = 0.442f

        private const val MESH_COLS = 16
        private const val MESH_ROWS = 20
        private const val VERTEX_COUNT = (MESH_COLS + 1) * (MESH_ROWS + 1)
        private const val INDEX_COUNT = MESH_COLS * MESH_ROWS * 6

        private const val LAYER_TORSO = "torse"
        private const val LAYER_LEFT_ARM = "bras_main_gauche"
        private const val LAYER_RIGHT_ARM = "bras_main_droit"
        private const val LAYER_HEAD = "tete"
        private const val LAYER_LEFT_EYE = "oeil_gauche"
        private const val LAYER_RIGHT_EYE = "oeil_droit"
        private const val LAYER_LEFT_PUPIL = "pupille_gauche"
        private const val LAYER_RIGHT_PUPIL = "pupille_droite"
        private const val LAYER_LEFT_EYELID = "paupiere_gauche"
        private const val LAYER_RIGHT_EYELID = "paupiere_droite"
        private const val LAYER_LEFT_BROW = "sourcil_gauche"
        private const val LAYER_RIGHT_BROW = "sourcil_droit"
        private const val LAYER_MOUTH = "bouche"
        private const val LAYER_HAIR = "meches_cheveux"

        private val LAYER_NAMES = listOf(
            LAYER_TORSO,
            LAYER_LEFT_ARM,
            LAYER_RIGHT_ARM,
            LAYER_HEAD,
            LAYER_LEFT_EYE,
            LAYER_RIGHT_EYE,
            LAYER_LEFT_PUPIL,
            LAYER_RIGHT_PUPIL,
            LAYER_LEFT_EYELID,
            LAYER_RIGHT_EYELID,
            LAYER_LEFT_BROW,
            LAYER_RIGHT_BROW,
            LAYER_MOUTH,
            LAYER_HAIR,
            "pose_joie_body",
            "pose_serieux_body",
            "pose_colere_body",
            "pose_calme_body",
            "pose_fatigue_body"
        )

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

        private fun buildIndexBuffer(): ShortBuffer {
            val indices = ShortArray(INDEX_COUNT)
            val colsPlus1 = MESH_COLS + 1
            var k = 0
            for (r in 0 until MESH_ROWS) {
                for (c in 0 until MESH_COLS) {
                    val tl = (r * colsPlus1 + c).toShort()
                    val tr = (tl + 1).toShort()
                    val bl = ((r + 1) * colsPlus1 + c).toShort()
                    val br = (bl + 1).toShort()
                    indices[k++] = tl
                    indices[k++] = bl
                    indices[k++] = tr
                    indices[k++] = tr
                    indices[k++] = bl
                    indices[k++] = br
                }
            }
            return ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(indices)
                    position(0)
                }
        }

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
