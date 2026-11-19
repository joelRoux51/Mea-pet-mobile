package com.meapet.mobile.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2dEmotionTest {

    @Test
    fun `extractFromText strips EMOTION tag and normalizes whitespace`() {
        val parsed = Live2dEmotion.extractFromText("[EMOTION: joie] C'est l'heure de boire de l'eau !")
        assertEquals("joie", parsed.emotion)
        assertEquals("C'est l'heure de boire de l'eau !", parsed.cleanText)

        val inline = Live2dEmotion.extractFromText("Tu devrais te reposer [emotion : fatigué] maintenant.")
        assertEquals("fatigue", inline.emotion)
        assertEquals("Tu devrais te reposer maintenant.", inline.cleanText)

        val accented = Live2dEmotion.extractFromText("Arrête ça ! [EMOTION: colère]")
        assertEquals("colere", accented.emotion)
        assertEquals("Arrête ça !", accented.cleanText)

        val noTag = Live2dEmotion.extractFromText("Message normal sans balise.")
        assertNull(noTag.emotion)
        assertEquals("Message normal sans balise.", noTag.cleanText)
    }

    @Test
    fun `resolveExpressionKey matches built-in French exp3 expressions`() {
        val builtIn = listOf("joie", "serieux", "calme", "colere", "fatigue", "normal")

        assertEquals("joie", Live2dEmotion.resolveExpressionKey("joie", builtIn))
        assertEquals("serieux", Live2dEmotion.resolveExpressionKey("sérieux", builtIn))
        assertEquals("calme", Live2dEmotion.resolveExpressionKey("calme", builtIn))
        assertEquals("colere", Live2dEmotion.resolveExpressionKey("colère", builtIn))
        assertEquals("fatigue", Live2dEmotion.resolveExpressionKey("fatigue", builtIn))
        assertEquals("normal", Live2dEmotion.resolveExpressionKey("normal", builtIn))
    }

    @Test
    fun `resolveExpressionKey maps emotions to external English exp3 names`() {
        val external = listOf("smile", "serious", "closed_eyes", "angry", "sleepy", "default")

        assertEquals("smile", Live2dEmotion.resolveExpressionKey("joie", external))
        assertEquals("serious", Live2dEmotion.resolveExpressionKey("serieux", external))
        assertEquals("closed_eyes", Live2dEmotion.resolveExpressionKey("calme", external))
        assertEquals("angry", Live2dEmotion.resolveExpressionKey("colere", external))
        assertEquals("sleepy", Live2dEmotion.resolveExpressionKey("fatigue", external))
        assertEquals("default", Live2dEmotion.resolveExpressionKey("normal", external))
        assertTrue(Live2dEmotion.isDefaultEmotion("normal"))
        assertFalse(Live2dEmotion.isDefaultEmotion("joie"))
    }

    @Test
    fun `normalizeEmotionName resolves Jarvis and expressive gesture aliases`() {
        assertEquals("calme", Live2dEmotion.normalizeEmotionName("prière"))
        assertEquals("calme", Live2dEmotion.normalizeEmotionName("verset"))
        assertEquals("joie", Live2dEmotion.normalizeEmotionName("coucou"))
        assertEquals("malice", Live2dEmotion.normalizeEmotionName("ironie"))
        assertEquals("malice", Live2dEmotion.normalizeEmotionName("langue"))
        assertEquals("etirement", Live2dEmotion.normalizeEmotionName("étirement"))
        assertEquals("surprise", Live2dEmotion.normalizeEmotionName("alerte"))
        assertEquals("surprise", Live2dEmotion.normalizeEmotionName("réveil"))
        assertEquals("curiosite", Live2dEmotion.normalizeEmotionName("curiosité"))
        assertEquals("curiosite", Live2dEmotion.normalizeEmotionName("photo"))
        assertEquals("clin_doeil", Live2dEmotion.normalizeEmotionName("clin d'œil"))
    }

    @Test
    fun `inferEmotionFromContext detects Bible verse prayer alarms photos and teasing`() {
        assertEquals(
            "calme",
            Live2dEmotion.inferEmotionFromContext("Donne-moi un verset de la Bible s'il te plaît", "Voici Psaume 23:1")
        )
        assertEquals(
            "calme",
            Live2dEmotion.inferEmotionFromContext("Lis un passage", "Car Dieu a tant aimé le monde (Jean 3:16)")
        )
        assertEquals(
            "surprise",
            Live2dEmotion.inferEmotionFromContext("", "⏰ DEBOUT ! C'est l'heure du réveil !")
        )
        assertEquals(
            "curiosite",
            Live2dEmotion.inferEmotionFromContext("Que vois-tu sur cette photo ?", "Je regarde l'image")
        )
        assertEquals(
            "malice",
            Live2dEmotion.inferEmotionFromContext("Tu te moques ?", "Haha, juste une petite touche d'ironie !")
        )
        assertEquals(
            "etirement",
            Live2dEmotion.inferEmotionFromContext("Fais un étirement", "C'est parti pour une petite pause")
        )
        assertEquals(
            "fatigue",
            Live2dEmotion.inferEmotionFromContext("Bonne nuit Alita", "Repose-toi bien")
        )
    }
}
