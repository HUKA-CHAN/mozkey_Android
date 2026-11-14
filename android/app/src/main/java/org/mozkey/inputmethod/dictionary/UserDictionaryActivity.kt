package org.mozkey.inputmethod.dictionary

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionary.PosType
import org.mozkey.inputmethod.R
import org.mozkey.inputmethod.engine.MozcEngine
import java.io.IOException

/** Lists the registered words and lets the user add, edit and delete them. */
class UserDictionaryActivity : Activity() {

    private val entries = ArrayList<UserDictionaryStore.Entry>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var emptyView: TextView

    /** Parts of speech offered in the dialog, most common first. */
    private val partsOfSpeech = listOf(
        PosType.NOUN to "名詞",
        PosType.PROPER_NOUN to "固有名詞",
        PosType.PERSONAL_NAME to "人名",
        PosType.FAMILY_NAME to "姓",
        PosType.FIRST_NAME to "名",
        PosType.ORGANIZATION_NAME to "組織",
        PosType.PLACE_NAME to "地名",
        PosType.ABBREVIATION to "短縮よみ",
        PosType.SUGGESTION_ONLY to "サジェストのみ",
        PosType.SA_IRREGULAR_CONJUGATION_NOUN to "名詞サ変",
        PosType.ADJECTIVE to "形容詞",
        PosType.ADVERB to "副詞",
        PosType.EMOTICON to "顔文字",
        PosType.SYMBOL to "記号",
        PosType.SUPPRESSION_WORD to "抑制単語(候補に出さない)",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.dictionary_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16f)
            setPadding(pad, pad, pad, pad)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val pad = dp(16f)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                view.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
                insets
            }
        }

        root.addView(Button(this).apply {
            text = getString(R.string.dictionary_add)
            isAllCaps = false
            setOnClickListener { showEditor(-1) }
        })

        emptyView = TextView(this).apply {
            text = getString(R.string.dictionary_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.75f
            setPadding(0, dp(16f), 0, 0)
        }
        root.addView(emptyView)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this).apply {
            adapter = this@UserDictionaryActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showEditor(position) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        try {
            entries.addAll(UserDictionaryStore.load(this))
        } catch (e: IOException) {
            Log.e(TAG, "Cannot read the user dictionary", e)
            Toast.makeText(this, R.string.dictionary_load_failed, Toast.LENGTH_LONG).show()
        }
        refreshList()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun posLabel(pos: PosType): String =
        partsOfSpeech.firstOrNull { it.first == pos }?.second ?: pos.name

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(entries.map { "${it.reading}  →  ${it.word}  (${posLabel(it.pos)})" })
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    /** Opens the add dialog ([index] < 0) or the edit dialog for an existing entry. */
    private fun showEditor(index: Int) {
        val existing = entries.getOrNull(index)

        val readingInput = EditText(this).apply {
            hint = getString(R.string.dictionary_reading_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(existing?.reading ?: "")
        }
        val wordInput = EditText(this).apply {
            hint = getString(R.string.dictionary_word_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(existing?.word ?: "")
        }
        val posInput = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@UserDictionaryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                partsOfSpeech.map { it.second },
            )
            val selected = partsOfSpeech.indexOfFirst { it.first == existing?.pos }
            setSelection(if (selected >= 0) selected else 0)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20f)
            setPadding(pad, dp(8f), pad, 0)
            addView(readingInput)
            addView(wordInput)
            addView(posInput)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.dictionary_add else R.string.dictionary_edit)
            .setView(form)
            .setPositiveButton(R.string.dictionary_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.dictionary_delete) { _, _ ->
                val updated = ArrayList(entries).apply { removeAt(index) }
                commit(updated)
            }
        }
        val dialog = builder.create()
        dialog.show()

        // Set after show() so that an invalid entry keeps the dialog open.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val reading = UserDictionaryStore.normalizeReading(readingInput.text.toString())
            val word = wordInput.text.toString().trim()
            val problem = UserDictionaryStore.validate(reading, word)
            if (problem != null) {
                Toast.makeText(this, problemMessage(problem), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val pos = partsOfSpeech[posInput.selectedItemPosition.coerceIn(0, partsOfSpeech.size - 1)].first
            val entry = UserDictionaryStore.Entry(reading, word, pos)

            val updated = ArrayList(entries)
            if (index >= 0) updated[index] = entry else updated.add(entry)
            // The same reading and word registered twice would only be a duplicate.
            val duplicate = updated.indices.firstOrNull { i ->
                updated[i] !== entry && updated[i].reading == reading && updated[i].word == word
            }
            if (duplicate != null) updated.removeAt(duplicate)

            if (commit(updated)) dialog.dismiss()
        }
    }

    /** Saves [updated], reloads the engine and refreshes the list. */
    private fun commit(updated: List<UserDictionaryStore.Entry>): Boolean {
        try {
            UserDictionaryStore.save(this, updated)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot write the user dictionary", e)
            Toast.makeText(this, R.string.dictionary_save_failed, Toast.LENGTH_LONG).show()
            return false
        }
        entries.clear()
        entries.addAll(updated)
        refreshList()
        // Takes effect immediately when the keyboard is running; otherwise the
        // file is read the next time the engine starts.
        MozcEngine.current?.reload()
        return true
    }

    private fun problemMessage(problem: UserDictionaryStore.Problem): String = getString(
        when (problem) {
            UserDictionaryStore.Problem.READING_EMPTY -> R.string.dictionary_error_reading_empty
            UserDictionaryStore.Problem.READING_INVALID -> R.string.dictionary_error_reading_invalid
            UserDictionaryStore.Problem.WORD_EMPTY -> R.string.dictionary_error_word_empty
            UserDictionaryStore.Problem.WORD_INVALID -> R.string.dictionary_error_word_invalid
            UserDictionaryStore.Problem.TOO_LONG -> R.string.dictionary_error_too_long
        }
    )

    private companion object {
        const val TAG = "MozkeyDictionary"
    }
}
