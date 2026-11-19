package com.meapet.mobile.live2d

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Live2dModelStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `importFromZipStream imports model in nested folder, lists, and deletes`() = runTest {
        val filesDir = tempFolder.newFolder("app_files")
        val store = Live2dModelStore(filesDir = filesDir)

        val modelJson = """{"Version":3,"FileReferences":{"Moc":"hiyori.moc3","Textures":["hiyori.2048/texture_00.png"]}}"""
        val zipBytes = buildZip(
            mapOf(
                "__MACOSX/._hiyori" to byteArrayOf(1, 2),
                "HiyoriPackage/hiyori.model3.json" to modelJson.toByteArray(),
                "HiyoriPackage/hiyori.moc3" to byteArrayOf(10, 20, 30),
                "HiyoriPackage/hiyori.2048/texture_00.png" to byteArrayOf(40, 50)
            )
        )

        val result = store.importFromZipStream(archiveName = "hiyori.zip") {
            ByteArrayInputStream(zipBytes)
        }
        assertTrue(result is Live2dImportResult.Success)
        val spec = (result as Live2dImportResult.Success).spec
        assertEquals("hiyori", spec.displayName)
        assertEquals("hiyori.model3.json", spec.modelJsonName)
        assertFalse(spec.isBuiltIn)
        assertTrue(File(spec.dirPath, "hiyori.moc3").isFile)
        assertTrue(File(spec.dirPath, "hiyori.2048/texture_00.png").isFile)

        val models = store.listModels()
        assertEquals(3, models.size)
        assertTrue(models[0].isBuiltIn)
        assertEquals(AvatarRenderType.LIVE2D, models[0].renderType)
        assertTrue(models[1].isBuiltIn)
        assertEquals(Live2dModelStore.ALITA_SPRITE_MODEL_ID, models[1].id)
        assertEquals(AvatarRenderType.SPRITE_2D, models[1].renderType)
        assertEquals(spec.id, models[2].id)
        assertEquals(spec.id, store.findById(spec.id).id)
        assertEquals(
            Live2dModelStore.ALITA_SPRITE_MODEL_ID,
            store.findById(Live2dModelStore.ALITA_SPRITE_MODEL_ID).id
        )

        assertTrue(store.deleteModel(spec.id))
        assertEquals(2, store.listModels().size)
        assertTrue(store.findById(spec.id).isBuiltIn)
    }

    @Test
    fun `importFromZipStream rejects missing model3 json and missing moc3`() = runTest {
        val filesDir = tempFolder.newFolder("app_files_err")
        val store = Live2dModelStore(filesDir = filesDir)

        val noModelZip = buildZip(mapOf("readme.txt" to "hello".toByteArray()))
        val noModelRes = store.importFromZipStream("bad.zip") { ByteArrayInputStream(noModelZip) }
        assertTrue(noModelRes is Live2dImportResult.MissingModel3Json)

        val missingMocZip = buildZip(
            mapOf("miku.model3.json" to """{"Version":3,"FileReferences":{"Moc":"miku.moc3"}}""".toByteArray())
        )
        val missingMocRes = store.importFromZipStream("miku.zip") { ByteArrayInputStream(missingMocZip) }
        assertTrue(missingMocRes is Live2dImportResult.MissingMoc3)
    }

    @Test
    fun `importFromZipStream ignores zip slip traversal entries`() = runTest {
        val filesDir = tempFolder.newFolder("app_files_slip")
        val store = Live2dModelStore(filesDir = filesDir)

        val zipBytes = buildZip(
            mapOf(
                "../../evil.txt" to "pwn".toByteArray(),
                "model.model3.json" to """{"Version":3,"FileReferences":{"Moc":"cat.moc3"}}""".toByteArray(),
                "cat.moc3" to byteArrayOf(1, 2, 3)
            )
        )
        val res = store.importFromZipStream("my_cat.zip") { ByteArrayInputStream(zipBytes) }
        assertTrue(res is Live2dImportResult.Success)
        val spec = (res as Live2dImportResult.Success).spec
        assertEquals("my_cat", spec.displayName)
        assertFalse(File(filesDir, "evil.txt").exists())
    }

    @Test
    fun `sprite renderer maps all emotion aliases to supported 2d sprite keys`() {
        assertEquals("joie", SpriteAvatarRenderer.resolveSupportedEmotion("joie"))
        assertEquals("joie", SpriteAvatarRenderer.resolveSupportedEmotion("happy"))
        assertEquals("joie", SpriteAvatarRenderer.resolveSupportedEmotion("coucou"))
        assertEquals("serieux", SpriteAvatarRenderer.resolveSupportedEmotion("Sérieux"))
        assertEquals("calme", SpriteAvatarRenderer.resolveSupportedEmotion("peaceful"))
        assertEquals("calme", SpriteAvatarRenderer.resolveSupportedEmotion("prière"))
        assertEquals("colere", SpriteAvatarRenderer.resolveSupportedEmotion("colère"))
        assertEquals("fatigue", SpriteAvatarRenderer.resolveSupportedEmotion("yawn"))
        assertEquals("malice", SpriteAvatarRenderer.resolveSupportedEmotion("malice"))
        assertEquals("etirement", SpriteAvatarRenderer.resolveSupportedEmotion("étirement"))
        assertEquals("surprise", SpriteAvatarRenderer.resolveSupportedEmotion("alerte"))
        assertEquals("curiosite", SpriteAvatarRenderer.resolveSupportedEmotion("curiosité"))
        assertEquals("clin_doeil", SpriteAvatarRenderer.resolveSupportedEmotion("wink"))
        assertEquals("normal", SpriteAvatarRenderer.resolveSupportedEmotion("normal"))
        assertEquals("normal", SpriteAvatarRenderer.resolveSupportedEmotion("unknown_mood"))
    }
}
