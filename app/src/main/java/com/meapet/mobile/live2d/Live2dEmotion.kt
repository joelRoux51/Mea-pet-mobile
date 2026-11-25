package com.meapet.mobile.live2d

import java.text.Normalizer
import java.util.Locale

data class ParsedEmotionText(
    val cleanText: String,
    val emotion: String?
)

/**
 * Extraction et correspondance des balises `[EMOTION: nom_de_l_emotion]` vers les
 * fichiers `.exp3.json` d'un modèle Live2D.
 */
object Live2dEmotion {

    const val EMOTION_JOIE = "joie"
    const val EMOTION_SERIEUX = "serieux"
    const val EMOTION_CALME = "calme"
    const val EMOTION_COLERE = "colere"
    const val EMOTION_FATIGUE = "fatigue"
    const val EMOTION_MALICE = "malice"
    const val EMOTION_ETIREMENT = "etirement"
    const val EMOTION_SURPRISE = "surprise"
    const val EMOTION_CURIOSITE = "curiosite"
    const val EMOTION_CLIN_DOEIL = "clin_doeil"
    const val EMOTION_NORMAL = "normal"

    private val EMOTION_TAG_REGEX = Regex(
        """\[\s*EMOTION\s*:\s*([^\]]+?)\s*\]""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val MULTI_SPACES_REGEX = Regex("""[ \t]{2,}""")
    private val SPACE_BEFORE_NEWLINE_REGEX = Regex("""[ \t]*\n[ \t]*""")

    /**
     * Table des alias acceptés pour chacune des émotions principales.
     * Permet de fonctionner avec les expressions intégrées (`joie.exp3.json`, etc.)
     * mais aussi avec des modèles externes utilisant des noms en anglais ou chinois.
     */
    private val EMOTION_ALIASES: Map<String, List<String>> = mapOf(
        EMOTION_JOIE to listOf(
            "joie", "joy", "happy", "smile", "smiling", "sourire", "souriante",
            "cheerful", "laugh", "coucou", "salut", "wave", "hello", "开心", "笑"
        ),
        EMOTION_SERIEUX to listOf(
            "serieux", "sérieux", "serieuse", "sérieuse", "serious", "focused",
            "stern", "analyse", "reflexion", "réflexion", "严肃", "认真"
        ),
        EMOTION_CALME to listOf(
            "calme", "calm", "relaxed", "peaceful", "closed", "closed_eyes",
            "close_eyes", "close_eye", "yeux_fermes", "sleep", "priere", "prière",
            "prayer", "pray", "verset", "bible", "psaume", "recueillement", "闭眼", "平静"
        ),
        EMOTION_COLERE to listOf(
            "colere", "colère", "angry", "anger", "mad", "frown", "furrowed",
            "sourcils_fronces", "annoyed", "生气", "愤怒"
        ),
        EMOTION_FATIGUE to listOf(
            "fatigue", "fatigué", "fatiguée", "fatiguee", "tired", "sleepy",
            "yawn", "yawning", "baillement", "bâillement", "drowsy", "困", "累"
        ),
        EMOTION_MALICE to listOf(
            "malice", "malicieux", "malicieuse", "taquin", "taquine", "ironie",
            "langue", "bleh", "tease", "playful", "cheeky", "smug", "pince_sans_rire"
        ),
        EMOTION_ETIREMENT to listOf(
            "etirement", "étirement", "etirer", "stretch", "stretching", "detente", "détente"
        ),
        EMOTION_SURPRISE to listOf(
            "surprise", "surpris", "surprisee", "surprised", "alerte", "alert",
            "alarm", "alarme", "reveil", "réveil", "wakeup", "debout", "shock",
            "shocked", "etonne", "étonné", "astonished", "urgent", "urgence"
        ),
        EMOTION_CURIOSITE to listOf(
            "curiosite", "curiosité", "curieux", "curieuse", "curious", "curiosity",
            "observation", "observe", "observer", "photo", "camera", "caméra",
            "vision", "ecoute", "écoute", "listen", "listening", "question",
            "intrigue", "intrigué", "intrigued"
        ),
        EMOTION_CLIN_DOEIL to listOf(
            "clin_doeil", "clin_d_oeil", "wink", "complicite", "complicité",
            "complice", "parfait", "pret", "prêt"
        ),
        EMOTION_NORMAL to listOf(
            "normal", "default", "neutral", "neutre", "idle", "reset",
            "defaut", "défaut", "默认", "正常"
        )
    )

    private val PRAYER_KEYWORDS = listOf(
        "verset", "bible", "biblique", "psaume", "psaumes", "evangile", "proverbes",
        "matthieu", "philippiens", "corinthiens", "romains", "esaie", "genese",
        "priere", "prier", "amen", "seigneur", "benediction"
    )
    private val BIBLE_REF_REGEX = Regex("""\b\d{1,3}:\d{1,3}\b""")
    private val SURPRISE_KEYWORDS = listOf(
        "debout", "reveil", "alarme", "minuteur", "rappel", "appel manque", "appels manques",
        "urgent", "urgence", "alerte", "waouh", "incroyable", "oh !", "attention !"
    )
    private val SLEEP_KEYWORDS = listOf(
        "bonne nuit", "fatigu", "sommeil", "baillement", "baille", "dormir", "dodo",
        "batterie faible", "low battery", "battery low"
    )
    private val ANGER_KEYWORDS = listOf(
        "erreur critique", "echec critique", "alerte rouge", "batterie critique"
    )
    private val STRETCH_KEYWORDS = listOf(
        "etire", "detend", "stretch"
    )
    private val MALICE_KEYWORDS = listOf(
        "haha", "hehe", "mdr", "taquin", "malic", "ironie", "tire la langue",
        "pince-sans-rire", "blague"
    )
    private val CURIOSITY_KEYWORDS = listOf(
        "photo", "image", "camera", "que vois-tu", "regarde", "analyse",
        "ecoute ce vocal", "message vocal", "curieux", "intriguant",
        "notification", "notif", "appels", "sms", "whatsapp", "telegram",
        "email", "nouveau message", "nouvel e-mail"
    )
    private val WINK_KEYWORDS = listOf(
        "clin d'oeil", "c'est note", "voix changee", "vitesse reglee", "avec plaisir"
    )
    private val GREETING_KEYWORDS = listOf(
        "coucou", "bonjour", "salut", "hello", "bonsoir"
    )

    /**
     * Déduit une émotion adaptée au contexte (ex. demande de verset biblique ou prière -> `calme`,
     * réveil/alarme -> `surprise`, photo/vocal -> `curiosite`, humour/ironie -> `malice`)
     * lorsque la réponse de l'IA ne contient pas explicitement de balise `[EMOTION: ...]`.
     */
    fun inferEmotionFromContext(userMessage: String, assistantReply: String): String? {
        val combined = normalizeSearchText("$userMessage $assistantReply")
        return when {
            PRAYER_KEYWORDS.any { combined.contains(it) } ||
                (BIBLE_REF_REGEX.containsMatchIn(combined) &&
                    (combined.contains("dieu") || combined.contains("christ") || combined.contains("jesus"))) -> EMOTION_CALME
            ANGER_KEYWORDS.any { combined.contains(it) } -> EMOTION_COLERE
            SURPRISE_KEYWORDS.any { combined.contains(it) } -> EMOTION_SURPRISE
            SLEEP_KEYWORDS.any { combined.contains(it) } -> EMOTION_FATIGUE
            STRETCH_KEYWORDS.any { combined.contains(it) } -> EMOTION_ETIREMENT
            MALICE_KEYWORDS.any { combined.contains(it) } -> EMOTION_MALICE
            CURIOSITY_KEYWORDS.any { combined.contains(it) } -> EMOTION_CURIOSITE
            WINK_KEYWORDS.any { combined.contains(it) } -> EMOTION_CLIN_DOEIL
            GREETING_KEYWORDS.any { combined.contains(it) } -> EMOTION_JOIE
            else -> null
        }
    }

    private fun normalizeSearchText(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .replace("œ", "oe")
            .replace("æ", "ae")
            .replace('’', '\'')
    }

    /**
     * Recherche une balise `[EMOTION: ...]` dans [rawText], extrait le nom de l'émotion
     * et retourne le texte nettoyé sans la balise ni espaces superflus.
     */
    fun extractFromText(rawText: String): ParsedEmotionText {
        var lastEmotion: String? = null
        val stripped = EMOTION_TAG_REGEX.replace(rawText) { match ->
            val rawEmotion = match.groupValues[1].trim()
            if (rawEmotion.isNotEmpty()) {
                lastEmotion = rawEmotion
            }
            ""
        }
        val cleanText = stripped
            .replace(MULTI_SPACES_REGEX, " ")
            .replace(SPACE_BEFORE_NEWLINE_REGEX, "\n")
            .trim()

        val canonicalOrRaw = lastEmotion?.let { normalizeEmotionName(it) }
        return ParsedEmotionText(cleanText = cleanText, emotion = canonicalOrRaw)
    }

    /**
     * Normalise un nom d'émotion vers sa clé canonique (`joie`, `serieux`, `calme`,
     * `colere`, `fatigue`, `malice`, `etirement`, `normal`) si reconnue, ou conserve le nom nettoyé tel quel
     * pour les expressions personnalisées de modèles externes.
     */
    fun normalizeEmotionName(rawEmotion: String): String {
        val cleaned = rawEmotion.trim()
        val norm = normalizeKey(cleaned)
        for ((canonical, aliases) in EMOTION_ALIASES) {
            if (aliases.any { normalizeKey(it) == norm }) {
                return canonical
            }
        }
        return cleaned
    }

    /**
     * Indique si l'émotion demandée correspond à l'état par défaut (`normal`).
     */
    fun isDefaultEmotion(emotion: String): Boolean {
        val norm = normalizeKey(emotion)
        return EMOTION_ALIASES[EMOTION_NORMAL]?.any { normalizeKey(it) == norm } == true
    }

    /**
     * Résout la clé `.exp3.json` disponible dans [availableExpressions] correspondant
     * à [emotionOrExpressionName]. Retourne `null` si aucune expression ne correspond.
     */
    fun resolveExpressionKey(
        emotionOrExpressionName: String,
        availableExpressions: Collection<String>
    ): String? {
        if (availableExpressions.isEmpty()) return null

        val rawNorm = normalizeKey(emotionOrExpressionName)
        if (rawNorm.isEmpty()) return null

        // 1) Correspondance exacte (insensible à la casse, aux accents et au suffixe .exp3.json)
        availableExpressions.firstOrNull { key ->
            normalizeKey(stripExpExtension(key)) == rawNorm
        }?.let { return it }

        // 2) Correspondance via les alias d'émotion (joie / serieux / calme / colere / fatigue / normal)
        val canonical = normalizeEmotionName(emotionOrExpressionName)
        val candidates = EMOTION_ALIASES[canonical] ?: listOf(emotionOrExpressionName)
        val normalizedCandidates = candidates.map { normalizeKey(it) }

        for (candidate in normalizedCandidates) {
            availableExpressions.firstOrNull { key ->
                normalizeKey(stripExpExtension(key)) == candidate
            }?.let { return it }
        }

        // 3) Correspondance partielle (ex. "face_smile_01" pour "smile")
        for (candidate in normalizedCandidates) {
            if (candidate.length < 3) continue
            availableExpressions.firstOrNull { key ->
                normalizeKey(stripExpExtension(key)).contains(candidate)
            }?.let { return it }
        }

        return null
    }

    private fun stripExpExtension(name: String): String {
        return name.trim().removeSuffix(".exp3.json")
    }

    private fun normalizeKey(input: String): String {
        val trimmed = input.trim()
        val decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace("œ", "oe")
            .replace("æ", "ae")
            .replace('-', '_')
            .replace(' ', '_')
            .replace('\'', '_')
            .replace('’', '_')
    }
}
