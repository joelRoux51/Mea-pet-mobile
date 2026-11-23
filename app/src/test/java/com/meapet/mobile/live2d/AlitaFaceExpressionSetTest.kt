package com.meapet.mobile.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlitaFaceExpressionSetTest {

    private fun resolveAssetsRoot(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets")
        )
        return candidates.firstOrNull { File(it, "live2d/alita_face_live2d/alita_face.model3.json").isFile }
            ?: error("Could not locate app/src/main/assets/live2d/alita_face_live2d/alita_face.model3.json from ${File(".").absolutePath}")
    }

    @Test
    fun `loads all 11 linked expressions from alita_face model3 and resolves emotion aliases`() {
        val assetsRoot = resolveAssetsRoot()
        val exprSet = AlitaFaceExpressionSet.load(
            dirPath = "live2d/alita_face_live2d/",
            modelJsonName = "alita_face.model3.json",
            readBytes = { relPath ->
                val f = File(assetsRoot, relPath)
                if (f.isFile) f.readBytes() else ByteArray(0)
            }
        )

        val expectedKeys = setOf(
            "normal",
            "joie",
            "serieux",
            "colere",
            "calme",
            "fatigue",
            "malice",
            "etirement",
            "surprise",
            "curiosite",
            "clin_doeil"
        )
        assertEquals(expectedKeys, exprSet.availableKeys())

        assertEquals("joie", exprSet.resolve("happy").name)
        assertEquals("colere", exprSet.resolve("colère").name)
        assertEquals("calme", exprSet.resolve("prière").name)
        assertEquals("fatigue", exprSet.resolve("yawn").name)
        assertEquals("serieux", exprSet.resolve("Sérieux").name)
        assertEquals("malice", exprSet.resolve("taquin").name)
        assertEquals("etirement", exprSet.resolve("étirement").name)
        assertEquals("surprise", exprSet.resolve("alerte").name)
        assertEquals("curiosite", exprSet.resolve("curiosité").name)
        assertEquals("clin_doeil", exprSet.resolve("wink").name)
        assertEquals("normal", exprSet.resolve("unknown_emotion").name)

        // Verify key expressive facial parameter bindings across expressions
        val colere = exprSet.resolve("colere")
        assertTrue(colere.cheek > 0.5f)
        assertEquals(2, colere.mouthVariant)

        val fatigue = exprSet.resolve("fatigue")
        assertEquals(1, fatigue.eyeLidVariant)
        assertEquals(7, fatigue.mouthVariant)

        val wink = exprSet.resolve("clin_doeil")
        assertTrue(wink.eyeLOpen < 0.1f)
        assertTrue(wink.eyeROpen > 0.9f)

        val calme = exprSet.resolve("calme")
        assertEquals(0f, calme.eyeLOpen, 0.001f)
        assertEquals(0f, calme.eyeROpen, 0.001f)
        assertEquals(6, calme.mouthVariant)
    }

    @Test
    fun `all declared face layers exist in bundled assets`() {
        val assetsRoot = resolveAssetsRoot()
        val layersDir = File(assetsRoot, "live2d/alita_face_live2d/layers")
        val requiredLayers = listOf(
            "torse.png",
            "tete.png",
            "joues_colere.png",
            "oeil_gauche.png",
            "oeil_droit.png",
            "pupille_gauche.png",
            "pupille_droite.png",
            "paupiere_gauche.png",
            "paupiere_droite.png",
            "paupiere_fatigue_gauche.png",
            "paupiere_fatigue_droite.png",
            "sourcil_gauche.png",
            "sourcil_droit.png",
            "bouche.png",
            "bouche_joie.png",
            "bouche_colere.png",
            "bouche_malice.png",
            "bouche_surprise.png",
            "bouche_serieux.png",
            "bouche_calme.png",
            "bouche_fatigue.png",
            "meches_cheveux.png"
        )
        for (layer in requiredLayers) {
            val f = File(layersDir, layer)
            assertTrue("Missing layer file: ${f.path}", f.isFile && f.length() > 0)
        }
    }
}
