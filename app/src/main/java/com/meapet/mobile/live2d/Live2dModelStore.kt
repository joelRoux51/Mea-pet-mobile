package com.meapet.mobile.live2d

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

enum class AvatarRenderType {
    LIVE2D,
    SPRITE_2D,
    ALITA_FACE_LIVE2D,
    DESK_BUDDY_PIXEL,
    DESK_BUDDY_VECTOR,
    DESK_BUDDY_ALITA_EYES;

    val isAlitaSpriteFamily: Boolean
        get() = this == SPRITE_2D || this == ALITA_FACE_LIVE2D

    val isDeskBuddyEyes: Boolean
        get() = this == DESK_BUDDY_PIXEL ||
            this == DESK_BUDDY_VECTOR ||
            this == DESK_BUDDY_ALITA_EYES
}

/**
 * Runtime specification of an avatar model (built-in Mea Live2D, built-in Alita 2D sprites,
 * built-in Alita Face Live2D, built-in Desk Buddy OLED eyes, or an imported external Live2D model).
 *
 * @property id Empty string for built-in Mea, or a built-in constant ID, or UUID string for imported models.
 * @property displayName Human-readable model name shown in settings.
 * @property dirPath Directory path ending with `/`. Asset-relative for built-in, absolute filesystem path for imported.
 * @property modelJsonName Relative filename of the `.model3.json` (or default PNG) inside [dirPath].
 * @property isBuiltIn True when using bundled APK assets or built-in procedural renderer.
 * @property renderType Rendering pipeline used for this avatar.
 */
data class Live2dModelSpec(
    val id: String,
    val displayName: String,
    val dirPath: String,
    val modelJsonName: String,
    val isBuiltIn: Boolean = false,
    val renderType: AvatarRenderType = AvatarRenderType.LIVE2D
)

@Serializable
internal data class Live2dModelMetadata(
    val id: String,
    val displayName: String,
    val modelJsonName: String,
    val createdAt: Long = System.currentTimeMillis()
)

sealed interface Live2dImportResult {
    data class Success(val spec: Live2dModelSpec) : Live2dImportResult
    data object CannotReadFile : Live2dImportResult
    data object EmptyArchive : Live2dImportResult
    data object MissingModel3Json : Live2dImportResult
    data object MissingMoc3 : Live2dImportResult
    data class Failed(val detail: String?) : Live2dImportResult
}

/**
 * Manages built-in and externally imported (`.zip`) avatar models in app-private storage.
 */
class Live2dModelStore(
    private val filesDir: File,
    private val context: Context? = null
) {
    constructor(context: Context) : this(context.filesDir, context.applicationContext)

    private val modelsRoot: File get() = File(filesDir, MODELS_DIR)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "Live2dModelStore"
        private const val MODELS_DIR = "live2d_models"
        private const val META_FILENAME = ".meapet_model.json"
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

        const val ALITA_SPRITE_MODEL_ID = "builtin_alita_sprite_2d"
        const val ALITA_FACE_MODEL_ID = "builtin_alita_face_live2d"
        const val DESK_BUDDY_PIXEL_MODEL_ID = "builtin_desk_buddy_pixel"
        const val DESK_BUDDY_VECTOR_MODEL_ID = "builtin_desk_buddy_vector"
        const val DESK_BUDDY_ALITA_EYES_MODEL_ID = "builtin_desk_buddy_alita_eyes"

        val BUILT_IN_MODEL = Live2dModelSpec(
            id = "",
            displayName = "Mea",
            dirPath = "live2d/mea_live2d/",
            modelJsonName = "mea.model3.json",
            isBuiltIn = true,
            renderType = AvatarRenderType.LIVE2D
        )

        val ALITA_SPRITE_MODEL = Live2dModelSpec(
            id = ALITA_SPRITE_MODEL_ID,
            displayName = "Alita (2D)",
            dirPath = "sprites/alita/",
            modelJsonName = "normal.png",
            isBuiltIn = true,
            renderType = AvatarRenderType.SPRITE_2D
        )

        val ALITA_FACE_MODEL = Live2dModelSpec(
            id = ALITA_FACE_MODEL_ID,
            displayName = "Alita (Visage Live2D)",
            dirPath = "live2d/alita_face_live2d/",
            modelJsonName = "alita_face.model3.json",
            isBuiltIn = true,
            renderType = AvatarRenderType.ALITA_FACE_LIVE2D
        )

        val DESK_BUDDY_PIXEL_MODEL = Live2dModelSpec(
            id = DESK_BUDDY_PIXEL_MODEL_ID,
            displayName = "Desk Buddy Pixel-LED",
            dirPath = "desk_buddy/pixel/",
            modelJsonName = "pixel_oled",
            isBuiltIn = true,
            renderType = AvatarRenderType.DESK_BUDDY_PIXEL
        )

        val DESK_BUDDY_VECTOR_MODEL = Live2dModelSpec(
            id = DESK_BUDDY_VECTOR_MODEL_ID,
            displayName = "Desk Buddy Vector",
            dirPath = "desk_buddy/vector/",
            modelJsonName = "vector_oled",
            isBuiltIn = true,
            renderType = AvatarRenderType.DESK_BUDDY_VECTOR
        )

        val DESK_BUDDY_ALITA_EYES_MODEL = Live2dModelSpec(
            id = DESK_BUDDY_ALITA_EYES_MODEL_ID,
            displayName = "Desk Buddy Cyber-Alita",
            dirPath = "desk_buddy/alita_eyes/",
            modelJsonName = "alita_eyes_oled",
            isBuiltIn = true,
            renderType = AvatarRenderType.DESK_BUDDY_ALITA_EYES
        )
    }

    fun listModels(): List<Live2dModelSpec> {
        val imported = listImportedInternal()
            .sortedBy { it.first.createdAt }
            .map { it.second }
        return listOf(
            BUILT_IN_MODEL,
            ALITA_SPRITE_MODEL,
            ALITA_FACE_MODEL,
            DESK_BUDDY_PIXEL_MODEL,
            DESK_BUDDY_VECTOR_MODEL,
            DESK_BUDDY_ALITA_EYES_MODEL
        ) + imported
    }

    fun findById(id: String): Live2dModelSpec {
        if (id.isBlank()) return BUILT_IN_MODEL
        if (id == ALITA_SPRITE_MODEL_ID) return ALITA_SPRITE_MODEL
        if (id == ALITA_FACE_MODEL_ID) return ALITA_FACE_MODEL
        if (id == DESK_BUDDY_PIXEL_MODEL_ID) return DESK_BUDDY_PIXEL_MODEL
        if (id == DESK_BUDDY_VECTOR_MODEL_ID) return DESK_BUDDY_VECTOR_MODEL
        if (id == DESK_BUDDY_ALITA_EYES_MODEL_ID) return DESK_BUDDY_ALITA_EYES_MODEL
        return listModels().firstOrNull { it.id == id } ?: BUILT_IN_MODEL
    }

    fun deleteModel(id: String): Boolean {
        if (id.isBlank()) return false
        val safeId = sanitizeId(id) ?: return false
        val dir = File(modelsRoot, safeId)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    suspend fun importFromZip(uri: Uri): Live2dImportResult = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Live2dImportResult.CannotReadFile
        val displayName = resolveUriDisplayName(ctx, uri)
        importFromZipStream(
            archiveName = displayName,
            openStream = { ctx.contentResolver.openInputStream(uri) }
        )
    }

    /**
     * Extracts and validates a Live2D `.zip` archive into `filesDir/live2d_models/<id>/`.
     * Stream factory is invoked twice (scan + extract) so even large archives use bounded memory.
     */
    suspend fun importFromZipStream(
        archiveName: String?,
        openStream: () -> InputStream?
    ): Live2dImportResult = withContext(Dispatchers.IO) {
        var targetDir: File? = null
        try {
            val entries = mutableListOf<String>()
            val firstStream = openStream() ?: return@withContext Live2dImportResult.CannotReadFile
            firstStream.use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val normalized = normalizeEntryPath(entry.name)
                            if (normalized != null && !shouldIgnoreEntry(normalized)) {
                                entries.add(normalized)
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }

            if (entries.isEmpty()) {
                return@withContext Live2dImportResult.EmptyArchive
            }

            // Pick the shallowest *.model3.json inside the archive
            val model3Entry = entries
                .filter { it.endsWith(".model3.json", ignoreCase = true) }
                .minByOrNull { it.count { c -> c == '/' } }
                ?: return@withContext Live2dImportResult.MissingModel3Json

            val rootPrefix = if ('/' in model3Entry) {
                model3Entry.substringBeforeLast('/') + "/"
            } else {
                ""
            }
            val modelJsonName = model3Entry.removePrefix(rootPrefix)

            val id = UUID.randomUUID().toString()
            targetDir = File(modelsRoot, id).apply { mkdirs() }
            val canonicalRoot = targetDir.canonicalPath + File.separator

            var totalWritten = 0L
            val buf = ByteArray(32 * 1024)
            val secondStream = openStream() ?: run {
                targetDir.deleteRecursively()
                return@withContext Live2dImportResult.CannotReadFile
            }
            secondStream.use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val normalized = normalizeEntryPath(entry.name)
                            if (normalized != null &&
                                !shouldIgnoreEntry(normalized) &&
                                normalized.startsWith(rootPrefix)
                            ) {
                                val relPath = normalized.removePrefix(rootPrefix)
                                if (relPath.isNotEmpty()) {
                                    val outFile = File(targetDir, relPath)
                                    // Zip-Slip check
                                    if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                                        throw SecurityException("Invalid zip entry path: ${entry.name}")
                                    }
                                    outFile.parentFile?.mkdirs()
                                    var entryBytes = 0L
                                    outFile.outputStream().buffered().use { out ->
                                        while (true) {
                                            val n = zis.read(buf, 0, buf.size)
                                            if (n < 0) break
                                            entryBytes += n
                                            totalWritten += n
                                            if (entryBytes > MAX_ENTRY_BYTES || totalWritten > MAX_TOTAL_BYTES) {
                                                throw IllegalStateException("Archive exceeds size limit")
                                            }
                                            out.write(buf, 0, n)
                                        }
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }

            // Validate referenced .moc3 exists
            val modelJsonFile = File(targetDir, modelJsonName)
            if (!modelJsonFile.isFile || !validateMocExists(targetDir, modelJsonFile)) {
                targetDir.deleteRecursively()
                return@withContext Live2dImportResult.MissingMoc3
            }

            val modelDisplayName = deriveDisplayName(modelJsonName, archiveName)
            val meta = Live2dModelMetadata(
                id = id,
                displayName = modelDisplayName,
                modelJsonName = modelJsonName
            )
            File(targetDir, META_FILENAME).writeText(json.encodeToString(meta))

            val spec = Live2dModelSpec(
                id = id,
                displayName = modelDisplayName,
                dirPath = targetDir.absolutePath.trimEnd('/') + "/",
                modelJsonName = modelJsonName,
                isBuiltIn = false
            )
            Log.i(TAG, "Imported Live2D model '$modelDisplayName' (${entries.size} entries) -> ${spec.dirPath}")
            Live2dImportResult.Success(spec)
        } catch (e: Exception) {
            Log.e(TAG, "Live2D import failed", e)
            targetDir?.deleteRecursively()
            Live2dImportResult.Failed(e.message)
        }
    }

    private fun listImportedInternal(): List<Pair<Live2dModelMetadata, Live2dModelSpec>> {
        val root = modelsRoot
        if (!root.isDirectory) return emptyList()
        val dirs = root.listFiles() ?: return emptyList()
        val results = mutableListOf<Pair<Live2dModelMetadata, Live2dModelSpec>>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, META_FILENAME)
            if (!metaFile.isFile) continue
            try {
                val meta = json.decodeFromString<Live2dModelMetadata>(metaFile.readText())
                val modelJson = File(dir, meta.modelJsonName)
                if (!modelJson.isFile) continue
                results.add(
                    meta to Live2dModelSpec(
                        id = meta.id,
                        displayName = meta.displayName,
                        dirPath = dir.absolutePath.trimEnd('/') + "/",
                        modelJsonName = meta.modelJsonName,
                        isBuiltIn = false
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid model dir ${dir.name}: ${e.message}")
            }
        }
        return results
    }

    private fun validateMocExists(modelDir: File, modelJsonFile: File): Boolean {
        return try {
            val root = json.parseToJsonElement(modelJsonFile.readText()).jsonObject
            val fileRefs = root["FileReferences"]?.jsonObject ?: return false
            val mocRel = fileRefs["Moc"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.removePrefix("./")?.removePrefix("/") ?: return false
            if (mocRel.isEmpty()) return false
            val mocFile = File(modelDir, mocRel)
            mocFile.isFile && mocFile.length() > 0 &&
                mocFile.canonicalPath.startsWith(modelDir.canonicalPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun deriveDisplayName(modelJsonName: String, archiveName: String?): String {
        val fromModel = modelJsonName
            .substringAfterLast('/')
            .removeSuffix(".model3.json")
            .trim()
        if (fromModel.isNotEmpty() && !fromModel.equals("model", ignoreCase = true)) {
            return fromModel
        }
        val fromArchive = archiveName
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        return fromArchive.ifEmpty { "Live2D Model" }
    }

    private fun resolveUriDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeId(id: String): String? {
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id == "." || id == "..") {
            return null
        }
        return id
    }

    private fun normalizeEntryPath(raw: String): String? {
        val cleaned = raw.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty() || cleaned.contains("../") || cleaned == "..") return null
        return cleaned
    }

    private fun shouldIgnoreEntry(path: String): Boolean {
        val fileName = path.substringAfterLast('/')
        return path.startsWith("__MACOSX/") || fileName.startsWith("._")
    }
}
