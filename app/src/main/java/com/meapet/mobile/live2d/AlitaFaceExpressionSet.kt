package com.meapet.mobile.live2d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Linked Live2D facial expression parsed from `alita_face.model3.json` and `expressions` `.exp3.json` files.
 */
data class AlitaFaceExpression(
    val name: String,
    val fadeInMs: Long = 240L,
    val angleX: Float = 0f,
    val angleY: Float = 0f,
    val angleZ: Float = 0f,
    val eyeLOpen: Float = 1f,
    val eyeROpen: Float = 1f,
    val eyeBallX: Float = 0f,
    val eyeBallY: Float = 0f,
    val pupilScale: Float = 1f,
    val browLY: Float = 0f,
    val browRY: Float = 0f,
    val browLAngle: Float = 0f,
    val browRAngle: Float = 0f,
    val cheek: Float = 0f,
    val mouthVariant: Int = 0,
    val eyeLidVariant: Int = 0
)

/**
 * Collection of linked expressions declared by `alita_face.model3.json`.
 */
class AlitaFaceExpressionSet(
    val expressions: Map<String, AlitaFaceExpression>
) {

    fun availableKeys(): Set<String> = expressions.keys

    fun resolve(emotion: String): AlitaFaceExpression {
        val resolvedKey = Live2dEmotion.resolveExpressionKey(emotion, expressions.keys)
        return (resolvedKey?.let { expressions[it] })
            ?: expressions[Live2dEmotion.EMOTION_NORMAL]
            ?: DEFAULT_EXPRESSION
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val DEFAULT_EXPRESSION = AlitaFaceExpression(name = Live2dEmotion.EMOTION_NORMAL)

        fun load(
            dirPath: String,
            modelJsonName: String = "alita_face.model3.json",
            readBytes: (String) -> ByteArray = Live2dPal::loadFileAsBytes
        ): AlitaFaceExpressionSet {
            val baseDir = dirPath.trimEnd('/')
            val modelBytes = readBytes("$baseDir/$modelJsonName")
            if (modelBytes.isEmpty()) {
                return AlitaFaceExpressionSet(emptyMap())
            }
            return try {
                val root = json.parseToJsonElement(modelBytes.decodeToString()).jsonObject
                val fileRefs = root["FileReferences"]?.jsonObject ?: return AlitaFaceExpressionSet(emptyMap())
                val exprArray = fileRefs["Expressions"]?.jsonArray ?: return AlitaFaceExpressionSet(emptyMap())

                val loaded = linkedMapOf<String, AlitaFaceExpression>()
                for (elem in exprArray) {
                    val entry = elem.jsonObject
                    val rawName = entry["Name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val relFile = entry["File"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.removePrefix("./")?.removePrefix("/").orEmpty()
                    if (relFile.isEmpty()) continue
                    val name = rawName.ifEmpty {
                        relFile.substringAfterLast('/').removeSuffix(".exp3.json").trim()
                    }
                    if (name.isEmpty()) continue
                    val expBytes = readBytes("$baseDir/$relFile")
                    if (expBytes.isEmpty()) continue
                    parseExpression(name, expBytes.decodeToString())?.let { loaded[name] = it }
                }
                AlitaFaceExpressionSet(loaded)
            } catch (_: Exception) {
                AlitaFaceExpressionSet(emptyMap())
            }
        }

        fun parseExpression(name: String, rawJson: String): AlitaFaceExpression? {
            return try {
                val root: JsonObject = json.parseToJsonElement(rawJson).jsonObject
                val fadeInSec = root["FadeInTime"]?.jsonPrimitive?.floatOrNull ?: 0.24f
                val fadeInMs = (fadeInSec * 1000f).toLong().coerceIn(80L, 800L)
                val params = mutableMapOf<String, Float>()
                root["Parameters"]?.jsonArray?.forEach { p ->
                    val obj = p.jsonObject
                    val id = obj["Id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val value = obj["Value"]?.jsonPrimitive?.floatOrNull ?: return@forEach
                    params[id] = value
                }
                AlitaFaceExpression(
                    name = name,
                    fadeInMs = fadeInMs,
                    angleX = params["ParamAngleX"] ?: 0f,
                    angleY = params["ParamAngleY"] ?: 0f,
                    angleZ = params["ParamAngleZ"] ?: 0f,
                    eyeLOpen = (params["ParamEyeLOpen"] ?: 1f).coerceIn(0f, 1f),
                    eyeROpen = (params["ParamEyeROpen"] ?: 1f).coerceIn(0f, 1f),
                    eyeBallX = (params["ParamEyeBallX"] ?: 0f).coerceIn(-1f, 1f),
                    eyeBallY = (params["ParamEyeBallY"] ?: 0f).coerceIn(-1f, 1f),
                    pupilScale = (params["ParamPupilScale"] ?: 1f).coerceIn(0.65f, 1.3f),
                    browLY = (params["ParamBrowLY"] ?: 0f).coerceIn(-1f, 1f),
                    browRY = (params["ParamBrowRY"] ?: 0f).coerceIn(-1f, 1f),
                    browLAngle = (params["ParamBrowLAngle"] ?: 0f).coerceIn(-1f, 1f),
                    browRAngle = (params["ParamBrowRAngle"] ?: 0f).coerceIn(-1f, 1f),
                    cheek = (params["ParamCheek"] ?: 0f).coerceIn(0f, 1f),
                    mouthVariant = (params["ParamMouthVariant"] ?: 0f).toInt(),
                    eyeLidVariant = (params["ParamEyeLidVariant"] ?: 0f).toInt()
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
