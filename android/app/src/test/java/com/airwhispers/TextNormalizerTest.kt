package com.airwhispers

import com.airwhispers.data.model.EmojiMode
import com.airwhispers.domain.tts.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text normalisation is the difference between "natural" and "robotic", so it gets
 * the densest test coverage in the project.
 */
class TextNormalizerTest {

    private fun speak(raw: String, mode: EmojiMode = EmojiMode.DESCRIBE_IMPORTANT) =
        TextNormalizer.normalize(raw, mode).text

    @Test
    fun `emoji that only decorate are dropped`() {
        assertEquals("I love you.", speak("I love you ❤️😂"))
        assertEquals("I can't talk right now.", speak("I can't talk right now 😂"))
    }

    @Test
    fun `meaning bearing emoji are described when the words do not already say it`() {
        assertEquals("I love you.", speak("I ❤️ you"))
        assertEquals("Please call me.", speak("Please 📞 me"))
    }

    @Test
    fun `call emoji is not repeated after the word call`() {
        val spoken = speak("Call me urgently 📞")
        assertFalse("should not say 'call' twice: $spoken", spoken.lowercase().contains("call call"))
    }

    @Test
    fun `ignore mode strips every emoji`() {
        assertEquals("Great work.", speak("Great work 👍🎉", EmojiMode.IGNORE))
    }

    @Test
    fun `read all mode describes known emoji`() {
        val spoken = speak("ok 👍🎉", EmojiMode.READ_ALL)
        assertTrue(spoken.contains("thumbs up"))
        assertTrue(spoken.contains("celebration"))
    }

    @Test
    fun `links are not spelled out`() {
        assertEquals("Check a link now.", speak("Check https://example.com/x?y=1 now"))
    }

    @Test
    fun `chat shorthand is expanded`() {
        assertEquals("Are you coming tomorrow?", speak("r u coming tmrw?"))
        assertEquals("Please send the message.", speak("pls send the msg"))
        assertEquals("Tomorrow works.", speak("tmrw works"))
    }

    @Test
    fun `markdown and quoting are removed`() {
        assertEquals("Important meeting today.", speak("**Important** meeting today"))
        assertEquals("Hello world.", speak("`hello` world"))
        assertFalse(speak("> quoted line").contains(">"))
    }

    @Test
    fun `repeated punctuation is collapsed`() {
        assertEquals("Wait what?", speak("Wait what????"))
    }

    @Test
    fun `terminal punctuation is added for natural intonation`() {
        assertEquals("On my way.", speak("On my way"))
        assertEquals("Where are you?", speak("Where are you?"))
    }

    @Test
    fun `emoji only messages are not speakable`() {
        val result = TextNormalizer.normalize("😂😂😂", EmojiMode.IGNORE)
        assertFalse(TextNormalizer.isSpeakable(result.text))
    }

    @Test
    fun `very long messages are shortened on a sentence boundary`() {
        val long = buildString {
            repeat(6) { append("This is sentence number ${it + 1} and it is reasonably long. ") }
        }
        val result = TextNormalizer.normalize(long.take(400), EmojiMode.DESCRIBE_IMPORTANT, maxChars = 160)
        assertTrue("should be shortened", result.wasTruncated)
        assertTrue("should stay near the limit", result.text.length <= 170)
        assertTrue("should end cleanly", result.text.endsWith(".") || result.text.endsWith("…"))
    }

    @Test
    fun `hindi text survives normalisation untouched`() {
        val spoken = speak("मैं आ रहा हूँ 👍")
        assertTrue(spoken.contains("मैं आ रहा हूँ"))
    }

    @Test
    fun `hinglish is left readable`() {
        assertEquals("Bhai please call karna.", speak("bhai pls call karna"))
    }
}
