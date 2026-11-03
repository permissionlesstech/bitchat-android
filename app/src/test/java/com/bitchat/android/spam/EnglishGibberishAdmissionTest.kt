package com.bitchat.android.spam

import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnglishGibberishAdmissionTest {
    private val timestampMs = 1_000_000_000L
    private val junk = "qxjvtpk zmxqvfb jkvxzpq vxjqzmt qzpvxjk"

    @Test
    fun `public admission preserves the multilingual Mesh Lab controls after hidden noise`() {
        val filter = SpamFilter(clock = { timestampMs })
        val token = "5a6b7c8d9e0f"
        listOf(
            "9!qxjv7#zmxq2%jkvxz8@vxjq4&qzpv6*jk $token-a",
            "vxqjzmpqzjxvktxjkvzpqzvxmjkpvxqjzt $token-b"
        ).forEachIndexed { index, content ->
            assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "noise-$index", content, timestampMs))
        }
        listOf(
            "This ordinary synthetic example should remain visible after the unwanted messages. The text is an invented conversation for checking message delivery. Thank you for the clear explanation. We can read the result and try the next example when everything is ready. $token-c",
            "これはソフトウェアの練習のために作った架空の例です。わかりやすい説明と役立つ提案をありがとうございます。この小さなアプリはインターネット接続なしで動作します。 $token-d",
            "هذا مثال مختلق لتجربة البرنامج. شكرا على الشرح الواضح والاقتراح المفيد. يعمل هذا التطبيق الصغير دون اتصال بالإنترنت. هل يمكنك شرح كيف يغير هذا الخيار النتيجة؟ $token-e",
            "이것은 소프트웨어 연습을 위해 만든 가상의 예제입니다. 명확한 설명과 유용한 제안에 감사드립니다. 이 작은 앱은 인터넷 연결 없이 작동합니다. 이 옵션이 결과를 어떻게 바꾸는지 설명해 주실 수 있나요? $token-f",
            "这是为软件练习编写的虚构示例。感谢清楚的解释和有用的建议。这个小应用无需连接互联网就能工作。你能解释这个选项如何改变结果吗？ $token-g"
        ).forEachIndexed { index, content ->
            assertEquals(content, SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "native-$index", content, timestampMs))
        }
    }

    @Test
    fun `English gibberish is hidden and its verdict is cached by message ID`() {
        val filter = SpamFilter(clock = { timestampMs })
        assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "junk", junk, timestampMs))
        assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "junk", junk, timestampMs))
    }

    @Test
    fun `hidden gibberish does not spend a senders message allowance`() {
        val filter = SpamFilter(clock = { timestampMs })
        repeat(20) { index ->
            assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "junk-$index", junk, timestampMs))
        }
        listOf("ok", "thanks", "yes", "no", "fine", "hello").forEachIndexed { index, content ->
            assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "ok-$index", content, timestampMs))
        }
        assertEquals(SpamVerdict.MESSAGE_RATE, filter.check("mesh:synthetic-a", "over-limit", "thanks", timestampMs))
    }

    @Test
    fun `the English rule can be disabled independently and changing config clears cached decisions`() {
        val filter = SpamFilter(clock = { timestampMs })
        assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "same", junk, timestampMs))
        filter.config = filter.config.copy(englishGibberishEnabled = false)
        assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "same", junk, timestampMs))
    }

    @Test
    fun `the master filter toggle still allows gibberish`() {
        val filter = SpamFilter(clock = { timestampMs }).apply { enabled = false }
        assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "junk", junk, timestampMs))
    }

    @Test
    fun `media descriptions are not scored as English text`() {
        val filter = SpamFilter(clock = { timestampMs })
        assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "media", junk, timestampMs, isText = false))
    }

    @Test
    fun `the existing debug bypass displays gibberish with the correct reason`() {
        val filter = SpamFilter(clock = { timestampMs }).apply { debugBypass = true }
        val message = BitchatMessage(id = "synthetic-junk", sender = "synthetic", content = junk, timestamp = Date(timestampMs))
        assertFalse(filter.shouldHide(message, "mesh:synthetic-a"))
        assertEquals(mapOf(message.id to SpamVerdict.ENGLISH_GIBBERISH), filter.flagged.value)
        filter.reset()
        assertEquals(emptyMap<String, SpamVerdict>(), filter.flagged.value)
    }
}
