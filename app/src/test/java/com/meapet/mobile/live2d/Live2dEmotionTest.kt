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
}
