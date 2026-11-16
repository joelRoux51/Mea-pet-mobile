package com.meapet.mobile.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language wrapper supporting system default, French, English, Spanish, and Chinese.
 * Persisted synchronously in SharedPreferences so [wrap] works inside attachBaseContext().
 */
object LocaleHelper {
    private const val PREFS_NAME = "meapet_locale"
    private const val KEY_LANG = "app_language"

    const val LANG_SYSTEM = "system"
    const val LANG_FR = "fr"
    const val LANG_EN = "en"
    const val LANG_ES = "es"
    const val LANG_ZH = "zh"

    val SUPPORTED_CODES = listOf(LANG_SYSTEM, LANG_FR, LANG_EN, LANG_ES, LANG_ZH)

    fun getLanguage(context: Context): String {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
        return prefs?.getString(KEY_LANG, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, languageCode: String) {
        val normalized = if (languageCode in SUPPORTED_CODES) languageCode else LANG_SYSTEM
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_LANG, normalized)
                ?.commit()
        } catch (_: Throwable) {}
    }

    fun wrap(context: Context): Context {
        val code = getLanguage(context)
        if (code == LANG_SYSTEM) return context
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val resConfig = context.resources?.configuration ?: return context
        val config = Configuration(resConfig)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config) ?: context
    }
}

/**
 * Safely resolves a string resource with fallback (guards Mockito-mocked Context/Application
 * in JVM unit tests where getString() returns null or throws Resources.NotFoundException).
 */
fun Context.getStringOrFallback(resId: Int, fallback: String, vararg args: Any): String {
    return try {
        val raw = if (args.isEmpty()) getString(resId) else getString(resId, *args)
        raw ?: if (args.isEmpty()) fallback else String.format(fallback, *args)
    } catch (_: Throwable) {
        if (args.isEmpty()) fallback else String.format(fallback, *args)
    }
}

