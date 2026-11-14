package org.mozkey.inputmethod.engine

import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.mozkey.inputmethod.settings.Prefs

/** Builders for the protocol messages this client sends. */
object MozcMessages {

    fun charKey(codePoint: Int): KeyEvent =
        KeyEvent.newBuilder().setKeyCode(codePoint).build()

    fun specialKey(key: KeyEvent.SpecialKey): KeyEvent =
        KeyEvent.newBuilder().setSpecialKey(key).build()

    fun command(type: SessionCommand.CommandType): SessionCommand =
        SessionCommand.newBuilder().setType(type).build()

    fun submitCandidate(id: Int): SessionCommand =
        SessionCommand.newBuilder()
            .setType(SessionCommand.CommandType.SUBMIT_CANDIDATE)
            .setId(id)
            .build()

    /**
     * Mobile request. The romaji table decides how key codes are interpreted:
     * the flick table maps one ASCII code per kana (see data/preedit/flick-hiragana.tsv),
     * the QWERTY table is ordinary romaji tuned for touch keyboards.
     */
    fun mobileRequest(layout: Prefs.KanaLayout): Request {
        val builder = Request.newBuilder()
            .setMixedConversion(true)
            .setZeroQuerySuggestion(true)
            .setUpdateInputModeFromSurroundingText(false)
            .setAutoPartialSuggestion(true)
            .setSpaceOnAlphanumeric(Request.SpaceOnAlphanumeric.SPACE_OR_CONVERT_KEEPING_COMPOSITION)
        when (layout) {
            Prefs.KanaLayout.FLICK -> builder
                .setSpecialRomanjiTable(Request.SpecialRomanjiTable.FLICK_TO_HIRAGANA)
                .setKanaModifierInsensitiveConversion(true)
                .setCrossingEdgeBehavior(Request.CrossingEdgeBehavior.DO_NOTHING)
            Prefs.KanaLayout.QWERTY -> builder
                .setSpecialRomanjiTable(Request.SpecialRomanjiTable.QWERTY_MOBILE_TO_HIRAGANA)
                .setKanaModifierInsensitiveConversion(false)
                .setCrossingEdgeBehavior(Request.CrossingEdgeBehavior.COMMIT_WITHOUT_CONSUMING)
        }
        return builder.build()
    }
}
