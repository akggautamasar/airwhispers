package com.airwhispers.domain.tts

import com.airwhispers.config.ProductConfig
import com.airwhispers.data.model.EmojiMode

/**
 * Turns a chat message into something a speech engine can say *naturally*.
 *
 * Pure Kotlin (no Android types) so every rule is unit-testable and the behaviour
 * is identical in the app, in tests, and anywhere else it is reused.
 *
 * Example:
 * ```
 * "I love you ❤️😂"  ->  "I love you."
 * ```
 */
object TextNormalizer {

    data class Result(
        val text: String,
        val describedEmoji: Int = 0,
        val droppedEmoji: Int = 0,
        val wasTruncated: Boolean = false,
        val wasEmpty: Boolean = true,
    )

    fun normalize(
        raw: String,
        emojiMode: EmojiMode = EmojiMode.DESCRIBE_IMPORTANT,
        maxChars: Int = ProductConfig.MAX_SPOKEN_CHARS,
    ): Result {
        if (raw.isBlank()) return Result(text = "")

        var described = 0
        var dropped = 0

        var text = raw
            .replace("\r\n", "\n")
            .replace('\u00A0', ' ')
            .replace(ZERO_WIDTH, "")

        text = stripFormatting(text)
        text = replaceLinks(text, emojiMode)
        val emojiResult = applyEmojiPolicy(text, emojiMode)
        text = emojiResult.first
        described += emojiResult.second.first
        dropped += emojiResult.second.second

        text = expandChatAbbreviations(text)
        text = tidy(text)
        text = capitaliseFirst(text)
        text = ensureTerminalPunctuation(text)

        var truncated = false
        if (text.length > maxChars) {
            text = truncate(text, maxChars)
            truncated = true
        }

        return Result(
            text = text,
            describedEmoji = described,
            droppedEmoji = dropped,
            wasTruncated = truncated,
            wasEmpty = text.isBlank(),
        )
    }

    /** A message with no letters or digits has nothing worth hearing. */
    fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() }

    // ------------------------------------------------------------------ steps

    private fun stripFormatting(input: String): String {
        var out = input
        out = out.replace(CODE_BLOCK, " ")
        out = out.replace(INLINE_CODE, "$1")
        out = out.replace(MARKDOWN_EMPHASIS, "$1")
        out = out.replace(BLOCKQUOTE, " ")
        out = out.replace(HEADING, "$1")
        out = out.replace(LEADING_BULLET, "$1")
        // Ordering controls used to spoof text direction in some clients.
        out = out.replace(BIDI_CONTROLS, "")
        return out
    }

    private fun replaceLinks(input: String, mode: EmojiMode): String =
        if (mode == EmojiMode.IGNORE) input.replace(URL, " ") else input.replace(URL, " a link ")

    /**
     * Emoji policy:
     *  - IGNORE            : every emoji disappears.
     *  - DESCRIBE_IMPORTANT: only emoji that *change meaning* are spoken, and never
     *                        when the surrounding words already say the same thing
     *                        ("I love you ❤️" must not become "I love you, love").
     *  - READ_ALL          : describe everything we have a name for.
     */
    private fun applyEmojiPolicy(input: String, mode: EmojiMode): Pair<String, Pair<Int, Int>> {
        val out = StringBuilder(input.length)
        var i = 0
        var described = 0
        var dropped = 0
        val lower = input.lowercase()
        while (i < input.length) {
            val cp = input.codePointAt(i)
            if (!isEmojiCodePoint(cp)) {
                if (cp == VARIATION_SELECTOR || cp == KEYCAP) {
                    // Orphan variation selectors / keycaps carry no meaning alone.
                    i += Character.charCount(cp)
                } else {
                    out.appendCodePoint(cp)
                    i += Character.charCount(cp)
                }
                continue
            }
            // Consume the whole cluster (FE0F, ZWJ chains, skin tones).
            var end = i + Character.charCount(cp)
            while (end < input.length) {
                val next = input.codePointAt(end)
                when {
                    next == VARIATION_SELECTOR || next == KEYCAP -> end += Character.charCount(next)
                    next == ZWJ && end + 1 < input.length && isEmojiCodePoint(input.codePointAt(end + 1)) ->
                        end += Character.charCount(next) + Character.charCount(input.codePointAt(end + 1))
                    next in SKIN_TONES -> end += Character.charCount(next)
                    else -> break
                }
            }
            val name = describe(cp)
            val speakIt = when {
                mode == EmojiMode.IGNORE -> false
                name == null -> false
                mode == EmojiMode.READ_ALL -> true
                else -> IMPORTANT in name.flags && !isRedundant(name, lower)
            }
            if (speakIt && name != null) {
                // Keep the emoji's position inside the sentence.
                if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                out.append(name.text)
                out.append(' ')
                described++
            } else {
                dropped++
            }
            i = end
        }
        return out.toString() to (described to dropped)
    }

    /**
     * A description is redundant when the words next to it already express it —
     * this is what keeps "I love you ❤️" from being read as "I love you, love".
     */
    private fun isRedundant(description: EmojiName, lowerText: String): Boolean =
        description.redundantWith.any { it in lowerText }

    private data class EmojiName(
        val text: String,
        val flags: Set<String> = emptySet(),
        val redundantWith: List<String> = emptyList(),
    )

    /** `IMPORTANT` marks descriptions that must survive in DESCRIBE_IMPORTANT mode. */
    private const val IMPORTANT = "important"

    private fun describe(baseCodePoint: Int): EmojiName? = EMOJI_NAMES[baseCodePoint]

    private val LOVE_WORDS = listOf("love", "luv", "lub", "pyar", "pyaar", "loving")

    private val EMOJI_NAMES: Map<Int, EmojiName> = buildMap {
        val loveFlag = setOf(IMPORTANT)
        // Hearts — meaning-bearing, but never after the word itself.
        listOf(
            0x2764, 0x1F9E1, 0x1F49B, 0x1F49A, 0x1F499, 0x1F49C, 0x1F5A4, 0x1FA75, 0x1FA76,
            0x1F49D, 0x1F496, 0x1F497, 0x1F498, 0x1F49E, 0x1F495, 0x1F49F, 0x1F60D, 0x1F970, 0x1FA77,
        ).forEach { put(it, EmojiName("love", loveFlag, LOVE_WORDS)) }
        listOf(0x1F618, 0x1F617, 0x1F619, 0x1F61A, 0x1F48B).forEach { put(it, EmojiName("a kiss")) }
        put(0x2757, EmojiName("important", setOf(IMPORTANT), listOf("important", "urgent")))
        put(0x203C, EmojiName("important", setOf(IMPORTANT), listOf("important", "urgent")))
        put(0x2049, EmojiName("really", setOf(IMPORTANT)))
        put(0x26A0, EmojiName("warning", setOf(IMPORTANT)))
        put(0x1F6A8, EmojiName("urgent", setOf(IMPORTANT), listOf("urgent", "emergency")))
        put(0x1F4DE, EmojiName("call", setOf(IMPORTANT), listOf("call", "phone", "ring", "dial")))
        put(0x260E, EmojiName("call", setOf(IMPORTANT), listOf("call", "phone", "ring", "dial")))
        put(0x1F4F1, EmojiName("phone", emptySet(), listOf("phone", "mobile", "cell")))
        put(0x1F622, EmojiName("sad"))
        put(0x1F62D, EmojiName("crying"))
        put(0x1F97A, EmojiName("worried"))
        put(0x1F614, EmojiName("sad"))
        put(0x1F602, EmojiName("laughing"))
        put(0x1F923, EmojiName("laughing"))
        put(0x1F605, EmojiName("laughing"))
        put(0x1F600, EmojiName("smiling"))
        put(0x1F601, EmojiName("smiling"))
        put(0x1F603, EmojiName("smiling"))
        put(0x1F604, EmojiName("smiling"))
        put(0x1F60A, EmojiName("smiling"))
        put(0x1F642, EmojiName("smiling"))
        put(0x1F609, EmojiName("winking"))
        put(0x1F914, EmojiName("thinking"))
        put(0x1F44D, EmojiName("thumbs up"))
        put(0x1F44E, EmojiName("thumbs down"))
        put(0x1F44F, EmojiName("applause"))
        put(0x1F64C, EmojiName("celebration"))
        put(0x1F389, EmojiName("celebration"))
        put(0x1F38A, EmojiName("celebration"))
        put(0x1F64F, EmojiName("please"))
        put(0x1F64B, EmojiName("hello"))
        put(0x1F44B, EmojiName("hello"))
        put(0x1F4AA, EmojiName("strong"))
        put(0x1F525, EmojiName("fire"))
        put(0x1F60E, EmojiName("cool"))
        put(0x1F91D, EmojiName("handshake"))
        put(0x1F917, EmojiName("hug"))
        put(0x1F4A9, EmojiName("poop"))
        put(0x1F615, EmojiName("confused"))
        put(0x1F610, EmojiName("neutral"))
        put(0x1F611, EmojiName("neutral"))
        put(0x1F636, EmojiName("speechless"))
        put(0x1F62E, EmojiName("surprised"))
        put(0x1F631, EmojiName("shocked"))
        put(0x1F621, EmojiName("angry"))
        put(0x1F620, EmojiName("angry"))
        put(0x1F92C, EmojiName("angry"))
        put(0x1F644, EmojiName("rolling eyes"))
        put(0x1F971, EmojiName("yawning"))
        put(0x1F634, EmojiName("sleeping"))
        put(0x1F62C, EmojiName("awkward"))
        put(0x1F910, EmojiName("zipped mouth"))
        put(0x1F92B, EmojiName("quiet"))
        put(0x1F4AF, EmojiName("hundred"))
        put(0x1F51D, EmojiName("on top"))
        put(0x2705, EmojiName("yes"))
        put(0x274C, EmojiName("no"))
        put(0x1F44C, EmojiName("okay"))
    }

    /**
     * Chat shorthand that a speech engine would otherwise spell out letter by
     * letter. Word-boundary matched so normal prose is untouched.
     */
    private val ABBREVIATIONS: List<Pair<Regex, String>> = listOf(
        Regex("\\br u\\b", RegexOption.IGNORE_CASE) to "are you",
        Regex("\\bu\\b", RegexOption.IGNORE_CASE) to "you",
        Regex("\\bur\\b", RegexOption.IGNORE_CASE) to "your",
        Regex("\\bpls\\b|\\bplz\\b", RegexOption.IGNORE_CASE) to "please",
        Regex("\\bpls\\b", RegexOption.IGNORE_CASE) to "please",
        Regex("\\bthx\\b|\\bty\\b|\\btysm\\b", RegexOption.IGNORE_CASE) to "thanks",
        Regex("\\btmrw\\b|\\btmr\\b", RegexOption.IGNORE_CASE) to "tomorrow",
        Regex("\\bmsg\\b", RegexOption.IGNORE_CASE) to "message",
        Regex("\\bbtw\\b", RegexOption.IGNORE_CASE) to "by the way",
        Regex("\\bidk\\b", RegexOption.IGNORE_CASE) to "I don't know",
        Regex("\\bimo\\b", RegexOption.IGNORE_CASE) to "in my opinion",
        Regex("\\basap\\b", RegexOption.IGNORE_CASE) to "as soon as possible",
        Regex("\\bb4\\b", RegexOption.IGNORE_CASE) to "before",
        Regex("\\bgr8\\b", RegexOption.IGNORE_CASE) to "great",
        Regex("\\bluv\\b", RegexOption.IGNORE_CASE) to "love",
        Regex("\\bhv\\b", RegexOption.IGNORE_CASE) to "have",
        Regex("\\bwt\\b", RegexOption.IGNORE_CASE) to "what",
        Regex("\\bim\\b", RegexOption.IGNORE_CASE) to "I'm",
        Regex("\\bdont\\b", RegexOption.IGNORE_CASE) to "don't",
        Regex("\\bcant\\b", RegexOption.IGNORE_CASE) to "can't",
    )

    private fun expandChatAbbreviations(input: String): String {
        var out = input
        ABBREVIATIONS.forEach { (pattern, replacement) -> out = pattern.replace(out, replacement) }
        return out
    }

    private fun tidy(input: String): String {
        var out = input
        out = out.replace(TEXT_EMOTICON_HAPPY, " ")
        out = out.replace(TEXT_EMOTICON_SAD, " ")
        out = out.replace("<3", " love ")
        out = out.replace(REPEATED_PUNCTUATION, "$1")
        out = out.replace(REPEATED_SPACES, " ")
        out = out.replace(SPACE_BEFORE_PUNCTUATION, "$1")
        out = out.replace(EMPTY_PAIRS, " ")
        out = out.replace(REPEATED_SPACES, " ")
        // Newlines become sentence breaks so the engine pauses naturally.
        out = out.replace(Regex("[\\n\\t]+"), ". ")
        out = out.replace(Regex("(\\.\\s*){2,}"), ". ")
        return out.trim()
    }

    /** "r u coming" -> "Are you coming" reads far more naturally. */
    private fun capitaliseFirst(input: String): String {
        if (input.isEmpty()) return input
        val first = input[0]
        return if (first.isLowerCase()) first.uppercaseChar() + input.substring(1) else input
    }

    private fun ensureTerminalPunctuation(input: String): String {
        if (input.isEmpty()) return input
        val last = input.last()
        return if (last in ".!?;:,…") input else "$input."
    }

    /** Shortens on a sentence boundary when possible, otherwise on a word. */
    internal fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val window = text.take(maxChars)
        val sentenceEnd = window.lastIndexOfAny(charArrayOf('.', '!', '?'))
        val cut = when {
            sentenceEnd > maxChars / 3 -> window.take(sentenceEnd + 1)
            else -> {
                val space = window.lastIndexOf(' ')
                (if (space > 0) window.take(space) else window).trimEnd().trimEnd(',', ';', ':', '-') + "…"
            }
        }
        val trimmed = cut.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.last() in ".!?") trimmed else ensureTerminalPunctuation(trimmed)
    }

    // ------------------------------------------------------------- character sets

    fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
        in 0x1F300..0x1FAFF -> true
        in 0x1F000..0x1F2FF -> true
        in 0x2600..0x27BF -> true
        in 0x2B00..0x2BFF -> true
        in 0x2190..0x21FF -> true
        in 0x2900..0x297F -> true
        in 0x1F1E6..0x1F1FF -> true
        0x203C, 0x2049, 0x2139, 0x24C2 -> true
        0x1F549, 0x1F54A -> true
        else -> false
    }

    private const val VARIATION_SELECTOR = 0xFE0F
    private const val KEYCAP = 0x20E3
    private const val ZWJ = 0x200D
    private val SKIN_TONES = 0x1F3FB..0x1F3FF

    private const val ZERO_WIDTH = "[\\u200B\\u200C\\uFEFF\\u180E]"
    private const val BIDI_CONTROLS = "[\\u202A-\\u202E\\u2066-\\u2069]"
    private const val URL =
        "(https?://\\S+|www\\.\\S+|[\\w.+-]+@[\\w-]+\\.[\\w.]+)"
    private const val CODE_BLOCK = "```[\\s\\S]*?```"
    private const val INLINE_CODE = "`([^`]*)`"
    private const val MARKDOWN_EMPHASIS = "[*_~]{1,3}([^*_~]+)[*_~]{1,3}"
    private const val BLOCKQUOTE = "(?m)^\\s*>+\\s*"
    private const val HEADING = "(?m)^\\s*#{1,6}\\s*([^\\n]*)"
    private const val LEADING_BULLET = "(?m)^\\s*[-•*]\\s+([^\\n]*)"
    private const val TEXT_EMOTICON_HAPPY = "[:;]-?[){DDPp]+"
    private const val TEXT_EMOTICON_SAD = "[:;]-?[(/|]+"
    private const val REPEATED_PUNCTUATION = "([!?.,])\\1{1,}"
    private const val REPEATED_SPACES = "\\s{2,}"
    private const val SPACE_BEFORE_PUNCTUATION = "\\s+([,.!?;:])"
    private const val EMPTY_PAIRS = "\\.\\s*\\.|,\\s*,|!\\s*!"
}
