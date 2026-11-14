package org.mozkey.inputmethod.dictionary

import android.content.Context
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionary
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryStorage
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Reads and writes Mozc's user dictionary file.
 *
 * The engine loads `user_dictionary.db` (a serialized `UserDictionaryStorage`)
 * from the user profile directory at start-up and again on a RELOAD command. On
 * Android nothing else writes that file, so the app edits it directly and then
 * asks the engine to reload. Entries live in one dictionary named
 * [DICTIONARY_NAME]; any other dictionary found in the file is kept untouched.
 */
object UserDictionaryStore {

    class Entry(val reading: String, val word: String, val pos: UserDictionary.PosType)

    enum class Problem { READING_EMPTY, READING_INVALID, WORD_EMPTY, WORD_INVALID, TOO_LONG }

    private const val DICTIONARY_NAME = "Mozkey"
    private const val FILE_NAME = "user_dictionary.db"

    /** Same limit as kMaxStringSize in dictionary/user_dictionary_util.cc (UTF-8 bytes). */
    private const val MAX_STRING_BYTES = 300

    /** Must be the directory handed to MozcJNI.onPostLoad(). */
    private fun profileDirectory(context: Context): File = File(context.filesDir, "profile")

    private fun file(context: Context): File = File(profileDirectory(context), FILE_NAME)

    @Synchronized
    @Throws(IOException::class)
    fun load(context: Context): List<Entry> {
        val dictionary = readStorage(context).dictionariesList.firstOrNull { it.name == DICTIONARY_NAME }
            ?: return emptyList()
        return dictionary.entriesList.map { Entry(it.key, it.value, it.pos) }
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(context: Context, entries: List<Entry>) {
        val storage = readStorage(context).toBuilder()
        val index = storage.dictionariesList.indexOfFirst { it.name == DICTIONARY_NAME }
        val dictionary = if (index >= 0) {
            storage.getDictionaries(index).toBuilder().clearEntries()
        } else {
            UserDictionary.newBuilder().setName(DICTIONARY_NAME).setId(newDictionaryId())
        }
        for (entry in entries) {
            dictionary.addEntries(
                UserDictionary.Entry.newBuilder()
                    .setKey(entry.reading)
                    .setValue(entry.word)
                    .setPos(entry.pos)
            )
        }
        if (index >= 0) storage.setDictionaries(index, dictionary) else storage.addDictionaries(dictionary)

        val directory = profileDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        // Write to a temporary file first so the engine never sees a partial file.
        val target = file(context)
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.outputStream().use { output ->
            storage.build().writeTo(output)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Cannot replace $target")
        }
    }

    private fun readStorage(context: Context): UserDictionaryStorage {
        val source = file(context)
        if (!source.isFile) return UserDictionaryStorage.getDefaultInstance()
        return source.inputStream().use { UserDictionaryStorage.parseFrom(it) }
    }

    private fun newDictionaryId(): Long {
        val random = SecureRandom()
        var id: Long
        do {
            id = random.nextLong()
        } while (id == 0L)
        return id
    }

    /**
     * Mirrors NormalizeReading() in dictionary/user_dictionary_util.cc for the
     * cases a touch keyboard produces: full-width ASCII becomes half-width and
     * katakana becomes hiragana.
     */
    fun normalizeReading(input: String): String {
        val builder = StringBuilder(input.length)
        for (c in input.trim()) {
            builder.append(
                when (c) {
                    in '！'..'～' -> c - 0xFEE0
                    in 'ァ'..'ヶ' -> c - 0x60
                    else -> c
                }
            )
        }
        return builder.toString()
    }

    /** Same character set as InternalValidateNormalizedReading(). */
    private fun isReadingCharacter(c: Char): Boolean =
        c in '!'..'~' ||
            c in 'ぁ'..'ゖ' ||
            c in '゛'..'゜' ||
            c in '・'..'ー' ||
            c in '、'..'。' ||
            c in '「'..'』' ||
            c == '〜'

    /** Checks an entry the way the engine does, so nothing is silently dropped on load. */
    fun validate(reading: String, word: String): Problem? {
        if (reading.isEmpty()) return Problem.READING_EMPTY
        if (!reading.all(::isReadingCharacter)) return Problem.READING_INVALID
        if (word.isEmpty()) return Problem.WORD_EMPTY
        if (word.any { it == '\n' || it == '\r' || it == '\t' }) return Problem.WORD_INVALID
        if (reading.toByteArray(Charsets.UTF_8).size > MAX_STRING_BYTES ||
            word.toByteArray(Charsets.UTF_8).size > MAX_STRING_BYTES
        ) {
            return Problem.TOO_LONG
        }
        return null
    }
}
