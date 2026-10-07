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
        // The 📞 must vanish entirely: the surrounding words already say it.
        assertEquals("Call me urgently.", speak("Call me urgently 📞"))
    }

    @Test
    fun `ignore mode strips every emoji`() {
        assertEquals("Great work.", speak("Great work 👍🎉", EmojiMode.IGNORE))
    }

    @Test
    fun `read all mode describes known emoji`() {
        assertEquals("Ok thumbs up celebration.", speak("ok 👍🎉", EmojiMode.READ_ALL))
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
        assertEquals("Quoted line.", speak("> quoted line"))
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
