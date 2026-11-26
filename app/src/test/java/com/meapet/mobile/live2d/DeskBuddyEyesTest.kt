package com.meapet.mobile.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskBuddyEyesTest {

    @Test
    fun `resolveEyePreset maps all 11 emotions and notification badges`() {
        val curious = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_CURIOSITE)
        assertEquals(DeskBuddyEyeShape.CURIOUS, curious.shape)
        assertEquals(DeskBuddyBadge.MESSAGE_BUBBLE, curious.badge)

        val surprise = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_SURPRISE)
        assertEquals(DeskBuddyEyeShape.ALERT, surprise.shape)
        assertEquals(DeskBuddyBadge.ALERT, surprise.badge)
        assertTrue(surprise.isAlertColor)

        val joie = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_JOIE)
        assertEquals(DeskBuddyEyeShape.HAPPY_ARC, joie.shape)
        assertEquals(DeskBuddyBadge.SPARKLE, joie.badge)

        val calme = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_CALME)
        assertEquals(DeskBuddyEyeShape.CALM_CLOSED, calme.shape)
        assertTrue(calme.isDimmed)
        assertEquals(0f, calme.openLeft, 0.001f)
        assertEquals(0f, calme.openRight, 0.001f)

        val fatigue = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_FATIGUE)
        assertEquals(DeskBuddyEyeShape.SLEEPY, fatigue.shape)
        assertEquals(DeskBuddyBadge.SLEEP_ZZ, fatigue.badge)
        assertTrue(fatigue.isDimmed)

        val colere = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_COLERE)
        assertEquals(DeskBuddyEyeShape.ANGRY, colere.shape)
        assertEquals(DeskBuddyBadge.ALERT, colere.badge)
        assertTrue(colere.isAlertColor)

        val malice = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_MALICE)
        assertEquals(DeskBuddyEyeShape.SLY_MALICE, malice.shape)
        assertEquals(DeskBuddyBadge.SPARKLE, malice.badge)

        val wink = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_CLIN_DOEIL)
        assertEquals(DeskBuddyEyeShape.WINK_LEFT, wink.shape)
        assertEquals(0f, wink.openLeft, 0.001f)
        assertEquals(1f, wink.openRight, 0.001f)

        val stretch = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_ETIREMENT)
        assertEquals(DeskBuddyEyeShape.HAPPY_ARC, stretch.shape)
        assertEquals(DeskBuddyBadge.NONE, stretch.badge)

        val focused = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_SERIEUX)
        assertEquals(DeskBuddyEyeShape.FOCUSED, focused.shape)
        assertEquals(DeskBuddyBadge.QUESTION, focused.badge)

        val normal = DeskBuddyEyesRenderer.resolveEyePreset("unknown")
        assertEquals(DeskBuddyEyeShape.NORMAL, normal.shape)
        assertEquals(DeskBuddyBadge.NONE, normal.badge)
        assertFalse(normal.isAlertColor)
    }

    @Test
    fun `computeIdleGaze periodically glances left and right then returns center`() {
        // Default period is 6400ms: [0.18..0.38) -> left (-0.75), [0.54..0.76) -> right (+0.75)
        val centerStart = DeskBuddyEyesRenderer.computeIdleGaze(500L, Live2dEmotion.EMOTION_NORMAL)
        assertEquals(0f, centerStart.x, 0.001f)

        val lookLeft = DeskBuddyEyesRenderer.computeIdleGaze(1600L, Live2dEmotion.EMOTION_NORMAL)
        assertTrue("Expected negative left gaze, got ${lookLeft.x}", lookLeft.x < -0.5f)

        val lookRight = DeskBuddyEyesRenderer.computeIdleGaze(4000L, Live2dEmotion.EMOTION_NORMAL)
        assertTrue("Expected positive right gaze, got ${lookRight.x}", lookRight.x > 0.5f)

        val calmGaze = DeskBuddyEyesRenderer.computeIdleGaze(1600L, Live2dEmotion.EMOTION_CALME)
        assertEquals(0f, calmGaze.x, 0.001f)
    }

    @Test
    fun `computeBlinkWeight performs periodic blinks and occasional double blinks`() {
        // Cycle interval = 3800ms, blink duration = 190ms
        // Outside blink window -> 0f
        assertEquals(0f, DeskBuddyEyesRenderer.computeBlinkWeight(2000L, Live2dEmotion.EMOTION_NORMAL), 0.001f)

        // Midpoint of primary blink (phaseMs = 95ms) -> 1.0f
        val midBlink = DeskBuddyEyesRenderer.computeBlinkWeight(95L, Live2dEmotion.EMOTION_NORMAL)
        assertEquals(1.0f, midBlink, 0.05f)

        // Cycle 2 (nowMs = 2 * 3800 + 340 + 95 = 8035ms) triggers second blink in double-blink!
        val doubleBlink = DeskBuddyEyesRenderer.computeBlinkWeight(2L * 3800L + 340L + 95L, Live2dEmotion.EMOTION_NORMAL)
        assertEquals(1.0f, doubleBlink, 0.05f)

        // Happy / calm / blush closed eyes do not blink
        assertEquals(0f, DeskBuddyEyesRenderer.computeBlinkWeight(95L, Live2dEmotion.EMOTION_JOIE), 0.001f)
        assertEquals(0f, DeskBuddyEyesRenderer.computeBlinkWeight(95L, Live2dEmotion.EMOTION_CALME), 0.001f)
        assertEquals(0f, DeskBuddyEyesRenderer.computeBlinkWeight(95L, Live2dEmotion.EMOTION_ROUGIR), 0.001f)
    }

    @Test
    fun `resolveEyePreset handles blush and wide eyes`() {
        val blush = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_ROUGIR)
        assertEquals(DeskBuddyEyeShape.BLUSH, blush.shape)
        assertEquals(DeskBuddyBadge.HEART, blush.badge)
        assertTrue(blush.hasBlush)

        val wide = DeskBuddyEyesRenderer.resolveEyePreset(Live2dEmotion.EMOTION_GROS_YEUX)
        assertEquals(DeskBuddyEyeShape.WIDE_EYES, wide.shape)
        assertEquals(DeskBuddyBadge.SPARKLE, wide.badge)
        assertFalse(wide.hasBlush)
    }

    @Test
    fun `computeIdleExpression cycles wink wide eyes and sleepy when normal and yields otherwise`() {
        assertEquals(Live2dEmotion.EMOTION_NORMAL, DeskBuddyEyesRenderer.computeIdleExpression(1_000L, Live2dEmotion.EMOTION_NORMAL))
        assertEquals(Live2dEmotion.EMOTION_CLIN_DOEIL, DeskBuddyEyesRenderer.computeIdleExpression(6_500L, Live2dEmotion.EMOTION_NORMAL))
        assertEquals(Live2dEmotion.EMOTION_GROS_YEUX, DeskBuddyEyesRenderer.computeIdleExpression(14_000L, Live2dEmotion.EMOTION_NORMAL))
        assertEquals(Live2dEmotion.EMOTION_FATIGUE, DeskBuddyEyesRenderer.computeIdleExpression(22_000L, Live2dEmotion.EMOTION_NORMAL))

        // Active non-normal emotion is never overridden by idle cycle
        assertEquals(Live2dEmotion.EMOTION_ROUGIR, DeskBuddyEyesRenderer.computeIdleExpression(6_500L, Live2dEmotion.EMOTION_ROUGIR))
        assertEquals(Live2dEmotion.EMOTION_COLERE, DeskBuddyEyesRenderer.computeIdleExpression(22_000L, Live2dEmotion.EMOTION_COLERE))
    }

    @Test
    fun `style mapping covers all 3 Desk Buddy render types`() {
        assertEquals(DeskBuddyStyle.PIXEL_OLED, DeskBuddyStyle.fromRenderType(AvatarRenderType.DESK_BUDDY_PIXEL))
        assertEquals(DeskBuddyStyle.VECTOR_SMOOTH, DeskBuddyStyle.fromRenderType(AvatarRenderType.DESK_BUDDY_VECTOR))
        assertEquals(DeskBuddyStyle.ALITA_CYBER_EYES, DeskBuddyStyle.fromRenderType(AvatarRenderType.DESK_BUDDY_ALITA_EYES))
    }
}
