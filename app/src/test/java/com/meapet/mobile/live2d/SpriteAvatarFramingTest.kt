package com.meapet.mobile.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteAvatarFramingTest {

    @Test
    fun `immersive mode frames avatar centered at 100 percent height`() {
        val framing = SpriteAvatarRenderer.resolveFraming(isOverlay = false, isImmersive = true)

        assertEquals(1.00f, framing.fillH, 0.0001f)
        assertEquals(1.00f, framing.fillW, 0.0001f)
        assertEquals(0.0f, framing.shiftXFraction, 0.0001f)
        assertEquals(0.0f, framing.shiftYFraction, 0.0001f)
        assertEquals(1.00f, framing.fallbackMaxHFactor, 0.0001f)
        assertEquals(0.50f, framing.fallbackCenterYFactor, 0.0001f)
    }

    @Test
    fun `immersive mode keeps full height on 9x16 and 9x20 portrait screens`() {
        val alitaAspect = 760f / 1200f
        val immersive = SpriteAvatarRenderer.resolveFraming(isOverlay = false, isImmersive = true)
        val normal = SpriteAvatarRenderer.resolveFraming(isOverlay = false, isImmersive = false)

        val visHalf9x16Immersive = SpriteAvatarRenderer.computeVisibleHalfHeight(
            texAspect = alitaAspect,
            screenAspect = 9f / 16f,
            framing = immersive
        )
        val visHalf9x20Immersive = SpriteAvatarRenderer.computeVisibleHalfHeight(
            texAspect = alitaAspect,
            screenAspect = 9f / 20f,
            framing = immersive
        )
        val visHalf9x20Normal = SpriteAvatarRenderer.computeVisibleHalfHeight(
            texAspect = alitaAspect,
            screenAspect = 9f / 20f,
            framing = normal
        )

        // Character mesh half-height is 1.0, so visHalf ~1.0 means ~98-100% screen height
        assertEquals(1.00f, visHalf9x16Immersive, 0.01f)
        assertTrue(
            "Expected immersive 9:20 visible half height near 1.0 but was $visHalf9x20Immersive",
            visHalf9x20Immersive <= 1.03f
        )
        assertTrue(
            "Immersive mode must frame taller than normal mode ($visHalf9x20Immersive < $visHalf9x20Normal)",
            visHalf9x20Immersive < visHalf9x20Normal
        )
    }

    @Test
    fun `overlay framing takes precedence over immersive flag`() {
        val overlay = SpriteAvatarRenderer.resolveFraming(isOverlay = true, isImmersive = true)
        assertEquals(1.02f, overlay.fillH, 0.0001f)
        assertEquals(0.018f, overlay.shiftXFraction, 0.0001f)
    }
}
