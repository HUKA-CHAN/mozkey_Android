package org.mozkey.inputmethod.keyboard

/** Keys that are handled by the IME service rather than typed as text. */
enum class FunctionKey {
    BACKSPACE, ENTER, SPACE, LEFT, RIGHT,
    MODE_KANA, MODE_ALPHABET, MODE_SYMBOL,
    SHIFT, SYMBOL_PAGE, SWITCH_IME,
}

sealed class KeyAction {
    /** A key code interpreted by Mozc's romaji table (kana modes). */
    data class MozcChar(val code: Int) : KeyAction()

    /** Text committed as is (alphabet and symbol modes). */
    data class Text(val text: String) : KeyAction()

    data class Function(val key: FunctionKey) : KeyAction()
}

/** One flick target of a key. */
class Flick(val label: String, val action: KeyAction)

class Key(
    val label: String,
    val action: KeyAction,
    /** Flick targets in the order left, up, right, down; null where none. */
    val flicks: List<Flick?> = emptyList(),
    val weight: Float = 1f,
    /** Rendered in the secondary key color. */
    val functional: Boolean = false,
    /** Fires on touch down and repeats while held. */
    val repeatable: Boolean = false,
) {
    val hasFlicks: Boolean get() = flicks.any { it != null }

    companion object {
        const val LEFT = 0
        const val UP = 1
        const val RIGHT = 2
        const val DOWN = 3
    }
}

class KeyboardLayout(val rows: List<List<Key>>, val rowHeightDp: Float)

enum class KeyboardMode { KANA_FLICK, KANA_QWERTY, ALPHABET, SYMBOL_1, SYMBOL_2 }

object KeyboardLayouts {

    fun forMode(mode: KeyboardMode, showSwitchKey: Boolean = true): KeyboardLayout = when (mode) {
        KeyboardMode.KANA_FLICK -> kanaFlick(showSwitchKey)
        KeyboardMode.KANA_QWERTY -> kanaQwerty(showSwitchKey)
        KeyboardMode.ALPHABET -> alphabet
        KeyboardMode.SYMBOL_1 -> symbols1(showSwitchKey)
        KeyboardMode.SYMBOL_2 -> symbols2(showSwitchKey)
    }

    private fun fn(label: String, key: FunctionKey, weight: Float = 1f, repeatable: Boolean = false) =
        Key(label, KeyAction.Function(key), weight = weight, functional = true, repeatable = repeatable)

    private fun text(label: String, weight: Float = 1f) = Key(label, KeyAction.Text(label), weight = weight)

    private fun textRow(characters: String): List<Key> =
        characters.codePoints().toArray().map { text(String(Character.toChars(it))) }

    private fun mozc(label: String, code: Char, weight: Float = 1f) =
        Key(label, KeyAction.MozcChar(code.code), weight = weight)

    /**
     * A 12-key flick key. [codes] are the five key codes (center, left, up, right,
     * down) and [labels] the matching characters, exactly as defined by
     * src/data/preedit/flick-hiragana.tsv. A space in [labels] means "no flick".
     */
    private fun flick(labels: String, codes: String, weight: Float = 1f): Key {
        require(labels.length == 5 && codes.length == 5)
        val flicks = (1..4).map { i ->
            if (labels[i] == ' ') null
            else Flick(labels[i].toString(), KeyAction.MozcChar(codes[i].code))
        }
        return Key(labels[0].toString(), KeyAction.MozcChar(codes[0].code), flicks, weight = weight)
    }

    const val LABEL_BACKSPACE = "⌫"
    const val LABEL_ENTER = "⏎"
    const val LABEL_SPACE = "空白"
    const val LABEL_SWITCH_IME = "🌐"
    const val LABEL_SHIFT = "⇧"

    // ---- かな (12 キー・フリック) ---------------------------------------------
    private fun kanaFlick(showSwitchKey: Boolean) = KeyboardLayout(
        rowHeightDp = 54f,
        rows = listOf(
            // Rows are centered by the view, so without the switch key the four
            // remaining keys widen to keep the row spanning the full width.
            listOfNotNull(
                if (showSwitchKey) fn(LABEL_SWITCH_IME, FunctionKey.SWITCH_IME) else null,
                flick("あいうえお", "1_;:@", if (showSwitchKey) 1f else 1.25f),
                flick("かきくけこ", "2abc|", if (showSwitchKey) 1f else 1.25f),
                flick("さしすせそ", "3def~", if (showSwitchKey) 1f else 1.25f),
                fn(LABEL_BACKSPACE, FunctionKey.BACKSPACE, weight = if (showSwitchKey) 1f else 1.25f, repeatable = true),
            ),
            listOf(
                fn("←", FunctionKey.LEFT, repeatable = true),
                flick("たちつてと", "4ghi$"),
                flick("なにぬねの", "5jkl%"),
                flick("はひふへほ", "6mno&"),
                fn("→", FunctionKey.RIGHT, repeatable = true),
            ),
            listOf(
                fn("記号", FunctionKey.MODE_SYMBOL),
                flick("まみむめも", "7pqrs"),
                flick("や(ゆ)よ", "8tuv^"),
                flick("らりるれろ", "9wxyz"),
                fn(LABEL_SPACE, FunctionKey.SPACE),
            ),
            listOf(
                fn("ABC", FunctionKey.MODE_ALPHABET),
                Key("小゛゜", KeyAction.MozcChar('*'.code)),
                flick("わをんー〜", "0+/-<"),
                flick("、。？！…", "#,?!>"),
                fn(LABEL_ENTER, FunctionKey.ENTER),
            ),
        ),
    )

    // ---- かな (QWERTY ローマ字) -----------------------------------------------
    private fun kanaQwerty(showSwitchKey: Boolean) = KeyboardLayout(
        rowHeightDp = 48f,
        rows = listOf(
            "qwertyuiop".map { mozc(it.toString(), it) },
            "asdfghjkl".map { mozc(it.toString(), it) } + mozc("ー", '-'),
            listOf(fn("ABC", FunctionKey.MODE_ALPHABET, weight = 1.5f)) +
                "zxcvbnm".map { mozc(it.toString(), it) } +
                fn(LABEL_BACKSPACE, FunctionKey.BACKSPACE, weight = 1.5f, repeatable = true),
            listOfNotNull(
                fn("記号", FunctionKey.MODE_SYMBOL, weight = 1.3f),
                if (showSwitchKey) fn(LABEL_SWITCH_IME, FunctionKey.SWITCH_IME) else null,
                mozc("、", ','),
                fn(LABEL_SPACE, FunctionKey.SPACE, weight = if (showSwitchKey) 2.7f else 3.7f),
                mozc("。", '.'),
                fn("←", FunctionKey.LEFT, repeatable = true),
                fn("→", FunctionKey.RIGHT, repeatable = true),
                fn(LABEL_ENTER, FunctionKey.ENTER, weight = 1.5f),
            ),
        ),
    )

    // ---- 英字 (直接入力) -------------------------------------------------------
    private val alphabet = KeyboardLayout(
        rowHeightDp = 48f,
        rows = listOf(
            textRow("qwertyuiop"),
            textRow("asdfghjkl"),
            listOf(fn(LABEL_SHIFT, FunctionKey.SHIFT, weight = 1.5f)) +
                textRow("zxcvbnm") +
                fn(LABEL_BACKSPACE, FunctionKey.BACKSPACE, weight = 1.5f, repeatable = true),
            listOf(
                fn("あ", FunctionKey.MODE_KANA, weight = 1.3f),
                fn("記号", FunctionKey.MODE_SYMBOL, weight = 1.3f),
                text(","),
                fn("space", FunctionKey.SPACE, weight = 3.4f),
                text("."),
                fn(LABEL_ENTER, FunctionKey.ENTER, weight = 1.5f),
            ),
        ),
    )

    // ---- 数字・記号 (直接入力) --------------------------------------------------
    private fun symbolBottomRow(showSwitchKey: Boolean) = listOfNotNull(
        fn("あ", FunctionKey.MODE_KANA, weight = 1.3f),
        fn("ABC", FunctionKey.MODE_ALPHABET, weight = 1.3f),
        if (showSwitchKey) fn(LABEL_SWITCH_IME, FunctionKey.SWITCH_IME) else null,
        fn("space", FunctionKey.SPACE, weight = if (showSwitchKey) 2.4f else 3.4f),
        fn("←", FunctionKey.LEFT, repeatable = true),
        fn("→", FunctionKey.RIGHT, repeatable = true),
        fn(LABEL_ENTER, FunctionKey.ENTER, weight = 1.5f),
    )

    private fun symbols1(showSwitchKey: Boolean) = KeyboardLayout(
        rowHeightDp = 48f,
        rows = listOf(
            textRow("1234567890"),
            textRow("-/:;()¥&@\""),
            listOf(fn("2/2", FunctionKey.SYMBOL_PAGE, weight = 1.5f)) +
                textRow(".,?!'、。") +
                fn(LABEL_BACKSPACE, FunctionKey.BACKSPACE, weight = 1.5f, repeatable = true),
            symbolBottomRow(showSwitchKey),
        ),
    )

    private fun symbols2(showSwitchKey: Boolean) = KeyboardLayout(
        rowHeightDp = 48f,
        rows = listOf(
            textRow("[]{}#%^*+="),
            textRow("_\\|~<>€$£・"),
            listOf(fn("1/2", FunctionKey.SYMBOL_PAGE, weight = 1.5f)) +
                textRow("「」ー〜…＠※") +
                fn(LABEL_BACKSPACE, FunctionKey.BACKSPACE, weight = 1.5f, repeatable = true),
            symbolBottomRow(showSwitchKey),
        ),
    )
}
