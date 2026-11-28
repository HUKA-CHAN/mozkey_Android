package org.mozkey.inputmethod

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.LinearLayout
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as MozcContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent as MozcKeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Result
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config
import org.mozkey.inputmethod.engine.Diagnostics
import org.mozkey.inputmethod.engine.MozcEngine
import org.mozkey.inputmethod.engine.MozcMessages
import org.mozkey.inputmethod.engine.PhraseHistory
import org.mozkey.inputmethod.keyboard.CandidateBarView
import org.mozkey.inputmethod.keyboard.FunctionKey
import org.mozkey.inputmethod.keyboard.KeyAction
import org.mozkey.inputmethod.keyboard.KeyboardLayouts
import org.mozkey.inputmethod.keyboard.KeyboardMode
import org.mozkey.inputmethod.keyboard.KeyboardView
import org.mozkey.inputmethod.keyboard.Theme
import org.mozkey.inputmethod.settings.Prefs
import org.mozkey.inputmethod.zenz.ZenzManager
import java.io.File

/**
 * Mozkey for Android: an InputMethodService on top of libmozc.so.
 *
 * Kana input is composed by the Mozc session (key events in, preedit /
 * candidates / result out). Alphabet and symbol keyboards commit text directly.
 * Live conversion and Zenz correction are driven by the session itself: the
 * service only has to honor `Output.callback`, which asks it to send a follow-up
 * session command after a delay.
 */
class MozkeyInputMethodService :
    InputMethodService(),
    KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private enum class ShiftState { OFF, ONCE, LOCKED }

    private lateinit var prefs: Prefs
    private lateinit var engine: MozcEngine
    private lateinit var zenz: ZenzManager
    private val main = Handler(Looper.getMainLooper())

    private var keyboardView: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null

    private var mode = KeyboardMode.KANA_FLICK
    private var shift = ShiftState.OFF
    private var enterLabel = KeyboardLayouts.LABEL_ENTER

    /** True while the editor shows a Mozc preedit. Updated when an output is rendered. */
    private var hasComposition = false

    /** Commands sent to the engine whose output has not been rendered yet. */
    private var inFlight = 0

    /**
     * Bumped whenever the composition is abandoned on the UI side. Outputs that
     * were requested under an older epoch are dropped instead of being rendered.
     */
    private var epoch = 0

    /** Start of the composing region in the editor, as last reported by the app. */
    private var composingStart = -1

    /** Surrounding text captured when the current composition started. */
    private var compositionContext: MozcContext? = null

    /** App-layer phrase history: phrases committed before, offered again by reading prefix. */
    private lateinit var phraseHistory: PhraseHistory

    /** Reading (concatenated preedit keys) of the composition in the last rendered output. */
    private var lastReading = ""

    /** History phrases currently shown in the candidate bar, by their reserved negative id. */
    private var historyById: Map<Int, PhraseHistory.Entry> = emptyMap()

    private var pendingCallback: Runnable? = null
    private var fieldIncognito = false
    private var fieldIsPassword = false

    private val isKanaMode: Boolean
        get() = mode == KeyboardMode.KANA_FLICK || mode == KeyboardMode.KANA_QWERTY

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        zenz = ZenzManager(applicationContext)
        ZenzManager.current = zenz
        engine = MozcEngine(this)
        MozcEngine.current = engine
        phraseHistory = PhraseHistory(File(filesDir, PHRASE_HISTORY_FILE))
        engine.initialize { ok ->
            Diagnostics.engineReady = ok
            if (ok) {
                engine.setRequest(MozcMessages.mobileRequest(prefs.kanaLayout))
                applyConfig()
                maybeLoadZenz()
            }
        }
    }

    override fun onDestroy() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        cancelPendingCallback()
        engine.syncData()
        phraseHistory.flush()
        if (MozcEngine.current === engine) MozcEngine.current = null
        if (ZenzManager.current === zenz) ZenzManager.current = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val theme = Theme.from(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            // Lets the flick guide extend over the candidate bar and the bottom padding.
            clipChildren = false
            clipToPadding = false
        }

        val bar = CandidateBarView(this)
        bar.onCandidateSelected = { candidate ->
            val entry = historyById[candidate.id]
            if (entry != null) commitHistoryPhrase(entry)
            else sendSessionCommand(MozcMessages.submitCandidate(candidate.id))
        }
        val barHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics
        ).toInt()
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))

        val keyboard = KeyboardView(this)
        keyboard.listener = this
        keyboard.hapticEnabled = prefs.haptic
        keyboard.heightScale = prefs.keyboardHeightPercent / 100f
        keyboard.flickPreviewEnabled = prefs.flickPreview
        keyboard.overflowTop = barHeight.toFloat()
        root.addView(
            keyboard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Since Android 15 an IME that targets API 35+ draws edge to edge, so the
        // keyboard has to keep its keys clear of the navigation bar and cutouts.
        // Some apps hide the navigation bar, which drops the visible bottom inset
        // to zero and leaves the bottom key row inside the system's gesture area,
        // where its touches are swallowed. Keep the clearance the bar would take
        // if it were shown, so the bottom row always stays clear of that area.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val navBottom = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()
                ).bottom
                val bottom = maxOf(bars.bottom, navBottom)
                view.setPadding(bars.left, 0, bars.right, bottom)
                keyboard.overflowBottom = bottom.toFloat()
                insets
            }
        }

        keyboardView = keyboard
        candidateBar = bar
        updateKeyboard()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        abandonComposition(finishInEditor = false)

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        fieldIsPassword = inputClass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) || inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        fieldIncognito = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

        mode = when {
            inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME -> KeyboardMode.SYMBOL_1
            fieldIsPassword ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI -> KeyboardMode.ALPHABET
            else -> kanaMode()
        }
        shift = ShiftState.OFF

        enterLabel = if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            KeyboardLayouts.LABEL_ENTER
        } else when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "実行"
            EditorInfo.IME_ACTION_SEARCH -> "検索"
            EditorInfo.IME_ACTION_SEND -> "送信"
            EditorInfo.IME_ACTION_NEXT -> "次へ"
            EditorInfo.IME_ACTION_DONE -> "完了"
            else -> KeyboardLayouts.LABEL_ENTER
        }

        applyConfig()
        maybeLoadZenz()
        updateKeyboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        abandonComposition(finishInEditor = true)
        engine.syncData()
        phraseHistory.flush()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        abandonComposition(finishInEditor = false)
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        composingStart = candidatesStart
        // The user (or the app) moved the cursor out of the text being composed:
        // leave that text as it is and start over. A report without a composing
        // region is ignored on purpose, because reports arrive asynchronously and
        // one for an earlier commit can still be in transit after the next
        // composition has started.
        if (hasComposition && candidatesStart >= 0 &&
            (newSelStart != newSelEnd || newSelStart < candidatesStart || newSelStart > candidatesEnd)
        ) {
            abandonComposition(finishInEditor = true)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android 17 enforces per-app memory limits. The Zenz model is the largest
        // allocation in this process and is cheap to reload, so give it back first.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            releaseZenz()
            engine.syncData()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseZenz()
    }

    // ----------------------------------------------------------- configuration

    private fun kanaMode(): KeyboardMode =
        if (prefs.kanaLayout == Prefs.KanaLayout.QWERTY) KeyboardMode.KANA_QWERTY else KeyboardMode.KANA_FLICK

    private fun applyConfig() {
        val liveConversion = prefs.liveConversion
        val zenzActive = liveConversion && prefs.zenz && zenz.isLoaded
        val timeout = prefs.zenzTimeoutMsec
        val incognito = prefs.incognito || fieldIncognito || fieldIsPassword
        Diagnostics.configLiveConversion = liveConversion
        Diagnostics.configZenz = zenzActive
        Diagnostics.configZenzTimeoutMsec = timeout
        engine.updateConfig { config ->
            config.sessionKeymap = Config.SessionKeymap.MOBILE
            config.useLiveConversion = liveConversion
            config.useZenzLiveCorrection = zenzActive
            config.zenzLiveCorrectionTimeoutMsec = timeout
            config.incognitoMode = incognito
        }
    }

    private fun maybeLoadZenz() {
        if (!engine.isReady || !prefs.zenz || !prefs.liveConversion) return
        if (zenz.state != ZenzManager.State.UNLOADED) return
        zenz.loadAsync { main.post { applyConfig() } }
    }

    private fun releaseZenz() {
        if (!zenz.isLoaded) return
        // Stop the session from scheduling Zenz requests before the model goes away.
        engine.updateConfig { it.useZenzLiveCorrection = false }
        zenz.unloadAsync()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_KANA_LAYOUT -> {
                abandonComposition(finishInEditor = true)
                if (engine.isReady) engine.setRequest(MozcMessages.mobileRequest(prefs.kanaLayout))
                if (isKanaMode) mode = kanaMode()
                updateKeyboard()
            }
            Prefs.KEY_HAPTIC -> keyboardView?.hapticEnabled = prefs.haptic
            // Colors are read when the views are created, so build them again.
            Prefs.KEY_KEYBOARD_THEME, Prefs.KEY_KEY_BORDERS -> setInputView(onCreateInputView())
            Prefs.KEY_FLICK_PREVIEW -> keyboardView?.flickPreviewEnabled = prefs.flickPreview
            Prefs.KEY_SHOW_IME_SWITCH_KEY -> updateKeyboard()
            Prefs.KEY_KEYBOARD_HEIGHT -> keyboardView?.heightScale = prefs.keyboardHeightPercent / 100f
            Prefs.KEY_ZENZ, Prefs.KEY_LIVE_CONVERSION -> {
                if (prefs.zenz && prefs.liveConversion) maybeLoadZenz() else releaseZenz()
                applyConfig()
            }
            else -> applyConfig()
        }
    }

    // ------------------------------------------------------------------- input

    override fun onKey(action: KeyAction) {
        when (action) {
            is KeyAction.MozcChar -> sendMozcKey(MozcMessages.charKey(action.code), isCharacter = true)
            is KeyAction.Text -> commitDirect(action.text)
            is KeyAction.Function -> onFunctionKey(action.key)
        }
    }

    override fun onLongPress(action: KeyAction): Boolean {
        if (action is KeyAction.Function && action.key == FunctionKey.SWITCH_IME) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            return true
        }
        return false
    }

    private fun onFunctionKey(key: FunctionKey) {
        val composing = hasComposition || inFlight > 0
        when (key) {
            FunctionKey.BACKSPACE ->
                if (isKanaMode && engine.isReady) {
                    sendMozcKey(MozcMessages.specialKey(MozcKeyEvent.SpecialKey.BACKSPACE))
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
            FunctionKey.SPACE ->
                if (isKanaMode && engine.isReady) {
                    sendMozcKey(MozcMessages.specialKey(MozcKeyEvent.SpecialKey.SPACE))
                } else {
                    commitDirect(" ")
                }
            FunctionKey.ENTER ->
                if (composing) sendMozcKey(MozcMessages.specialKey(MozcKeyEvent.SpecialKey.VIRTUAL_ENTER))
                else sendKeyChar('\n')
            FunctionKey.LEFT ->
                if (composing) sendMozcKey(MozcMessages.specialKey(MozcKeyEvent.SpecialKey.VIRTUAL_LEFT))
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            FunctionKey.RIGHT ->
                if (composing) sendMozcKey(MozcMessages.specialKey(MozcKeyEvent.SpecialKey.VIRTUAL_RIGHT))
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            FunctionKey.MODE_KANA -> switchMode(kanaMode())
            FunctionKey.MODE_ALPHABET -> switchMode(KeyboardMode.ALPHABET)
            FunctionKey.MODE_SYMBOL -> switchMode(KeyboardMode.SYMBOL_1)
            FunctionKey.SYMBOL_PAGE ->
                switchMode(if (mode == KeyboardMode.SYMBOL_1) KeyboardMode.SYMBOL_2 else KeyboardMode.SYMBOL_1)
            FunctionKey.SHIFT -> {
                shift = when (shift) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> ShiftState.LOCKED
                    ShiftState.LOCKED -> ShiftState.OFF
                }
                updateKeyboard()
            }
            FunctionKey.SWITCH_IME ->
                if (!switchToNextInputMethod(false)) {
                    getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }
            FunctionKey.NONE -> Unit
        }
    }

    private fun switchMode(newMode: KeyboardMode) {
        if (mode == newMode) return
        val leavingKana = isKanaMode
        mode = newMode
        if (leavingKana && !isKanaMode) {
            // Direct-input keyboards bypass Mozc, so nothing may stay half composed.
            abandonComposition(finishInEditor = true)
        }
        shift = ShiftState.OFF
        updateKeyboard()
    }

    /** Commits text without involving Mozc (alphabet and symbol keyboards). */
    private fun commitDirect(text: String) {
        val connection = currentInputConnection ?: return
        if (hasComposition || inFlight > 0) abandonComposition(finishInEditor = true)
        val shifted = shift != ShiftState.OFF && mode == KeyboardMode.ALPHABET
        connection.commitText(if (shifted) text.uppercase() else text, 1)
        if (shift == ShiftState.ONCE) {
            shift = ShiftState.OFF
            updateKeyboard()
        }
    }

    private fun sendMozcKey(key: MozcKeyEvent, isCharacter: Boolean = false) {
        if (!engine.isReady) return
        cancelPendingCallback()
        if (isCharacter && !hasComposition && inFlight == 0) {
            compositionContext = captureContext()
        }
        val sentEpoch = epoch
        inFlight++
        Diagnostics.keysSent.incrementAndGet()
        engine.sendKey(key, compositionContext) { output ->
            if (sentEpoch != epoch) return@sendKey
            inFlight--
            render(output)
            if (!output.consumed && key.hasSpecialKey()) handleUnconsumed(key.specialKey)
        }
    }

    private fun sendSessionCommand(command: SessionCommand) {
        if (!engine.isReady) return
        cancelPendingCallback()
        val sentEpoch = epoch
        inFlight++
        engine.sendCommand(command, compositionContext) { output ->
            if (sentEpoch != epoch) return@sendCommand
            inFlight--
            render(output)
        }
    }

    /**
     * Reads the text around the cursor once, at the start of a composition. Mozc
     * uses it for history-aware prediction and Zenz uses it as left/right context.
     * Password fields never expose their content to the engine.
     */
    private fun captureContext(): MozcContext? {
        val builder = MozcContext.newBuilder()
        if (fieldIsPassword) {
            return builder.setInputFieldType(MozcContext.InputFieldType.PASSWORD).build()
        }
        builder.inputFieldType = MozcContext.InputFieldType.NORMAL
        val connection = currentInputConnection ?: return builder.build()
        connection.getTextBeforeCursor(CONTEXT_CHARS, 0)?.let { builder.precedingText = it.toString() }
        connection.getTextAfterCursor(CONTEXT_CHARS, 0)?.let { builder.followingText = it.toString() }
        return builder.build()
    }

    /** A special key Mozc did not handle (no composition): apply it to the editor. */
    private fun handleUnconsumed(key: MozcKeyEvent.SpecialKey) {
        when (key) {
            MozcKeyEvent.SpecialKey.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            MozcKeyEvent.SpecialKey.SPACE -> currentInputConnection?.commitText(" ", 1)
            MozcKeyEvent.SpecialKey.VIRTUAL_ENTER, MozcKeyEvent.SpecialKey.ENTER -> sendKeyChar('\n')
            MozcKeyEvent.SpecialKey.VIRTUAL_LEFT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            MozcKeyEvent.SpecialKey.VIRTUAL_RIGHT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ output

    private fun render(output: Output) {
        val connection = currentInputConnection
        val hasPreedit = output.hasPreedit() && output.preedit.segmentCount > 0
        val committed = output.hasResult() && output.result.type == Result.ResultType.STRING

        // Phrase history learns from commits. The reading of the part that was
        // just committed precedes the reading of what is still being composed.
        val remainingReading =
            if (hasPreedit) output.preedit.segmentList.joinToString("") { it.key } else ""
        if (committed) recordPhrase(output.result.value, remainingReading)
        lastReading = remainingReading

        if (connection != null) {
            connection.beginBatchEdit()
            if (output.hasDeletionRange()) {
                val range = output.deletionRange
                connection.deleteSurroundingText(-range.offset, range.length + range.offset)
            }
            if (committed) {
                connection.commitText(output.result.value, 1)
            }
            if (hasPreedit) {
                showPreedit(connection, output.preedit)
            } else if (hasComposition && !committed) {
                connection.setComposingText("", 1)
            }
            connection.endBatchEdit()
        }

        hasComposition = hasPreedit
        if (committed || !hasPreedit) {
            // The text around the cursor changed; read it again for the next composition.
            compositionContext = null
        }

        showCandidates(output)
        candidateBar?.setStatus(
            when {
                !hasPreedit -> CandidateBarView.Status.NONE
                output.zenzLiveCorrectionApplied -> CandidateBarView.Status.ZENZ_APPLIED
                output.zenzLiveCorrectionPending -> CandidateBarView.Status.ZENZ_PENDING
                else -> CandidateBarView.Status.NONE
            }
        )
        if (output.zenzLiveCorrectionPending) Diagnostics.zenzPendingOutputs.incrementAndGet()
        if (output.zenzLiveCorrectionApplied) Diagnostics.zenzAppliedOutputs.incrementAndGet()
        updateDynamicLabels()
        scheduleCallback(output)
    }

    private fun showPreedit(connection: InputConnection, preedit: Preedit) {
        val text = StringBuilder()
        val spans = ArrayList<Triple<Int, Int, Boolean>>()
        var converted = false
        for (segment in preedit.segmentList) {
            val start = text.length
            text.append(segment.value)
            val highlighted = segment.annotation == Preedit.Segment.Annotation.HIGHLIGHT
            if (highlighted) converted = true
            spans.add(Triple(start, text.length, highlighted))
        }
        val styled = SpannableString(text)
        val focusColor = Theme.from(this).candidateFocus
        for ((start, end, highlighted) in spans) {
            if (start == end) continue
            styled.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (highlighted) {
                styled.setSpan(BackgroundColorSpan(focusColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attribute = TextAttribute.Builder()
            if (Build.VERSION.SDK_INT >= API_ANDROID_17) {
                // Android 17 accessibility: tell screen readers whether this text is
                // a chosen conversion candidate or still the raw reading.
                attribute.setTextSuggestionSelected(converted)
            }
            connection.setComposingText(styled, 1, attribute.build())
        } else {
            connection.setComposingText(styled, 1)
        }

        // Mozc reports the caret in Unicode characters from the start of the preedit.
        val length = text.codePointCount(0, text.length)
        val cursor = preedit.cursor
        if (cursor in 0 until length && composingStart >= 0) {
            val offset = composingStart + text.offsetByCodePoints(0, cursor)
            connection.setSelection(offset, offset)
        }
    }

    private fun showCandidates(output: Output) {
        val bar = candidateBar ?: return
        val list = ArrayList<CandidateBarView.Candidate>()
        var focused = -1
        if (output.hasAllCandidateWords() && output.allCandidateWords.candidatesCount > 0) {
            val words = output.allCandidateWords
            for (word in words.candidatesList) {
                list.add(
                    CandidateBarView.Candidate(
                        word.id, word.value,
                        if (word.hasAnnotation() && word.annotation.hasDescription()) word.annotation.description else null,
                    )
                )
            }
            if (words.hasFocusedIndex()) focused = words.focusedIndex
        } else if (output.hasCandidateWindow()) {
            val window = output.candidateWindow
            for (candidate in window.candidateList) {
                list.add(
                    CandidateBarView.Candidate(
                        candidate.id, candidate.value,
                        if (candidate.hasAnnotation() && candidate.annotation.hasDescription()) {
                            candidate.annotation.description
                        } else null,
                    )
                )
            }
            if (window.hasFocusedIndex()) focused = window.focusedIndex
        }
        // History phrases go first: they are what the user most likely wants
        // to type again, and they never carry the conversion focus.
        val history = historySuggestions(output)
        historyById = if (history.isEmpty()) {
            emptyMap()
        } else {
            history.mapIndexed { index, entry -> historyId(index) to entry }.toMap()
        }
        if (history.isNotEmpty()) {
            val chips = history.mapIndexed { index, entry ->
                CandidateBarView.Candidate(historyId(index), entry.value, HISTORY_DESCRIPTION)
            }
            list.addAll(0, chips)
            if (focused >= 0) focused += chips.size
        }
        bar.setCandidates(list, focused)
    }

    // --------------------------------------------------------- phrase history

    /** Phrase history is learned and offered only where Mozc itself would learn. */
    private fun phraseLearningAllowed(): Boolean =
        prefs.phraseHistory && !prefs.incognito && !fieldIncognito && !fieldIsPassword

    /**
     * Records one committed conversion in the phrase history. [remainingReading]
     * is the reading of the part still being composed after the commit (empty
     * when the commit finished the composition); the committed part's reading
     * is what precedes it in [lastReading].
     */
    private fun recordPhrase(value: String, remainingReading: String) {
        if (!phraseLearningAllowed()) return
        val committedReading = when {
            remainingReading.isEmpty() -> lastReading
            lastReading.endsWith(remainingReading) ->
                lastReading.dropLast(remainingReading.length)
            else -> return
        }
        if (committedReading.isEmpty()) return
        phraseHistory.record(committedReading, value)
    }

    /**
     * Previously committed phrases whose reading starts with the current one.
     * Only in the plain suggestion state: once a segment is highlighted, the
     * bar belongs to the conversion candidates.
     */
    private fun historySuggestions(output: Output): List<PhraseHistory.Entry> {
        if (!phraseLearningAllowed()) return emptyList()
        if (!output.hasPreedit() || output.preedit.segmentCount == 0) return emptyList()
        if (output.preedit.segmentList.any {
                it.annotation == Preedit.Segment.Annotation.HIGHLIGHT
            }
        ) return emptyList()
        val reading = output.preedit.segmentList.joinToString("") { it.key }
        if (reading.isEmpty()) return emptyList()
        return phraseHistory.suggest(reading)
    }

    /** Commits a history phrase directly, replacing the current composition. */
    private fun commitHistoryPhrase(entry: PhraseHistory.Entry) {
        // Count the reuse under the phrase's own reading, not the typed prefix.
        phraseHistory.record(entry.reading, entry.value)
        val connection = currentInputConnection
        abandonComposition(finishInEditor = false)
        connection?.commitText(entry.value, 1)
    }

    /**
     * Live conversion and Zenz correction are asynchronous on the session side.
     * The session asks for a follow-up command (apply the debounced conversion,
     * poll the Zenz result) through `Output.callback`; stale callbacks carry a
     * generation number and are rejected by the session itself.
     */
    private fun scheduleCallback(output: Output) {
        cancelPendingCallback()
        if (!output.hasCallback() || !output.callback.hasSessionCommand()) return
        val command = output.callback.sessionCommand
        when (command.type) {
            SessionCommand.CommandType.APPLY_LIVE_CONVERSION ->
                Diagnostics.liveConversionCallbacks.incrementAndGet()
            SessionCommand.CommandType.APPLY_ZENZ_LIVE_CORRECTION ->
                Diagnostics.zenzCallbacks.incrementAndGet()
            else -> {}
        }
        val delay = output.callback.delayMillisec.toLong().coerceIn(0L, 10_000L)
        val runnable = Runnable {
            pendingCallback = null
            sendSessionCommand(command)
        }
        pendingCallback = runnable
        main.postDelayed(runnable, delay)
    }

    private fun cancelPendingCallback() {
        pendingCallback?.let { main.removeCallbacks(it) }
        pendingCallback = null
    }

    /**
     * Drops the current composition. With [finishInEditor] the visible preedit is
     * kept as plain text; either way the session context is reset and every output
     * that is still on its way is ignored.
     */
    private fun abandonComposition(finishInEditor: Boolean) {
        cancelPendingCallback()
        val hadWork = hasComposition || inFlight > 0
        if (finishInEditor && hadWork) currentInputConnection?.finishComposingText()
        epoch++
        inFlight = 0
        hasComposition = false
        compositionContext = null
        composingStart = -1
        lastReading = ""
        historyById = emptyMap()
        if (engine.isReady) {
            // RESET_CONTEXT cancels any composition and confirms pending learning.
            // REVERT must not be used here: with no composition it undoes the
            // learning of the last committed conversion.
            engine.sendCommand(MozcMessages.command(SessionCommand.CommandType.RESET_CONTEXT))
        }
        candidateBar?.setCandidates(emptyList(), -1)
        candidateBar?.setStatus(CandidateBarView.Status.NONE)
        updateDynamicLabels()
    }

    // ---------------------------------------------------------------------- UI

    private fun updateKeyboard() {
        val keyboard = keyboardView ?: return
        keyboard.setLayout(KeyboardLayouts.forMode(mode, prefs.showImeSwitchKey))
        keyboard.setShifted(shift != ShiftState.OFF)
        updateDynamicLabels()
    }

    private fun updateDynamicLabels() {
        val keyboard = keyboardView ?: return
        keyboard.setEnterLabel(if (hasComposition) "確定" else enterLabel)
        keyboard.setSpaceLabel(if (hasComposition && isKanaMode) "変換" else null)
    }

    companion object {
        /** Characters of surrounding text handed to the engine on each side. */
        private const val CONTEXT_CHARS = 64

        /** File in the app's private storage holding the phrase history. */
        private const val PHRASE_HISTORY_FILE = "phrase_history.tsv"

        /** Candidate-bar description shown on history phrases. */
        private const val HISTORY_DESCRIPTION = "履歴"

        /** History candidates use reserved negative ids; Mozc's own ids are never negative. */
        private const val HISTORY_ID_BASE = -1_000_000

        private fun historyId(index: Int): Int = HISTORY_ID_BASE - index

        /** Build.VERSION_CODES for Android 17. */
        private const val API_ANDROID_17 = 37
    }
}
