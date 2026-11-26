package com.meapet.mobile.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchReactionHelperTest {

    private val allVoiceFiles = listOf(
        "jp_有事吗？.ogg",
        "jp_嘿嘿。.ogg",
        "jp_啊，我在想事情呢，干嘛？.ogg",
        "jp_哼。.ogg",
        "jp_没事就别打扰我。.ogg",
        "jp_别摸我头发。.ogg",
        "jp_啊......摸耳朵前，先说一声喵。.ogg",
        "jp_别摸了.ogg",
        "jp_变态。.ogg",
        "jp_你想死一次吗？.ogg",
        "jp_尾巴是很敏感的不知道吗。.ogg",
        "jp_尾巴不许碰喵！！.ogg"
    )

    private val cjkRegex = Regex("""[\u4E00-\u9FFF]""")

    @Test
    fun `all 12 chinese voice clips translate into clean french without cjk characters`() {
        for (file in allVoiceFiles) {
            val key = TouchReactionHelper.extractVoiceKey(file)
            val frAlita = TouchReactionHelper.translateVoiceText(key, langCode = "fr", isAlita = true)
            val frMea = TouchReactionHelper.translateVoiceText(key, langCode = "fr", isAlita = false)
            val enAlita = TouchReactionHelper.translateVoiceText(key, langCode = "en", isAlita = true)
            val esAlita = TouchReactionHelper.translateVoiceText(key, langCode = "es", isAlita = true)

            assertFalse("Alita FR still contains CJK for $file: $frAlita", cjkRegex.containsMatchIn(frAlita))
            assertFalse("Mea FR still contains CJK for $file: $frMea", cjkRegex.containsMatchIn(frMea))
            assertFalse("Alita EN still contains CJK for $file: $enAlita", cjkRegex.containsMatchIn(enAlita))
            assertFalse("Alita ES still contains CJK for $file: $esAlita", cjkRegex.containsMatchIn(esAlita))
        }
    }

    @Test
    fun `cat ear and tail lines adapt to Alita without meow or tail`() {
        val earFr = TouchReactionHelper.translateVoiceText(
            rawKey = "啊......摸耳朵前，先说一声喵。",
            langCode = "fr",
            isAlita = true
        )
        assertFalse(earFr.contains("miaou", ignoreCase = true))
        assertFalse(earFr.contains("oreilles", ignoreCase = true))

        val tailFr = TouchReactionHelper.translateVoiceText(
            rawKey = "尾巴不许碰喵！！",
            langCode = "fr",
            isAlita = true
        )
        assertFalse(tailFr.contains("miaou", ignoreCase = true))
        assertFalse(tailFr.contains("queue", ignoreCase = true))
    }

    @Test
    fun `repeated taps escalate emotions from friendly to annoyed to angry and reset after timeout`() {
        val tracker = TouchReactionTracker(tapResetMs = 10_000L)

        val tap1 = tracker.onTap(allVoiceFiles, nowMs = 1_000L, langCode = "fr", isAlita = true) { 0 }
        assertNotNull(tap1)
        assertEquals(0, tap1!!.stage)
        assertEquals(Live2dEmotion.EMOTION_NORMAL, tap1.emotion)
        assertEquals("jp_有事吗？.ogg", tap1.filename)

        val tap2 = tracker.onTap(allVoiceFiles, nowMs = 2_000L, langCode = "fr", isAlita = true) { 0 }
        assertEquals(0, tap2!!.stage)
        assertEquals(Live2dEmotion.EMOTION_JOIE, tap2.emotion)

        val tap3 = tracker.onTap(allVoiceFiles, nowMs = 3_000L, langCode = "fr", isAlita = true) { 0 }
        assertEquals(1, tap3!!.stage)
        assertEquals(Live2dEmotion.EMOTION_SERIEUX, tap3.emotion)

        val tap4 = tracker.onTap(allVoiceFiles, nowMs = 4_000L, langCode = "fr", isAlita = true) { 0 }
        assertEquals(1, tap4!!.stage)
        assertEquals(Live2dEmotion.EMOTION_SERIEUX, tap4.emotion)

        val tap5 = tracker.onTap(allVoiceFiles, nowMs = 5_000L, langCode = "fr", isAlita = true) { 0 }
        assertEquals(2, tap5!!.stage)
        assertEquals(Live2dEmotion.EMOTION_COLERE, tap5.emotion)
        assertTrue(tap5.filename in listOf("jp_别摸了.ogg", "jp_变态。.ogg", "jp_你想死一次吗？.ogg", "jp_尾巴不许碰喵！！.ogg"))

        // After > 10s cooldown, annoyance resets back to normal
        val tapAfterCooldown = tracker.onTap(allVoiceFiles, nowMs = 20_000L, langCode = "fr", isAlita = true) { 0 }
        assertEquals(0, tapAfterCooldown!!.stage)
        assertEquals(1, tapAfterCooldown.tapCount)
        assertEquals(Live2dEmotion.EMOTION_NORMAL, tapAfterCooldown.emotion)
    }

    @Test
    fun `desk buddy tap starts surprised then gets serious and angry on repeated taps`() {
        val tracker = TouchReactionTracker(tapResetMs = 10_000L)

        val tap1 = tracker.onDeskBuddyTap(nowMs = 1_000L, langCode = "fr") { 0 }
        assertEquals(Live2dEmotion.EMOTION_SURPRISE, tap1.emotion)
        assertEquals(0, tap1.stage)
        assertEquals(1, tap1.tapCount)

        val tap2 = tracker.onDeskBuddyTap(nowMs = 2_000L, langCode = "fr") { 0 }
        assertEquals(Live2dEmotion.EMOTION_SERIEUX, tap2.emotion)
        assertEquals(1, tap2.stage)

        val tap3 = tracker.onDeskBuddyTap(nowMs = 3_000L, langCode = "fr") { 0 }
        assertEquals(Live2dEmotion.EMOTION_COLERE, tap3.emotion)
        assertEquals(2, tap3.stage)

        val tapAfterCooldown = tracker.onDeskBuddyTap(nowMs = 20_000L, langCode = "fr") { 0 }
        assertEquals(Live2dEmotion.EMOTION_SURPRISE, tapAfterCooldown.emotion)
        assertEquals(1, tapAfterCooldown.tapCount)
    }

    @Test
    fun `caress reaction triggers blush emotion with localized affectionate text`() {
        val fr = TouchReactionHelper.caressReaction(langCode = "fr") { 0 }
        assertEquals(Live2dEmotion.EMOTION_ROUGIR, fr.emotion)
        assertTrue(fr.text.isNotBlank())

        val en = TouchReactionHelper.caressReaction(langCode = "en") { 1 }
        assertEquals(Live2dEmotion.EMOTION_ROUGIR, en.emotion)
        assertTrue(en.text.contains("blush", ignoreCase = true))
    }
}
