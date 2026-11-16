package com.meapet.mobile.live2d

import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.ICubismModelSetting
import com.live2d.sdk.cubism.framework.effect.CubismLook
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater
import com.live2d.sdk.cubism.framework.motion.CubismLookUpdater
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer
import com.live2d.sdk.cubism.framework.rendering.android.CubismRenderTargetAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid

/**
 * Core Live2D model wrapper extending CubismUserModel.
 * Loads .moc3, textures, motions, physics, pose, and expressions.
 */
class Live2dModel(modelDirName: String) : CubismUserModel() {

    val renderingBuffer = CubismRenderTargetAndroid()
    var motionUpdated = false
        private set

    private val idManager = CubismFramework.getIdManager()
    private val idParamAngleX = idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_X.id)
    private val idParamAngleY = idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_Y.id)
    private val idParamAngleZ = idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_Z.id)
    private val idParamBodyAngleX = idManager.getId(CubismDefaultParameterId.ParameterId.BODY_ANGLE_X.id)
    private val idParamEyeBallX = idManager.getId(CubismDefaultParameterId.ParameterId.EYE_BALL_X.id)
    private val idParamEyeBallY = idManager.getId(CubismDefaultParameterId.ParameterId.EYE_BALL_Y.id)

    private var modelHomeDirectory = modelDirName
    private var modelSetting: ICubismModelSetting? = null

    /** 触摸视角跟随的拖拽值（由 Live2dManager 每帧设置）。 */
    var dragX = 0.0f
    var dragY = 0.0f

    private val motions = mutableMapOf<String, ACubismMotion>()
    private val expressions = mutableMapOf<String, ACubismMotion>()
    private val eyeBlinkIds = mutableListOf<CubismId>()
    private val lipSyncIds = mutableListOf<CubismId>()

    init {
        mocConsistency = Live2dDefine.MOC_CONSISTENCY_VALIDATION_ENABLE
    }

    /** User-time seconds accumulator */
    private var userTimeSeconds = 0.0f

    fun loadAssets(dir: String, fileName: String) {
        modelHomeDirectory = dir
        setupModel("$dir$fileName")

        val renderer = CubismRendererAndroid.create(
            Live2dDelegate.getInstance().windowWidth,
            Live2dDelegate.getInstance().windowHeight
        )
        setupRenderer(renderer)

        setupTextures()
    }

    fun deleteModel() {
        delete()
    }

    fun update() {
        // 模型未加载时跳过本帧（GL 线程防御，上层已有 try-catch）
        val m = model ?: return

        isUpdated(false)

        val deltaTimeSeconds = Live2dPal.getDeltaTime()
        userTimeSeconds += deltaTimeSeconds

        motionUpdated = false
        m.loadParameters()

        // Auto-start idle motion if nothing is playing and an idle group has motions
        if (motionManager.isFinished()) {
            val ms = modelSetting
            if (ms != null) {
                when {
                    ms.getMotionCount(Live2dDefine.MotionGroup.IDLE) > 0 ->
                        startMotion(Live2dDefine.MotionGroup.IDLE, 0, Live2dDefine.Priority.IDLE)
                    ms.getMotionCount("idle") > 0 ->
                        startMotion("idle", 0, Live2dDefine.Priority.IDLE)
                }
            }
        } else {
            motionUpdated = motionManager.updateMotion(m, deltaTimeSeconds)
        }

        m.saveParameters()
        updateScheduler.onLateUpdate(m, deltaTimeSeconds)

        // 在 model.update() 之前应用视角跟随参数，确保本帧生效
        applyDragLook(dragX, dragY)

        m.update()

        isUpdated(true)
    }

    fun startMotion(group: String, number: Int, priority: Int): Int {
        val modelSetting = modelSetting ?: return -1
        if (number < 0 || number >= modelSetting.getMotionCount(group)) return -1

        if (priority == Live2dDefine.Priority.FORCE) {
            motionManager.setReservationPriority(priority)
        } else if (!motionManager.reserveMotion(priority)) {
            if (Live2dDefine.DEBUG_LOG_ENABLE) {
                CubismFramework.coreLogFunction("[APP] cannot start motion.")
            }
            return -1
        }

        val fileName = modelSetting.getMotionFileName(group, number)
        val name = "${group}_$number"

        var motion = motions[name] as? CubismMotion

        if (motion == null && fileName.isNotEmpty()) {
            val path = joinModelPath(fileName)
            val buffer = Live2dPal.loadFileAsBytes(path)
            val tmp = if (buffer.isNotEmpty()) loadMotion(buffer) as? CubismMotion else null
            if (tmp != null) {
                motion = tmp
                val fadeIn = modelSetting.getMotionFadeInTimeValue(group, number)
                if (fadeIn != -1.0f) motion.fadeInTime = fadeIn
                val fadeOut = modelSetting.getMotionFadeOutTimeValue(group, number)
                if (fadeOut != -1.0f) motion.fadeOutTime = fadeOut
            }
        }

        if (motion == null) return -1

        if (Live2dDefine.DEBUG_LOG_ENABLE) {
            CubismFramework.coreLogFunction("[APP] start motion: ${group}_$number")
        }

        return motionManager.startMotionPriority(motion, priority)
    }

    fun draw(matrix: CubismMatrix44) {
        if (model == null) {
            try {
                Live2dDelegate.getInstance().activity?.finish()
            } catch (_: Exception) { /* not always in an activity */ }
            return
        }

        // Apply model matrix to the projection matrix
        val mm = modelMatrix ?: return
        CubismMatrix44.multiply(
            mm.array,
            matrix.array,
            matrix.array
        )

        castRenderer<CubismRendererAndroid>().apply {
            setMvpMatrix(matrix)
            drawModel()
        }
    }

    /**
     * 设置渲染器级整体不透明度（0.0~1.0），悬浮窗透明度调节使用。
     *
     * 在 GL 线程每帧调用；透明度经 CubismShaderAndroid 的 u_baseColor 乘进
     * 绘制管线，对所有机型的合成路径统一生效（GLSurfaceView 的 View.setAlpha
     * 在部分机型上不进入 GL Surface 合成，详见 FloatingLive2dService）。
     */
    fun setRenderingOpacity(alpha: Float) {
        castRenderer<CubismRendererAndroid>().setOpacity(alpha)
    }

    /**
     * 根据触摸位置直接设置模型角度和视线参数。
     * 绕过 Cubism Look 系统，更可靠地实现视角跟随。
     *
     * @param dx 归一化触摸 X [-1..1]
     * @param dy 归一化触摸 Y [-1..1]
     */
    fun applyDragLook(dx: Float, dy: Float) {
        val m = model ?: return
        m.setParameterValue(idParamAngleX, dx * 30.0f)
        m.setParameterValue(idParamAngleY, dy * 15.0f)
        m.setParameterValue(idParamEyeBallX, dx)
        m.setParameterValue(idParamEyeBallY, -dy)
    }

    /**
     * (Re-)bind textures using a custom texture manager.
     * Used by the overlay service which has its own GL context and texture manager.
     */
    fun bindTextures(tm: Live2dTextureManager) {
        val ms = modelSetting ?: return
        for (i in 0 until ms.textureCount) {
            val texName = ms.getTextureFileName(i)
            if (texName.isEmpty()) continue
            val texPath = joinModelPath(texName)
            val texInfo = tm.createTextureFromPngFile(texPath) ?: continue
            castRenderer<CubismRendererAndroid>().bindTexture(i, texInfo.id)
            castRenderer<CubismRendererAndroid>().isPremultipliedAlpha(Live2dDefine.PREMULTIPLIED_ALPHA_ENABLE)
        }
    }

    // ---- private helpers ----

    private fun joinModelPath(relative: String): String {
        val cleanRel = relative.removePrefix("./").removePrefix("/")
        return modelHomeDirectory.trimEnd('/') + "/" + cleanRel
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : CubismRenderer> castRenderer(): T = getRenderer() as T

    private fun setupModel(model3JsonPath: String): Boolean {
        val model3Json = Live2dPal.loadFileAsBytes(model3JsonPath)
        if (model3Json.isEmpty()) return false
        modelSetting = CubismModelSettingJson(model3Json)

        if (modelSetting?.json == null) {
            if (Live2dDefine.DEBUG_LOG_ENABLE) {
                CubismFramework.coreLogFunction("[ERROR] model3.json is not found")
            }
            try {
                Live2dDelegate.getInstance().activity?.finish()
            } catch (_: Exception) { /* overlay mode — no activity to finish */ }
            return false
        }

        // 上面已确认 modelSetting.json 非空；取非空引用避免反复 !!
        val ms = modelSetting ?: return false

        // Load .moc3
        val mocName = ms.modelFileName
        if (mocName.isNotEmpty()) {
            val buffer = Live2dPal.loadFileAsBytes(joinModelPath(mocName))
            if (buffer.isNotEmpty()) {
                loadModel(buffer, mocConsistency)
            }
        }

        // Expressions
        val expCount = ms.expressionCount
        if (expCount > 0) {
            for (i in 0 until expCount) {
                val name = ms.getExpressionName(i)
                val expFile = ms.getExpressionFileName(i)
                if (expFile.isEmpty()) continue
                val buffer = Live2dPal.loadFileAsBytes(joinModelPath(expFile))
                if (buffer.isNotEmpty()) {
                    loadExpression(buffer)?.let { expressions[name] = it }
                }
            }
            updateScheduler.addUpdatableList(CubismExpressionUpdater(expressionManager))
        }

        // Pose
        val posePath = ms.poseFileName
        if (posePath.isNotEmpty()) {
            val buffer = Live2dPal.loadFileAsBytes(joinModelPath(posePath))
            if (buffer.isNotEmpty()) loadPose(buffer)
        }
        if (pose != null) {
            updateScheduler.addUpdatableList(CubismPoseUpdater(pose))
        }

        // Physics
        val physicsPath = ms.physicsFileName
        if (physicsPath.isNotEmpty()) {
            val buffer = Live2dPal.loadFileAsBytes(joinModelPath(physicsPath))
            if (buffer.isNotEmpty()) loadPhysics(buffer)
        }
        if (physics != null) {
            updateScheduler.addUpdatableList(CubismPhysicsUpdater(physics))
        }

        // UserData
        val userDataPath = ms.userDataFile
        if (userDataPath.isNotEmpty()) {
            val buffer = Live2dPal.loadFileAsBytes(joinModelPath(userDataPath))
            if (buffer.isNotEmpty()) loadUserData(buffer)
        }

        // Look (eye tracking by drag)
        val look = CubismLook.create()
        look.setParameters(
            listOf(
                CubismLook.LookParameterData(idParamAngleX, 30.0f),
                CubismLook.LookParameterData(idParamAngleY, 0.0f, 30.0f),
                CubismLook.LookParameterData(idParamAngleZ, 0.0f, 0.0f, -30.0f),
                CubismLook.LookParameterData(idParamBodyAngleX, 10.0f),
                CubismLook.LookParameterData(idParamEyeBallX, 1.0f),
                CubismLook.LookParameterData(idParamEyeBallY, 0.0f, 1.0f)
            )
        )
        updateScheduler.addUpdatableList(CubismLookUpdater(look, dragManager))

        updateScheduler.sortUpdatableList()

        // Layout
        val layout = mutableMapOf<String, Float>()
        ms.getLayoutMap(layout)
        val mm = modelMatrix ?: return false
        mm.setupFromLayout(layout)

        model?.saveParameters()

        // Preload motions
        for (i in 0 until ms.motionGroupCount) {
            val group = ms.getMotionGroupName(i)
            preLoadMotionGroup(group)
        }

        motionManager.stopAllMotions()
        return true
    }

    private fun preLoadMotionGroup(group: String) {
        val ms = modelSetting ?: return
        val count = ms.getMotionCount(group)
        for (i in 0 until count) {
            val name = "${group}_$i"
            val path = ms.getMotionFileName(group, i)
            if (path.isEmpty()) continue

            val fullPath = joinModelPath(path)
            val buffer = Live2dPal.loadFileAsBytes(fullPath)
            if (buffer.isEmpty()) continue
            val tmp = loadMotion(buffer) as? CubismMotion ?: continue

            val fadeIn = ms.getMotionFadeInTimeValue(group, i)
            if (fadeIn != -1.0f) tmp.fadeInTime = fadeIn
            val fadeOut = ms.getMotionFadeOutTimeValue(group, i)
            if (fadeOut != -1.0f) tmp.fadeOutTime = fadeOut
            tmp.setEffectIds(eyeBlinkIds, lipSyncIds)

            motions[name] = tmp
        }
    }

    private fun setupTextures() {
        val modelSetting = modelSetting ?: return
        for (i in 0 until modelSetting.textureCount) {
            val texName = modelSetting.getTextureFileName(i)
            if (texName.isEmpty()) continue

            val texPath = joinModelPath(texName)
            val texInfo = Live2dDelegate.getInstance()
                .textureManager
                .createTextureFromPngFile(texPath) ?: continue

            castRenderer<CubismRendererAndroid>().bindTexture(i, texInfo.id)
            castRenderer<CubismRendererAndroid>().isPremultipliedAlpha(Live2dDefine.PREMULTIPLIED_ALPHA_ENABLE)
        }
    }
}
