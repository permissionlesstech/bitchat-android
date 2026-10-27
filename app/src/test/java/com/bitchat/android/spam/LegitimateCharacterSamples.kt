package com.bitchat.android.spam

/** Synthetic non-spam examples. These are development controls, not a population sample. */
internal object LegitimateCharacterSamples {
    data class Sample(val id: String, val category: String, val content: String)

    private fun group(category: String, vararg messages: String): List<Sample> =
        messages.mapIndexed { index, content -> Sample("$category-${index + 1}", category, content) }

    val samples = group(
        "numbers_in_chat",
        "Please send 2 copies of the synthetic example.",
        "The sample has 3 pages and 12 diagrams.",
        "I tried this 4 times and it worked every time.",
        "The pretend order contains 24 blue pencils.",
        "The example timer runs for 30 seconds.",
        "There are 128 entries in this demonstration list.",
        "The sample file contains 4096 bytes.",
        "The fictional price is EUR 12.50 including delivery.",
        "The recipe needs 250 g of flour and 100 ml of milk.",
        "The example uses a 16:9 image with a 1920x1080 preview.",
        "The progress indicator is at 75% in this fictional example.",
        "The demonstration temperature is 21.5 degrees.",
        "Version 2.0.3 fixes the sample menu problem.",
        "The sample calendar entry uses 2099-01-02 as a fictional date.",
        "The example shows 09:30 and 10:45 as pretend time values.",
        "Try option 1 first, then compare it with option 2.",
        "The fictional total is 19.95 + 4.00 = 23.95.",
        "Use a 3 mm drill for the small demonstration piece.",
        "The example battery indicator reads 80%, not a real measurement.",
        "We need 6 more samples to reach a total of 20."
    ) + group(
        "punctuation_in_chat",
        "Really? That's the result I was hoping for!",
        "Thanks!!! This makes the explanation much easier to understand.",
        "I'm not sure... could you explain the second part?",
        "The sample is called \"the small example\" in the instructions.",
        "Try the other option (the one below the preview).",
        "The choices are [small], [medium], and [large].",
        "Please compare these: red/blue, on/off, and yes/no.",
        "That looks good :) thanks for checking it!",
        "Oops :-/ I clicked the wrong button again.",
        "I think it's ready... unless I've missed something?!",
        "Could you check the spelling of 'example' in this paragraph?",
        "The heading uses a colon: here is the explanation.",
        "The first part works; the second part still needs attention.",
        "Please keep the hyphenated sample-label in the demonstration.",
        "Use the + button to add an item and the - button to remove it.",
        "This is a pretend quote: \"Can you hear me?\"",
        "The sample uses A/B testing to compare two layouts.",
        "I agree -- the shorter explanation is easier to follow.",
        "That's exactly what I meant!!! Thank you so much!!!",
        "The example includes commas, parentheses (like these), and a final period."
    ) + group(
        "numeric_and_math",
        "2 + 2 = 4",
        "12 * 8 = 96",
        "144 / 12 = 12",
        "100 - 37 = 63",
        "3.14159",
        "0.125 = 1/8",
        "50% + 25% = 75%",
        "(2 + 3) * 4 = 20",
        "1, 1, 2, 3, 5, 8, 13, 21",
        "x^2 + y^2 = r^2",
        "f(x) = 2*x + 3",
        "a <= b && b < c",
        "6.02e23",
        "0.000001",
        "-12.5 + 7.25 = -5.25",
        "10:15 + 00:45 = 11:00",
        "1/2 + 1/3 = 5/6",
        "sqrt(81) = 9",
        "2099-01-02",
        "00:01:30.500"
    ) + group(
        "code_and_structured",
        "val count = 3",
        "println(\"hello\")",
        "fun add(a: Int, b: Int) = a + b",
        "if (count > 0) { showExample() }",
        "items.map { it.name }.sorted()",
        "SELECT sample FROM demo WHERE count > 3;",
        "{\"example\":true,\"count\":3}",
        "[1, 2, 3, 4, 5]",
        "<sample enabled=\"true\">hello</sample>",
        "sample_count: 12\nexample_enabled: true",
        "demo_key=example_value; mode=test; count=7",
        "for (index in 0 until 10) println(index)",
        "const sample = { count: 3, enabled: true };",
        "result = [value * 2 for value in range(5)]",
        "name,count,enabled\ndemo,3,true",
        "^example_[a-z]+[0-9]{2}$",
        "`sampleValues.map { it + 1 }`",
        "```json\n{\"synthetic\":true,\"value\":42}\n```",
        "config/demo.json",
        "value != null && value.length >= 2"
    ) + group(
        "urls",
        "https://example.invalid",
        "https://example.invalid/demo",
        "https://example.invalid/sample?count=3",
        "https://example.invalid/docs#example",
        "https://example.invalid/a/b/c",
        "https://example.invalid/search?q=sample+text",
        "https://example.invalid/sample%20file.txt",
        "https://example.invalid/demo?enabled=true&count=12",
        "www.example.invalid/demo",
        "example.invalid/sample",
        "Please read https://example.invalid/demo for the synthetic explanation.",
        "The fictional example is documented at https://example.invalid/docs.",
        "Try https://example.invalid/sample?count=3 for the demonstration.",
        "The sample link is https://example.invalid/a/b/c and contains no real service.",
        "See https://example.invalid/docs#example for another explanation.",
        "The pretend download is https://example.invalid/sample.zip.",
        "The synthetic image is https://example.invalid/image.png?size=small.",
        "This is a fictional address: https://example.invalid/test?x=1&y=2.",
        "Open https://example.invalid/help to read the sample instructions.",
        "The example website is www.example.invalid and is deliberately nonfunctional."
    ) + group(
        "hashes_and_identifiers",
        "demo_value_0001",
        "SAMPLE_REFERENCE_1234567890",
        "demo-0000-1111-2222-3333",
        "00000000-1111-4222-8333-444444444444",
        "0x0123456789abcdef",
        "AAECAwQFBgcICQoLDA0ODw==",
        "c3ludGhldGljIGV4YW1wbGU=",
        "example.user@example.invalid",
        "Synthetic reference: DEMO-2099-000123",
        "The pretend identifier is sample_value_0001.",
        "The fictional attachment is example_document_v2.3.txt.",
        "sample.package.ExampleClass",
        "exampleFunction(sample_value, count=3)",
        "Example checksum: 8a4f2c9d6e1b70358c2d4a6f9b1e5073",
        "Synthetic digest: 6b3e91a04fd728c59a107e3b2c8d465fb9017a2c5e38d6f04b72c9e135a8fd60",
        "sample-id: qxjvtpkzmxqvfbjkvxzpqvxjqzmt",
        "`qxjvtpkzmxqvfbjkvxzpqvxjqzmt`",
        "{\"sample_id\":\"qxjvtpkzmxqvfbjkvxzpqvxjqzmt\"}",
        "The fictional build label is demo_release_1.2.3_debug.",
        "This synthetic example token is AAECAwQFBgcICQoLDA0ODw==."
    ) + group(
        "short_replies",
        "ok", "yes", "no", "thanks", "hello", "hi 1", "ok 2", "v2.0", "42", "3/4",
        ":)", ":-)", ":-P", "?!", "...", "brb", "idk", "hmmmm", "don't", "can't"
    ) + group(
        "unicode",
        *(EnglishGibberishSamples.multilingual + listOf(
            "Thanks for the example 👍",
            "😀 🎉 👍 🤖 🐙 🦕",
            "The fictional price is €12.50 and the sample length is 3 µm.",
            "This synthetic sentence uses café, naïve, and résumé as examples."
        )).toTypedArray()
    ) + EnglishGibberishSamples.english.mapIndexed { index, content ->
        Sample("english-${index + 1}", "english", content)
    }
}
