package com.meapet.mobile.live2d

import android.util.Log
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton manager that owns the Live2D model and drives update/draw.
 */
class Live2dManager private constructor() {

    companion object {
        private const val TAG = "Live2dManager"

        private const val MODEL_DIR_NAME = "live2d/mea_live2d"
        private const val MODEL_JSON_NAME = "mea.model3.json"

        @Volatile
        private var instance: Live2dManager? = null

        fun getInstance(): Live2dManager {
            return instance ?: synchronized(this) {
                instance ?: Live2dManager().also { instance = it }
            }
        }

        fun releaseInstance() {
            CubismOffscreenManagerAndroid.releaseInstance()
            instance = null
        }

        /** 触摸分区触发的消息事件流。ChatViewModel 收集此流显示气泡。 */
        private val _tapMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val tapMessageEvent = _tapMessage.asSharedFlow()

        /** 发送分区消息。可由任意线程调用。 */
        fun emitTapMessage(text: String) {
            _tapMessage.tryEmit(text)
        }
    }

    /** GL 线程写入（loadModel/resetModel），UI 线程可能读取（onUpdate 经 Live2dView.render）。
     *  @Volatile 保证跨线程可见性，避免读到过期引用。 */
    @Volatile
    var model: Live2dModel? = null
        private set

    @Volatile
    var activeSpec: Live2dModelSpec = Live2dModelStore.BUILT_IN_MODEL
        private set

    @Volatile
    private var loadedSpec: Live2dModelSpec? = null

    private var modelLoaded = false

    private val spriteRenderer = SpriteAvatarRenderer()

    @Volatile
    private var dragX = 0f

    @Volatile
    private var dragY = 0f

    private val projection = CubismMatrix44.create()

    data class EmotionRequest(val sequence: Long, val emotion: String)

    private val emotionSequence = AtomicLong(0L)
    private val latestEmotionRef = AtomicReference<EmotionRequest?>(null)

    @Volatile
    private var lastAppliedMainSequence = 0L

    /**
     * Planifie l'application d'une expression Live2D (`.exp3.json`) sur le thread OpenGL.
     * Peut être appelée depuis n'importe quel thread (ex. serveur Webhook sur Dispatchers.IO).
     */
    fun triggerEmotion(emotion: String) {
        val cleaned = emotion.trim()
        if (cleaned.isEmpty()) return
        val seq = emotionSequence.incrementAndGet()
        latestEmotionRef.set(EmotionRequest(sequence = seq, emotion = cleaned))
    }

    /** Retourne la dernière demande d'émotion en attente (partagée entre l'écran principal et la fenêtre flottante). */
    fun latestEmotionRequest(): EmotionRequest? = latestEmotionRef.get()

    fun setActiveModel(spec: Live2dModelSpec): Boolean {
        if (spec == activeSpec) return false
        activeSpec = spec
        Live2dPal.clearByteCache()
        return true
    }

    /** 在 GL 上下文重建或切模型时强制重新加载模型。 */
    fun resetModel() {
        modelLoaded = false
        loadedSpec = null
        spriteRenderer.reset()
        model?.deleteModel()
        model = null
        loadModel()
    }

    fun loadModel() {
        val spec = activeSpec
        if (modelLoaded && loadedSpec == spec) return
        val dir = spec.dirPath
        if (spec.renderType == AvatarRenderType.SPRITE_2D) {
            Log.d(TAG, "Loading 2D sprite avatar from: $dir")
            val ok = spriteRenderer.load(dir, Live2dDelegate.getInstance().textureManager)
            if (ok) {
                model = null
                loadedSpec = spec
                modelLoaded = true
                Log.d(TAG, "2D sprite avatar loaded successfully: ${spec.displayName}")
            } else {
                Log.e(TAG, "Failed to load 2D sprite avatar: ${spec.displayName}")
                activeSpec = Live2dModelStore.BUILT_IN_MODEL
                loadedSpec = null
                modelLoaded = false
                loadModel()
            }
            return
        }
        Log.d(TAG, "Loading model from: $dir${spec.modelJsonName}")
        try {
            val m = Live2dModel(dir)
            m.loadAssets(dir, spec.modelJsonName)
            model = m
            loadedSpec = spec
            modelLoaded = true
            Log.d(TAG, "Model loaded successfully: ${spec.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${spec.displayName}", e)
            // Fall back to built-in model if external model failed to load
            if (!spec.isBuiltIn) {
                activeSpec = Live2dModelStore.BUILT_IN_MODEL
                loadedSpec = null
                modelLoaded = false
                loadModel()
            }
        }
    }

    /**
     * 启动时异步预热模型文件到内存缓存（IO 线程）。
     */
    fun prewarmModel(context: android.content.Context) {
        Live2dPal.prewarmDirectory(context, activeSpec.dirPath)
    }

    fun onUpdate() {
        if (modelLoaded && loadedSpec != activeSpec) {
            Live2dDelegate.getInstance().textureManager.releaseInvalidTextures()
            resetModel()
        }
        val delegate = Live2dDelegate.getInstance()
        val width = delegate.windowWidth
        val height = delegate.windowHeight
        if (width <= 0 || height <= 0) return

        if (loadedSpec?.renderType == AvatarRenderType.SPRITE_2D) {
            latestEmotionRef.get()?.let { req ->
                if (req.sequence != lastAppliedMainSequence) {
                    lastAppliedMainSequence = req.sequence
                    spriteRenderer.setEmotion(req.emotion)
                    Log.d(TAG, "Emotion '${req.emotion}' applied on 2D sprite avatar")
                }
            }
            spriteRenderer.draw(
                viewWidth = width,
                viewHeight = height,
                shaderProgramId = delegate.view.spriteShaderProgramId,
                dragX = dragX,
                dragY = dragY,
                opacity = 1f,
                isOverlay = false
            )
            return
        }

        val m = model ?: return

        val aspect = width.toFloat() / height.toFloat()
        val displayRatio = height.toFloat() / width.toFloat()

        CubismOffscreenManagerAndroid.getInstance().beginFrameProcess()

        projection.loadIdentity()

        val core = m.model ?: return
        val mm = m.modelMatrix ?: return
        val canvasRatio = core.canvasHeight / core.canvasWidth

        if (canvasRatio < displayRatio) {
            mm.setWidth(2.4f)
            projection.scale(1.0f, aspect)
        } else {
            mm.setHeight(2.4f)
            projection.scale(1.0f / aspect, 1.0f)
        }

        delegate.view.preModelDraw(m)
        latestEmotionRef.get()?.let { req ->
            if (req.sequence != lastAppliedMainSequence) {
                lastAppliedMainSequence = req.sequence
                val applied = m.setExpressionForEmotion(req.emotion)
                Log.d(TAG, "Emotion '${req.emotion}' applied on main model: $applied")
            }
        }
        m.update()
        m.draw(projection)
        delegate.view.postModelDraw(m)

        CubismOffscreenManagerAndroid.getInstance().endFrameProcess()
        CubismOffscreenManagerAndroid.getInstance().releaseStaleRenderTextures()
    }

    /** 视角跟随：直接设 model 字段，update 内部会读取并应用。 */
    fun onDrag(x: Float, y: Float) {
        dragX = x.coerceIn(-1f, 1f)
        dragY = y.coerceIn(-1f, 1f)
        model?.let { m ->
            m.dragX = dragX
            m.dragY = dragY
            // 也通知 Cubism SDK 的 dragManager（保留原有 Look 系统兼容）
            try { m.setDragging(x, y) } catch (_: Exception) {}
        }
    }

    fun setRenderTargetSize(width: Int, height: Int) {
        model?.setRenderTargetSize(width, height)
    }
}
