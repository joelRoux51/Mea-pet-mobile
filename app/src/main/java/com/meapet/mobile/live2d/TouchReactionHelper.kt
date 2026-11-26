package com.meapet.mobile.live2d

import android.content.Context
import com.meapet.mobile.core.LocaleHelper
import java.util.Locale

/**
 * Translates Live2D/sprite touch voice keys into the active UI language and
 * manages repeated-tap irritation escalation (`normal`/`joie` -> `serieux` -> `colere`).
 */
object TouchReactionHelper {

    data class LocalizedPhrase(
        val fr: String,
        val en: String,
        val es: String,
        val zh: String
    )

    data class TapReaction(
        val filename: String,
        val text: String,
        val emotion: String,
        val stage: Int,
        val tapCount: Int
    )

    private val GENERAL_PHRASES: Map<String, LocalizedPhrase> = mapOf(
        "有事吗？" to LocalizedPhrase(
            fr = "Tu as besoin de quelque chose ?",
            en = "Need something?",
            es = "¿Necesitas algo?",
            zh = "有事吗？"
        ),
        "嘿嘿。" to LocalizedPhrase(
            fr = "Héhé.",
            en = "Hehe.",
            es = "Jeje.",
            zh = "嘿嘿。"
        ),
        "啊，我在想事情呢，干嘛？" to LocalizedPhrase(
            fr = "Oh, j'étais dans mes pensées... qu'y a-t-il ?",
            en = "Oh, I was lost in thought... what's up?",
            es = "Estaba pensando en mis cosas... ¿qué pasa?",
            zh = "啊，我在想事情呢，干嘛？"
        ),
        "哼。" to LocalizedPhrase(
            fr = "Hmph.",
            en = "Hmph.",
            es = "Hmpf.",
            zh = "哼。"
        ),
        "没事就别打扰我。" to LocalizedPhrase(
            fr = "Si ce n'est rien d'important, laisse-moi tranquille.",
            en = "If it's nothing, don't bother me.",
            es = "Si no es nada, no me molestes.",
            zh = "没事就别打扰我。"
        ),
        "别摸我头发。" to LocalizedPhrase(
            fr = "Arrête de me toucher les cheveux.",
            en = "Stop touching my hair.",
            es = "Deja de tocarme el pelo.",
            zh = "别摸我头发。"
        ),
        "别摸了" to LocalizedPhrase(
            fr = "Arrête de me toucher !",
            en = "Stop touching me!",
            es = "¡Deja de tocarme!",
            zh = "别摸了"
        ),
        "变态。" to LocalizedPhrase(
            fr = "Pervers.",
            en = "Pervert.",
            es = "Pervertido.",
            zh = "变态。"
        ),
        "你想死一次吗？" to LocalizedPhrase(
            fr = "Tu veux voir ce que ça fait si je m'énerve ?!",
            en = "Do you want to die once?!",
            es = "¿Quieres probar qué pasa si me enfado?!",
            zh = "你想死一次吗？"
        )
    )

    private val MEA_ONLY_PHRASES: Map<String, LocalizedPhrase> = mapOf(
        "啊......摸耳朵前，先说一声喵。" to LocalizedPhrase(
            fr = "Hé... préviens-moi avant de me toucher les oreilles, miaou.",
            en = "Hey... warn me before touching my ears, meow.",
            es = "Oye... avísame antes de tocarme las orejas, miau.",
            zh = "啊......摸耳朵前，先说一声喵。"
        ),
        "尾巴是很敏感的不知道吗。" to LocalizedPhrase(
            fr = "Tu ne sais pas que ma queue est très sensible ?",
            en = "Don't you know my tail is super sensitive?",
            es = "¿No sabes que mi cola es muy sensible?",
            zh = "尾巴是很敏感的不知道吗。"
        ),
        "尾巴不许碰喵！！" to LocalizedPhrase(
            fr = "Pas touche à ma queue, miaou !!",
            en = "Hands off my tail, meow!!",
            es = "¡¡No me toques la cola, miau!!",
            zh = "尾巴不许碰喵！！"
        )
    )

    private val ALITA_ONLY_PHRASES: Map<String, LocalizedPhrase> = mapOf(
        "啊......摸耳朵前，先说一声喵。" to LocalizedPhrase(
            fr = "Hé... préviens-moi avant de me toucher la tête.",
            en = "Hey... warn me before touching my head.",
            es = "Oye... avísame antes de tocarme la cabeza.",
            zh = "啊......摸这里前，先说一声。"
        ),
        "尾巴是很敏感的不知道吗。" to LocalizedPhrase(
            fr = "Mes capteurs sont sensibles à cet endroit, tu sais ?",
            en = "My sensors are sensitive there, you know?",
            es = "Mis sensores son sensibles ahí, ¿sabes?",
            zh = "这里的传感器很敏感，不知道吗。"
        ),
        "尾巴不许碰喵！！" to LocalizedPhrase(
            fr = "Interdiction de toucher ici !!",
            en = "Do not touch there!!",
            es = "¡¡Prohibido tocar ahí!!",
            zh = "不许碰这里！！"
        )
    )

    private val STAGE_0_KEYS = setOf(
        "有事吗？",
        "嘿嘿。",
        "啊，我在想事情呢，干嘛？"
    )

    private val STAGE_1_KEYS = setOf(
        "哼。",
        "没事就别打扰我。",
        "别摸我头发。",
        "啊......摸耳朵前，先说一声喵。",
        "尾巴是很敏感的不知道吗。"
    )

    private val STAGE_2_KEYS = setOf(
        "别摸了",
        "变态。",
        "你想死一次吗？",
        "尾巴不许碰喵！！"
    )

    private val EXTRAPOLATED_ESCALATION_FR = listOf(
        "Sérieusement... tu vas finir par m'énerver !",
        "Encore une fois et je passe en mode combat !",
        "Bon, ça suffit maintenant !"
    )

    fun extractVoiceKey(filename: String): String {
        val base = filename.substringBeforeLast('.')
        return if ('_' in base) base.substringAfter('_') else base
    }

    fun resolveLanguageCode(context: Context?): String {
        val stored = context?.let { LocaleHelper.getLanguage(it) } ?: LocaleHelper.LANG_SYSTEM
        val code = if (stored == LocaleHelper.LANG_SYSTEM) {
            Locale.getDefault().language
        } else {
            stored
        }
        return when (code.lowercase(Locale.ROOT).take(2)) {
            "fr" -> LocaleHelper.LANG_FR
            "en" -> LocaleHelper.LANG_EN
            "es" -> LocaleHelper.LANG_ES
            "zh" -> LocaleHelper.LANG_ZH
            else -> LocaleHelper.LANG_FR
        }
    }

    fun translateVoiceText(
        rawKey: String,
        langCode: String,
        isAlita: Boolean
    ): String {
        val phrase = if (isAlita) {
            ALITA_ONLY_PHRASES[rawKey] ?: GENERAL_PHRASES[rawKey]
        } else {
            MEA_ONLY_PHRASES[rawKey] ?: GENERAL_PHRASES[rawKey]
        } ?: return rawKey

        return when (langCode.lowercase(Locale.ROOT).take(2)) {
            "en" -> phrase.en
            "es" -> phrase.es
            "zh" -> phrase.zh
            else -> phrase.fr
        }
    }

    fun stageForTapCount(tapCount: Int): Int = when {
        tapCount <= 2 -> 0
        tapCount <= 4 -> 1
        else -> 2
    }

    fun emotionForTapCount(tapCount: Int): String = when {
        tapCount <= 1 -> Live2dEmotion.EMOTION_NORMAL
        tapCount == 2 -> Live2dEmotion.EMOTION_JOIE
        tapCount <= 4 -> Live2dEmotion.EMOTION_SERIEUX
        else -> Live2dEmotion.EMOTION_COLERE
    }

    fun filterFilesForStage(files: List<String>, stage: Int): List<String> {
        if (files.isEmpty()) return emptyList()
        val preferredKeys = when (stage) {
            0 -> STAGE_0_KEYS
            1 -> STAGE_1_KEYS
            else -> STAGE_2_KEYS
        }
        val primary = files.filter { extractVoiceKey(it) in preferredKeys }
        if (primary.isNotEmpty()) return primary

        // Fallback gracefully inside zones that have no stage-0 friendly clips (e.g. lower_left)
        return when (stage) {
            0 -> files.filter { extractVoiceKey(it) in STAGE_1_KEYS }.ifEmpty { files }
            1 -> files.filter { extractVoiceKey(it) in STAGE_0_KEYS || extractVoiceKey(it) in STAGE_2_KEYS }.ifEmpty { files }
            else -> files.filter { extractVoiceKey(it) in STAGE_1_KEYS }.ifEmpty { files }
        }
    }

    fun angryPrefixOrOverride(baseText: String, tapCount: Int, langCode: String): String {
        if (tapCount < 6) return baseText
        val code = langCode.lowercase(Locale.ROOT).take(2)
        return when {
            code == "fr" && tapCount >= 7 && tapCount % 2 == 1 -> {
                EXTRAPOLATED_ESCALATION_FR[(tapCount - 7) % EXTRAPOLATED_ESCALATION_FR.size]
            }
            else -> baseText
        }
    }

    private val DESK_BUDDY_SURPRISE = LocalizedPhrase(
        fr = "Oh ! Tu m'as surpris !",
        en = "Whoa! You surprised me!",
        es = "¡Oh! ¡Me has sorprendido!",
        zh = "唔！吓我一跳！"
    )

    private val DESK_BUDDY_ANNOYED = LocalizedPhrase(
        fr = "Hé, doucement avec l'écran...",
        en = "Hey, easy on the screen...",
        es = "Oye, más suave con la pantalla...",
        zh = "喂，轻一点碰屏幕…"
    )

    private val DESK_BUDDY_ANGRY_POOL = listOf(
        LocalizedPhrase(
            fr = "Hé ! Arrête de me tapoter !",
            en = "Hey! Stop poking me!",
            es = "¡Oye! ¡Deja de darme toques!",
            zh = "喂！别一直戳我！"
        ),
        LocalizedPhrase(
            fr = "Tu cherches à m'énerver ?!",
            en = "Are you trying to make me mad?!",
            es = "¿Intentas hacerme enfadar?!",
            zh = "你是想惹我生气吗？！"
        ),
        LocalizedPhrase(
            fr = "Bon, ça suffit maintenant !",
            en = "Okay, that's enough now!",
            es = "¡Bueno, ya basta!",
            zh = "够了哦，别再戳了！"
        )
    )

    private val CARESS_PHRASES = listOf(
        LocalizedPhrase(
            fr = "Oh... c'est agréable... ///",
            en = "Aww... that feels nice... ///",
            es = "Oh... qué agradable... ///",
            zh = "唔…好舒服…///"
        ),
        LocalizedPhrase(
            fr = "Héhé... tu me fais rougir ! ///",
            en = "Hehe... you're making me blush! ///",
            es = "Jeje... ¡me haces sonrojar! ///",
            zh = "嘿嘿…你让我脸红啦！///"
        )
    )

    private fun pickLocalized(phrase: LocalizedPhrase, langCode: String): String =
        when (langCode.lowercase(Locale.ROOT).take(2)) {
            "en" -> phrase.en
            "es" -> phrase.es
            "zh" -> phrase.zh
            else -> phrase.fr
        }

    fun deskBuddyReactionForTap(tapCount: Int, langCode: String, picker: (Int) -> Int): TapReaction {
        return when {
            tapCount <= 1 -> TapReaction(
                filename = "",
                text = pickLocalized(DESK_BUDDY_SURPRISE, langCode),
                emotion = Live2dEmotion.EMOTION_SURPRISE,
                stage = 0,
                tapCount = tapCount
            )
            tapCount == 2 -> TapReaction(
                filename = "",
                text = pickLocalized(DESK_BUDDY_ANNOYED, langCode),
                emotion = Live2dEmotion.EMOTION_SERIEUX,
                stage = 1,
                tapCount = tapCount
            )
            else -> {
                val idx = picker(DESK_BUDDY_ANGRY_POOL.size).coerceIn(0, DESK_BUDDY_ANGRY_POOL.lastIndex)
                TapReaction(
                    filename = "",
                    text = pickLocalized(DESK_BUDDY_ANGRY_POOL[idx], langCode),
                    emotion = Live2dEmotion.EMOTION_COLERE,
                    stage = 2,
                    tapCount = tapCount
                )
            }
        }
    }

    fun caressReaction(langCode: String, picker: (Int) -> Int = { 0 }): TapReaction {
        val idx = picker(CARESS_PHRASES.size).coerceIn(0, CARESS_PHRASES.lastIndex)
        return TapReaction(
            filename = "",
            text = pickLocalized(CARESS_PHRASES[idx], langCode),
            emotion = Live2dEmotion.EMOTION_ROUGIR,
            stage = 0,
            tapCount = 0
        )
    }
}

/**
 * Stateful tracker for repeated taps and irritation cooldown.
 */
class TouchReactionTracker(
    private val tapResetMs: Long = DEFAULT_TAP_RESET_MS
) {
    private var consecutiveTaps = 0
    private var lastTapMs = 0L

    @Synchronized
    fun onTap(
        availableFiles: List<String>,
        nowMs: Long,
        langCode: String,
        isAlita: Boolean,
        picker: (Int) -> Int
    ): TouchReactionHelper.TapReaction? {
        if (availableFiles.isEmpty()) return null

        if (lastTapMs > 0L && (nowMs - lastTapMs) > tapResetMs) {
            consecutiveTaps = 0
        }
        consecutiveTaps += 1
        lastTapMs = nowMs

        val stage = TouchReactionHelper.stageForTapCount(consecutiveTaps)
        val emotion = TouchReactionHelper.emotionForTapCount(consecutiveTaps)
        val pool = TouchReactionHelper.filterFilesForStage(availableFiles, stage)
        val idx = picker(pool.size).coerceIn(0, pool.lastIndex)
        val chosen = pool[idx]

        val rawKey = TouchReactionHelper.extractVoiceKey(chosen)
        val translated = TouchReactionHelper.translateVoiceText(
            rawKey = rawKey,
            langCode = langCode,
            isAlita = isAlita
        )
        val finalText = TouchReactionHelper.angryPrefixOrOverride(translated, consecutiveTaps, langCode)

        return TouchReactionHelper.TapReaction(
            filename = chosen,
            text = finalText,
            emotion = emotion,
            stage = stage,
            tapCount = consecutiveTaps
        )
    }

    /**
     * Desk Buddy screen tap escalation:
     * Tap 1 -> surprised (`surprise`), Tap 2 -> annoyed (`serieux`), Tap 3+ -> angry (`colere`).
     */
    @Synchronized
    fun onDeskBuddyTap(
        nowMs: Long,
        langCode: String,
        picker: (Int) -> Int
    ): TouchReactionHelper.TapReaction {
        if (lastTapMs > 0L && (nowMs - lastTapMs) > tapResetMs) {
            consecutiveTaps = 0
        }
        consecutiveTaps += 1
        lastTapMs = nowMs
        return TouchReactionHelper.deskBuddyReactionForTap(consecutiveTaps, langCode, picker)
    }

    @Synchronized
    fun reset() {
        consecutiveTaps = 0
        lastTapMs = 0L
    }

    companion object {
        const val DEFAULT_TAP_RESET_MS = 10_000L
    }
}
