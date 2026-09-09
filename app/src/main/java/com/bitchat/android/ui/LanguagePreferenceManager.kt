package com.bitchat.android.ui

import android.content.Context
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bitchat.android.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

data class AppLanguage(
    val languageTag: String,
    val endonym: String,
)

object LanguagePreferenceManager {
    fun currentLanguageTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags()

    fun setLanguage(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageTag)
        )
    }

    fun supportedLanguages(
        context: Context,
        @XmlRes localeConfig: Int = R.xml.locales_config,
    ): List<AppLanguage> = readLanguageTags(context, localeConfig)
        .map { languageTag ->
            val locale = Locale.forLanguageTag(languageTag)
            AppLanguage(
                languageTag = languageTag,
                endonym = locale.getDisplayName(locale)
                    .replaceFirstChar { first ->
                        if (first.isLowerCase()) first.titlecase(locale) else first.toString()
                    },
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.endonym })

    internal fun readLanguageTags(
        context: Context,
        @XmlRes localeConfig: Int,
    ): List<String> {
        val parser = context.resources.getXml(localeConfig)
        return buildList {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
                event = parser.next()
            }
        }
    }

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
}
