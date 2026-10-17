package com.bitchat.android.service

import android.icu.text.PluralRules
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PeerAvailabilityPluralTest {
    @Test
    fun `plural categories preserve the actual peer count`() {
        // The JVM suite does not package app resources. Read the shipped catalog,
        // then select its bucket using Android's ICU rules rather than guessing
        // that the category named "one" can only describe the number 1.
        for (qualifier in listOf("fil", "ru", "uk", "fr", "pt-rBR", "bn", "fa", "hi")) {
            val locale = Locale.forLanguageTag(qualifier.replace("-r", "-"))
            val strings = File("src/main/res/values-$qualifier/strings.xml").readText()
            val body = Regex(
                """<plurals name="notification_active_peers_body">(.*?)</plurals>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(strings)!!.groupValues[1]
            val items = Regex("""<item quantity="([^"]+)">(.*?)</item>""")
                .findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            val rules = PluralRules.forLocale(locale)
            for (count in listOf(0, 1, 2, 3, 5, 21, 31, 101)) {
                val category = rules.select(count.toDouble())
                val template = items[category] ?: items.getValue("other")
                val message = String.format(locale, template, count)
                val expected = String.format(locale, "%d", count)
                assertTrue("$qualifier count $count rendered as $message", message.contains(expected))
            }
        }
    }
}
