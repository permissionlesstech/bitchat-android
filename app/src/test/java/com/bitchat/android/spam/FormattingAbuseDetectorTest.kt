package com.bitchat.android.spam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Random

class FormattingAbuseDetectorTest {
    private val marks = "\u0300\u0301\u0302\u0303\u0304\u0305\u0306\u0307\u0308\u0309\u030a\u030b"

    @Test
    fun `extreme mark stacks are rejected on the first message`() {
        assertTrue(FormattingAbuseDetector.isAbusive("Example a" + marks.repeat(3)))
        assertTrue(FormattingAbuseDetector.isAbusive("Synthetic sample".map {
            if (it.isLetter()) "$it$marks" else it.toString()
        }.joinToString("")))
        assertTrue(FormattingAbuseDetector.isAbusive(diverseText(1_100) + "a" + marks.repeat(3)))
    }

    @Test
    fun `combining mark thresholds allow smaller isolated examples`() {
        val stack = marks.repeat(3)
        assertFalse(FormattingAbuseDetector.isAbusive("Example a" + stack.take(31)))
        assertTrue(FormattingAbuseDetector.isAbusive("Example a" + stack.take(32)))
        assertFalse(FormattingAbuseDetector.isAbusive(("a" + stack.take(21)).repeat(3)))
        assertTrue(FormattingAbuseDetector.isAbusive(("a" + stack.take(16)).repeat(4)))
    }

    @Test
    fun `zero width joiners cannot split an extreme mark stack`() {
        val payload = ("\u0301".repeat(7) + "\u200d").repeat(1_024)
        assertTrue(FormattingAbuseDetector.isAbusive(payload))
    }

    @Test
    fun `invisible controls preserve mark counts without counting as marks`() {
        val controls = listOf("\u200d", "\u200c", "\u200b", "\u2060", "\u200e", "\u200f", "\u00ad", "\ufeff", "\u0000")
        controls.forEach { control ->
            val prefix = (marks.take(7) + control).repeat(4)
            assertFalse(FormattingAbuseDetector.isAbusive(prefix + marks.take(3)))
            assertTrue(FormattingAbuseDetector.isAbusive(prefix + marks.take(4)))
        }
    }

    @Test
    fun `distributed mark stacks survive invisible separators inside each stack`() {
        val stack = (marks.take(4) + "\u200d").repeat(4)
        assertTrue(FormattingAbuseDetector.isAbusive(("a" + stack).repeat(4)))
    }

    @Test
    fun `rendered bases and whitespace still separate small mark stacks`() {
        listOf("a", " ", "\n", "🚀").forEach { separator ->
            val text = (separator + marks.take(7) + "\u200d").repeat(32)
            assertFalse(FormattingAbuseDetector.isAbusive(text))
        }
        assertFalse(FormattingAbuseDetector.isAbusive("क्\u200dष नमस्ते 👩‍👩‍👧‍👦"))
    }

    @Test
    fun `invisible floods include mixed controls while ordinary bidi remains allowed`() {
        val invisible = "\u200b\u200e"
        assertFalse(FormattingAbuseDetector.isAbusive(invisible.repeat(63) + "\u200b"))
        assertTrue(FormattingAbuseDetector.isAbusive(invisible.repeat(64)))
        assertTrue(FormattingAbuseDetector.isAbusive("\u0000\u0001".repeat(64)))
        assertFalse(FormattingAbuseDetector.isAbusive(invisible.repeat(64) + diverseText(500)))
        assertFalse(FormattingAbuseDetector.isAbusive("عربي\u200f ordinary\u200e text"))
    }

    @Test
    fun `symbol emoji and mixed whitespace walls are rejected`() {
        assertTrue(FormattingAbuseDetector.isAbusive("🚀🌈🎉".repeat(200)))
        assertTrue(FormattingAbuseDetector.isAbusive("!@#".repeat(300)))
        assertTrue(FormattingAbuseDetector.isAbusive(" \n\t".repeat(200)))
        assertTrue(FormattingAbuseDetector.isAbusive("\u0085\u00a0".repeat(256)))
        assertFalse(FormattingAbuseDetector.isAbusive(" \n\t".repeat(170) + " "))
        assertFalse(FormattingAbuseDetector.isAbusive("A normal celebration 🚀🌈🎉!"))
    }

    @Test
    fun `long internal repetition is detected across writing systems`() {
        assertTrue(FormattingAbuseDetector.isAbusive(
            "Le canal de démonstration répète toujours cette phrase. ".repeat(16)
        ))
        assertTrue(FormattingAbuseDetector.isAbusive("这是用于压力测试的合成重复文本。".repeat(32)))
        assertTrue(FormattingAbuseDetector.isAbusive("z".repeat(128)))
        assertFalse(FormattingAbuseDetector.isAbusive("z".repeat(127)))
        assertFalse(FormattingAbuseDetector.isAbusive("Help, please respond! ".repeat(5)))
    }

    @Test
    fun `ordinary combining scripts joined emoji and decomposed accents remain allowed`() {
        val examples = listOf(
            "Tiếng Việt đầy đủ dấu vẫn phải hiển thị bình thường.",
            "नमस्ते, यह सामान्य संदेश है। हम कल फिर बात करेंगे।",
            "العَرَبِيَّةُ مَعَ التَّشْكِيلِ تَبْقَى مَقْرُوءَةً.",
            "สวัสดีครับ เราจะพบกันพรุ่งนี้ ขอบคุณสำหรับข้อความ",
            "A family 👩‍👩‍👧‍👦, a rainbow 🏳️‍🌈 and a heart ❤️.",
            "Combining accents can be written as a\u0301\u0302\u0303.",
            "",
            "\u0301".repeat(7)
        )
        examples.forEach { example ->
            assertFalse(example, FormattingAbuseDetector.isAbusive(example))
            assertFalse(example, FormattingAbuseDetector.isAbusive(
                Normalizer.normalize(example, Normalizer.Form.NFD)
            ))
        }
    }

    @Test
    fun `diverse text is allowed up to the input work bound`() {
        val text = diverseText(8_192)
        assertFalse(FormattingAbuseDetector.isAbusive(text))
        assertTrue(FormattingAbuseDetector.isAbusive(text + "x"))
        assertTrue(FormattingAbuseDetector.isAbusive(text.take(8_191) + "🚀"))
        assertTrue(FormattingAbuseDetector.isAbusive("Synthetic material. ".repeat(40_000)))
        assertTrue(FormattingAbuseDetector.isAbusive("\ufdfax".repeat(2_000)))
    }

    @Test
    fun `diverse multilingual prose remains allowed`() {
        val text = """
            Bonjour, voici les nouvelles du groupe. La réunion commence demain matin;
            nous discuterons des horaires, du matériel et des prochaines activités.
            Merci de préparer vos questions et de prévenir les personnes absentes.
            今天的讨论包括交通安排、社区花园以及周末的志愿活动。大家可以分享不同的建议。
            The notes include a timetable, instructions for new volunteers, and a map
            drawn with fictional landmarks. Please bring water and a notebook. There
            are several open questions about accessibility, equipment, and the weather.
        """.trimIndent()
        assertFalse(FormattingAbuseDetector.isAbusive(text))
    }

    private fun diverseText(length: Int): String {
        val random = Random(42)
        return buildString { repeat(length) { append('a' + random.nextInt(26)) } }
    }
}
